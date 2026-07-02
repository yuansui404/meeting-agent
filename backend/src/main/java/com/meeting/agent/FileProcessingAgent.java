package com.meeting.agent;

import com.meeting.service.FileProcessingService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

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

    public Map<String, Object> processFile(MultipartFile file, Long dialogueId) {
        try {
            return fileProcessingService.saveDialogueFile(file, dialogueId);
        } catch (java.io.IOException e) {
            throw new RuntimeException("File processing failed: " + e.getMessage(), e);
        }
    }
}
