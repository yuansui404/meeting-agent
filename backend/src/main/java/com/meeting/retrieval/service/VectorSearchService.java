package com.meeting.retrieval.service;

import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.model.entity.DocumentEntity;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.document.repository.DocumentRepository;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        List<ChunkResult> results = new ArrayList<>();
        Map<Long, DocumentEntity> docCache = new HashMap<>();

        for (int i = 0; i < hits.size(); i++) {
            VectorSearchHit hit = hits.get(i);

            DocumentEntity doc = docCache.computeIfAbsent(hit.documentId(),
                    id -> documentRepository.findById(id).orElse(null));

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
