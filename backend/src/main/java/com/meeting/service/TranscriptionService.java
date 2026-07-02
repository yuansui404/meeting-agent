package com.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.OpenAIClient;
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
import java.util.*;

@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

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

    private final OpenAIClient openAIClient = new OpenAIClient();

    public TranscriptionService(FileProcessingService fileProcessingService,
                                MeetingDateExtractor meetingDateExtractor) {
        this.fileProcessingService = fileProcessingService;
        this.meetingDateExtractor = meetingDateExtractor;
    }

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

            // Transcription self-check: validate person names, terms, numbers
            if (result != null && !result.isEmpty() && !result.startsWith("转写失败")
                    && deepseekApiKey != null && !deepseekApiKey.isBlank()) {
                try {
                    String corrected = performTranscriptionSelfCheck(result);
                    if (corrected != null && !corrected.equals(result)) {
                        log.info("Transcription self-check corrected {} errors for {}",
                                countDifferences(result, corrected), fileName);
                        result = corrected;
                    }
                } catch (Exception e) {
                    log.warn("Transcription self-check failed for {}: {}", fileName, e.getMessage());
                }
            }

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
            OpenAIRequest request = OpenAIRequest.builder()
                    .model("deepseek-chat")
                    .messages(List.of(
                            OpenAIMessage.builder().role("system")
                                    .content("你是一个严谨的语音转写文本校对专家。").build(),
                            OpenAIMessage.builder().role("user").content(checkPrompt).build()
                    ))
                    .temperature(0.1)
                    .maxTokens(8192)
                    .build();

            OpenAIResponse response = openAIClient.call(deepseekApiKey, deepseekApiUrl, request);
            String corrected = response.getFirstChoice().getMessage().getContentAsString();

            if (corrected == null || corrected.contains("无需修改") || corrected.contains("无需修正")) {
                return text;
            }

            log.info("Transcription self-check found corrections: original={} chars, corrected={} chars",
                    text.length(), corrected.length());
            return corrected;
        } catch (Exception e) {
            log.warn("Transcription self-check OpenAIClient call failed: {}", e.getMessage());
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
}
