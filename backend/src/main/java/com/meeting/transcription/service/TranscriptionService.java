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
import java.util.concurrent.TimeUnit;

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
            log.info("Starting transcription: fileName={}, dialogueId={}, filePath={}",
                    fileName, dialogueId, filePath);
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
            // 1. Get audio duration via ffprobe, split into segments if needed
            double durationSec = getAudioDuration(path);
            int segmentDuration = (int) Math.ceil(Math.min(durationSec, 600)); // max 10min/seg (~3800 tokens)

            // For short audio, process directly
            if (durationSec <= segmentDuration) {
                return transcribeSegment(path);
            }

            // 2. Find natural split points using silence detection
            double[] splitPoints = findSplitPointsBySilence(path, durationSec, segmentDuration);
            log.info("Audio duration={}s, {} split points: {}", (int) durationSec, splitPoints.length,
                    Arrays.stream(splitPoints).mapToObj(s -> String.format("%.0fs", s)).collect(java.util.stream.Collectors.joining(", ")));

            // 3. Split long audio into segments and transcribe each
            List<String> transcripts = new ArrayList<>();
            int segIndex = 0;
            double segStart = 0;
            for (double splitAt : splitPoints) {
                Path segPath = path.resolveSibling(path.getFileName() + ".seg" + segIndex + ".mp3");
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg", "-i", path.toString(),
                            "-ss", String.valueOf(segStart),
                            "-to", String.valueOf(splitAt),
                            "-c:a", "libmp3lame", "-b:a", "32k",
                            "-y", segPath.toString()
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(120, TimeUnit.SECONDS);
                    String ffmpegOut = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (!finished || p.exitValue() != 0 || !Files.exists(segPath)) {
                        log.warn("Segment {} extraction failed (exit={}): {}", segIndex, p.exitValue(), ffmpegOut);
                        break;
                    }
                    log.info("Transcribing segment {} ({}-{}s, {}KB)", segIndex, (int)segStart, (int)splitAt, Files.size(segPath) / 1024);
                    String text = transcribeSegment(segPath);
                    if (text != null && !text.startsWith("转写失败")) {
                        transcripts.add(text);
                        log.info("Segment {} transcribed ({} chars)", segIndex, text.length());
                    } else {
                        log.warn("Segment {} transcription failed: {}", segIndex, text);
                    }
                    segIndex++;
                    segStart = splitAt;
                } finally {
                    try { Files.deleteIfExists(segPath); } catch (Exception ignored) {}
                }
            }

            // Last segment (if any remaining audio)
            if (segStart < durationSec) {
                Path segPath = path.resolveSibling(path.getFileName() + ".seg" + segIndex + ".mp3");
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg", "-i", path.toString(),
                            "-ss", String.valueOf(segStart),
                            "-c:a", "libmp3lame", "-b:a", "32k",
                            "-y", segPath.toString()
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(120, TimeUnit.SECONDS);
                    if (finished && p.exitValue() == 0 && Files.exists(segPath)) {
                        String text = transcribeSegment(segPath);
                        if (text != null && !text.startsWith("转写失败")) {
                            transcripts.add(text);
                            log.info("Last segment {} transcribed ({} chars)", segIndex, text.length());
                        }
                    }
                } finally {
                    try { Files.deleteIfExists(segPath); } catch (Exception ignored) {}
                }
            }

            if (transcripts.isEmpty()) {
                return "转写失败: 所有分段均失败";
            }
            return String.join("\n\n", transcripts);
        } catch (Exception e) {
            log.error("MiMo ASR failed for {}: {}", audioPath, e.getMessage());
            return "转写失败: " + e.getMessage();
        }
    }

    private double[] findSplitPointsBySilence(Path path, double durationSec, int targetSegmentSec) {
        java.util.List<Double> silenceEnds = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", path.toString(),
                    "-af", "silencedetect=noise=-30dB:d=0.5",
                    "-f", "null", "-"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);

            // Parse silence_end lines: "silence_end: 123.456 | silence_duration: 2.345"
            for (String line : output.split("\n")) {
                if (line.contains("silence_end")) {
                    try {
                        String[] parts = line.split("silence_end: ")[1].split(" \\|");
                        double silenceEnd = Double.parseDouble(parts[0].trim());
                        if (line.contains("silence_duration:")) {
                            String durPart = line.split("silence_duration: ")[1].trim();
                            double dur = Double.parseDouble(durPart);
                            if (dur >= 1.0) {
                                silenceEnds.add(silenceEnd);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Silence detection failed, falling back to uniform split: {}", e.getMessage());
        }

        // Build split points: near each target boundary, find nearest silence_end within 30s window
        java.util.List<Double> splitPoints = new ArrayList<>();
        int searchWindow = 30;
        for (double target = targetSegmentSec; target < durationSec; target += targetSegmentSec) {
            double best = target;
            double minDist = Double.MAX_VALUE;
            for (double se : silenceEnds) {
                double dist = Math.abs(se - target);
                if (dist <= searchWindow && dist < minDist) {
                    minDist = dist;
                    best = se;
                }
            }
            if (!splitPoints.isEmpty() && best <= splitPoints.get(splitPoints.size() - 1)) {
                continue;
            }
            splitPoints.add(best);
        }

        return splitPoints.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private double getAudioDuration(Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    path.toString()
            );
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(10, TimeUnit.SECONDS);
            return Double.parseDouble(output);
        } catch (Exception e) {
            log.warn("Failed to get audio duration, assuming 300s: {}", e.getMessage());
            return 300;
        }
    }

    private String transcribeSegment(Path audioInput) throws Exception {
        // 1. Read and base64 encode
        String base64Audio;
        try (InputStream fis = Files.newInputStream(audioInput)) {
            byte[] audioBytes = fis.readAllBytes();
            base64Audio = Base64.getEncoder().encodeToString(audioBytes);
        }

        // 2. Build request (MiMo official format)
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", "mimo-v2.5-asr");
        requestBody.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_audio",
                        "input_audio", Map.of(
                                "data", "data:audio/mp3;base64," + base64Audio
                        )
                ))
        )));
        requestBody.put("asr_options", Map.of("language", "auto"));

        // 5. HTTP POST with retry on 429
        String apiUrl = mimoProps.url() + "/v1/chat/completions";
        ObjectMapper mapper = new ObjectMapper();
        int maxRetries = 3;
        int retryDelayMs = 5000;
        int lastResponseCode = 0;
        String lastErrorBody = "";

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (attempt > 0) {
                log.info("MiMo ASR retry {}/{} after {}ms", attempt + 1, maxRetries, retryDelayMs);
                Thread.sleep(retryDelayMs);
                retryDelayMs *= 2;
            }

            HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("api-key", mimoProps.apiKey());
                conn.setDoOutput(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(300000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(mapper.writeValueAsBytes(requestBody));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 429 && attempt < maxRetries - 1) {
                    lastResponseCode = responseCode;
                    String errorBody = "(no error body)";
                    try (InputStream errStream = conn.getErrorStream()) {
                        if (errStream != null) {
                            errorBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    lastErrorBody = errorBody;
                    log.warn("MiMo ASR rate limited (429), will retry: {}", errorBody);
                    continue;
                }

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

                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
                String text = root.path("choices").path(0).path("message").path("content").asText(null);
                if (text == null || text.isBlank()) {
                    log.warn("MiMo ASR response missing content field: {}", responseBody);
                    return "转写完成（无文本输出）";
                }
                return text;
            } finally {
                conn.disconnect();
            }
        }

        log.warn("MiMo ASR failed after {} retries: HTTP {}", maxRetries, lastResponseCode);
        return "转写失败: 限流重试 " + maxRetries + " 次后仍失败 (" + lastResponseCode + "): " + lastErrorBody;
    }

}
