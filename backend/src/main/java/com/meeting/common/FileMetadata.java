package com.meeting.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FileMetadata(
        String fileId,
        String fileName,
        String filePath,
        String ext,
        Long fileSize,
        String status,
        String transcriptionFilePath
) {}
