package com.meeting.transcription.service;

import com.meeting.common.FileMetadata;
import com.meeting.document.service.DocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    public String build(List<FileMetadata> messageFiles) {
        if (messageFiles == null || messageFiles.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【本次提交的文件 — 请重点参考这些文件回答】\n");
        int currentLength = sb.length();

        for (FileMetadata fm : messageFiles) {
            if (currentLength >= MAX_TOTAL_FILE_CONTEXT) {
                if (fm.fileName() != null) {
                    sb.append("【来源：").append(fm.fileName()).append("】（上下文长度限制，内容已省略）\n");
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

    private void appendFileContent(StringBuilder sb, FileMetadata fm) {
        if (fm.fileName() == null || fm.filePath() == null || fm.ext() == null) return;
        if (IMAGE_FORMATS.contains(fm.ext().toLowerCase())) return;

        long size = fm.fileSize() != null ? fm.fileSize() : 0;
        if (size > MAX_FILE_SIZE) {
            // For large audio/video files, try sidecar transcription file
            if (FileProcessingService.isTranscribable(fm.ext().toLowerCase())) {
                Path transcriptionPath = Path.of(fm.filePath() + ".transcription.md");
                if (Files.exists(transcriptionPath)) {
                    try {
                        String transcription = Files.readString(transcriptionPath, StandardCharsets.UTF_8);
                        if (transcription != null && !transcription.isBlank()) {
                            String preview = transcription.length() > MAX_PREVIEW_LENGTH
                                    ? transcription.substring(0, MAX_PREVIEW_LENGTH) + "..."
                                    : transcription;
                            sb.append("【来源：").append(fm.fileName()).append(" — 录音转写】\n").append(preview).append("\n\n");
                            return;
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read transcription sidecar file: {}", transcriptionPath);
                    }
                }
            }
            sb.append("【来源：").append(fm.fileName()).append("】（文件过大，跳过内容提取）\n");
            return;
        }

        String content = extractFileContent(Path.of(fm.filePath()), fm.ext().toLowerCase());

        // For audio/video files, check for sidecar transcription file
        if (content == null && FileProcessingService.isTranscribable(fm.ext().toLowerCase())) {
            Path transcriptionPath = Path.of(fm.filePath() + ".transcription.md");
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
            sb.append("【来源：").append(fm.fileName()).append("】\n").append(preview).append("\n\n");
        } else {
            sb.append("【来源：").append(fm.fileName()).append("】（无法提取文字内容）\n");
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
            log.warn("Failed to extract file content: {} (ext={})", filePath, ext, e);
            return null;
        }
    }
}
