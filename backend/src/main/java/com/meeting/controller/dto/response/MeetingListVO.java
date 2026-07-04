package com.meeting.controller.dto.response;

import java.time.LocalDateTime;

public record MeetingListVO(
        Long id,
        String title,
        String status,
        LocalDateTime createdAt,
        LocalDateTime meetingDate,
        Long fileSize,
        String participants
) {}
