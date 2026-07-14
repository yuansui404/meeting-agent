# RAG 检索质量评估

## 环境准备

```bash
cd scripts/eval
pip install -r requirements.txt
```

## 使用

```bash
# 全量评估（基础指标）
python evaluate.py --run-name "baseline-rrf-only"

# 全量评估（含 RAGAS LLM 语义指标）
export DEEPSEEK_API_KEY="your-api-key"
python evaluate.py --run-name "ragas-eval" --ragas

# 按主题评估
python evaluate.py --run-name "budget-test" --topic "预算"

# 对比两次运行
python evaluate.py --compare "baseline-rrf-only" "rerank-on"
```

`--ragas` 开关说明：启用后额外计算 RAGAS 的 context_precision / context_recall，
需配置 `DEEPSEEK_API_KEY` 环境变量（复用 DeepSeek 作为 LLM judge）。
默认关闭，仅计算基础指标。

## 指标说明

| 指标 | 类型 | 含义 |
|------|------|------|
| NDCG@3 | 基础 | 排序质量，越靠前的相关文档权重越高 |
| P@3 | 基础 | 前 3 个结果中有多少是目标文档 |
| R@3 | 基础 | 目标文档在前 3 中召回的比例 |
| MRR | 基础 | 第一个目标文档的排位倒数 |
| E@top | 基础 | evidenceLevel 达标率 |
| Context Precision | RAGAS | 检索结果中相关片段的比例（LLM 语义判断） |
| Context Recall | RAGAS | 需要的信息是否被检索到（LLM 语义判断） |