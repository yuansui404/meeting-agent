package com.meeting.conversation.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 搜索场景的 response-checker 调用提醒中间件。
 * 在 agent_spawn 前（onActing）和 search_agent 结果回传后（onReasoning）两个关键点
 * 注入提醒，确保 LLM 在搜索完成后不会遗忘调用 response-checker 进行一致性校验。
 */
@Slf4j
@Component
public class SearchCheckReminderMiddleware implements MiddlewareBase {

    private static final String REMINDER =
            "[System Reminder] 搜索完成后，请在最终回答前使用 response_checker 工具进行一致性校验。";

    private static final String MANDATORY_REMINDER =
            "[System] 搜索代理已返回结果。在回复用户之前，你必须：\n" +
            "1. 根据检索结果草拟回答\n" +
            "2. 调用 response_checker(userQuestion, searchResults, draftResponse) 进行校验\n" +
            "3. 校验通过（PASS）后再回复用户。不得跳过此步骤。";

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx,
                                     ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        boolean isSpawn = input.toolCalls().stream()
                .anyMatch(tc -> "agent_spawn".equals(tc.getName()));

        if (isSpawn) {
            log.debug("Search sub-agent about to be spawned, injecting response-checker reminder");
            agent.observe(Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .textContent(REMINDER)
                    .build()).subscribe();
        }

        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx,
                                        ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 检查是否发起了 search_agent 的 spawn
        boolean hasSearchSpawn = input.messages().stream()
                .anyMatch(msg -> msg.getContentBlocks(ToolUseBlock.class).stream()
                        .anyMatch(tc -> "agent_spawn".equals(tc.getName())
                                && tc.getInput() != null
                                && "search_agent".equals(tc.getInput().get("agent_id"))));

        // 检查 response_checker 是否已被调用
        boolean hasCheckerCalled = input.messages().stream()
                .anyMatch(msg -> msg.getContentBlocks(ToolUseBlock.class).stream()
                        .anyMatch(tc -> "response_checker".equals(tc.getName())));

        if (hasSearchSpawn && !hasCheckerCalled) {
            log.debug("Search results detected without response_checker, injecting mandatory reminder");
            input.messages().add(Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .textContent(MANDATORY_REMINDER)
                    .build());
        }

        return next.apply(input);
    }
}