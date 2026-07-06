package com.meeting.conversation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 助手消息的 metadata 结构，用于存储思考过程、工具调用等信息。
 * 支持 chat（普通对话）和 rewrite（改写结果）两种类型。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentResponseMetadata(
        /** 消息类型：chat / rewrite */
        String type,
        /** 思考过程（chat 类型） */
        String thinking,
        /** 工具调用记录（chat 类型） */
        List<ToolCallRecord> toolCalls,
        /** 改写结果ID（rewrite 类型） */
        Long rewriteResultId
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallRecord(String id, String name, String result) {}
}
