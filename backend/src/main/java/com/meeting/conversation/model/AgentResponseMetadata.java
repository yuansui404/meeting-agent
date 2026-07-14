package com.meeting.conversation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 助手消息的 metadata 结构，用于存储思考过程、工具调用等信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentResponseMetadata(
        /** 消息类型：chat / rewrite */
        String type,
        /** 思考过程（chat 类型） */
        String thinking,
        /** 工具调用记录（chat 类型） */
        List<ToolCallRecord> toolCalls
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallRecord(String id, String name, String result) {}
}
