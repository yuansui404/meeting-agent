package com.meeting.eval;

import com.meeting.common.RetryUtils;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;

import java.util.List;

/**
 * Standalone LLM client for EvalRunner. No Spring dependency.
 * Uses SenseNova DeepSeek API (OpenAI-compatible).
 *
 * <p>API key is read from environment variable {@code SENSENOVA_API_KEY}
 * (fallback: {@code DEEPSEEK_API_KEY}).
 */
class EvalLlmClient {

    private static final String ENV_API_KEY = "SENSENOVA_API_KEY";
    private static final String ENV_API_KEY_FALLBACK = "DEEPSEEK_API_KEY";
    private static final String DEFAULT_URL = "https://token.sensenova.cn";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 1000;

    private final OpenAIClient client;
    private final String apiKey;
    private final String url;
    private final String model;

    EvalLlmClient() {
        this.client = new OpenAIClient();
        String key = System.getenv(ENV_API_KEY);
        if (key == null || key.isBlank()) {
            key = System.getenv(ENV_API_KEY_FALLBACK);
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable " + ENV_API_KEY + " (or " + ENV_API_KEY_FALLBACK + ") is not set. "
                            + "EvalRunner requires API key for LLM-based metrics. "
                            + "Use --skip-llm to skip LLM metrics.");
        }
        this.apiKey = key;
        this.url = DEFAULT_URL;
        this.model = DEFAULT_MODEL;
    }

    /**
     * Call LLM with system + user prompt, return the response text.
     */
    String call(String systemPrompt, String userPrompt, int maxTokens) {
        OpenAIRequest request = OpenAIRequest.builder()
                .model(model)
                .messages(List.of(
                        OpenAIMessage.builder().role("system").content(systemPrompt).build(),
                        OpenAIMessage.builder().role("user").content(userPrompt).build()
                ))
                .temperature(0.0)
                .maxTokens(maxTokens)
                .build();

        OpenAIResponse response = RetryUtils.retryWithBackoff(
                "EvalLLM", MAX_ATTEMPTS, INITIAL_DELAY_MS,
                () -> client.call(apiKey, url, request));

        if (response == null || response.getFirstChoice() == null
                || response.getFirstChoice().getMessage() == null) {
            return "";
        }
        String content = response.getFirstChoice().getMessage().getContentAsString();
        return content != null ? content.trim() : "";
    }
}