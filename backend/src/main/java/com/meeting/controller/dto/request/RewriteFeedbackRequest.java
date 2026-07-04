package com.meeting.controller.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RewriteFeedbackRequest(
        @NotNull(message = "rewriteResultId 不能为空") Long rewriteResultId,
        @NotNull(message = "paragraphIndex 不能为空") Integer paragraphIndex,
        @NotBlank(message = "action 不能为空") String action
) {}
