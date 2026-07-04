package com.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "embedding")
public record EmbeddingProperties(
        String provider,
        String apiKey,
        String model,
        String url
) {}
