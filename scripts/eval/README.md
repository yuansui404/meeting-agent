# RAG 检索质量评估

## 环境准备

```bash
cd scripts/eval
pip install -r requirements.txt
```

## 使用

```bash
# 全量评估
python evaluate.py --run-name "baseline-rrf-only"

# 按主题评估
python evaluate.py --run-name "budget-test" --topic "预算"

# 对比两次运行
python evaluate.py --compare "baseline-rrf-only" "rerank-on"
```

## 指标说明

| 指标 | 含义 |
|------|------|
| NDCG@3 | 排序质量，越靠前的相关文档权重越高 |
| P@3 | 前 3 个结果中有多少是目标文档 |
| R@3 | 目标文档在前 3 中召回的比例 |
| MRR | 第一个目标文档的排位倒数 |
| E@top | evidenceLevel 达标率 |