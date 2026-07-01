package com.meeting.retrieval.service;

import com.meeting.retrieval.model.ChunkResult;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "rag.retrieval.rerank-enabled", havingValue = "true")
public class DeepSeekReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekReranker.class);
    private static final int BATCH_SIZE = 10;
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)(?:/10)?\\s*[:：]");

    private final OpenAIClient openAIClient;
    private final String apiKey;
    private final String apiUrl;

    public DeepSeekReranker(@Value("${deepseek.api-key:}") String apiKey,
                            @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.openAIClient = new OpenAIClient();
    }

    @Override
    public List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() <= topN) return candidates;

        List<ChunkResult> results = new ArrayList<>();

        // Process in batches
        for (int start = 0; start < candidates.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, candidates.size());
            List<ChunkResult> batch = candidates.subList(start, end);
            List<Integer> scores = scoreBatch(query, batch);
            for (int i = 0; i < batch.size(); i++) {
                int idx = start + i;
                double score = scores.get(i) / 10.0;
                // Create a new ChunkResult with reranked score as finalScore
                // (preserving original scores for reference)
                ChunkResult original = batch.get(i);
                results.add(ChunkResult.builder()
                        .chunkId(original.getChunkId())
                        .documentId(original.getDocumentId())
                        .content(original.getContent())
                        .chunkIndex(original.getChunkIndex())
                        .speaker(original.getSpeaker())
                        .sectionType(original.getSectionType())
                        .fileName(original.getFileName())
                        .vectorScore(original.getVectorScore())
                        .ftsScore(original.getFtsScore())
                        .rrfScore(original.getRrfScore())
                        .finalScore(score)
                        .vectorRank(original.getVectorRank())
                        .ftsRank(original.getFtsRank())
                        .build());
            }
        }

        // Sort by finalScore descending, return topN
        results.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        return results.stream().limit(topN).collect(Collectors.toList());
    }

    private List<Integer> scoreBatch(String query, List<ChunkResult> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据查询相关性对以下文本段落评分。\n\n");
        sb.append("查询：").append(query).append("\n\n");
        sb.append("段落列表：\n");
        for (int i = 0; i < batch.size(); i++) {
            String content = batch.get(i).getContent();
            String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;
            sb.append(i + 1).append(": ").append(preview).append("\n---\n");
        }
        sb.append("\n请对每个段落给出 0-10 的整数评分（0=完全不相关，10=高度相关）。\n");
        sb.append("输出格式：每行一个 \"序号: 分数\"，如 \"1: 8\"、\"2: 3\"，不要其他内容。");

        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model("deepseek-chat")
                    .messages(List.of(
                            OpenAIMessage.builder().role("system")
                                    .content("你是一个文档相关性评分专家。严格按照格式输出评分。").build(),
                            OpenAIMessage.builder().role("user").content(sb.toString()).build()
                    ))
                    .temperature(0.1)
                    .maxTokens(512)
                    .build();

            OpenAIResponse response = openAIClient.call(apiKey, apiUrl, request);
            String content = response.getFirstChoice().getMessage().getContentAsString();

            // Parse scores: expect lines like "1: 8", "2: 3", "3: 10/10"
            List<Integer> scores = new ArrayList<>();
            if (content != null) {
                Matcher matcher = SCORE_PATTERN.matcher(content);
                while (matcher.find()) {
                    int score = Integer.parseInt(matcher.group(1));
                    scores.add(Math.max(0, Math.min(10, score)));
                }
            }

            // If parsing failed or wrong count, default to 5
            if (scores.isEmpty()) {
                log.warn("DeepSeekReranker: could not parse scores from response: {}", content);
                return batch.stream().map(c -> 5).collect(Collectors.toList());
            }

            // If fewer scores than batch, pad with 5
            while (scores.size() < batch.size()) {
                scores.add(5);
            }

            return scores.subList(0, batch.size());
        } catch (Exception e) {
            log.warn("DeepSeekReranker batch scoring failed: {}", e.getMessage());
            return batch.stream().map(c -> 5).collect(Collectors.toList());
        }
    }
}
