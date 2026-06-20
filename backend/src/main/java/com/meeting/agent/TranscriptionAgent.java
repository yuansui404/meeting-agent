package com.meeting.agent;

import com.meeting.service.TranscriptionService;
import org.springframework.stereotype.Component;

/**
 * 语音转写 Agent
 * 负责调用 FunASR 服务进行语音识别和说话人识别
 */
@Component
public class TranscriptionAgent {

    private final TranscriptionService transcriptionService;

    public TranscriptionAgent(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    public void startTranscription(Long meetingId) {
        transcriptionService.startTranscription(meetingId);
    }

    public String getTranscription(Long meetingId) {
        return transcriptionService.getTranscription(meetingId);
    }
}
