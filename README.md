# 会议纪要智能体

个人使用的会议纪要智能体，支持 MP4 视频文件上传，自动生成会议文字纪要，并提供基于 RAG 技术的智能搜索功能。

## 技术架构

- **前端**: React + Ant Design
- **后端**: Java + MiMo-V2.5-ASR（语音识别）
- **数据库**: PostgreSQL + pgvector
- **部署**: Docker Compose

## 快速开始

```bash
# 启动所有服务
docker compose up -d

# 前端 http://localhost:3000
# 后端 http://localhost:8080
# MiMo ASR（云端 API）
```

## 项目结构

```
meeting_agent/
├── backend/     # Java 后端
├── frontend/    # React 前端
├── docker/      # Docker 配置
├── docs/        # 项目文档
└── prd/         # 需求文档
```
