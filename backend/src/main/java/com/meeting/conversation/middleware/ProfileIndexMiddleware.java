package com.meeting.conversation.middleware;

import com.meeting.user.service.ProfileService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class ProfileIndexMiddleware implements MiddlewareBase {

    private final ProfileService profileService;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        String index = profileService.buildFileIndex();
        if (index == null || index.isBlank()) {
            return next.apply(input);
        }

        List<Msg> msgs = new ArrayList<>(input.msgs());
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i).getRole() == MsgRole.USER) {
                Msg original = msgs.get(i);
                String enriched = index + "\n" + original.getTextContent();
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
}
