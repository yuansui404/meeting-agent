package com.meeting.controller.dto.response;

import java.time.LocalDateTime;

public record MeetingDetailVO(
        Long id,
        String title,
        String transcription,
        Integer duration,
        Long fileSize,
        String status,
        LocalDateTime createdAt,
        Long dialogueId,
        String mdFilePath,
        LocalDateTime meetingDate
) {}
