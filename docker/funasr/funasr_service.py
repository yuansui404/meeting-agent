"""
FunASR 语音识别服务

提供 HTTP API 供 Java 后端调用，支持：
- /health 健康检查
- /transcribe 语音转写
"""

import os
import tempfile
import subprocess
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="FunASR Service")

# 全局模型实例（懒加载）
asr_pipeline = None


def get_asr_pipeline():
    """获取 ASR pipeline（懒加载）"""
    global asr_pipeline
    if asr_pipeline is None:
        try:
            from modelscope.pipelines import pipeline
            from modelscope.utils.constant import Tasks

            asr_pipeline = pipeline(
                task=Tasks.auto_speech_recognition,
                model='damo/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch',
            )
        except ImportError:
            # modelscope 未安装，使用模拟模式
            pass
    return asr_pipeline


class AudioRequest(BaseModel):
    audio_path: str
    enable_speaker_diarization: bool = True


class TranscriptionSegment(BaseModel):
    start: float
    end: float
    text: str
    speaker: Optional[str] = None


class TranscriptionResponse(BaseModel):
    success: bool
    text: str
    segments: list[TranscriptionSegment] = []
    message: str = ""


@app.get("/health")
async def health_check():
    return {"status": "healthy"}


@app.post("/transcribe", response_model=TranscriptionResponse)
async def transcribe_audio(request: AudioRequest):
    """执行语音转写"""
    audio_path = request.audio_path

    if not os.path.exists(audio_path):
        raise HTTPException(status_code=400, detail=f"Audio file not found: {audio_path}")

    try:
        pipeline = get_asr_pipeline()

        if pipeline is None:
            # 模拟模式：提取音频时长返回占位结果
            duration = _get_audio_duration(audio_path)
            return TranscriptionResponse(
                success=True,
                text=f"[模拟转写结果] 音频时长: {duration:.1f}s，请安装 modelscope 以启用真实识别。",
                segments=[],
                message="mock_mode"
            )

        # 真实识别
        result = pipeline(audio_path=audio_path)

        segments = []
        full_text = ""

        if isinstance(result, dict):
            raw_text = result.get("text", "")
            full_text = raw_text

            # 尝试解析时间戳
            for ts_list in result.get("timestamp", []):
                if len(ts_list) >= 3:
                    segments.append(TranscriptionSegment(
                        start=float(ts_list[0]),
                        end=float(ts_list[1]),
                        text=str(ts_list[2]),
                    ))

        return TranscriptionResponse(
            success=True,
            text=full_text,
            segments=segments,
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


def _get_audio_duration(audio_path: str) -> float:
    """使用 ffprobe 获取音频时长"""
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "format=duration", "-of", "csv=p=0", audio_path],
            capture_output=True, text=True, timeout=30
        )
        return float(result.stdout.strip())
    except (subprocess.TimeoutExpired, FileNotFoundError, ValueError):
        return 0.0


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8501)
