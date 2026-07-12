#!/bin/bash
# ============================================================
# BGE-Reranker-v2-m3 ONNX 模型导出脚本
# 使用 optimum 从 HuggingFace 导出模型，供 ONNX Runtime Java 推理
# ============================================================
set -euo pipefail

MODEL_DIR="${BGE_MODEL_DIR:-$(dirname "$0")/../models/bge-reranker}"
MODEL_NAME="BAAI/bge-reranker-v2-m3"

echo "导出模型: $MODEL_NAME → $MODEL_DIR"

# 方法一：Docker Python 容器导出（推荐，无需本地安装 Python）
if command -v docker &>/dev/null; then
    echo "使用 Docker Python 容器导出..."
    mkdir -p "$MODEL_DIR"
    docker run --rm \
        -v "$MODEL_DIR:/models/bge-reranker" \
        python:3.11-slim \
        bash -c "
            pip install -q optimum torch sentencepiece protobuf && \
            optimum-cli export onnx --model $MODEL_NAME /models/bge-reranker && \
            echo '导出完成' || echo '导出失败'
        "
    echo "模型已导出到: $MODEL_DIR"
    echo "文件列表:"
    ls -lh "$MODEL_DIR"
    exit 0
fi

# 方法二：本地 Python 导出（需要预先安装 torch + optimum）
echo "Docker 不可用，尝试本地 Python 导出..."
pip install -q optimum torch sentencepiece protobuf

optimum-cli export onnx --model "$MODEL_NAME" "$MODEL_DIR"

echo "模型已导出到: $MODEL_DIR"
echo "文件列表:"
ls -lh "$MODEL_DIR"