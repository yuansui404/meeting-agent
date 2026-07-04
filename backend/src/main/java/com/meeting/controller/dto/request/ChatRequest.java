package com.meeting.controller.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank(message = "消息不能为空") String message,
        List<Long> fileIds,
        List<FileInfo> files
) {
    public record FileInfo(String fileId, String fileName, String filePath, String ext) {}
}
