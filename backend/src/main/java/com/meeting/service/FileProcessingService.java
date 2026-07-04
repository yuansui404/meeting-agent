package com.meeting.service;

import com.meeting.config.FileProperties;
import com.meeting.document.service.DocumentTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessingService {

    private static final Set<String> VIDEO_FORMATS = Set.of(".mp4", ".avi", ".mov", ".mkv", ".webm", ".wmv", ".flv");
    private static final Set<String> AUDIO_FORMATS = Set.of(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".flac");
    private static final Set<String> DOCUMENT_FORMATS = Set.of(".pdf", ".doc", ".docx", ".txt", ".md", ".csv", ".xlsx", ".pptx");
    private static final Set<String> IMAGE_FORMATS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg");

    private static final Set<String> ALL_FORMATS = new java.util.HashSet<>();
    static {
        ALL_FORMATS.addAll(VIDEO_FORMATS);
        ALL_FORMATS.addAll(AUDIO_FORMATS);
        ALL_FORMATS.addAll(DOCUMENT_FORMATS);
        ALL_FORMATS.addAll(IMAGE_FORMATS);
    }

    private final FileProperties fileProps;

    /**
     * Save a dialogue file to disk without creating a MeetingMinutes record.
     * Returns file metadata for storage in state_json.
     */
    public Map<String, Object> saveDialogueFile(MultipartFile file, Long dialogueId) throws IOException {
        if (dialogueId == null) {
            throw new IllegalArgumentException("dialogueId is required");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("File name is required");
        }

        String ext = getExtension(filename);
        if (!ALL_FORMATS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file format: " + ext);
        }

        // Archive: {upload-dir}/dialogue-{id}/user/{yyyy-MM-dd}/{uuid}_{filename}
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String subDir = "dialogue-" + dialogueId;
        Path uploadPath = Paths.get(fileProps.uploadDir(), subDir, "user", dateStr);
        Files.createDirectories(uploadPath);

        String fileId = UUID.randomUUID().toString();
        String savedFilename = fileId + "_" + filename;
        Path filePath = uploadPath.resolve(savedFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> result = new HashMap<>();
        result.put("fileId", fileId);
        result.put("fileName", filename);
        result.put("filePath", filePath.toString());
        result.put("fileSize", file.getSize());
        result.put("ext", ext);
        result.put("status", isTranscribable(ext) ? "processing" : "completed");
        return result;
    }

    /**
     * Extract audio from a video file using ffmpeg.
     * Returns the path to the extracted WAV file.
     */
    public Path extractAudio(Path videoPath) {
        Path audioPath = getAudioPath(videoPath);
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", videoPath.toString(),
                    "-vn", "-acodec", "pcm_s16le",
                    "-ar", "16000", "-ac", "1",
                    "-y", audioPath.toString()
            );
            pb.redirectErrorStream(true);
            process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);

            if (!finished) {
                log.error("FFmpeg audio extraction timed out for {}", videoPath);
                return audioPath;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                log.error("FFmpeg audio extraction failed for {}: {}", videoPath, error);
            } else {
                log.info("Audio extracted: {} -> {}", videoPath, audioPath);
            }
        } catch (Exception e) {
            log.error("Audio extraction failed for {}: {}", videoPath, e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return audioPath;
    }

    /**
     * Generate a formatted markdown summary of transcription content.
     * Writes to {upload-dir}/dialogue-{id}/assistant/{yyyy-MM-dd}/{timestamp}.md
     */
    public String generateMarkdown(String transcription, String title, Long dialogueId) {
        try {
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String mdFilename = "transcription-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".md";
            String subDir = "dialogue-" + dialogueId;
            Path mdPath = Paths.get(fileProps.uploadDir(), subDir, "assistant", dateStr, mdFilename);
            Files.createDirectories(mdPath.getParent());

            String now = LocalDateTime.now().toString();
            String safeTitle = title != null ? title.replace("<", "&lt;").replace(">", "&gt;") : "未知";
            String mdContent = String.format("""
                    # 会议纪要：%s

                    - **文件名**: %s
                    - **时间**: %s
                    - **所属对话**: %s

                    ---

                    ## 转写内容

                    %s
                    """,
                    safeTitle, safeTitle, now, "对话 " + dialogueId,
                    transcription != null ? transcription : "（暂无转写内容）");

            Files.writeString(mdPath, mdContent, StandardCharsets.UTF_8);
            return mdPath.toString();
        } catch (IOException e) {
            log.error("Failed to generate markdown for dialogue {}", dialogueId, e);
            return null;
        }
    }

    public static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    public static boolean isTranscribable(String ext) {
        return VIDEO_FORMATS.contains(ext) || AUDIO_FORMATS.contains(ext);
    }

    public static boolean isVideo(String ext) {
        return VIDEO_FORMATS.contains(ext);
    }

    public static boolean isAudio(String ext) {
        return AUDIO_FORMATS.contains(ext);
    }

    public static boolean isDocument(String ext) {
        return DOCUMENT_FORMATS.contains(ext);
    }

    public static boolean isImage(String ext) {
        return IMAGE_FORMATS.contains(ext);
    }

    public Path getAudioPath(Path videoPath) {
        String name = videoPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String baseName = dot >= 0 ? name.substring(0, dot) : name;
        return videoPath.getParent().resolve(baseName + ".wav");
    }

    private static final Set<String> TEXT_FORMATS = Set.of(
            ".txt", ".md", ".csv", ".json", ".xml", ".html", ".yaml", ".yml", ".properties", ".log");
    private static final Set<String> DOC_READER_FORMATS = Set.of(".pdf", ".doc", ".docx");

    public static String readFileContent(Path filePath, String ext) {
        try {
            if (TEXT_FORMATS.contains(ext)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            } else if (DOC_READER_FORMATS.contains(ext)) {
                return DocumentTextExtractor.extractText(filePath, ext);
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to read file content: {}", e.getMessage());
            return null;
        }
    }

    public static String readFileContentWithSidecar(Path filePath, String ext) {
        String content = readFileContent(filePath, ext);
        if (content == null && isTranscribable(ext)) {
            try {
                Path transcriptionPath = Path.of(filePath.toString() + ".transcription.md");
                if (Files.exists(transcriptionPath)) {
                    content = Files.readString(transcriptionPath, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("Failed to read transcription sidecar: {}", e.getMessage());
            }
        }
        return content;
    }

    public static boolean isPathSafe(Path path, String uploadDir) {
        try {
            Path uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
            Path resolved = path.toAbsolutePath().normalize();
            return resolved.startsWith(uploadRoot);
        } catch (Exception e) {
            return false;
        }
    }
}
