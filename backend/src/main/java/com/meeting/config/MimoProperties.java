package com.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mimo")
public record MimoProperties(
        String apiKey,
        String url
) {}
