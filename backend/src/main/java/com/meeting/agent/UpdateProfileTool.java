package com.meeting.agent;

import com.meeting.service.ProfileService;
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
public class UpdateProfileTool implements AgentTool {

    private final ProfileService profileService;

    @Override
    public String getName() {
        return "update_profile";
    }

    @Override
    public String getDescription() {
        return "更新用户画像（Profile）信息。当用户表达个人偏好、习惯用语、工作信息，或要求 agent 记住某些信息时使用。可以创建新文件或追加/修改已有文件的内容。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filename", Map.of(
                "type", "string",
                "description", "文件名（如 我的偏好.md），必须以 .md 结尾"
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "要保存的内容（Markdown 格式）"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("filename", "content")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String filename = input.getOrDefault("filename", "").toString();
        String content = input.getOrDefault("content", "").toString();

        if (filename.isBlank() || !filename.endsWith(".md")) {
            return Mono.just(ToolResultBlock.text("文件名必须以 .md 结尾"));
        }
        if (content.isBlank()) {
            return Mono.just(ToolResultBlock.text("内容不能为空"));
        }

        try {
            // Read once to avoid TOCTOU race condition
            String existing = null;
            try {
                existing = profileService.readFile(filename);
            } catch (IllegalArgumentException e) {
                // file does not exist
            }

            if (existing != null) {
                String updated = existing.trim() + "\n\n" + content.trim();
                profileService.saveFile(filename, updated);
                log.info("Updated profile file: {}", filename);
            } else {
                profileService.saveFile(filename, content);
                log.info("Created profile file: {}", filename);
            }

            return Mono.just(ToolResultBlock.text("已成功更新「" + filename.replace(".md", "") + "」信息。"));
        } catch (Exception e) {
            log.warn("Update profile failed: {}", e.getMessage());
            return Mono.just(ToolResultBlock.error("更新用户画像异常，请稍后重试"));
        }
    }
}
