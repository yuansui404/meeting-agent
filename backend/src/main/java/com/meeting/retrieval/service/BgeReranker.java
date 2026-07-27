package com.meeting.retrieval.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import com.meeting.config.RagProperties;
import com.meeting.retrieval.model.ChunkResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    private final RagProperties ragProperties;

    /** 检测到的 logit 维度，缓存避免重复探测 */
    private volatile Integer detectedNumLogits = null;

    // 熔断状态
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAt = new AtomicLong(0);

    public BgeReranker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        String modelDir = System.getenv("BGE_MODEL_DIR") != null
                ? System.getenv("BGE_MODEL_DIR")
                : "/models/bge-reranker";
        try {

            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
            this.session = env.createSession(Paths.get(modelDir, "model.onnx").toString(), options);

            this.useTokenTypeIds = session.getInputInfo().containsKey("token_type_ids");

            this.tokenizer = HuggingFaceTokenizer.newInstance(
                    Paths.get(modelDir, "tokenizer.json"));
            log.info("BGE Reranker loaded from {}, token_type_ids={}",
                    modelDir, useTokenTypeIds);
        } catch (Exception e) {
            log.error("Failed to load BGE Reranker model from {}: {}",
                    modelDir, e.getMessage(), e);
            throw new RuntimeException("Failed to load BGE Reranker model from " + modelDir, e);
        }
    }

    /**
     * 对候选结果进行重排序。
     * 用 cross-encoder 模型对 (query, chunk) 逐对打分，按分数降序重排，取 topN。
     * 分数仅用于内部排序，不写入 ChunkResult。
     */
    @Override
    public List<ChunkResult> reRank(String query, List<ChunkResult> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return candidates;

        // 检查熔断状态
        if (isCircuitOpen()) {
            log.warn("BGE Reranker circuit is OPEN, skipping rerank and falling back to RRF scores");
            return fallbackByRrf(candidates, topN);
        }

        // 对所有候选块分批打分，scores 仅用于内部排序
        int n = candidates.size();
        float[] allScores = new float[n];
        for (int i = 0; i < n; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, n);
            float[] batchScores = scoreBatch(query, candidates.subList(i, end));
            System.arraycopy(batchScores, 0, allScores, i, batchScores.length);
        }

        // 按分数降序排列，取 topN 返回
        return IntStream.range(0, n)
                .boxed()
                .sorted((a, b) -> Float.compare(allScores[b], allScores[a]))
                .limit(topN)
                .map(candidates::get)
                .collect(Collectors.toList());
    }

    /**
     * 对一批 (query, chunk) 执行 ONNX 推理，返回每个 chunk 的分数用于排序。
     * 推理失败时降级为 RRF 分数。
     */
    private float[] scoreBatch(String query, List<ChunkResult> batch) {
        try {
            // 对每个 (query, chunk) 编码，确定批内最大序列长度
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

            // Run inference with proper native memory cleanup
            List<OnnxTensor> ownedTensors = new ArrayList<>();
            try {
                OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, inputIds);
                ownedTensors.add(inputIdsTensor);
                OnnxTensor attnMaskTensor = OnnxTensor.createTensor(env, attnMask);
                ownedTensors.add(attnMaskTensor);

                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", inputIdsTensor);
                inputs.put("attention_mask", attnMaskTensor);

                if (useTokenTypeIds) {
                    OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(env, new long[batchSize][maxLen]);
                    ownedTensors.add(tokenTypeTensor);
                    inputs.put("token_type_ids", tokenTypeTensor);
                }

                try (OrtSession.Result output = session.run(inputs)) {
                    float[][] logits = (float[][]) output.get("logits").orElseThrow().getValue();

                    // 缓存 logit 维度检测结果（同一模型不会变）
                    if (detectedNumLogits == null) {
                        detectedNumLogits = logits[0].length;
                        log.info("BGE reranker detected {} logits, useTokenTypeIds={}",
                                detectedNumLogits, useTokenTypeIds);
                    }

                    float[] scores = new float[batchSize];
                    if (detectedNumLogits == 1) {
                        // raw logit 单调性保持排序，无需 sigmoid
                        for (int i = 0; i < batchSize; i++) {
                            scores[i] = logits[i][0];
                        }
                    } else if (detectedNumLogits == 2) {
                        // logit 差值（relevant - not_relevant）与 softmax 单调一致
                        for (int i = 0; i < batchSize; i++) {
                            scores[i] = logits[i][1] - logits[i][0];
                        }
                    } else {
                        log.warn("Unexpected BGE reranker logits dimension: {} (expected 1 or 2), falling back to RRF scores", detectedNumLogits);
                        for (int i = 0; i < batchSize; i++) {
                            scores[i] = (float) batch.get(i).getRrfScore();
                        }
                    }
                    // 成功 — 重置连续失败计数
                    consecutiveFailures.set(0);
                    return scores;
                }
            } finally {
                for (OnnxTensor t : ownedTensors) {
                    try { t.close(); } catch (Exception ignored) {}
                }
            }

        } catch (Exception e) {
            int failed = consecutiveFailures.incrementAndGet();
            int threshold = ragProperties.getCircuitBreaker().getFailureThreshold();
            log.warn("BGE Reranker scoring failed for batch of {} (consecutive failures: {}/{}): {}",
                    batch.size(), failed, threshold, e.getMessage());

            // 达到阈值 → 打开熔断
            if (failed >= threshold) {
                long cooldownMs = ragProperties.getCircuitBreaker().getCooldownMs();
                circuitOpenedAt.set(System.currentTimeMillis());
                log.error("BGE Reranker circuit OPENED after {} consecutive failures, cooling down for {}ms",
                        failed, cooldownMs);
            }

            float[] fallback = new float[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                fallback[i] = (float) batch.get(i).getRrfScore();
            }
            return fallback;
        }
    }

    private boolean isCircuitOpen() {
        long openedAt = circuitOpenedAt.get();
        if (openedAt == 0) return false;
        long cooldownMs = ragProperties.getCircuitBreaker().getCooldownMs();
        if (System.currentTimeMillis() - openedAt > cooldownMs) {
            // 冷却期结束 → 半开，允许下次尝试
            log.info("BGE Reranker circuit HALF-OPEN, will retry on next call");
            circuitOpenedAt.set(0);
            consecutiveFailures.set(0);
            return false;
        }
        return true;
    }

    private List<ChunkResult> fallbackByRrf(List<ChunkResult> candidates, int topN) {
        return candidates.stream()
                .sorted((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()))
                .limit(topN)
                .collect(Collectors.toList());
    }
}