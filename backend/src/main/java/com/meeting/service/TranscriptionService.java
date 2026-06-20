package com.meeting.service;

import com.meeting.entity.MeetingMinutes;
import com.meeting.repository.MeetingMinutesRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class TranscriptionService {

    private final MeetingMinutesRepository meetingRepository;
    private final RestTemplate restTemplate;

    @Value("${funasr.url}")
    private String funasrUrl;

    public TranscriptionService(MeetingMinutesRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
        this.restTemplate = new RestTemplate();
    }

    @Async
    public void startTranscription(Long meetingId) {
        meetingRepository.findById(meetingId).ifPresent(meeting -> {
            try {
                meeting.setStatus("processing");
                meetingRepository.save(meeting);

                // 调用 FunASR 服务
                String result = callFunASR(meeting.getFilePath());

                meeting.setTranscription(result);
                meeting.setStatus("completed");
                meetingRepository.save(meeting);
            } catch (Exception e) {
                meeting.setStatus("failed");
                meetingRepository.save(meeting);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public String callFunASR(String audioPath) {
        try {
            Map<String, Object> request = Map.of("audio_path", audioPath, "enable_speaker_diarization", true);
            Map<String, Object> response = restTemplate.postForObject(
                    funasrUrl + "/transcribe", request, Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                return (String) response.get("text");
            }
            return "转写失败: " + (response != null ? response.get("message") : "no response");
        } catch (Exception e) {
            return "转写失败: " + e.getMessage();
        }
    }

    public String getTranscription(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .map(MeetingMinutes::getTranscription)
                .orElse(null);
    }
}
