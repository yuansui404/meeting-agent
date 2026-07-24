package com.meeting.agent;

import com.meeting.common.RetryUtils;
import com.meeting.config.DeepSeekProperties;
import com.meeting.config.RagProperties;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.OpenAIClient;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseCheckTool implements AgentTool {

    private static final String CHECKER_SYSTEM_PROMPT = """
            你是一个严谨的答案质量检查员。只检查回答是否忠实于检索到的资料内容。

            检查内容：
            1. 答案中的信息是否都有检索资料支持
            2. 答案是否准确回应了用户问题

            输出规则：
            - 所有信息都有来源 → 回复「PASS」
            - 有信息无来源 → 列出具体哪些信息无来源
            - 如果不确定 → 回复「PASS」
            """;

    private final OpenAIClient openAIClient;
    private final DeepSeekProperties deepSeekProps;
    private final RagProperties ragProperties;

    @Override
    public String getName() {
        return "response_checker";
    }

    @Override
    public String getDescription() {
        return "对搜索结果进行一致性校验。比对[检索资料]和[拟回复内容]的一致性，确保拟回复内容不包含检索资料以外的信息。"
                + "在搜索场景中，根据检索结果草拟回答后，必须调用此工具进行校验。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("userQuestion", Map.of(
                "type", "string",
                "description", "用户原始问题"
        ));
        properties.put("searchResults", Map.of(
                "type", "string",
                "description", "检索到的资料内容（来自 search_agent 或 search_documents 的返回结果）"
        ));
        properties.put("draftResponse", Map.of(
                "type", "string",
                "description", "拟回复用户的内容草稿"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("userQuestion", "searchResults", "draftResponse")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String userQuestion = (String) input.getOrDefault("userQuestion", "");
            String searchResults = (String) input.getOrDefault("searchResults", "");
            String draftResponse = (String) input.getOrDefault("draftResponse", "");

            if (userQuestion.isBlank() || searchResults.isBlank() || draftResponse.isBlank()) {
                return ToolResultBlock.error("缺少必要参数：userQuestion, searchResults, draftResponse");
            }

            String userPrompt = String.format("""
                    用户问题：
                    %s

                    检索资料：
                    %s

                    拟回复内容：
                    %s

                    请检查拟回复内容中的信息是否都有检索资料支持，并准确回应了用户问题。""",
                    userQuestion, searchResults, draftResponse);

            try {
                OpenAIRequest request = OpenAIRequest.builder()
                        .model(deepSeekProps.getModel())
                        .messages(List.of(
                                OpenAIMessage.builder().role("system")
                                        .content(CHECKER_SYSTEM_PROMPT).build(),
                                OpenAIMessage.builder().role("user")
                                        .content(userPrompt).build()
                        ))
                        .temperature(0.0)
                        .maxTokens(512)
                        .build();

                RagProperties.Retry retryConfig = ragProperties.getRetry();
                OpenAIResponse response = RetryUtils.retryWithBackoff("ResponseCheck",
                        retryConfig.getMaxAttempts(), retryConfig.getInitialDelayMs(), () ->
                                openAIClient.call(deepSeekProps.getApiKey(), deepSeekProps.getUrl(), request));
                String content = response.getFirstChoice().getMessage().getContentAsString();

                if (content == null || content.isBlank()) {
                    return ToolResultBlock.text("PASS");
                }

                return ToolResultBlock.text(content.trim());
            } catch (Exception e) {
                log.warn("Response check LLM call failed: {}", e.getMessage());
                return ToolResultBlock.error("检查失败: " + e.getMessage());
            }
        });
    }
}