# 会议纪要 Agent 概要记忆系统设计

## 概述
为会议纪要 Agent 增加概要记忆（Profile Memory）能力，让 agent 能记住用户的个人偏好、习惯用语和组织信息。这是 Agent 通用能力的第一部分，基于三层记忆架构中的"概要记忆层"实现。

## 设计原则
1. **用户可见可编辑** — 所有记忆文件以 markdown 形式存储，用户可在前端页面直接查看和修改
2. **Agent 可读写** — 通过 `read_profile` / `update_profile` 工具，agent 也能主动查询和更新记忆
3. **文件化存储** — 使用 .md 文件而非数据库，方便用户直接编辑和迁移
4. **自动注入** — 每次对话自动将 profile 目录下全部内容注入 system prompt

## 架构

### 三层记忆体系（仅实现概要记忆层）

```
┌──────────────────────────────────────┐
│  System Prompt                        │
│  ├── 原始 prompt                      │
│  └── 用户画像（profile 目录下全部 .md） │
├──────────────────────────────────────┤
│  AgentScope Session (多轮对话上下文)    │  ← 已有
├──────────────────────────────────────┤
│  PostgreSQL                           │
│  ├── dialogue_messages                │  ← 已有
│  └── (对话相关表)                      │  ← 已有
└──────────────────────────────────────┘
```

### 文件目录结构

```
{uploadDir}/profile/
├── .system.json              ← 系统设置（agent 行为参数，不暴露给前端编辑）
├── 我的偏好.md               ← 用户创建的偏好说明
├── 与会人.md                 ← 自动提取 + 用户可编辑（从 knowledge-base 迁移）
└── (任意命名的 .md 文件)     ← 用户自由创建
```

### 注入方式
1. 每次 `streamChat` 时，扫描 `profile/` 目录下所有 `.md` 文件
2. 按文件名排序，拼接成 `=== 用户画像 ===` 章节
3. 追加到 system prompt 末尾

## API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/profile/files` | 返回 profile 目录下所有 .md 文件名列表 |
| GET | `/api/profile/{filename}` | 返回某个 .md 文件内容 |
| PUT | `/api/profile/{filename}` | 保存某个 .md 文件内容 |
| POST | `/api/profile/{filename}` | 新建 .md 文件 |
| DELETE | `/api/profile/{filename}` | 删除 .md 文件 |

## Agent 工具

| 工具名 | 功能 |
|--------|------|
| `read_profile` | 读取用户画像全部内容，让 agent 了解已记录的信息 |
| `update_profile` | 追加或修改某个 .md 文件的内容 |

## 与会人.md 迁移

- **当前路径**: `{uploadDir}/knowledge-base/与会人.md`
- **目标路径**: `{uploadDir}/profile/与会人.md`
- **影响文件**: VectorizationService.java, RewriteService.java
- **迁移方式**: 启动时检测旧路径有文件而新路径没有，自动复制

## 前端交互

- 侧边栏新增"个人设置"入口（齿轮图标）
- 页面：多标签记忆文件编辑区，列出所有 .md 文件名，点击编辑 markdown
- 与会人名单独立展示
- 保存按钮持久化到文件

## 新增/修改文件清单

### 后端新增
- `ProfileController.java` — 增删改查 profile 文件
- `ProfileService.java` — 读写 profile 文件 + agent 工具

### 后端修改
- `ChatService.java` — system prompt 拼接 profile 内容
- `VectorizationService.java` — 与会人.md 写入路径迁移
- `RewriteService.java` — 与会人.md 读取路径迁移

### 前端新增
- `ProfileSettings.tsx` — 个人设置页面组件
- `api.ts` — 新增 profile API 调用
- `App.tsx` — 侧边栏新增入口

## 实现步骤

1. 后端 ProfileService + ProfileController
2. ChatService 注入 profile 到 system prompt
3. 注册 `read_profile` / `update_profile` Agent 工具
4. 迁移与会人.md 路径 + 启动迁移逻辑
5. 前端 ProfileSettings 组件
6. 前端侧边栏入口

## 后续
- 任务拆解（Task Decomposition）能力
- 工具调用扩展（更多 AgentTool）
- 跨 session 长期事实记忆（如必要）

---

*设计日期：2026-06-28*
*状态：设计完成，待实现*
