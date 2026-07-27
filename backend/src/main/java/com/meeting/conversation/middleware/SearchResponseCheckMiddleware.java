package com.meeting.conversation.middleware;

import com.meeting.common.RetryUtils;
import com.meeting.config.DeepSeekProperties;
import com.meeting.config.RagProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.OpenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索场景的 response-checker 强制拦截中间件。
 * 在 onReasoning 中检测 search_agent 已返回但 response_checker 未调用时，
 * 拦截 LLM 的文本响应流，自动调用 DeepSeek API 执行校验。
 * PASS → 放行；FAIL → 注入修正指令并拦截，让 LLM 在下一轮重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchResponseCheckMiddleware implements MiddlewareBase {

    private final OpenAIClient openAIClient;
    private final DeepSeekProperties deepSeekProps;
    private final RagProperties ragProperties;

    private static final int MAX_CHECK_RETRIES = 3;
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

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

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx,
                                         ReasoningInput input,
                                         Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 检测：是否 spawn 过 search_agent 且未调用 response_checker
        if (!hasSearchSpawned(input) || hasCheckerBeenCalled(input)) {
            return next.apply(input);
        }

        String sessionId = ctx.getSessionId();

        // 包装响应流：收集当前轮 LLM 生成的所有事件，检查后再释放
        return next.apply(input)
                .collectList()
                .flatMapMany(events -> {
                    // LLM 本轮调用了 response_checker → 放行
                    if (events.stream()
                            .filter(e -> e instanceof ToolCallStartEvent)
                            .anyMatch(e -> "response_checker".equals(((ToolCallStartEvent) e).getToolCallName()))) {
                        retryCounts.remove(sessionId);
                        return Flux.fromIterable(events);
                    }

                    // 提取文本回复
                    String draftResponse = events.stream()
                            .filter(e -> e instanceof TextBlockDeltaEvent)
                            .map(e -> ((TextBlockDeltaEvent) e).getDelta())
                            .collect(Collectors.joining());

                    // 无文本回复（纯工具调用）→ 放行
                    if (draftResponse.isBlank()) {
                        return Flux.fromIterable(events);
                    }

                    // 自动调用 checker
                    String checkResult = callChecker(input, draftResponse);

                    if (checkResult != null && checkResult.contains("PASS")) {
                        retryCounts.remove(sessionId);
                        return Flux.fromIterable(events);
                    }

                    // 检查未通过
                    int retries = retryCounts.merge(sessionId, 1, Integer::sum);
                    if (retries >= MAX_CHECK_RETRIES) {
                        log.warn("Response check failed after {} retries, letting through", retries);
                        retryCounts.remove(sessionId);
                        return Flux.fromIterable(events);
                    }

                    log.warn("Response check FAILED (retry {}/{}): {}", retries, MAX_CHECK_RETRIES, checkResult);
                    // 注入修正指令，让 LLM 在下一轮修正
                    agent.observe(Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .textContent("你的回答未通过忠实度校验（" + (checkResult != null ? checkResult : "未知错误") + "）。"
                                    + "请根据检索资料修正回答，修正后重新调用 response_checker 校验。")
                            .build()).subscribe();
                    // 返回空事件流，不推给用户
                    return Flux.empty();
                });
    }

    /**
     * 检查消息历史中是否曾 spawn 过 search_agent。
     */
    private boolean hasSearchSpawned(ReasoningInput input) {
        return input.messages().stream()
                .anyMatch(msg -> msg.getContentBlocks(ToolUseBlock.class).stream()
                        .anyMatch(tc -> "agent_spawn".equals(tc.getName())
                                && tc.getInput() != null
                                && "search_agent".equals(tc.getInput().get("agent_id"))));
    }

    /**
     * 检查消息历史中是否已调用过 response_checker。
     */
    private boolean hasCheckerBeenCalled(ReasoningInput input) {
        return input.messages().stream()
                .anyMatch(msg -> msg.getContentBlocks(ToolUseBlock.class).stream()
                        .anyMatch(tc -> "response_checker".equals(tc.getName())));
    }

    /**
     * 调用 DeepSeek API 执行响应校验。
     */
    private String callChecker(ReasoningInput input, String draftResponse) {
        String userQuestion = extractUserQuestion(input);
        String searchResults = extractSearchResults(input);

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
                            OpenAIMessage.builder().role("system").content(CHECKER_SYSTEM_PROMPT).build(),
                            OpenAIMessage.builder().role("user").content(userPrompt).build()
                    ))
                    .temperature(0.0)
                    .maxTokens(512)
                    .build();

            RagProperties.Retry retryConfig = ragProperties.getRetry();
            OpenAIResponse response = RetryUtils.retryWithBackoff("MiddlewareResponseCheck",
                    retryConfig.getMaxAttempts(), retryConfig.getInitialDelayMs(), () ->
                            openAIClient.call(deepSeekProps.getApiKey(), deepSeekProps.getUrl(), request));
            String content = response.getFirstChoice().getMessage().getContentAsString();
            return content != null && !content.isBlank() ? content.trim() : "PASS";
        } catch (Exception e) {
            log.warn("Middleware response check API call failed: {}", e.getMessage());
            return "PASS"; // API 调用失败时放行，避免阻塞用户
        }
    }

    /**
     * 提取最后一个用户消息。
     */
    private String extractUserQuestion(ReasoningInput input) {
        return input.messages().stream()
                .filter(msg -> msg.getRole() == MsgRole.USER)
                .reduce((first, second) -> second)
                .map(Msg::getTextContent)
                .orElse("");
    }

    /**
     * 提取 search_agent 返回的检索结果文本。
     */
    private String extractSearchResults(ReasoningInput input) {
        return input.messages().stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .filter(tr -> "agent_spawn".equals(tr.getName()))
                .findFirst()
                .map(tr -> tr.getOutput().stream()
                        .filter(cb -> cb instanceof TextBlock)
                        .map(cb -> ((TextBlock) cb).getText())
                        .collect(Collectors.joining("\n")))
                .orElse("");
    }
}