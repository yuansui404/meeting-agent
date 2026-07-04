package com.meeting.document.service;

import com.meeting.common.BusinessException;
import com.meeting.common.JsonUtil;
import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;
import com.meeting.document.model.entity.DocumentChunkEntity;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ChunkStrategy chunkStrategy;
    private final RagProperties ragProperties;
    private final TransactionTemplate transactionTemplate;

    /**
     * 处理文档：分块 → 生成 embedding → 批量入库
     *
     * 事务拆分为三段：
     * 1. 标记 PROCESSING（短事务）
     * 2. embedding 生成（无事务，纯网络 I/O）
     * 3. 批量保存 chunks + 标记 COMPLETED（短事务）
     */
    public void processDocument(Long documentId, String text) {
        // 1. 标记处理中（短事务）
        transactionTemplate.executeWithoutResult(status -> {
            DocumentEntity doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> BusinessException.notFound("文档不存在"));
            doc.setStatus("PROCESSING");
        });

        List<ChunkSegment> segments;
        try {
            // 2. 分块（CPU 操作，无事务）
            segments = chunkStrategy.chunk(text, ragProperties.getChunk());
            log.info("Document {} chunked into {} segments", documentId, segments.size());

            // 3. 生成 embedding（网络 I/O，无事务）
            List<float[]> embeddings = new ArrayList<>(segments.size());
            for (ChunkSegment segment : segments) {
                embeddings.add(embeddingService.generateEmbedding(segment.getContent()));
            }

            // 4. 批量入库（短事务）
            transactionTemplate.executeWithoutResult(status -> {
                DocumentEntity doc = documentRepository.findById(documentId)
                        .orElseThrow(() -> BusinessException.notFound("文档不存在"));

                List<DocumentChunkEntity> chunks = new ArrayList<>(segments.size());
                for (int i = 0; i < segments.size(); i++) {
                    ChunkSegment segment = segments.get(i);
                    DocumentChunkEntity chunk = new DocumentChunkEntity();
                    chunk.setDocument(doc);
                    chunk.setContent(segment.getContent());
                    chunk.setChunkIndex(segment.getIndex());
                    chunk.setSpeaker(segment.getSpeaker());
                    chunk.setSectionType(segment.getSectionType());
                    chunk.setEmbedding(embeddings.get(i));
                    chunk.setMetadata(JsonUtil.toJson(
                            Map.<String, Object>of("length", segment.getContent().length())
                    ));
                    chunks.add(chunk);
                }
                chunkRepository.saveAll(chunks);

                doc.setStatus("COMPLETED");
                doc.setChunkCount(segments.size());
            });

            log.info("Document {} ETL completed, {} chunks", documentId, segments.size());

        } catch (Exception e) {
            log.error("Document {} ETL failed", documentId, e);
            transactionTemplate.executeWithoutResult(status -> {
                documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.setStatus("FAILED");
                });
            });
            throw BusinessException.processingFailed("文档处理失败: " + e.getMessage(), e);
        }
    }
}
