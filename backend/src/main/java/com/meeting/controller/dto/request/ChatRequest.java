package com.meeting.controller.dto.request;

import com.meeting.common.FileMetadata;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank(message = "消息不能为空") String message,
        List<FileMetadata> files
) {}
