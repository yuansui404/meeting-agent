package com.meeting.document.service;

import com.meeting.common.BusinessException;
import com.meeting.common.JsonUtil;
import com.meeting.config.RagProperties;
import com.meeting.document.model.ChunkSegment;
import com.meeting.document.model.entity.DocumentChunkV2Entity;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkV2Repository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkServiceV2 {

    private final DocumentRepository documentRepository;
    private final DocumentChunkV2Repository chunkV2Repository;
    private final EmbeddingService embeddingService;
    private final ChunkStrategy chunkStrategy;
    private final RagProperties ragProperties;
    private final TransactionTemplate transactionTemplate;

    public void processDocument(Long documentId, String text) {
        transactionTemplate.executeWithoutResult(status -> {
            DocumentEntity doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> BusinessException.notFound("文档不存在"));
            doc.setStatus("PROCESSING");
        });

        List<ChunkSegment> segments;
        try {
            DocumentEntity docForChunk = documentRepository.findById(documentId)
                    .orElseThrow(() -> BusinessException.notFound("文档不存在"));
            segments = chunkStrategy.chunk(text, ragProperties.getChunk(), docForChunk.getTitle());
            log.info("Document {} chunked into {} segments", documentId, segments.size());

            // Prepend title to each chunk's content
            String title = docForChunk.getTitle();
            List<String> contents = segments.stream()
                    .map(s -> title + "\n\n" + s.getContent())
                    .toList();
            List<float[]> embeddings = embeddingService.generateEmbeddings(contents);

            transactionTemplate.executeWithoutResult(status -> {
                DocumentEntity doc = documentRepository.findById(documentId)
                        .orElseThrow(() -> BusinessException.notFound("文档不存在"));

                List<DocumentChunkV2Entity> chunks = new ArrayList<>(segments.size());
                for (int i = 0; i < segments.size(); i++) {
                    ChunkSegment segment = segments.get(i);
                    DocumentChunkV2Entity chunk = new DocumentChunkV2Entity();
                    chunk.setDocument(doc);
                    chunk.setContent(title + "\n\n" + segment.getContent());
                    chunk.setChunkIndex(segment.getIndex());
                    chunk.setSpeaker(segment.getSpeaker());
                    chunk.setEmbedding(embeddings.get(i));
                    chunk.setMetadata(JsonUtil.toJson(buildMetadata(segment, doc)));
                    chunks.add(chunk);
                }
                chunkV2Repository.saveAll(chunks);

                doc.setStatus("COMPLETED");
            });

            log.info("Document {} V2 ETL completed, {} chunks", documentId, segments.size());

        } catch (Exception e) {
            log.error("Document {} V2 ETL failed", documentId, e);
            transactionTemplate.executeWithoutResult(status -> {
                documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.setStatus("FAILED");
                });
            });
            throw BusinessException.processingFailed("文档处理失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildMetadata(ChunkSegment segment, DocumentEntity doc) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("length", segment.getContent().length());
        metadata.put("document_title", doc.getTitle());
        if (doc.getMeetingDate() != null) metadata.put("meeting_date", doc.getMeetingDate().toString());
        if (doc.getParticipants() != null) metadata.put("participants", doc.getParticipants());
        if (segment.getTopic() != null) metadata.put("topic", segment.getTopic());
        if (segment.getSectionHeading() != null) metadata.put("section_heading", segment.getSectionHeading());
        if (segment.getSpeaker() != null) metadata.put("speaker", segment.getSpeaker());
        return metadata;
    }
}