package com.meeting.agent;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import com.meeting.conversation.service.DocxExportService;
import com.meeting.template.model.entity.TemplateEntity;
import com.meeting.template.service.TemplateService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExportDocxTool implements AgentTool {

    private final DocxExportService docxExportService;
    private final TemplateService templateService;
    private final FileProperties fileProperties;

    @Override
    public String getName() {
        return "export_docx";
    }

    @Override
    public String getDescription() {
        return "将改写后的会议纪要内容填入排版模板，导出为 .docx 文件。在用户要求导出或整理为 .docx 文件时调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", Map.of(
                "type", "string",
                "description", "改写后的会议纪要完整内容"
        ));
        properties.put("templateName", Map.of(
                "type", "string",
                "description", "模板名称（如「会议纪要模板」），不传则使用默认模板"
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
            String templateName = (String) input.get("templateName");

            if (content.isBlank()) {
                return ToolResultBlock.error("导出内容不能为空，请提供改写后的会议纪要内容");
            }

            // Resolve template
            TemplateEntity template = resolveTemplate(templateName);
            if (template == null) {
                return ToolResultBlock.error("未找到可用的排版模板，请先上传模板");
            }

            // Generate docx via DocxExportService
            Path tempFile = docxExportService.export(content, template.getId());

            // Copy to persistent export directory
            Path exportDir = Path.of(fileProperties.uploadDir(), "exports");
            Files.createDirectories(exportDir);

            String uuid = UUID.randomUUID().toString();
            String contentTitle = extractTitle(content);
            String filename = uuid + "_" + sanitizeFilename(contentTitle) + ".docx";
            Path exportPath = exportDir.resolve(filename);
            Files.copy(tempFile, exportPath, StandardCopyOption.REPLACE_EXISTING);

            // Clean temp file
            Files.deleteIfExists(tempFile);

            String downloadUrl = "/api/export/download/" + uuid;
            log.info("Docx exported successfully: {} -> {}", tempFile, exportPath);

            String result = String.format(
                    "文档已生成！下载链接：%s（模板：%s，文件名：%s）",
                    downloadUrl, template.getName(), filename);
            return ToolResultBlock.text(result);
        });
    }

    private TemplateEntity resolveTemplate(String templateName) {
        List<TemplateEntity> templates = templateService.list();
        if (templates.isEmpty()) {
            return null;
        }
        if (templateName != null && !templateName.isBlank()) {
            return templates.stream()
                    .filter(t -> templateName.equals(t.getName()))
                    .findFirst()
                    .orElse(templates.getFirst());
        }
        return templates.getFirst();
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }

    /**
     * 从内容中提取标题用于文件名。取第一个标题行（# 开头或加粗开头行），
     * 否则取第一个非空行，兜底返回"会议纪要"。
     */
    private String extractTitle(String content) {
        // 先找 # 或 ## 标题
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceAll("^#+\\s*", "").trim();
            }
        }
        // 再找 **xxx** 格式的强调行（有时标题用加粗表示）
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length() > 4) {
                return trimmed.replaceAll("\\*\\*", "").trim();
            }
        }
        // 取第一个非空行
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "会议纪要";
    }
}