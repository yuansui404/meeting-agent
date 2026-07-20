---
name: rewrite-routing
description: 改写/润色会议纪要 — 路由 skill
---

## 改写任务流程

你已识别到改写/润色任务。请严格按以下步骤执行：

### Step 1: 加载模板
先调用 `list_templates` 查看可用模板，根据内容风格选择合适的模板。
使用 `load_skill_through_path` 加载所选模板的 SKILL.md，获取模板规范。

### Step 2: 委托 rewrite_agent
立即调用 `agent_spawn(agent_id="rewrite_agent")`，将模板要求和文件上下文交给它处理。
**不要**在主 agent 中搜索文件、读取与会人信息或做任何准备工作。

### Step 3: 导出 .md 文件（用户同意后）
rewrite_agent 返回改写结果后，主动询问用户是否需要导出文件。
用户同意后，调用 `export_docx`（参数：content=改写内容）。
`export_docx` 是通用工具，始终可用，无需激活。

### 禁止行为
- ❌ 不要调用 `execute` 或 `read_profile` 做准备工作
- ❌ 不要跳过模板加载直接 spawn
- ❌ 不要自己动手改写内容