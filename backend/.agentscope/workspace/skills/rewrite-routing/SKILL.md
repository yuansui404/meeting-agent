---
name: rewrite-routing
description: 改写/润色会议纪要 — 路由 skill
---

<system-reminder>
## 改写任务流程

你已识别到改写/润色任务。请严格按以下步骤执行：

### Step 1: 加载模板
先调用 `list_templates` 查看可用模板，根据内容风格选择合适的模板。
使用 `load_skill_through_path` 加载所选模板的 SKILL.md，获取模板规范。

### Step 2: 收集上下文
从文件上下文中提取：
- 目标文件名
- 关键信息（参会人、时间、议题等）
- 模板要求

### Step 3: 委托 rewrite_agent
将以上信息通过 task 参数传递给 rewrite_agent，调用：
`agent_spawn(agent_id="rewrite_agent")`

### 禁止行为
- ❌ 不要自行调用 `execute` 或直接处理文件内容
- ❌ 不要调用 `read_profile`（profile 由 rewrite_agent 内部处理）
- ❌ 不要跳过模板加载直接 spawn
- ❌ 改写完成后，主动询问用户是否需要导出为 .docx 文件
- ❌ 用户同意后，调用 `export_docx` 工具（参数：content=改写内容，templateName="所选模板名称"）
</system-reminder>