package com.meeting.config;

import io.agentscope.core.model.OpenAIClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAIClientConfig {

    /**
     * OpenAIClient 从环境变量 OPENAI_API_KEY / OPENAI_BASE_URL 读取配置。
     */
    @Bean
    public OpenAIClient openAIClient() {
        return new OpenAIClient();
    }
}
