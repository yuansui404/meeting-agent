package com.meeting.agent;

import com.meeting.service.TranscriptionService;
import org.springframework.stereotype.Component;

/**
 * 语音转写 Agent
 * 负责调用 MiMo-V2.5-ASR 服务进行语音识别
 */
@Component
public class TranscriptionAgent {

    private final TranscriptionService transcriptionService;

    public TranscriptionAgent(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    public void startTranscription(String filePath, String fileName, Long dialogueId) {
        transcriptionService.startTranscription(filePath, fileName, dialogueId);
    }
}
