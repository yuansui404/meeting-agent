package com.meeting.service;

import com.meeting.document.service.DocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责从文件元数据构建上下文文本，供 Agent 使用。
 * 从 ChatService 中抽取，独立管理文件读取、截断、拼接逻辑。
 */
@Slf4j
@Component
public class FileContextBuilder {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB per file
    private static final int MAX_TOTAL_FILE_CONTEXT = 10_000;   // 10K chars total cap
    private static final int MAX_PREVIEW_LENGTH = 2000;          // per-file preview

    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");
    private static final Set<String> TEXT_FORMATS = Set.of(".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties");
    private static final Set<String> DOC_FORMATS = Set.of(".pdf", ".doc", ".docx");

    /**
     * 构建文件上下文文本，包含文件内容摘要。
     * 总长度不超过 MAX_TOTAL_FILE_CONTEXT 字符，超限后只追加文件名。
     */
    public String build(List<Map<String, Object>> messageFiles) {
        if (messageFiles == null || messageFiles.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【本次提交的文件 — 请重点参考这些文件回答】\n");
        int currentLength = sb.length();

        for (Map<String, Object> fm : messageFiles) {
            if (currentLength >= MAX_TOTAL_FILE_CONTEXT) {
                String name = (String) fm.get("fileName");
                if (name != null) {
                    sb.append("【来源：").append(name).append("】（上下文长度限制，内容已省略）\n");
                }
            } else {
                int before = sb.length();
                appendFileContent(sb, fm);
                currentLength += (sb.length() - before);
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建带文件上下文的 enriched prompt。
     */
    public String buildEnrichedMessage(String fileContext, String userMessage) {
        if (fileContext == null || fileContext.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("请参考以下资料来回答问题。\n");
        sb.append("要求：\n");
        sb.append("1. 答案必须直接引用资料中的原文，不得添加资料中没有的信息\n");
        sb.append("2. 「本次提交的文件」是用户当前关注的重点，优先参考\n");
        sb.append("3. 「对话历史中的文件」仅在用户提及相关内容时参考\n");
        sb.append("\n资料内容：\n").append(fileContext);
        sb.append("\n\n问题：").append(userMessage);
        return sb.toString();
    }

    private void appendFileContent(StringBuilder sb, Map<String, Object> fm) {
        String name = (String) fm.get("fileName");
        String path = (String) fm.get("filePath");
        String ext = (String) fm.get("ext");
        if (name == null || path == null || ext == null) return;
        if (IMAGE_FORMATS.contains(ext.toLowerCase())) return;

        Number sizeNum = (Number) fm.get("fileSize");
        long size = sizeNum != null ? sizeNum.longValue() : 0;
        if (size > MAX_FILE_SIZE) {
            sb.append("【来源：").append(name).append("】（文件过大，跳过内容提取）\n");
            return;
        }

        String content = extractFileContent(Path.of(path), ext.toLowerCase());

        // For audio/video files, check for sidecar transcription file
        if (content == null && FileProcessingService.isTranscribable(ext.toLowerCase())) {
            Path transcriptionPath = Path.of(path + ".transcription.md");
            if (Files.exists(transcriptionPath)) {
                try {
                    content = Files.readString(transcriptionPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Failed to read transcription sidecar file: {}", transcriptionPath);
                }
            }
        }

        if (content != null && !content.isBlank()) {
            String preview = content.length() > MAX_PREVIEW_LENGTH
                    ? content.substring(0, MAX_PREVIEW_LENGTH) + "..."
                    : content;
            sb.append("【来源：").append(name).append("】\n").append(preview).append("\n\n");
        } else {
            sb.append("【来源：").append(name).append("】（无法提取文字内容）\n");
        }
    }

    private String extractFileContent(Path filePath, String ext) {
        try {
            if (TEXT_FORMATS.contains(ext)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            if (DOC_FORMATS.contains(ext)) {
                return DocumentTextExtractor.extractText(filePath, ext);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
