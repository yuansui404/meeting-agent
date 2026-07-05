package com.meeting.transcription.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.config.MimoProperties;
import com.meeting.meeting.service.MeetingDateExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final FileProcessingService fileProcessingService;
    private final MeetingDateExtractor meetingDateExtractor;
    private final MimoProperties mimoProps;

    /**
     * Start asynchronous transcription for a dialogue audio/video file.
     * Writes the result to a sidecar file at {filePath}.transcription.md
     * and generates a formatted markdown summary.
     */
    @Async
    public void startTranscription(String filePath, String fileName, Long dialogueId) {
        try {
            String ext = FileProcessingService.getExtension(fileName);
            String audioPath = filePath;

            // Extract audio from video files
            if (FileProcessingService.isVideo(ext)) {
                Path extractedAudio = fileProcessingService.extractAudio(Path.of(filePath));
                audioPath = extractedAudio.toString();
            }

            // Call MiMo-V2.5-ASR HTTP API
            String result = callMiMoASR(audioPath);

            // Write transcription to sidecar file
            Path transcriptionPath = Path.of(filePath + ".transcription.md");
            String mdContent = String.format("""
                    # 转写结果：%s

                    - **文件名**: %s
                    - **所属对话**: %s

                    ---

                    ## 转写内容

                    %s
                    """,
                    fileName, fileName, "对话 " + dialogueId,
                    result != null ? result : "（转写失败）");
            Files.writeString(transcriptionPath, mdContent, StandardCharsets.UTF_8);
            log.info("Transcription saved to sidecar file: {}", transcriptionPath);

            // Generate formatted markdown in assistant directory
            fileProcessingService.generateMarkdown(result, fileName, dialogueId);

        } catch (Exception e) {
            log.error("Transcription failed for dialogue file {}: {}", fileName, e.getMessage());
        }
    }

    public String callMiMoASR(String audioPath) {
        Path path = Path.of(audioPath);
        if (!Files.exists(path)) {
            return "转写失败: 音频文件不存在 " + audioPath;
        }

        try {
            // 1. Check file size before reading into memory (hard limit at 200MB)
            long fileSize = Files.size(path);
            if (fileSize > 200 * 1024 * 1024) {
                return "转写失败: 文件过大 (" + (fileSize / 1024 / 1024) + "MB)，最大支持 200MB";
            }

            // 2. Read audio file and base64 encode (try-with-resources to ensure stream closure)
            String base64Audio;
            try (InputStream fis = Files.newInputStream(path)) {
                byte[] audioBytes = fis.readAllBytes();
                base64Audio = Base64.getEncoder().encodeToString(audioBytes);
            }
            String ext = audioPath.toLowerCase().endsWith(".wav") ? "wav" : "mp3";

            // 3. Build OpenAI-compatible request
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", "MiMo-V2.5-ASR");
            requestBody.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                            "type", "audio",
                            "audio", Map.of(
                                    "data", base64Audio,
                                    "format", ext
                            )
                    ))
            )));

            // 4. HTTP POST to MiMo API
            String apiUrl = mimoProps.url() + "/v1/chat/completions";
            HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("api-key", mimoProps.apiKey());
                conn.setDoOutput(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(300000);

                ObjectMapper mapper = new ObjectMapper();
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(mapper.writeValueAsBytes(requestBody));
                }

                // 5. Parse response
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    String errorBody = "(no error body)";
                    try (InputStream errStream = conn.getErrorStream()) {
                        if (errStream != null) {
                            errorBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    log.warn("MiMo ASR HTTP {}: {}", responseCode, errorBody);
                    return "转写失败: MiMo API 返回 " + responseCode;
                }

                String responseBody;
                try (InputStream is = conn.getInputStream()) {
                    responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.info("MiMo ASR raw response length={}", responseBody.length());

                // 6. Parse response using ObjectMapper instead of fragile string matching
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
                String text = root.path("choices").path(0).path("message").path("content").asText(null);
                if (text == null || text.isBlank()) {
                    log.warn("MiMo ASR response missing content field: {}", responseBody);
                    return "转写完成（无文本输出）";
                }
                log.info("MiMo ASR completed, text length={}", text.length());
                return text;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.error("MiMo ASR failed for {}: {}", audioPath, e.getMessage());
            return "转写失败: " + e.getMessage();
        }
    }

}
