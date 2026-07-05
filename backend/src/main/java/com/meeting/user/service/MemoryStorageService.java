package com.meeting.user.service;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryStorageService {

    private static final int MAX_CONTENT_LENGTH = 100_000;

    private final FileProperties fileProps;
    private Path memoryFile;

    @PostConstruct
    public void init() {
        this.memoryFile = Path.of(fileProps.uploadDir(), "memory", "MEMORY.md");
    }

    public String read() {
        if (!Files.exists(memoryFile)) {
            return "";
        }
        try {
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取记忆文件失败: {}", memoryFile, e);
            throw BusinessException.processingFailed("读取记忆文件失败", e);
        }
    }

    public synchronized void write(String content) {
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException("内容过长，最大10万字符");
        }
        try {
            Files.createDirectories(memoryFile.getParent());
            Files.writeString(memoryFile, content != null ? content : "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("保存记忆文件失败: {}", memoryFile, e);
            throw BusinessException.processingFailed("保存记忆文件失败", e);
        }
    }
}
