package com.meeting.agent;

import com.meeting.user.service.ProfileService;
import io.agentscope.core.message.ToolResultBlock;
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
public class ReadProfileTool implements AgentTool {

    private final ProfileService profileService;

    @Override
    public String getName() {
        return "read_profile";
    }

    @Override
    public String getDescription() {
        return "读取用户画像（Profile）的全部内容。当需要了解用户的偏好、习惯用语、个人背景或任何已记录的用户信息时使用。返回所有 .md 文件的完整内容。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            List<String> files = profileService.listFiles();
            if (files.isEmpty()) {
                return ToolResultBlock.text("用户画像为空，没有已记录的信息。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("用户画像内容：\n\n");
            for (String filename : files) {
                try {
                    String content = profileService.readFile(filename);
                    String label = filename.replace(".md", "");
                    sb.append("=== ").append(label).append(" ===\n");
                    sb.append(content).append("\n\n");
                } catch (Exception e) {
                    log.warn("Failed to read profile file {}: {}", filename, e.getMessage());
                }
            }

            return ToolResultBlock.text(sb.toString());
        });
    }
}
