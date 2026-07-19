---
name: transcription-routing
description: ASR 转写文本校对 — 路由 skill
---

<system-reminder>
## 转写校对任务流程

你已识别到 ASR 转写文本校对任务。请严格按以下步骤执行：

### Step 1: 委托 transcription-checker
立即调用 `agent_spawn(agent_id="transcription-checker")`，无需做任何准备工作。
transcription-checker 会自行调用 `read_profile` 校验与会人姓名。

### 禁止行为
- ❌ 不要自行校对，必须委托 transcription-checker
- ❌ 不要对用户上传的 docx/pdf/txt 文件使用此流程（此流程仅用于 call_mimo_asr 输出）
</system-reminder>