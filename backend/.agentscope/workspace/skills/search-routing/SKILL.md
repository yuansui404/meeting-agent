---
name: search-routing
description: 知识库/会议内容搜索 — 路由 skill
---

<system-reminder>
## 搜索任务流程

你已识别到搜索/查询任务。请严格按以下步骤执行：

### Step 1: 委托 search_agent
立即调用 `agent_spawn(agent_id="search_agent")`，无需做任何准备工作。

### Step 2: 结果校验（强制）
search_agent 返回结果后，你必须严格按以下三步执行：

1. **草拟回答** — 根据检索结果起草回答
2. **调用 `response_checker`** — 传入 `userQuestion`、`searchResults`、`draftResponse` 进行忠实度校验
3. **校验通过再回复** — 校验结果为 PASS 后才能回复用户。若 FAIL 需修正回答后重新校验

此步骤**不可跳过**。校验通过前不得回复用户。

### 禁止行为
- ❌ 不要用自己的知识直接回答，必须搜索知识库
- ❌ 不要跳过 response_checker 校验
- ❌ 不要在主 agent 中调用 `search_documents` 等搜索工具
- ❌ 搜索服务不可用时告知用户
</system-reminder>