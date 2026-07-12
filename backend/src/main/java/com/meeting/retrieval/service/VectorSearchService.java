package com.meeting.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.document.model.VectorSearchHit;
import com.meeting.document.repository.DocumentChunkRepository;
import com.meeting.retrieval.model.ChunkMetadataParser;
import com.meeting.retrieval.model.ChunkResult;
import com.meeting.llm.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public List<ChunkResult> search(String query, int topK) {
        float[] queryVector = embeddingService.generateEmbedding(query);

        String embeddingStr = floatArrayToString(queryVector);
        String vectorStr = "[" + embeddingStr + "]";

        List<VectorSearchHit> hits = chunkRepository.vectorSearch(vectorStr, topK);

        List<ChunkResult> results = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            VectorSearchHit hit = hits.get(i);
            var parsed = ChunkMetadataParser.parse(hit.metadata(), objectMapper);

            results.add(ChunkResult.builder()
                    .chunkId(hit.id())
                    .documentId(hit.documentId())
                    .content(hit.content())
                    .chunkIndex(hit.chunkIndex())
                    .speaker(hit.speaker())
                    .fileName(parsed.fileName())
                    .meetingDate(parsed.meetingDate())
                    .vectorScore(hit.similarityScore())
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
