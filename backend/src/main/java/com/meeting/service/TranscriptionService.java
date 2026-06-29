package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private final MeetingMinutesRepository meetingRepository;
    private final FileProcessingService fileProcessingService;
    private final MeetingDateExtractor meetingDateExtractor;

    @Value("${mimo.api-key:}")
    private String mimoApiKey;

    @Value("${mimo.url:https://token-plan-cn.xiaomimimo.com}")
    private String mimoUrl;

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.url:https://api.deepseek.com}")
    private String deepseekApiUrl;

    public TranscriptionService(MeetingMinutesRepository meetingRepository,
                                FileProcessingService fileProcessingService,
                                MeetingDateExtractor meetingDateExtractor) {
        this.meetingRepository = meetingRepository;
        this.fileProcessingService = fileProcessingService;
        this.meetingDateExtractor = meetingDateExtractor;
    }

    @Async
    public void startTranscription(Long meetingId) {
        meetingRepository.findById(meetingId).ifPresent(meeting -> {
            try {
                meeting.setStatus("processing");
                meetingRepository.save(meeting);

                String audioPath = meeting.getFilePath();
                String ext = FileProcessingService.getExtension(meeting.getTitle());

                // Extract audio from video files
                if (FileProcessingService.isVideo(ext)) {
                    fileProcessingService.extractAudio(meetingId);
                    Path audioFilePath = fileProcessingService.getAudioPath(Paths.get(meeting.getFilePath()));
                    audioPath = audioFilePath.toString();
                }

                // Call MiMo-V2.5-ASR HTTP API
                String result = callMiMoASR(audioPath);

                // Transcription self-check: validate person names, terms, numbers
                if (result != null && !result.isEmpty() && !result.startsWith("转写失败")
                        && deepseekApiKey != null && !deepseekApiKey.isBlank()) {
                    try {
                        String corrected = performTranscriptionSelfCheck(result);
                        if (corrected != null && !corrected.equals(result)) {
                            log.info("Transcription self-check corrected {} errors for meeting {}",
                                    countDifferences(result, corrected), meetingId);
                            result = corrected;
                        }
                    } catch (Exception e) {
                        log.warn("Transcription self-check failed for {}: {}", meetingId, e.getMessage());
                    }
                }

                meeting.setTranscription(result);
                meeting.setStatus("completed");
                meetingRepository.save(meeting);

                // Extract meeting date from transcript
                try {
                    var md = meetingDateExtractor.extract(result);
                    if (md != null) {
                        meeting.setMeetingDate(md);
                        meetingRepository.save(meeting);
                    }
                } catch (Exception e) {
                    log.warn("Meeting date extraction failed for transcription {}: {}", meetingId, e.getMessage());
                }

                // Generate markdown file
                try {
                    String mdPath = fileProcessingService.generateMarkdown(meeting);
                    if (mdPath != null) {
                        meeting.setMdFilePath(mdPath);
                        meetingRepository.save(meeting);
                    }
                } catch (Exception me) {
                    log.warn("Markdown generation failed for meeting {}", meetingId, me);
                }

            } catch (Exception e) {
                log.error("Transcription failed for meeting {}", meetingId, e);
                meeting.setStatus("failed");
                meetingRepository.save(meeting);
            }
        });
    }

    public String callMiMoASR(String audioPath) {
        Path path = Path.of(audioPath);
        if (!Files.exists(path)) {
            return "转写失败: 音频文件不存在 " + audioPath;
        }

        try {
            // 1. Read audio file and base64 encode
            byte[] audioBytes = Files.readAllBytes(path);
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
            String ext = audioPath.toLowerCase().endsWith(".wav") ? "wav" : "mp3";

            // 2. Build OpenAI-compatible request
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

            // 3. HTTP POST to MiMo API
            String apiUrl = mimoUrl + "/v1/chat/completions";
            HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("api-key", mimoApiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(300000);

            ObjectMapper mapper = new ObjectMapper();
            try (OutputStream os = conn.getOutputStream()) {
                os.write(mapper.writeValueAsBytes(requestBody));
            }

            // 4. Parse response
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("MiMo ASR HTTP {}: {}", responseCode, errorBody);
                return "转写失败: MiMo API 返回 " + responseCode;
            }

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.info("MiMo ASR raw response length={}", responseBody.length());

            // Extract content from response: {"choices":[{"message":{"content":"..."}}]}
            int contentStart = responseBody.indexOf("\"content\":\"");
            if (contentStart < 0) {
                log.warn("MiMo ASR response missing content field: {}", responseBody);
                return "转写完成（无文本输出）";
            }
            contentStart += 11;
            int contentEnd = responseBody.indexOf("\"", contentStart);
            if (contentEnd < 0) return "转写完成（无文本输出）";

            String text = responseBody.substring(contentStart, contentEnd);
            log.info("MiMo ASR completed, text length={}", text.length());
            return text;
        } catch (Exception e) {
            log.error("MiMo ASR failed for {}: {}", audioPath, e.getMessage());
            return "转写失败: " + e.getMessage();
        }
    }

    /**
     * Self-check: validate transcription text via DeepSeek for person names,
     * technical terminology, numbers, and other common ASR errors.
     */
    private String performTranscriptionSelfCheck(String text) {
        String checkPrompt = """
                你是一个语音识别文本校对专家。请检查以下会议转写文本，修正：

                1. 人名拼写错误（如"张弢"而非"张涛"、"李钜"而非"李炬"等常见人名错误）
                2. 专业术语错误（ICT/金融/法律等行业术语）
                3. 数字错误（日期、金额、百分比等）
                4. 同音字/近音字错误
                5. 明显的语法或断句问题

                注意事项：
                - 只修正确定有误的内容，不要随意改动
                - 保持原文风格和语气
                - 不要添加原文没有的信息
                - 如果无需修改，回复「无需修改」

                转写文本：
                %s
                """.formatted(text);

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", "deepseek-chat");
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", "你是一个严谨的语音转写文本校对专家。"),
                    Map.of("role", "user", "content", checkPrompt)
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 8192);

            ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(requestBody);

            HttpURLConnection conn = (HttpURLConnection) URI.create(deepseekApiUrl + "/chat/completions").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + deepseekApiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) return text;

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Parse JSON response
            int contentStart = responseBody.indexOf("\"content\":\"");
            if (contentStart < 0) return text;
            contentStart += 11;
            int contentEnd = responseBody.indexOf("\"", contentStart);
            if (contentEnd < 0) return text;

            String corrected = responseBody.substring(contentStart, contentEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r");

            if (corrected.contains("无需修改") || corrected.contains("无需修正")) {
                return text;
            }

            log.info("Transcription self-check found corrections: original={} chars, corrected={} chars",
                    text.length(), corrected.length());
            return corrected;
        } catch (Exception e) {
            log.warn("Transcription self-check HTTP call failed: {}", e.getMessage());
            return text;
        }
    }

    /**
     * Rough character-by-character difference count between two strings.
     */
    private int countDifferences(String original, String corrected) {
        int len = Math.min(original.length(), corrected.length());
        int diffs = Math.abs(original.length() - corrected.length());
        for (int i = 0; i < len; i++) {
            if (original.charAt(i) != corrected.charAt(i)) diffs++;
        }
        return diffs;
    }

    public String getTranscription(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .map(MeetingMinutes::getTranscription)
                .orElse(null);
    }
}
