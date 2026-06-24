package com.meeting.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meeting.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DeepSeekChatClient {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DeepSeekChatClient(@Value("${deepseek.api-key:}") String apiKey,
                              @Value("${deepseek.model:deepseek-chat}") String model,
                              @Value("${deepseek.url:https://api.deepseek.com}") String baseUrl,
                              ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    public JsonNode chat(List<Map<String, String>> messages, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", 2048);
        body.put("stream", stream);

        ArrayNode msgArray = body.putArray("messages");
        messages.forEach(m -> {
            ObjectNode msg = msgArray.addObject();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
        });

        try {
            String json = webClient.post()
                    .uri(baseUrl + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                            .filter(e -> e.getMessage() != null && e.getMessage().contains("429")))
                    .timeout(Duration.ofSeconds(30))
                    .block();

            return objectMapper.readTree(json);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                throw BusinessException.rateLimited("当前处理繁忙，请稍后再试");
            }
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                throw BusinessException.timeout("处理超时，请重新发送");
            }
            log.error("DeepSeek API call failed", e);
            throw new BusinessException("AI 服务调用失败");
        }
    }
}
