#!/bin/bash
# FunASR 模型下载脚本
# 用于在 Docker 构建或手动部署时下载模型

MODELS_DIR="models/funasr"
mkdir -p "$MODELS_DIR"

echo "=== 下载 FunASR 模型 ==="

# 方案1: 使用 modelscope 下载
if command -v pip &> /dev/null && pip list 2>/dev/null | grep -q modelscope; then
    echo "使用 modelscope 下载..."
    python3 -c "
from modelscope.hub.snapshot_download import snapshot_download
snapshot_download('damo/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch', cache_dir='$MODELS_DIR')
snapshot_download('damo/speech_campplus_sv_zh-cn_16k-task-vocab8404-pytorch', cache_dir='$MODELS_DIR')
print('模型下载完成')
"
else
    echo "未安装 modelscope，请安装后手动下载："
    echo "  pip install modelscope"
    echo "  python3 -c \"from modelscope.hub.snapshot_download import snapshot_download; snapshot_download('damo/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch')\""
fi

echo "=== 完成 ==="
