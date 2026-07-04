package com.meeting.controller.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AddMessageRequest(
        @NotBlank(message = "role 不能为空") String role,
        @NotBlank(message = "content 不能为空") String content,
        String messageType
) {}
