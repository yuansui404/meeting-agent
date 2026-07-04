package com.meeting.controller.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateTitleRequest(
        @NotBlank(message = "标题不能为空") String title
) {}
