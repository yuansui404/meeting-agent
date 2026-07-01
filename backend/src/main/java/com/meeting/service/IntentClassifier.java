package com.meeting.service;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    public enum Intent {
        REWRITE,
        SEARCH_KB,
        CHAT
    }

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final OpenAIClient openAIClient;

    public IntentClassifier(@Value("${deepseek.api-key:}") String apiKey,
                            @Value("${deepseek.url:https://api.deepseek.com}") String apiUrl,
                            @Value("${deepseek.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.openAIClient = new OpenAIClient();
    }

    /**
     * Classify user message intent. Returns a list of intents ordered by execution priority.
     * Falls back to [CHAT] on any error.
     */
    public List<Intent> classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of(Intent.CHAT);
        }

        String prompt = buildClassificationPrompt(userMessage);
        try {
            OpenAIRequest request = OpenAIRequest.builder()
                    .model(model)
                    .messages(List.of(
                            OpenAIMessage.builder().role("system").content("你是一个意图分类器，分析用户消息返回对应的意图名称。").build(),
                            OpenAIMessage.builder().role("user").content(prompt).build()
                    ))
                    .temperature(0.0)
                    .maxTokens(32)
                    .build();

            OpenAIResponse response = openAIClient.call(apiKey, apiUrl, request);
            String content = response.getFirstChoice().getMessage().getContentAsString();
            return parseIntents(content);
        } catch (Exception e) {
            log.warn("Intent classification failed, falling back to CHAT: {}", e.getMessage());
            return List.of(Intent.CHAT);
        }
    }

    private String buildClassificationPrompt(String userMessage) {
        return """
分析以下用户消息的意图。

可用意图:
- rewrite: 用户要求改写、润色、重写、修稿会议纪要
- search_kb: 用户明确要查询知识库中的会议内容、决策、讨论记录
- chat: 其他所有对话（包括上传文件、问问题、闲聊等）

输出格式: 用逗号分隔的意图列表，按执行顺序排列。
仅返回意图名称，不要任何其他内容。

示例:
- "改一下这个纪要" → rewrite
- "于总上周说了什么" → search_kb
- "先查ICT决策，再改写成纪要" → search_kb,rewrite
- "帮我上传文件" → chat
- "你好" → chat

用户消息: %s
""".formatted(userMessage);
    }

    private List<Intent> parseIntents(String response) {
        if (response == null) return List.of(Intent.CHAT);

        String trimmed = response.trim().toLowerCase();
        if (trimmed.isBlank()) return List.of(Intent.CHAT);

        String[] parts = trimmed.split(",");
        List<Intent> intents = new ArrayList<>();
        for (String part : parts) {
            part = part.trim();
            switch (part) {
                case "rewrite" -> intents.add(Intent.REWRITE);
                case "search_kb" -> intents.add(Intent.SEARCH_KB);
                case "chat" -> intents.add(Intent.CHAT);
                default -> log.debug("Unknown intent token: {}", part);
            }
        }

        // Deduplicate adjacent identical intents
        List<Intent> deduplicated = new ArrayList<>();
        for (Intent intent : intents) {
            if (deduplicated.isEmpty() || deduplicated.get(deduplicated.size() - 1) != intent) {
                deduplicated.add(intent);
            }
        }

        if (deduplicated.isEmpty()) return List.of(Intent.CHAT);
        return deduplicated;
    }
}
