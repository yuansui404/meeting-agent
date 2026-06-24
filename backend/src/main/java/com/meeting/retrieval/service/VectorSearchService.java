package com.meeting.retrieval.service;

import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingService embeddingService;

    public List<ChunkResult> search(String query, int topK) {
        float[] queryVector = embeddingService.generateEmbedding(query);

        String embeddingStr = floatArrayToString(queryVector);
        String vectorStr = "[" + embeddingStr + "]";

        List<Map<String, Object>> rawResults = chunkRepository.vectorSearch(vectorStr, topK);

        List<ChunkResult> results = new ArrayList<>();
        Map<Long, DocumentEntity> docCache = new ConcurrentHashMap<>();

        for (int i = 0; i < rawResults.size(); i++) {
            Map<String, Object> row = rawResults.get(i);
            Long docId = ((Number) row.get("document_id")).longValue();

            DocumentEntity doc = docCache.computeIfAbsent(docId,
                    id -> documentRepository.findById(id).orElse(null));

            results.add(ChunkResult.builder()
                    .chunkId(((Number) row.get("id")).longValue())
                    .documentId(docId)
                    .content((String) row.get("content"))
                    .chunkIndex(((Number) row.get("chunk_index")).intValue())
                    .speaker((String) row.get("speaker"))
                    .sectionType((String) row.get("section_type"))
                    .fileName(doc != null ? doc.getTitle() : "")
                    .vectorScore(((Number) row.get("similarity")).doubleValue())
                    .vectorRank(i + 1)
                    .build());
        }

        log.debug("Vector search: query={}, topK={}, found={}", query, topK, results.size());
        return results;
    }

    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
