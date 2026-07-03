package com.meeting.service;

/**
 * SSE 事件协议定义，消除 ChatStreamService 中的魔法字符串。
 */
public final class SseEventTypes {

    private SseEventTypes() {}

    public enum SseEventType {
        TEXT_DELTA,
        THINKING_DELTA,
        TOOL_CALL,
        TOOL_RESULT,
        DONE,
        ERROR
    }

    public record SseToolEvent(String action, String id, String name) {}

    public record SseToolResultEvent(String action, String id, String name, String delta) {}
}
