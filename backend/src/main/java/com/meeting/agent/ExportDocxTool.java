package com.meeting.agent;

import com.meeting.config.FileProperties;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExportDocxTool implements AgentTool {

    private final FileProperties fileProperties;

    @Override
    public String getName() {
        return "export_docx";
    }

    @Override
    public String getDescription() {
        return "将改写后的会议纪要内容导出为 .md 文件并返回下载链接。在用户要求导出或整理文件时调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", Map.of(
                "type", "string",
                "description", "改写后的会议纪要完整内容（markdown 格式）"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("content")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String content = (String) input.getOrDefault("content", "");

            if (content.isBlank()) {
                return ToolResultBlock.error("导出内容不能为空，请提供改写后的会议纪要内容");
            }

            // Save to persistent export directory as .md
            Path exportDir = Path.of(fileProperties.uploadDir(), "exports");
            Files.createDirectories(exportDir);

            String uuid = UUID.randomUUID().toString();
            String contentTitle = extractTitle(content);
            String filename = uuid + "_" + sanitizeFilename(contentTitle) + ".md";
            Path exportPath = exportDir.resolve(filename);
            Files.writeString(exportPath, content, StandardCharsets.UTF_8);

            String downloadUrl = "/api/export/download/" + uuid;
            log.info("Markdown exported successfully: {} -> {}", filename, exportPath);

            String result = String.format(
                    "文档已生成！下载链接：%s（文件名：%s）",
                    downloadUrl, filename);
            return ToolResultBlock.text(result);
        });
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }

    /**
     * 从内容中提取标题用于文件名。取第一个标题行（# 开头或加粗开头行），
     * 否则取第一个非空行，兜底返回"会议纪要"。
     */
    private String extractTitle(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceAll("^#+\\s*", "").trim();
            }
        }
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length() > 4) {
                return trimmed.replaceAll("\\*\\*", "").trim();
            }
        }
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "会议纪要";
    }
}