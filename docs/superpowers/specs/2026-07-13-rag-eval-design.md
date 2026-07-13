# RAG 检索质量评估系统设计

## 背景

RAG 管线持续迭代（分数归一化、BGE Reranker、邻居条件扩展、metadata 透传等），但缺乏系统性的量化评估手段。每次改动是否有效全靠主观体验判断。

需要一套离线评估系统，用测试集 + 量化指标衡量检索质量，支撑管线配置的迭代决策。

## 架构

```
测试集 (pg DB)    评估脚本 (Python)        后端 RAG 管线
┌────────────┐    ┌──────────────────┐    ┌────────────────┐
│ eval_test  │───→│ for each Q:      │───→│ /api/search    │
│ _case      │    │   collect result │    │ (完整管线)     │
└────────────┘    │   compute metric │    └────────────────┘
                  │   store result   │
                  └────────┬─────────┘
                           ↓
                   ┌───────────────┐
                   │ eval_run      │
                   │ eval_result   │
                   └───────────────┘
```

## 数据库

### 建表 DDL

```sql
-- 测试用例
CREATE TABLE eval_test_case (
    id SERIAL PRIMARY KEY,
    question TEXT NOT NULL,                         -- 测试问题
    ground_truth_doc_ids BIGINT[] NOT NULL,         -- 期望命中的文档ID
    ground_truth_answer TEXT,                       -- 期望的标准回答（用于 RAGAS context_recall）
    topic VARCHAR(100),                             -- 主题分类（预算/技术/人事…）
    query_type VARCHAR(50) DEFAULT 'fact',          -- 查询类型：fact / summary / reasoning / comparison
    difficulty VARCHAR(20) DEFAULT 'Medium',        -- 难度：Easy / Medium / Hard
    notes TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 评估运行记录
CREATE TABLE eval_run (
    id SERIAL PRIMARY KEY,
    run_name VARCHAR(200) NOT NULL,                 -- 运行名称，如 "baseline-rrf-only"
    config JSONB,                                   -- 运行时的 RAG 配置快照
    metrics JSONB,                                  -- 聚合指标
    created_at TIMESTAMP DEFAULT NOW()
);

-- 逐条评估结果
CREATE TABLE eval_result (
    id SERIAL PRIMARY KEY,
    run_id INT NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    test_case_id INT NOT NULL REFERENCES eval_test_case(id),
    ndcg_3 DOUBLE PRECISION,                       -- NDCG@3（排序质量）
    precision_3 DOUBLE PRECISION,                   -- P@3
    recall_3 DOUBLE PRECISION,                      -- R@3
    mrr DOUBLE PRECISION,                           -- Mean Reciprocal Rank
    latency_ms INT,                                 -- 检索耗时（毫秒）
    evidence_level VARCHAR(20),                     -- SUFFICIENT/PARTIAL/WEAK/NONE
    total_candidates INT,                           -- 实际检索到的候选数
    retrieved_doc_ids BIGINT[],                     -- 实际检索到的文档ID
    details JSONB                                   -- 详细结果（每个 chunk 的分数、来源等）
);
```

## 测试集

初始约 20-30 条，覆盖维度：

| 维度 | 说明 | 示例 |
|------|------|------|
| 主题覆盖 | 预算/技术/人事/战略… | "Q2预算审批结论" |
| 时间敏感 | 近期 vs 历史 | "上个月的技术评审会" |
| 发言人 | 特定人名的查询 | "张弢提了什么建议" |
| 模糊匹配 | 跨文档、非精确词 | "那笔500万的拨款" |
| 查询类型 | 事实/总结/推理/对比 | "对比Q1和Q2的预算方案" |
| 难度 | Easy / Medium / Hard | 多跳推理 → Hard |

通过 SQL 直接管理，后续可实现管理 API。

## 评估指标

### 基础层（硬匹配，无 LLM 依赖）

| 指标 | 公式 | 含义 |
|------|------|------|
| NDCG@3 | 归一化折损累计增益 | 排序质量，越靠前的相关文档权重越高 |
| P@3 | relevant_docs_in_top3 / 3 | 前 3 个结果中有多少是目标文档 |
| R@3 | relevant_docs_in_top3 / total_relevant | 目标文档在前 3 中召回的比例 |
| MRR | 1 / first_relevant_rank | 第一个目标文档出现在第几位 |
| E@top | evidenceLevel 达标率 | SUFFICIENT/PARTIAL 占比 |

基于 `ground_truth_doc_ids` 作硬匹配，不依赖 LLM，结果稳定。NDCG 比 MRR 更能反映排序质量——MRR 只关心第一个相关文档，NDCG 给每个位置赋予不同的权重。

### RAGAS 层（LLM 语义判断）

复用 DeepSeek API key。注意 `context_recall` 需要 `ground_truth_answer`（标准答案文本），而非 doc_id。`context_precision` 需要 LLM 判断每个 chunk 是否与问题语义相关。

**开发优先级**：先跑通基础层（硬匹配），基础指标已能覆盖 80% 的迭代决策。RAGAS 层在基础层稳定后引入。

## 评估脚本

位置：`scripts/eval/evaluate.py`

```python
# 伪代码
def evaluate(run_name):
    test_cases = load_test_cases_from_db()
    config = snapshot_current_config()        # 记录当前 RAG 配置

    run = create_eval_run(run_name, config)

    for case in test_cases:
        t0 = time.now()
        result = call_search_api(case.question)
        latency = time.now() - t0

        metrics = compute_metrics(
            retrieved=result.chunks,
            ground_truth_doc_ids=case.ground_truth_doc_ids,
            latency_ms=latency,
            evidence_level=result.evidence_level,
        )
        # metrics = { ndcg_3, precision_3, recall_3, mrr, latency_ms, evidence_level }

        # RAGAS 层（二期引入）
        # ragas = compute_ragas(case.question, result.chunks, case.ground_truth_answer)
        # metrics.update(ragas)

        save_eval_result(run.id, case.id, metrics)

    summary = aggregate_metrics(run.id)
    update_run_metrics(run.id, summary)
    print_report(summary)
```

依赖：
- `pip install ragas`（RAGAS）
- `pip install psycopg2`（数据库连接）
- `pip install requests`（调用后端 API）

## 使用方式

```bash
# 全量评估
cd scripts/eval && python evaluate.py --run-name "rerank-on-vs-off"

# 指定主题
python evaluate.py --run-name "budget-only" --topic "预算"

# 对比两次运行
python evaluate.py --compare "baseline" "rerank-on"
```

## 开发计划

1. 建表（init-db.sql 追加 DDL）
2. 构造初始测试集（SQL INSERT 20-30 条）
3. 开发 Python 评估脚本（基础指标）
4. 集成 RAGAS 指标
5. 运行基线评估 + 输出报告
6. 文档 + 流程说明