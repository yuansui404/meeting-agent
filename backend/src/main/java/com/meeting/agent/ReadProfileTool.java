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
        return "读取用户画像（Profile）的内容。不传 filename 则读取全部文件，传 filename 则只读取指定文件。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filename", Map.of(
                "type", "string",
                "description", "可选，指定要读取的文件名（如 与会人.md）。不传则读取全部文件。"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of()
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String filename = input.containsKey("filename") ? input.get("filename").toString() : null;

            if (filename != null && !filename.isBlank()) {
                return readSingleFile(filename);
            }
            return readAllFiles();
        });
    }

    private ToolResultBlock readSingleFile(String filename) {
        try {
            String content = profileService.readFile(filename);
            String label = filename.replace(".md", "");
            return ToolResultBlock.text("=== " + label + " ===\n" + content);
        } catch (Exception e) {
            return ToolResultBlock.text("读取文件失败: " + e.getMessage());
        }
    }

    private ToolResultBlock readAllFiles() {
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
    }
}
