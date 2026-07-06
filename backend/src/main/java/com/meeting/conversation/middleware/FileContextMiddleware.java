package com.meeting.conversation.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.event.AgentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 从 RuntimeContext 读取 fileContext，注入到最后一条 user 消息中。
 * 这样 UserMessage 只存原始问题（state_json 干净），模型运行时仍能看到文件内容。
 */
@Component
public class FileContextMiddleware implements MiddlewareBase {

    private static final String PREFIX = "请参考以下资料来回答问题。";
    private static final String REQUIREMENTS = """
            要求：
            1. 答案必须直接引用资料中的原文，不得添加资料中没有的信息
            2. 「本次提交的文件」是用户当前关注的重点，优先参考
            3. 「对话历史中的文件」仅在用户提及相关内容时参考
            """;
    private static final String QUESTION_DELIMITER = "\n\n问题：";

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        String fileContext = ctx.get("fileContext");
        if (fileContext == null || fileContext.isBlank()) {
            return next.apply(input);
        }

        List<Msg> msgs = new ArrayList<>(input.msgs());
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MsgRole.USER) {
                Msg original = msgs.get(i);
                String enriched = buildEnrichedMessage(fileContext, original.getTextContent());
                msgs.set(i, Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(enriched)
                        .metadata(original.getMetadata())
                        .build());
                break;
            }
        }
        return next.apply(new AgentInput(msgs));
    }

    private String buildEnrichedMessage(String fileContext, String userMessage) {
        if (fileContext == null || fileContext.isEmpty()) {
            return userMessage;
        }
        return PREFIX + "\n" + REQUIREMENTS + "\n资料内容：\n" + fileContext + QUESTION_DELIMITER + userMessage;
    }
}
