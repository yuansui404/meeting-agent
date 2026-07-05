package com.meeting.retrieval.service;

import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        List<VectorSearchHit> hits = chunkRepository.vectorSearch(vectorStr, topK);

        // Batch fetch all documents to avoid N+1 queries
        Set<Long> docIds = hits.stream().map(VectorSearchHit::documentId).collect(Collectors.toSet());
        Map<Long, DocumentEntity> docCache = new HashMap<>();
        if (!docIds.isEmpty()) {
            documentRepository.findAllById(docIds).forEach(doc -> docCache.put(doc.getId(), doc));
        }

        List<ChunkResult> results = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            VectorSearchHit hit = hits.get(i);
            DocumentEntity doc = docCache.get(hit.documentId());

            results.add(ChunkResult.builder()
                    .chunkId(hit.id())
                    .documentId(hit.documentId())
                    .content(hit.content())
                    .chunkIndex(hit.chunkIndex())
                    .speaker(hit.speaker())
                    .sectionType(hit.sectionType())
                    .fileName(doc != null ? doc.getTitle() : "")
                    .vectorScore(hit.similarityScore())
                    .vectorRank(Integer.valueOf(i + 1))
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
