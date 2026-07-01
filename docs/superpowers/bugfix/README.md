# Bugfix 记录归档

项目开发过程中的 bug 修复记录，按时间倒序排列。

---

## 2026-07-01

### 1. 文件上下文新旧文件混淆
- **问题**：上传第一个文件问"是什么"能正确回答；上传第二个文件要求"详细总结"时，模型会同时总结两个文件。
- **原因**：`buildFileBlocks()` 返回所有对话文件的内容 block，LLM 无法区分新旧文件。
- **修复**：`buildFileBlocks()` 剥离文本内容（仅保留图片 ImageBlock），新建 `buildFileTextContext()` 将文本分为"本次提交的文件"（高优先级）和"对话历史中的文件"（低优先级）两个区间。
- **改动文件**：`ChatService.java`

### 2. 知识库自动误调用（upload_to_knowledge_base）
- **问题**：用户要求"详细总结"时，LLM 自动调用 `upload_to_knowledge_base` 将文件保存到知识库。
- **原因**：LLM 认为"总结"意味着需要持久化保存；系统提示词限制不够明确。
- **修复**：`ChatService.java` 系统提示词新增"关于 upload_to_knowledge_base 的严格规则"，明确列出允许和禁止调用场景；`UploadToKnowledgeBaseTool.java` 描述同步加强。同时修复了工具描述字符串中 ASCII/Unicode 双引号混用导致的编译错误。
- **改动文件**：`ChatService.java`、`UploadToKnowledgeBaseTool.java`

### 3. 快速指令需手动回车
- **问题**：输入区文件卡片的"详细总结/简单摘要/提取要点"悬浮按钮仅填充输入框，需用户按回车才发送。
- **修复**：`onClick` 改为直接调用 `sendMessage()`，与 `handleSuggestionClick` 行为一致。
- **改动文件**：`DialoguePanel.tsx`

### 4. 文件预览为空
- **问题**：上传文件后点击预览，右侧抽屉展示空白。
- **原因**：非音频文件（PDF、DOCX）的 `transcription` 字段存储了空 JSON 字符串 `"{}"`，前端 `if (meeting.transcription)` 对 `"{}"` 返回 true，导致空白渲染。
- **修复**：前端增加 `meeting.transcription !== '{}' && meeting.transcription.length > 10` 判断。
- **改动文件**：`DialoguePanel.tsx`

---

## 2026-06-27

### 5. SSE 竞态条件 — emitter.complete() 先于 addMessage()
- **问题**：`streamChatWithDeepSeek()` 和 `streamChatWithZhipu()` 中 emitter.complete() 在 dialogueService.addMessage() 之前执行，导致前端收到 done 事件后刷新页面，消息尚未入库。
- **修复**：调整执行顺序，先调用 `dialogueService.addMessage()` 再发送 SSE done 事件。
- **改动文件**：`ChatService.java`
- **关联**：与会人收集 + 改写二次校验任务

---

## 2026-06-25

### 6. 改写文件读取 — 优先读文件而非转写字端
- **问题**：风格改写时优先读取数据库 `transcription` 字端，该字端可能因为存储格式问题不是最新的文件内容。
- **修复**：`buildFullDocumentReferences()` 优先通过 `meetingRepository.findById()` 加载文件系统上的原始文件，fallback 到转写字端。
- **改动文件**：`RewriteService.java`

---

## 2026-06-23

### 7. SSE 断连续传
- **问题**：客户端断开 SSE 连接（刷新页面/关闭标签页）后，LLM 流式回复中断，已收到的 token 未持久化。
- **修复**：`clientDisconnected` 标志位 + `finally` 块：检测到断开后继续消费 LLM 流直到完整回复，最终存入数据库。刷新页面后前端重新调用 `loadMessages()` 获取完整回复。
- **改动文件**：`ChatService.java`

### 8. RAG 意图识别 — 余弦相似度阈值过滤
- **问题**：非相关查询（如日常聊天）也会触发 RAG 检索，注入无用上下文。
- **修复**：设置余弦相似度阈值 0.6，低于阈值的 RAG 结果不注入上下文；双模式提示（有上下文时要求引用来源，无上下文时正常对话）。
- **改动文件**：`SearchService.java`、`HybridSearchService.java`
