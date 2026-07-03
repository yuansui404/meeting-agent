package com.meeting.config;

import io.agentscope.core.model.OpenAIClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAIClientConfig {

    @Bean
    public OpenAIClient openAIClient() {
        return new OpenAIClient();
    }
}
