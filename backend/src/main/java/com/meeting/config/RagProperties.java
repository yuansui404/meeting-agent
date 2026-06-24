package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Evidence evidence = new Evidence();
    private TimeDecay timeDecay = new TimeDecay();
    private Conversation conversation = new Conversation();

    @Data
    public static class Chunk {
        private String strategy = "structural";
        private int size = 512;
        private int overlap = 128;
    }

    @Data
    public static class Retrieval {
        private int vectorTopk = 20;
        private int ftsTopk = 20;
        private int rrfK = 60;
        private boolean rerankEnabled = false;
        private int rerankTopk = 5;
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
}
