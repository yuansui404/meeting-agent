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

    public String getTranscription(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .map(MeetingMinutes::getTranscription)
                .orElse(null);
    }
}
