package com.meeting.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 检索质量评估运行器。
 * 纯 Java main 方法，不依赖 Spring 上下文。
 * 在 IDEA 中右键 Run 即可执行。
 *
 * <p>前提：后端服务运行在 http://localhost:8080
 * <p>--ragas 模式需要 DEEPSEEK_API_KEY 环境变量
 */
public class EvalRunner {

    // ────────────────────────────── 数据模型 ──────────────────────────────

    record TestCase(
            int id,
            String question,
            List<Integer> groundTruthDocIds,
            String groundTruthAnswer,
            String topic,
            String queryType,
            String difficulty
    ) {}

    record SearchHit(String source, String content, double score, String speaker,
                     String participants, String topic, String sectionHeading,
                     Integer documentId) {}

    record SearchResponse(String evidenceLevel, String strategyUsed, int totalCandidates,
                          String queryUsed, List<SearchHit> results) {}

    record EvalResult(
            int testCaseId,
            String question,
            double ndcg3,
            double precision3,
            double recall3,
            double mrr,
            long latencyMs,
            String evidenceLevel,
            int totalCandidates,
            List<Integer> retrievedDocIds,
            Map<String, Object> details
    ) {}

    record EvalRun(
            String runName,
            LocalDateTime createdAt,
            String config,
            List<EvalResult> results,
            Map<String, Object> summary,
            Map<String, Object> ragasSummary
    ) {}

    // ────────────────────────────── 配置 ──────────────────────────────

    static final String SEARCH_URL = "http://localhost:8080/api/search";
    static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    static final String DEEPSEEK_MODEL = "deepseek-chat";
    static final String RESULTS_DIR = "../scripts/eval/results";
    static final String TEST_CASES_FILE = "../scripts/eval/test_cases.json";

    static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ────────────────────────────── main ──────────────────────────────

    public static void main(String[] args) throws Exception {
        String runName = "eval-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmmss"));
        boolean ragasEnabled = false;
        List<String> compare = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--run-name" -> runName = args[++i];
                case "--ragas" -> ragasEnabled = true;
                case "--compare" -> compare = List.of(args[++i], args[++i]);
            }
        }

        if (compare != null) {
            compareRuns(compare.get(0), compare.get(1));
            return;
        }

        System.out.println("=".repeat(60));
        System.out.println("  RAG Evaluation Runner");
        System.out.println("  Run: " + runName);
        System.out.println("  RAGAS: " + (ragasEnabled ? "enabled" : "disabled"));
        System.out.println("=".repeat(60));

        // 1. 加载测试集
        File testFile = resolvePath(TEST_CASES_FILE).toFile();
        if (!testFile.exists()) {
            System.err.println("ERROR: test cases file not found: " + testFile.getAbsolutePath());
            System.exit(1);
        }
        List<TestCase> cases = mapper.readValue(testFile, new TypeReference<>() {});
        System.out.println("Loaded " + cases.size() + " test cases");

        // 2. 逐条评估
        List<EvalResult> results = new ArrayList<>();
        List<Map<String, Object>> ragasScores = new ArrayList<>();

        for (TestCase c : cases) {
            long t0 = System.currentTimeMillis();
            SearchResponse searchResp = callSearchApi(c.question);
            long elapsed = System.currentTimeMillis() - t0;

            EvalResult result = computeMetrics(c, searchResp, elapsed);
            results.add(result);

            StringBuilder line = new StringBuilder();
            line.append(String.format("  [%2d] %-40s P@3=%.2f MRR=%.2f",
                    c.id, truncate(c.question, 40), result.precision3(), result.mrr()));

            // RAGAS
            if (ragasEnabled) {
                Map<String, Object> ragas = computeRagasMetrics(c, searchResp);
                result.details().put("ragas", ragas);
                if (!ragas.isEmpty()) ragasScores.add(ragas);
                line.append(String.format(" CP=%.4f CR=%.4f",
                        ragas.getOrDefault("context_precision", 0.0),
                        ragas.getOrDefault("context_recall", 0.0)));
            }

            System.out.println(line);
        }

        // 3. 聚合
        Map<String, Object> summary = aggregate(results);
        Map<String, Object> ragasSummary = aggregateRagas(ragasScores);

        // 4. 报告
        printReport(runName, summary, ragasSummary);
        printSliceAnalysis(results);

        // 5. 保存结果
        saveResults(runName, summary, ragasSummary, results);
    }

    // ────────────────────────────── API 调用 ──────────────────────────────

    static SearchResponse callSearchApi(String query) throws Exception {
        String url = SEARCH_URL + "?query=" + java.net.URLEncoder.encode(query, "UTF-8");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            System.err.println("  [warn] API returned " + resp.statusCode());
            return new SearchResponse("NONE", "", 0, "", List.of());
        }

        var root = mapper.readTree(resp.body());
        var data = root.get("data");
        if (data == null) {
            return new SearchResponse("NONE", "", 0, "", List.of());
        }

        String evidenceLevel = optStr(data, "evidenceLevel", "NONE");
        String strategy = optStr(data, "strategyUsed", "");
        int total = optInt(data, "totalCandidates", 0);
        String queryUsed = optStr(data, "queryUsed", "");

        List<SearchHit> hits = new ArrayList<>();
        var resultsArr = data.get("results");
        if (resultsArr != null) {
            for (var item : resultsArr) {
                hits.add(new SearchHit(
                        optStr(item, "source", "未知文档"),
                        optStr(item, "content", ""),
                        optDouble(item, "score", 0.0),
                        optStr(item, "speaker", ""),
                        optStr(item, "participants", ""),
                        optStr(item, "topic", ""),
                        optStr(item, "sectionHeading", ""),
                        item.has("documentId") ? item.get("documentId").asInt() : null
                ));
            }
        }

        return new SearchResponse(evidenceLevel, strategy, total, queryUsed, hits);
    }

    // ────────────────────────────── 指标计算 ──────────────────────────────

    static EvalResult computeMetrics(TestCase c, SearchResponse resp, long latencyMs) {
        // 提取 doc_id（去重，保留顺序）
        List<Integer> docIds = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (var hit : resp.results()) {
            Integer docId = hit.documentId();
            if (docId != null && seen.add(docId)) {
                docIds.add(docId);
            }
        }

        Set<Integer> gtSet = new HashSet<>(c.groundTruthDocIds());
        List<Integer> top3 = docIds.size() > 3 ? docIds.subList(0, 3) : docIds;

        // P@3
        long relevantInTop3 = top3.stream().filter(gtSet::contains).count();
        double precision3 = top3.isEmpty() ? 0.0 : (double) relevantInTop3 / top3.size();

        // R@3
        double recall3 = gtSet.isEmpty() ? 0.0 : (double) relevantInTop3 / gtSet.size();

        // MRR
        double mrr = 0.0;
        for (int i = 0; i < docIds.size(); i++) {
            if (gtSet.contains(docIds.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        // NDCG@3
        double dcg = 0.0;
        for (int i = 0; i < top3.size(); i++) {
            double rel = gtSet.contains(top3.get(i)) ? 1.0 : 0.0;
            dcg += (i == 0) ? rel : rel / (i + 1);
        }
        double idcg = 0.0;
        int idealCount = Math.min(gtSet.size(), 3);
        for (int i = 0; i < idealCount; i++) {
            idcg += (i == 0) ? 1.0 : 1.0 / (i + 1);
        }
        double ndcg = idcg > 0 ? dcg / idcg : 0.0;

        return new EvalResult(
                c.id(), c.question(),
                round(ndcg, 4), round(precision3, 4), round(recall3, 4), round(mrr, 4),
                latencyMs, resp.evidenceLevel(), resp.totalCandidates(),
                docIds, new HashMap<>()
        );
    }

    // ────────────────────────────── RAGAS ──────────────────────────────

    static Map<String, Object> computeRagasMetrics(TestCase c, SearchResponse resp) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("  [warn] DEEPSEEK_API_KEY not set, skipping RAGAS");
            return Map.of();
        }

        List<String> contexts = resp.results().stream()
                .map(SearchHit::content)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());

        if (contexts.isEmpty()) {
            return Map.of("context_precision", 0.0, "context_recall", 0.0);
        }

        double contextPrecision = 0.0;
        int relevantCount = 0;
        for (String ctx : contexts) {
            String judgment = askLlm(apiKey, String.format("""
                    你是一个评估助手。判断以下检索到的内容是否与用户问题相关。
                    只回答「是」或「否」，不要解释。

                    问题：%s

                    检索内容：%s

                    是否相关？""", c.question(), truncate(ctx, 500)));

            if (judgment.contains("是")) relevantCount++;
        }
        contextPrecision = (double) relevantCount / contexts.size();

        double contextRecall = 0.0;
        if (c.groundTruthAnswer() != null && !c.groundTruthAnswer().isBlank()) {
            String combined = String.join("\n---\n", contexts);
            String judgment = askLlm(apiKey, String.format("""
                    你是一个评估助手。判断以下检索到的内容是否包含足够信息来支撑标准答案。
                    只回答「是」或「否」，不要解释。

                    标准答案：%s

                    检索内容：%s

                    是否支撑？""", c.groundTruthAnswer(), truncate(combined, 1000)));

            contextRecall = judgment.contains("是") ? 1.0 : 0.0;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("context_precision", round(contextPrecision, 4));
        if (c.groundTruthAnswer() != null) {
            result.put("context_recall", round(contextRecall, 4));
        }
        return result;
    }

    static String askLlm(String apiKey, String prompt) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", DEEPSEEK_MODEL,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是一个评估助手，只回答「是」或「否」。"),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.0,
                    "max_tokens", 10
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DEEPSEEK_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var root = mapper.readTree(resp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            return content.trim();

        } catch (Exception e) {
            System.err.println("  [warn] LLM call failed: " + e.getMessage());
            return "";
        }
    }

    // ────────────────────────────── 聚合 ──────────────────────────────

    static Map<String, Object> aggregate(List<EvalResult> results) {
        double avgNdcg = results.stream().mapToDouble(EvalResult::ndcg3).average().orElse(0);
        double avgPrecision = results.stream().mapToDouble(EvalResult::precision3).average().orElse(0);
        double avgRecall = results.stream().mapToDouble(EvalResult::recall3).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(EvalResult::mrr).average().orElse(0);
        double avgLatency = results.stream().mapToLong(EvalResult::latencyMs).average().orElse(0);
        long passedEvidence = results.stream()
                .filter(r -> "SUFFICIENT".equals(r.evidenceLevel()) || "PARTIAL".equals(r.evidenceLevel()))
                .count();

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalCases", results.size());
        s.put("avgNdcg", round(avgNdcg, 4));
        s.put("avgPrecision", round(avgPrecision, 4));
        s.put("avgRecall", round(avgRecall, 4));
        s.put("avgMrr", round(avgMrr, 4));
        s.put("avgLatencyMs", Math.round(avgLatency));
        s.put("evidencePassRate", round((double) passedEvidence / results.size(), 4));
        return s;
    }

    static Map<String, Object> aggregateRagas(List<Map<String, Object>> scores) {
        if (scores.isEmpty()) return Map.of();
        double avgCp = scores.stream()
                .filter(s -> s.containsKey("context_precision"))
                .mapToDouble(s -> (double) s.get("context_precision"))
                .average().orElse(0);
        double avgCr = scores.stream()
                .filter(s -> s.containsKey("context_recall"))
                .mapToDouble(s -> (double) s.get("context_recall"))
                .average().orElse(0);
        return Map.of("avgContextPrecision", round(avgCp, 4), "avgContextRecall", round(avgCr, 4));
    }

    // ────────────────────────────── 报告 ──────────────────────────────

    static void printReport(String runName, Map<String, Object> summary, Map<String, Object> ragasSummary) {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("  Run: " + runName);
        System.out.println("=".repeat(50));
        System.out.printf("  Cases:        %5d%n", summary.get("totalCases"));
        System.out.printf("  NDCG@3:       %8.4f%n", summary.get("avgNdcg"));
        System.out.printf("  Precision@3:  %8.4f%n", summary.get("avgPrecision"));
        System.out.printf("  Recall@3:     %8.4f%n", summary.get("avgRecall"));
        System.out.printf("  MRR:          %8.4f%n", summary.get("avgMrr"));
        System.out.printf("  Avg Latency:  %8.0fms%n", summary.get("avgLatencyMs"));
        System.out.printf("  E@top:        %8.0f%%%n", (double) summary.get("evidencePassRate") * 100);
        if (!ragasSummary.isEmpty()) {
            System.out.printf("  Context Prec: %8.4f%n", ragasSummary.get("avgContextPrecision"));
            System.out.printf("  Context Rec:  %8.4f%n", ragasSummary.get("avgContextRecall"));
        }
        System.out.println("=".repeat(50));
    }

    static void printSliceAnalysis(List<EvalResult> results) throws Exception {
        // 读取测试集获取分组信息
        List<TestCase> cases = mapper.readValue(resolvePath(TEST_CASES_FILE).toFile(), new TypeReference<>() {});
        Map<Integer, TestCase> caseMap = cases.stream().collect(Collectors.toMap(TestCase::id, c -> c));

        Map<String, List<EvalResult>> groups = new LinkedHashMap<>();
        for (var r : results) {
            TestCase c = caseMap.get(r.testCaseId());
            String key = (c != null ? c.queryType() : "unknown") + " | " + (c != null ? c.difficulty() : "unknown");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        System.out.println("\n  --- Slice Analysis ---");
        for (var entry : groups.entrySet()) {
            var group = entry.getValue();
            double ndcg = group.stream().mapToDouble(EvalResult::ndcg3).average().orElse(0);
            double prec = group.stream().mapToDouble(EvalResult::precision3).average().orElse(0);
            double rec = group.stream().mapToDouble(EvalResult::recall3).average().orElse(0);
            System.out.printf("  [%-14s] (n=%2d) NDCG=%.4f  P=%.4f  R=%.4f%n",
                    entry.getKey(), group.size(), ndcg, prec, rec);
        }
    }

    // ────────────────────────────── 结果保存 ──────────────────────────────

    static void saveResults(String runName, Map<String, Object> summary,
                            Map<String, Object> ragasSummary, List<EvalResult> results) throws Exception {
        Path dir = resolvePath(RESULTS_DIR);
        Files.createDirectories(dir);

        EvalRun run = new EvalRun(runName, LocalDateTime.now(), "{}", results, summary, ragasSummary);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(run);

        String fileName = runName.replaceAll("[^a-zA-Z0-9_-]", "_") + ".json";
        Path outFile = dir.resolve(fileName);
        Files.writeString(outFile, json);
        System.out.println("\nResults saved to: " + outFile.toAbsolutePath());
    }

    // ────────────────────────────── 对比 ──────────────────────────────

    static void compareRuns(String run1, String run2) throws Exception {
        Path dir = resolvePath(RESULTS_DIR);
        EvalRun r1 = loadRun(dir, run1);
        EvalRun r2 = loadRun(dir, run2);
        if (r1 == null || r2 == null) return;

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  Comparison: " + run1 + "  vs  " + run2);
        System.out.println("=".repeat(70));

        Map<Integer, EvalResult> map1 = r1.results().stream().collect(Collectors.toMap(EvalResult::testCaseId, r -> r));
        Map<Integer, EvalResult> map2 = r2.results().stream().collect(Collectors.toMap(EvalResult::testCaseId, r -> r));

        List<Integer> allIds = new ArrayList<>(map1.keySet());
        allIds.retainAll(new HashSet<>(map2.keySet()));
        Collections.sort(allIds);

        List<Double> ndcgDiffs = new ArrayList<>();
        for (int id : allIds) {
            EvalResult e1 = map1.get(id);
            EvalResult e2 = map2.get(id);
            double diff = e2.ndcg3() - e1.ndcg3();
            ndcgDiffs.add(diff);
            String marker = diff > 0.01 ? "▲" : (diff < -0.01 ? "▼" : "─");
            long latDiff = e2.latencyMs() - e1.latencyMs();
            String latStr = Math.abs(latDiff) > 10 ? String.format("lat=%d→%dms (%+d)", e1.latencyMs(), e2.latencyMs(), latDiff) : "";
            System.out.printf("  %s [%2d] %-45s NDCG: %.3f→%.3f (%+.3f)  EV: %s→%s  %s%n",
                    marker, id, truncate(e1.question(), 45),
                    e1.ndcg3(), e2.ndcg3(), diff,
                    e1.evidenceLevel(), e2.evidenceLevel(), latStr);
        }

        double avg1 = allIds.stream().mapToDouble(id -> map1.get(id).ndcg3()).average().orElse(0);
        double avg2 = allIds.stream().mapToDouble(id -> map2.get(id).ndcg3()).average().orElse(0);
        double avgLat1 = allIds.stream().mapToLong(id -> map1.get(id).latencyMs()).average().orElse(0);
        double avgLat2 = allIds.stream().mapToLong(id -> map2.get(id).latencyMs()).average().orElse(0);
        long improved = ndcgDiffs.stream().filter(d -> d > 0.01).count();
        long degraded = ndcgDiffs.stream().filter(d -> d < -0.01).count();
        long unchanged = ndcgDiffs.size() - improved - degraded;

        System.out.println("\n  --- Summary ---");
        System.out.printf("  Avg NDCG: %.4f → %.4f (%+.4f)%n", avg1, avg2, avg2 - avg1);
        System.out.printf("  Avg Lat:  %.0f → %.0fms (%+.0fms)%n", avgLat1, avgLat2, avgLat2 - avgLat1);
        System.out.printf("  Cases:    %d improved, %d degraded, %d unchanged%n", improved, degraded, unchanged);
    }

    static EvalRun loadRun(Path dir, String name) throws Exception {
        // 按文件名或 runName 模糊匹配
        File[] files = dir.toFile().listFiles((d, f) -> f.endsWith(".json"));
        if (files == null) return null;

        for (File f : files) {
            EvalRun run = mapper.readValue(f, EvalRun.class);
            if (run.runName().equals(name) || f.getName().contains(name)) {
                return run;
            }
        }
        System.err.println("Run not found: " + name);
        return null;
    }

    // ────────────────────────────── 工具 ──────────────────────────────

    static Path resolvePath(String relativePath) {
        // 优先相对于 backend/ 目录，fallback 到当前目录
        Path backend = Path.of("..");
        Path path = backend.resolve(relativePath);
        if (path.toFile().exists()) {
            return path.normalize();
        }
        // 试试从 project root
        path = Path.of(relativePath);
        if (path.toFile().exists()) {
            return path.normalize();
        }
        return Path.of(relativePath);
    }

    static String optStr(com.fasterxml.jackson.databind.JsonNode node, String field, String def) {
        var v = node.get(field);
        return v != null ? v.asText() : def;
    }

    static int optInt(com.fasterxml.jackson.databind.JsonNode node, String field, int def) {
        var v = node.get(field);
        return v != null ? v.asInt() : def;
    }

    static double optDouble(com.fasterxml.jackson.databind.JsonNode node, String field, double def) {
        var v = node.get(field);
        return v != null ? v.asDouble() : def;
    }

    static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}