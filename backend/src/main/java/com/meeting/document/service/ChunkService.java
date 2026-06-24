package com.meeting.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.common.BusinessException;
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
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper;

    @Transactional
    public void processDocument(Long documentId, String text) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> BusinessException.notFound("文档不存在"));

        doc.setStatus("PROCESSING");
        documentRepository.save(doc);

        try {
            List<ChunkSegment> segments = chunkStrategy.chunk(text, ragProperties.getChunk());
            log.info("Document {} chunked into {} segments", documentId, segments.size());

            for (ChunkSegment segment : segments) {
                DocumentChunkEntity chunk = new DocumentChunkEntity();
                chunk.setDocumentId(documentId);
                chunk.setContent(segment.getContent());
                chunk.setChunkIndex(segment.getIndex());
                chunk.setSpeaker(segment.getSpeaker());
                chunk.setSectionType(segment.getSectionType());

                float[] embedding = embeddingService.generateEmbedding(segment.getContent());
                chunk.setEmbedding(embedding);

                chunk.setMetadata(objectMapper.writeValueAsString(
                        Map.of("length", segment.getContent().length())
                ));

                chunkRepository.save(chunk);
            }

            doc.setStatus("COMPLETED");
            doc.setChunkCount(segments.size());
            documentRepository.save(doc);

            log.info("Document {} ETL completed, {} chunks", documentId, segments.size());

        } catch (Exception e) {
            log.error("Document {} ETL failed", documentId, e);
            doc.setStatus("FAILED");
            documentRepository.save(doc);
            throw new BusinessException("文档处理失败: " + e.getMessage());
        }
    }
}
