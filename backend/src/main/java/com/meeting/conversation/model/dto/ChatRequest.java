package com.meeting.conversation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatRequest {
    @NotNull
    private Long conversationId;
    @NotBlank
    private String content;
}
