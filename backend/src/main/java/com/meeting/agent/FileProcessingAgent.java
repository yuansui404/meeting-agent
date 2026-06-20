package com.meeting.agent;

import com.meeting.entity.MeetingMinutes;
import com.meeting.service.FileProcessingService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件处理 Agent
 * 负责文件上传验证、音频提取、临时文件清理
 */
@Component
public class FileProcessingAgent {

    private final FileProcessingService fileProcessingService;

    public FileProcessingAgent(FileProcessingService fileProcessingService) {
        this.fileProcessingService = fileProcessingService;
    }

    public MeetingMinutes processFile(MultipartFile file) {
        try {
            return fileProcessingService.uploadFile(file);
        } catch (java.io.IOException e) {
            throw new RuntimeException("File processing failed: " + e.getMessage(), e);
        }
    }

    public void cleanup(Long meetingId) {
        fileProcessingService.cleanupFile(meetingId);
    }
}
