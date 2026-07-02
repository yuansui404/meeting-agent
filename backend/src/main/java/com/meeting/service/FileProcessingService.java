package com.meeting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

@Service
public class FileProcessingService {

    private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

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

    @Value("${file.upload-dir}")
    private String uploadDir;

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
        Path uploadPath = Paths.get(uploadDir, subDir, "user", dateStr);
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
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", videoPath.toString(),
                    "-vn", "-acodec", "pcm_s16le",
                    "-ar", "16000", "-ac", "1",
                    "-y", audioPath.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                log.error("FFmpeg audio extraction failed for {}: {}", videoPath, error);
            } else {
                log.info("Audio extracted: {} -> {}", videoPath, audioPath);
            }
        } catch (Exception e) {
            log.error("Audio extraction failed for {}: {}", videoPath, e.getMessage());
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
            Path mdPath = Paths.get(uploadDir, subDir, "assistant", dateStr, mdFilename);
            Files.createDirectories(mdPath.getParent());

            String now = LocalDateTime.now().toString();
            String mdContent = String.format("""
                    # 会议纪要：%s

                    - **文件名**: %s
                    - **时间**: %s
                    - **所属对话**: %s

                    ---

                    ## 转写内容

                    %s
                    """,
                    title, title, now, "对话 " + dialogueId,
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
}
