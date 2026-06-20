# 会议纪要智能体项目（极简版）

## 项目概述
个人使用的会议纪要智能体，支持MP4视频文件上传，自动生成会议文字纪要，并提供基于RAG技术的智能搜索功能。

## 技术架构
- **前端**: React + Ant Design（极简界面）
- **后端**: Java + FunASR（语音识别）
- **数据库**: PostgreSQL + pgvector（单表 + 向量存储）
- **部署**: Docker Compose

## 核心功能
1. **文件处理**: MP4视频上传、音频提取
2. **语音转文字**: FunASR语音识别、生成会议纪要
3. **智能搜索**: RAG技术实现历史会议内容语义搜索

## 项目目录
```
meeting_agent/
├── prd/                 # 产品需求文档
├── docs/                # 项目文档
│   └── superpowers/     # 设计文档
├── backend/             # Java后端（FunASR）
├── frontend/            # React前端
├── docker/              # Docker配置
└── CLAUDE.md           # 本文件
```

## 数据库设计
- **单表存储**: `meeting_minutes` 表存储所有会议信息
- **向量存储**: `meeting_vectors` 表存储纪要分块的向量表示
- **索引优化**: 全文搜索索引 + 向量索引

## 开发指南
- 使用Docker Compose运行所有服务
- 后端使用Java + FunASR进行语音识别
- 使用Sentence-BERT生成文本向量
- 搜索功能结合全文检索和向量检索

## 相关文档
- [设计文档](docs/superpowers/specs/2026-06-19-meeting-agent-design.md)
- [需求文档](prd/需求.md)