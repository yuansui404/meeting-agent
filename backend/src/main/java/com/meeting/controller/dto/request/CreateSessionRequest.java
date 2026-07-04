package com.meeting.controller.dto.request;

public record CreateSessionRequest(
        String title,
        Long meetingId
) {}
