package com.meeting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Embedding service with configurable backend.
 *
 * Supports:
 * - deepseek: DeepSeek API (if available for your key/tier)
 * - openai: OpenAI-compatible API (text-embedding-ada-002, etc.)
 *
 * Configuration in application.yml or Nacos:
 *   embedding:
 *     provider: openai          # deepseek | openai | simple
 *     api-key: sk-xxx
 *     model: text-embedding-ada-002
 *     url: https://api.openai.com/v1/embeddings
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final String provider;
    private final String apiKey;
    private final String model;
    private final String url;

    private RestClient restClient;

    public EmbeddingService(@Value("${embedding.provider:simple}") String provider,
                            @Value("${embedding.api-key:}") String apiKey,
                            @Value("${embedding.model:text-embedding-ada-002}") String model,
                            @Value("${embedding.url:https://api.openai.com/v1/embeddings}") String url) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.url = url;

        if (!"simple".equals(provider) && apiKey != null && !apiKey.isBlank()) {
            this.restClient = RestClient.builder()
                    .baseUrl(url)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build();
        }
    }

    public float[] generateEmbedding(String text) {
        return switch (provider) {
            case "openai" -> generateOpenAiEmbedding(text);
            case "deepseek" -> generateDeepSeekEmbedding(text);
            default -> generateSimpleEmbedding(text);
        };
    }

    /**
     * OpenAI-compatible embedding API (also works with many providers)
     */
    @SuppressWarnings("unchecked")
    private float[] generateOpenAiEmbedding(String text) {
        log.debug("Generating embedding via OpenAI-compatible API: provider={}, model={}", provider, model);
        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "input", List.of(text)
            );

            Map<String, Object> response = restClient.post()
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new RuntimeException("Embedding API returned null response");
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) {
                throw new RuntimeException("Embedding API returned empty data");
            }

            List<Double> embeddingList = (List<Double>) data.get(0).get("embedding");
            float[] embedding = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                embedding[i] = embeddingList.get(i).floatValue();
            }
            log.debug("Generated {} dim embedding for text ({} chars)", embedding.length, text.length());
            return embedding;
        } catch (Exception e) {
            log.error("OpenAI-compatible embedding API call failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * DeepSeek embedding API (may not be available for all keys/tiers)
     */
    @SuppressWarnings("unchecked")
    private float[] generateDeepSeekEmbedding(String text) {
        Map<String, Object> request = Map.of(
                "model", model,
                "input", List.of(text)
        );

        Map<String, Object> response = restClient.post()
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new RuntimeException("Embedding API returned null response");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("Embedding API returned empty data");
        }

        List<Double> embeddingList = (List<Double>) data.get(0).get("embedding");
        float[] embedding = new float[embeddingList.size()];
        for (int i = 0; i < embeddingList.size(); i++) {
            embedding[i] = embeddingList.get(i).floatValue();
        }
        return embedding;
    }

    /**
     * Simple deterministic embedding for testing purposes.
     * 1536-dimensional vector using character and word-level features.
     * Same text always produces the same vector.
     */
    private float[] generateSimpleEmbedding(String text) {
        int dims = 1536;
        float[] embedding = new float[dims];

        if (text == null || text.isBlank()) {
            return embedding;
        }

        // Word-level feature: hash each word to specific dimension range
        String[] words = text.split("[\\s,，。；;：:！!？?\\[\\]()（）]+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isBlank()) continue;
            int hash = Math.abs(word.hashCode()) % dims;
            embedding[hash] += Math.min(word.length(), 10) * 0.5f;

            // Bigram features
            if (i > 0) {
                String bigram = words[i-1] + word;
                int bgHash = Math.abs(bigram.hashCode()) % dims;
                embedding[bgHash] += 0.3f;
            }
        }

        // Character-level features
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int hash = (chars[i] * 31 + i * 17) % dims;
            if (hash < 0) hash += dims;
            embedding[hash] += 0.1f;

            // Char bigram
            if (i > 0) {
                int cHash = ((chars[i-1] << 8) | chars[i]) % dims;
                if (cHash < 0) cHash += dims;
                embedding[cHash] += 0.05f;
            }
        }

        // Normalize
        float norm = 0;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dims; i++) embedding[i] /= norm;
        }

        return embedding;
    }
}
