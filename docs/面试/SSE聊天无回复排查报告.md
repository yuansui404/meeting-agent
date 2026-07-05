# SSE 聊天无回复排查报告

## 1. 问题描述

**现象**：用户在对话界面与 AI 聊天时，发送消息后没有任何回复。通过 curl 直接调用 `POST /api/dialogue/{id}/chat` 端点，只收到 `event:done`，没有收到任何 AI 回复文本。

**影响范围**：所有对话的 AI 聊天功能完全不可用。

**环境**：Docker Compose 部署，后端 Spring Boot + agentscope 2.0.0-RC3，模型 DeepSeek V4 Flash。

---

## 2. 排查过程

### 2.1 第一步：确认服务状态

```bash
curl http://localhost:8080/api/health
# {"status":"OK","service":"meeting-agent"}
```

后端健康检查通过，服务正常运行。

### 2.2 第二步：确认 agent 是否生成了回复

查看后端日志，发现 agent **确实生成了回复**：

```
INFO  [MeetingAssistant] POST_REASONING | text: 你好！有什么可以帮到你的吗？
INFO  [MeetingAssistant] POST_CALL | response: 你好！有什么可以帮到你的吗？
```

但 curl 只收到了：

```
event:done
data:
```

**结论**：agent 生成了回复，但 SSE 流没有把回复传递给客户端。

### 2.3 第三步：定位 SSE 事件转发逻辑

核心文件：`ChatStreamService.java`

原代码使用 `agent.streamEvents()` 获取事件流，然后通过 `forwardEvent()` 方法中的 `instanceof` 链分发事件：

```java
agent.streamEvents(msg, ctx)
    .doOnNext(event -> forwardEvent(emitter, event, ...))
    .blockLast();
```

`forwardEvent()` 方法只处理了以下事件类型：
- `TextBlockDeltaEvent` — 文本增量
- `ThinkingBlockDeltaEvent` — 思考过程
- `ToolCallStartEvent` / `ToolCallEndEvent` — 工具调用
- `ToolResultXxx` — 工具结果

**其他事件类型被静默丢弃**（没有 else 分支，也没有日志）。

### 2.4 第四步：添加诊断日志

在 `forwardEvent()` 中添加了未识别事件的 WARN 日志后，发现了完整的事件序列：

```
WARN  Unhandled: class=AgentStartEvent
WARN  Unhandled: class=ModelCallStartEvent
WARN  Unhandled: class=TextBlockStartEvent
WARN  Unhandled: class=TextBlockEndEvent
WARN  Unhandled: class=ModelCallEndEvent
WARN  Unhandled: class=AgentEndEvent
```

**关键发现**：

1. **没有 `TextBlockDeltaEvent`** — 代码中处理的事件类型根本没有被发出
2. **没有 `AgentResultEvent`** — 包含最终回复的事件也没有被发出
3. **所有事件的 `metadata` 都是 null** — 事件本身不携带任何文本内容
4. **事件只是生命周期标记**（start/end），不含实际数据

### 2.5 第五步：分析 agentscope 库的两套 API

通过反编译 `agentscope-core-2.0.0-RC3.jar`，发现了两套完全不同的 API：

| API | 返回类型 | 内容 |
|-----|---------|------|
| `agent.streamEvents()` | `Flux<AgentEvent>` | 生命周期事件（start/end），**无文本内容** |
| `agent.stream()` | `Flux<Event>` | 内容事件，**包含 `Msg message` 字段** |

`AgentEvent` 体系（`streamEvents()` 返回）：

```
AgentStartEvent          — 无内容
ModelCallStartEvent      — 无内容
TextBlockStartEvent      — 无内容（只有 replyId, blockId）
TextBlockEndEvent        — 无内容（只有 replyId, blockId）
ModelCallEndEvent        — 无内容（只有 usage）
AgentEndEvent            — 无内容
```

`Event` 体系（`stream()` 返回）：

```
Event {
    EventType type;     — REASONING / AGENT_RESULT / TOOL_RESULT / HINT / SUMMARY
    Msg message;        — 包含 getTextContent() 返回实际文本
    boolean isLast;
}
```

**根因**：代码使用了错误的 API。`streamEvents()` 只返回生命周期标记，不包含文本内容。应该使用 `stream()` API。

---

## 3. 根因总结

```
用户请求 → ChatController → ChatService → ChatStreamService.stream()
                                              ↓
                                    agent.streamEvents(msg, ctx)
                                              ↓
                                    Flux<AgentEvent>（只有生命周期事件，无文本）
                                              ↓
                                    forwardEvent() — 不匹配任何 instanceof → 静默丢弃
                                              ↓
                                    emitter.send(DONE) → 客户端只收到 done
```

**正确流程应该是**：

```
用户请求 → ChatController → ChatService → ChatStreamService.stream()
                                              ↓
                                    agent.stream(msg, ctx)
                                              ↓
                                    Flux<Event>（包含 Msg message，有文本内容）
                                              ↓
                                    REASONING → 收集文本（持久化用）
                                    AGENT_RESULT → 发送 SSE + 持久化
                                              ↓
                                    emitter.send(TEXT_DELTA) + emitter.send(DONE)
```

---

## 4. 修复方案

### 4.1 核心修改：切换 API

```java
// 修改前（错误）
agent.streamEvents(msg, ctx)    // 返回 Flux<AgentEvent>，无文本内容

// 修改后（正确）
agent.stream(msg, ctx)          // 返回 Flux<Event>，包含 Msg message
```

### 4.2 事件分发重构：Map 替代 instanceof 链

**修改前**（50 行 if-else 链）：

```java
if (event instanceof TextBlockDeltaEvent e) {
    // ...
} else if (event instanceof ThinkingBlockDeltaEvent e) {
    // ...
} else if (event instanceof ToolCallStartEvent e) {
    // ...
} // ... 10+ 个 else if
```

**修改后**（EnumMap 注册表）：

```java
private final Map<EventType, EventHandler> handlers = new EnumMap<>(EventType.class) {{
    put(EventType.REASONING,    ChatStreamService.this::collectText);
    put(EventType.AGENT_RESULT, ChatStreamService.this::streamAndPersist);
    put(EventType.TOOL_RESULT,  ChatStreamService.this::forwardToolResult);
}};

private void forwardEvent(Event event, SseEmitter emitter, ...) {
    EventHandler handler = handlers.get(event.getType());
    if (handler != null) handler.handle(event, emitter, fullResponse);
}
```

### 4.3 文本处理策略

| EventType | 处理方式 | 原因 |
|-----------|---------|------|
| `REASONING` | `collectText` — 只记录，不发 SSE | 推理过程可能包含累积文本，发给客户端会重复 |
| `AGENT_RESULT` | `streamAndPersist` — 发 SSE + 持久化 | 最终结果，权威文本，只发一次 |
| `TOOL_RESULT` | `forwardToolResult` — 发 SSE 工具结果事件 | 工具执行结果，前端单独展示 |

---

## 5. 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `backend/src/main/java/com/meeting/service/ChatStreamService.java` | 核心修复：切换 API + 重构事件分发 |
| `backend/src/main/java/com/meeting/document/repository/DocumentChunkRepository.java` | 附带修复：JPA 查询方法改用 @Query 注解 |

---

## 6. 验证结果

修复前：

```bash
curl -N -X POST http://localhost:8080/api/dialogue/53/chat \
  -H "Content-Type: application/json" -d '{"message": "你好"}'
# event:done
# data:
```

修复后：

```bash
curl -N -X POST http://localhost:8080/api/dialogue/53/chat \
  -H "Content-Type: application/json" -d '{"message": "你好"}'
# event:TEXT_DELTA
# data:你好呀！👋 有什么可以帮到你的吗？
#
# event:DONE
# data:
```

---

## 7. 经验总结

1. **不要假设第三方库的 API 行为**：`streamEvents()` 和 `stream()` 名字相似但返回完全不同的数据。必须通过反编译或文档确认实际行为。

2. **静默丢弃是最大的调试敌人**：原代码的 instanceof 链没有 else 分支，不匹配的事件被完全忽略。添加日志是排查的第一步。

3. **日志中间件 ≠ 数据流**：`AgentTraceMiddleware` 通过 hook 系统记录了回复文本，让人误以为事件流中也有文本。实际上 hook 系统和事件 Flux 是两条独立的通道。

4. **instanceof 链不易扩展**：改用 Map<EventType, Handler> 注册表模式，新增事件类型只需加一行 `put()`。
