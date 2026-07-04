package com.meeting.controller.dto.response;

public record FileUploadVO(
        String fileId,
        String fileName,
        String filePath,
        Long fileSize,
        Long dialogueId
) {}
