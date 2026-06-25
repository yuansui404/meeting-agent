package com.meeting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private final MeetingMinutesRepository meetingRepository;
    private final FileProcessingService fileProcessingService;
    private final MeetingDateExtractor meetingDateExtractor;

    @Value("${funasr.url}")
    private String funasrUrl;

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

                // Call FunASR WebSocket service
                String result = callFunASR(audioPath);

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

    public String callFunASR(String audioPath) {
        Path path = Path.of(audioPath);
        if (!Files.exists(path)) {
            return "转写失败: 音频文件不存在 " + audioPath;
        }

        try {
            // Read WAV file and extract raw PCM data
            byte[] wavBytes = Files.readAllBytes(path);
            if (wavBytes.length < 44) {
                return "转写失败: 无效的 WAV 文件";
            }

            // Parse sample rate from WAV header (bytes 24-27, little-endian)
            int sampleRate = (wavBytes[24] & 0xFF)
                    | ((wavBytes[25] & 0xFF) << 8)
                    | ((wavBytes[26] & 0xFF) << 16)
                    | ((wavBytes[27] & 0xFF) << 24);

            // Find "data" chunk (scan after the RIFF/WAVE header)
            int dataOffset = -1;
            for (int i = 12; i < wavBytes.length - 8; ) {
                int chunkLen = (wavBytes[i + 4] & 0xFF)
                        | ((wavBytes[i + 5] & 0xFF) << 8)
                        | ((wavBytes[i + 6] & 0xFF) << 16)
                        | ((wavBytes[i + 7] & 0xFF) << 24);
                if (wavBytes[i] == 'd' && wavBytes[i + 1] == 'a'
                        && wavBytes[i + 2] == 't' && wavBytes[i + 3] == 'a') {
                    dataOffset = i + 8;
                    break;
                }
                i += 8 + chunkLen;
                if (chunkLen % 2 != 0) i++; // padding byte
            }

            if (dataOffset < 0) {
                return "转写失败: 未找到音频数据块";
            }

            byte[] pcmData = Arrays.copyOfRange(wavBytes, dataOffset, wavBytes.length);

            // Build WebSocket URI from funasrUrl (e.g. http://funasr-service:10095)
            URI configUri = URI.create(funasrUrl);
            String wsUri = "ws://" + configUri.getHost() + ":" + configUri.getPort() + "/";

            StringBuilder transcript = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            ObjectMapper objectMapper = new ObjectMapper();

            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUri), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            try {
                                JsonNode json = objectMapper.readTree(data.toString());
                                String mode = json.has("mode") ? json.get("mode").asText() : "";
                                String text = json.has("text") ? json.get("text").asText() : "";
                                boolean isFinal = json.has("is_final") && json.get("is_final").asBoolean();
                                // Only collect offline (non-streaming) results to avoid duplicates
                                if (!text.isEmpty() && !"2pass-online".equals(mode)) {
                                    transcript.append(text);
                                }
                                if (isFinal) {
                                    latch.countDown();
                                }
                            } catch (Exception e) {
                                log.warn("Parse FunASR response error: {}", data, e);
                            }
                            return WebSocket.Listener.super.onText(webSocket, data, last);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            log.error("FunASR WebSocket error", error);
                            latch.countDown();
                        }
                    })
                    .get(10, TimeUnit.SECONDS);

            // Calculate chunk stride (~60ms audio per chunk)
            // Default config: chunk_size=[5,10,5], chunk_interval=10
            int stride = (int) (60L * 10 / 10 / 1000.0 * sampleRate * 2);

            // Send initial config
            ObjectNode config = objectMapper.createObjectNode();
            config.put("mode", "2pass");
            config.put("wav_name", path.getFileName().toString());
            config.put("wav_format", "pcm");
            config.put("audio_fs", sampleRate);
            ArrayNode chunkSize = config.putArray("chunk_size");
            chunkSize.add(5);
            chunkSize.add(10);
            chunkSize.add(5);
            config.put("chunk_interval", 10);
            config.put("is_speaking", true);
            config.put("hotwords", "");
            config.put("itn", true);
            ws.sendText(config.toString(), true);

            // Send audio chunks
            for (int offset = 0; offset < pcmData.length; offset += stride) {
                int end = Math.min(offset + stride, pcmData.length);
                ws.sendBinary(ByteBuffer.wrap(pcmData, offset, end - offset), true);
                Thread.sleep(60);
            }

            // Send end signal
            ObjectNode endMsg = objectMapper.createObjectNode();
            endMsg.put("is_speaking", false);
            ws.sendText(endMsg.toString(), true);

            // Wait for result (max 5 minutes)
            if (!latch.await(300, TimeUnit.SECONDS)) {
                log.warn("FunASR transcription timed out for {}", audioPath);
            }
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "ok");

            String result = transcript.toString().trim();
            return !result.isEmpty() ? result : "转写完成（无文本输出）";
        } catch (Exception e) {
            log.error("FunASR transcription failed for {}", audioPath, e);
            return "转写失败: " + e.getMessage();
        }
    }

    public String getTranscription(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .map(MeetingMinutes::getTranscription)
                .orElse(null);
    }
}
