package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Search search = new Search();
    private Evidence evidence = new Evidence();
    private TimeDecay timeDecay = new TimeDecay();
    private Conversation conversation = new Conversation();
    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Data
    public static class Chunk {
        private String strategy = "structural";
        private int size = 512;
    }

    @Data
    public static class Retrieval {
        private int vectorTopk = 10;
        private int ftsTopk = 10;
        private int rrfK = 60;
        private boolean rerankEnabled = false;
        private int rerankTopk = 5;
    }

    @Data
    public static class Search {
        /** 搜索方法：vector-only | hybrid */
        private String method = "hybrid";
        /** 是否开启 BGE 重排序 */
        private boolean rerankEnabled = true;
        /** 是否开启查询改写/分解 */
        private boolean queryRewriteEnabled = true;
    }

    @Data
    public static class Evidence {
        private double threshold = 0.7;
        private boolean adaptive = true;
        private double adaptiveStep = 0.05;
        private double adaptiveMax = 0.85;
    }

    @Data
    public static class TimeDecay {
        private boolean enabled = true;
        private int recentDays = 30;
        private double recentWeight = 1.2;
        private double normalWeight = 1.0;
        private double oldWeight = 0.8;
        private double archiveWeight = 0.5;
    }

    @Data
    public static class Conversation {
        private int summaryTrigger = 3000;
        private int maxVisibleMessages = 10;
        private int maxContextTokens = 32000;
    }

    @Data
    public static class Retry {
        /** 最大重试次数（含首次） */
        private int maxAttempts = 3;
        /** 首次重试延迟（毫秒） */
        private long initialDelayMs = 1000;
        /** 退避倍数 */
        private double multiplier = 2.0;
    }

    @Data
    public static class CircuitBreaker {
        /** 连续失败多少次后熔断 */
        private int failureThreshold = 3;
        /** 熔断冷却时间（毫秒） */
        private long cooldownMs = 60000;
    }
}