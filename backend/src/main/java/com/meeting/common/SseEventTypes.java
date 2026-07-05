package com.meeting.common;

/**
 * SSE 事件协议定义，消除 ChatStreamService 中的魔法字符串。
 */
public final class SseEventTypes {

    private SseEventTypes() {}

    public enum SseEventType {
        TEXT_DELTA("text_delta"),
        THINKING_DELTA("thinking"),
        TOOL_CALL("tool_call"),
        TOOL_RESULT("tool_result"),
        DONE("done"),
        ERROR("error");

        private final String value;

        SseEventType(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record SseToolEvent(String action, String id, String name) {}

    public record SseToolResultEvent(String action, String id, String name, String delta) {}
}
