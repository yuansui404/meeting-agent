package com.meeting.retrieval.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import com.meeting.retrieval.model.ChunkResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "rag.retrieval.rerank-enabled", havingValue = "true")
public class BgeReranker implements Reranker {

    private static final int MAX_SEQ_LEN = 512;
    private static final int BATCH_SIZE = 8;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean useTokenTypeIds;

    public BgeReranker() {
        try {
            String modelDir = System.getenv("BGE_MODEL_DIR") != null
                    ? System.getenv("BGE_MODEL_DIR")
                    : "/models/bge-reranker";

            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
            this.session = env.createSession(Paths.get(modelDir, "model.onnx").toString(), options);

            this.useTokenTypeIds = session.getInputInfo().containsKey("token_type_ids");

            this.tokenizer = HuggingFaceTokenizer.newInstance(
                    Paths.get(modelDir, "tokenizer.json").toString());
            log.info("BGE Reranker loaded from {}, token_type_ids={}",
                    modelDir, useTokenTypeIds);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load BGE Reranker model from /models/bge-reranker", e);
        }
    }

    @Override
    public List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return candidates;
        if (candidates.size() <= topN) return candidates;

        List<ChunkResult> results = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, candidates.size());
            List<ChunkResult> batch = candidates.subList(i, end);
            float[] scores = scoreBatch(query, batch);

            for (int j = 0; j < batch.size(); j++) {
                ChunkResult original = batch.get(j);
                results.add(ChunkResult.builder()
                        .chunkId(original.getChunkId())
                        .documentId(original.getDocumentId())
                        .content(original.getContent())
                        .chunkIndex(original.getChunkIndex())
                        .speaker(original.getSpeaker())
                        .fileName(original.getFileName())
                        .vectorScore(original.getVectorScore())
                        .ftsScore(original.getFtsScore())
                        .rrfScore(original.getRrfScore())
                        .finalScore(scores[j])
                        .vectorRank(original.getVectorRank())
                        .ftsRank(original.getFtsRank())
                        .build());
            }
        }

        results.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        return results.stream().limit(topN).collect(Collectors.toList());
    }

    private float[] scoreBatch(String query, List<ChunkResult> batch) {
        try {
            // Encode each query-document pair and find max length
            List<long[]> allIds = new ArrayList<>();
            List<long[]> allMasks = new ArrayList<>();
            int maxLen = 0;

            for (ChunkResult r : batch) {
                Encoding enc = tokenizer.encode(query, r.getContent());
                long[] ids = enc.getIds();
                long[] mask = enc.getAttentionMask();
                allIds.add(ids);
                allMasks.add(mask);
                maxLen = Math.max(maxLen, Math.min(ids.length, MAX_SEQ_LEN));
            }

            // Build padded batch tensors
            int batchSize = batch.size();
            long[][] inputIds = new long[batchSize][maxLen];
            long[][] attnMask = new long[batchSize][maxLen];

            for (int i = 0; i < batchSize; i++) {
                long[] ids = allIds.get(i);
                long[] mask = allMasks.get(i);
                int len = Math.min(ids.length, maxLen);
                System.arraycopy(ids, 0, inputIds[i], 0, len);
                System.arraycopy(mask, 0, attnMask[i], 0, len);
            }

            // Run inference
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, inputIds));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, attnMask));
            if (useTokenTypeIds) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[batchSize][maxLen]));
            }

            OrtSession.Result output = session.run(inputs);
            float[][] logits = (float[][]) output.get("logits").orElseThrow().getValue();

            // Softmax to get relevance probability for class 1
            float[] scores = new float[batchSize];
            for (int i = 0; i < batchSize; i++) {
                float e0 = (float) Math.exp(logits[i][0]);
                float e1 = (float) Math.exp(logits[i][1]);
                scores[i] = 2.0f * e1 / (e0 + e1) - 1.0f;
            }
            return scores;

        } catch (Exception e) {
            log.warn("BGE Reranker scoring failed for batch of {}: {}",
                    batch.size(), e.getMessage());
            float[] fallback = new float[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                fallback[i] = (float) batch.get(i).getRrfScore();
            }
            return fallback;
        }
    }
}