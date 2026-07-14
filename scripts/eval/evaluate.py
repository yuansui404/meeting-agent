import json
import time
import argparse
from dataclasses import dataclass, field
from typing import Optional
import psycopg2
import psycopg2.extras
import requests
import os


DB_URL = "postgresql://user:password@localhost:5432/meeting_agent"
SEARCH_API = "http://localhost:8080/api/search"


@dataclass
class TestCase:
    id: int
    question: str
    ground_truth_doc_ids: list[int]
    ground_truth_answer: Optional[str] = None
    topic: Optional[str] = None
    query_type: Optional[str] = None
    difficulty: Optional[str] = None


@dataclass
class SearchResult:
    chunks: list[dict]
    evidence_level: str
    total_candidates: int


@dataclass
class Metrics:
    ndcg_3: float = 0.0
    precision_3: float = 0.0
    recall_3: float = 0.0
    mrr: float = 0.0
    latency_ms: int = 0
    evidence_level: str = "NONE"
    total_candidates: int = 0
    retrieved_doc_ids: list[int] = field(default_factory=list)
    details: dict = field(default_factory=dict)


def load_test_cases(db_url: str, topic: Optional[str] = None) -> list[TestCase]:
    conn = psycopg2.connect(db_url)
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    if topic:
        cur.execute("SELECT * FROM eval_test_case WHERE topic = %s ORDER BY id", (topic,))
    else:
        cur.execute("SELECT * FROM eval_test_case ORDER BY id")
    cases = []
    for row in cur.fetchall():
        cases.append(TestCase(
            id=row["id"],
            question=row["question"],
            ground_truth_doc_ids=row["ground_truth_doc_ids"],
            ground_truth_answer=row.get("ground_truth_answer"),
            topic=row.get("topic"),
            query_type=row.get("query_type"),
            difficulty=row.get("difficulty"),
        ))
    cur.close()
    conn.close()
    return cases


def call_search_api(query: str, base_url: str = SEARCH_API) -> SearchResult:
    resp = requests.get(base_url, params={"query": query}, timeout=30)
    resp.raise_for_status()
    body = resp.json()
    data = body.get("data", {})
    return SearchResult(
        chunks=data.get("results", []),
        evidence_level=data.get("evidenceLevel", "NONE"),
        total_candidates=data.get("totalCandidates", 0),
    )


def compute_metrics(
    search_result: SearchResult,
    ground_truth_doc_ids: list[int],
    latency_ms: int,
) -> Metrics:
    doc_ids = []
    seen = set()
    for chunk in search_result.chunks:
        doc_id = chunk.get("docId") or chunk.get("documentId")
        if doc_id and doc_id not in seen:
            doc_ids.append(doc_id)
            seen.add(doc_id)

    gt_set = set(ground_truth_doc_ids)
    top3 = doc_ids[:3]

    relevant_in_top3 = sum(1 for d in top3 if d in gt_set)
    precision_3 = relevant_in_top3 / 3 if top3 else 0.0

    total_relevant = len(gt_set) if gt_set else 1
    recall_3 = relevant_in_top3 / total_relevant if total_relevant > 0 else 0.0

    first_rank = None
    for i, d in enumerate(doc_ids):
        if d in gt_set:
            first_rank = i + 1
            break
    mrr = 1.0 / first_rank if first_rank else 0.0

    # NDCG@3
    dcg = 0.0
    for i, d in enumerate(top3):
        rel = 1.0 if d in gt_set else 0.0
        if i == 0:
            dcg += rel
        else:
            dcg += rel / (i + 1)

    idcg = 0.0
    for i in range(min(len(gt_set), 3)):
        if i == 0:
            idcg += 1.0
        else:
            idcg += 1.0 / (i + 1)
    ndcg = dcg / idcg if idcg > 0 else 0.0

    return Metrics(
        ndcg_3=round(ndcg, 4),
        precision_3=round(precision_3, 4),
        recall_3=round(recall_3, 4),
        mrr=round(mrr, 4),
        latency_ms=latency_ms,
        evidence_level=search_result.evidence_level,
        total_candidates=search_result.total_candidates,
        retrieved_doc_ids=doc_ids,
    )


# ──────────────────────────────────────────────
# RAGAS 指标（LLM 语义判断，通过 --ragas 开关控制）
# ──────────────────────────────────────────────

def compute_ragas_metrics(question: str, chunks: list[dict], ground_truth_answer: Optional[str]) -> dict:
    """使用 RAGAS 计算 context_precision / context_recall，复用 DeepSeek API。"""
    try:
        from ragas import evaluate as ragas_evaluate
        from ragas.metrics import context_precision, context_recall
        from datasets import Dataset
    except ImportError:
        print("  [warn] ragas 或 datasets 未安装，跳过 RAGAS 指标")
        print("  [warn] 请执行: pip install ragas datasets")
        return {}

    # 提取检索到的文本内容
    contexts = [c.get("content", "") for c in chunks if c.get("content")]
    if not contexts:
        return {"context_precision": 0.0, "context_recall": 0.0}

    # 配置 DeepSeek 作为 RAGAS 的 LLM
    api_key = os.environ.get("DEEPSEEK_API_KEY")
    base_url = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")
    if not api_key:
        print("  [warn] DEEPSEEK_API_KEY 未设置，跳过 RAGAS 指标")
        return {}

    # RAGAS 通过环境变量识别 OpenAI 兼容 API
    os.environ["OPENAI_API_KEY"] = api_key
    os.environ["OPENAI_BASE_URL"] = base_url

    data = {
        "question": [question],
        "contexts": [contexts],
    }
    if ground_truth_answer:
        data["ground_truth"] = [ground_truth_answer]

    dataset = Dataset.from_dict(data)

    metrics = [context_precision]
    if ground_truth_answer:
        metrics.append(context_recall)

    try:
        result = ragas_evaluate(dataset, metrics=metrics)
        ret = {}
        if "context_precision" in result:
            ret["context_precision"] = round(float(result["context_precision"]), 4)
        if "context_recall" in result:
            ret["context_recall"] = round(float(result["context_recall"]), 4)
        return ret
    except Exception as e:
        print(f"  [warn] RAGAS 评估失败: {e}")
        return {}


# ──────────────────────────────────────────────
# 数据库操作
# ──────────────────────────────────────────────

def fetch_rag_config(api_url: str) -> dict:
    """从后端获取当前 RAG 参数快照"""
    rag_api = api_url.rstrip("/search").rstrip("/") + "/rag/config"
    try:
        resp = requests.get(rag_api, timeout=10)
        if resp.ok:
            return resp.json()
    except Exception as e:
        print(f"  [warn] 无法获取 RAG 配置: {e}")
    return {}


def create_eval_run(db_url: str, run_name: str, config: dict, ragas_enabled: bool = False) -> int:
    config["ragas_enabled"] = ragas_enabled
    conn = psycopg2.connect(db_url)
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO eval_run (run_name, config) VALUES (%s, %s) RETURNING id",
        (run_name, json.dumps(config)),
    )
    run_id = cur.fetchone()[0]
    conn.commit()
    cur.close()
    conn.close()
    return run_id


def save_eval_result(db_url: str, run_id: int, test_case_id: int, metrics: Metrics):
    conn = psycopg2.connect(db_url)
    cur = conn.cursor()
    details_json = json.dumps(metrics.details) if metrics.details else None
    cur.execute(
        """INSERT INTO eval_result
           (run_id, test_case_id, ndcg_3, precision_3, recall_3, mrr, latency_ms,
            evidence_level, total_candidates, retrieved_doc_ids, details)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)""",
        (run_id, test_case_id, metrics.ndcg_3, metrics.precision_3, metrics.recall_3,
         metrics.mrr, metrics.latency_ms, metrics.evidence_level,
         metrics.total_candidates, metrics.retrieved_doc_ids, details_json),
    )
    conn.commit()
    cur.close()
    conn.close()


def aggregate_metrics(db_url: str, run_id: int) -> dict:
    conn = psycopg2.connect(db_url)
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("""
        SELECT
            AVG(ndcg_3) as avg_ndcg,
            AVG(precision_3) as avg_precision,
            AVG(recall_3) as avg_recall,
            AVG(mrr) as avg_mrr,
            AVG(latency_ms) as avg_latency,
            COUNT(*) as total_cases,
            SUM(CASE WHEN evidence_level IN ('SUFFICIENT', 'PARTIAL') THEN 1 ELSE 0 END) as passed_evidence
        FROM eval_result WHERE run_id = %s
    """, (run_id,))
    row = cur.fetchone()
    summary = dict(row) if row else {}
    # 写回 eval_run.metrics
    cur.execute("UPDATE eval_run SET metrics = %s WHERE id = %s",
                (json.dumps(summary), run_id))
    conn.commit()
    cur.close()
    conn.close()
    return summary


# ──────────────────────────────────────────────
# 报告输出
# ──────────────────────────────────────────────

def print_report(run_name: str, summary: dict, ragas_avg: Optional[dict] = None):
    print(f"\n{'='*50}")
    print(f"  Run: {run_name}")
    print(f"{'='*50}")
    print(f"  Cases:        {summary.get('total_cases', 0):>5}")
    print(f"  NDCG@3:       {summary.get('avg_ndcg', 0):>8.4f}")
    print(f"  Precision@3:  {summary.get('avg_precision', 0):>8.4f}")
    print(f"  Recall@3:     {summary.get('avg_recall', 0):>8.4f}")
    print(f"  MRR:          {summary.get('avg_mrr', 0):>8.4f}")
    print(f"  Avg Latency:  {summary.get('avg_latency', 0):>8.0f}ms")
    evidence_pass = summary.get('passed_evidence', 0)
    total = summary.get('total_cases', 1)
    print(f"  E@top:        {evidence_pass}/{total} ({evidence_pass/total*100:.0f}%)")
    if ragas_avg:
        print(f"  Context Prec: {ragas_avg.get('avg_context_precision', 0):>8.4f}")
        print(f"  Context Rec:  {ragas_avg.get('avg_context_recall', 0):>8.4f}")
    print(f"{'='*50}\n")


def slice_analysis(db_url: str, run_id: int):
    """按 query_type / difficulty 分组输出指标"""
    conn = psycopg2.connect(db_url)
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("""
        SELECT tc.query_type, tc.difficulty,
               AVG(r.ndcg_3) as avg_ndcg,
               AVG(r.precision_3) as avg_precision,
               AVG(r.recall_3) as avg_recall,
               COUNT(*) as cnt
        FROM eval_result r
        JOIN eval_test_case tc ON r.test_case_id = tc.id
        WHERE r.run_id = %s
        GROUP BY tc.query_type, tc.difficulty
        ORDER BY tc.query_type, tc.difficulty
    """, (run_id,))
    rows = cur.fetchall()
    print(f"\n  --- Slice Analysis ---")
    for row in rows:
        print(f"  [{row['query_type']:12s} | {row['difficulty']:6s}] (n={row['cnt']:2d})  "
              f"NDCG={row['avg_ndcg']:.4f}  P={row['avg_precision']:.4f}  R={row['avg_recall']:.4f}")
    cur.close()
    conn.close()


# ──────────────────────────────────────────────
# 主逻辑
# ──────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="RAG 检索质量评估")
    parser.add_argument("--run-name", required=True, help="评估运行名称")
    parser.add_argument("--topic", help="按主题过滤测试用例")
    parser.add_argument("--db-url", default=DB_URL, help="数据库连接字符串")
    parser.add_argument("--api-url", default=SEARCH_API, help="搜索 API 地址")
    parser.add_argument("--ragas", action="store_true", help="启用 RAGAS 指标（需 DEEPSEEK_API_KEY 环境变量）")
    parser.add_argument("--compare", nargs=2, metavar=("RUN1", "RUN2"), help="对比两次运行结果")
    args = parser.parse_args()

    if args.compare:
        compare_runs(args.db_url, args.compare[0], args.compare[1])
        return

    db_url = args.db_url
    api_url = args.api_url

    cases = load_test_cases(db_url, topic=args.topic)
    if not cases:
        print("No test cases found.")
        return

    print(f"Loaded {len(cases)} test cases{' for topic: ' + args.topic if args.topic else ''}")
    rag_config = fetch_rag_config(api_url)
    run_id = create_eval_run(db_url, args.run_name, rag_config, ragas_enabled=args.ragas)

    ragas_scores = []  # 收集 RAGAS 分数用于报告

    for case in cases:
        t0 = time.time()
        search_result = call_search_api(case.question, api_url)
        elapsed = int((time.time() - t0) * 1000)

        metrics = compute_metrics(search_result, case.ground_truth_doc_ids, elapsed)

        # RAGAS 指标（可选）
        if args.ragas:
            ragas_result = compute_ragas_metrics(
                case.question, search_result.chunks, case.ground_truth_answer
            )
            metrics.details["ragas"] = ragas_result
            if ragas_result:
                ragas_scores.append(ragas_result)

        save_eval_result(db_url, run_id, case.id, metrics)

        parts = [f"P@3={metrics.precision_3:.2f}", f"MRR={metrics.mrr:.2f}"]
        if metrics.details.get("ragas"):
            r = metrics.details["ragas"]
            cp = r.get("context_precision", "N/A")
            cr = r.get("context_recall", "N/A")
            parts.append(f"CP={cp} CR={cr}")
        detail = " | ".join(parts)
        print(f"  [{case.id:2d}] {case.question[:40]:40s} {detail}")

    summary = aggregate_metrics(db_url, run_id)

    # RAGAS 聚合
    ragas_avg = None
    if ragas_scores:
        ragas_avg = {
            "avg_context_precision": round(
                sum(s.get("context_precision", 0) for s in ragas_scores if "context_precision" in s)
                / len([s for s in ragas_scores if "context_precision" in s]), 4
            ),
            "avg_context_recall": round(
                sum(s.get("context_recall", 0) for s in ragas_scores if "context_recall" in s)
                / len([s for s in ragas_scores if "context_recall" in s]), 4
            ),
        }

    print_report(args.run_name, summary, ragas_avg)
    slice_analysis(db_url, run_id)


# ──────────────────────────────────────────────
# 对比分析
# ──────────────────────────────────────────────

def compare_runs(db_url: str, run1: str, run2: str):
    """逐项对比两次运行 + 切片分析"""
    conn = psycopg2.connect(db_url)
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # 获取两次运行的 ID 和配置
    cur.execute("SELECT id, config, created_at FROM eval_run WHERE run_name = %s ORDER BY created_at DESC LIMIT 1", (run1,))
    r1 = cur.fetchone()
    cur.execute("SELECT id, config, created_at FROM eval_run WHERE run_name = %s ORDER BY created_at DESC LIMIT 1", (run2,))
    r2 = cur.fetchone()

    if not r1 or not r2:
        print("Run not found")
        cur.close()
        conn.close()
        return

    print(f"\n{'='*70}")
    print(f"  Comparison: {run1}  vs  {run2}")
    print(f"  {r1['created_at']}  vs  {r2['created_at']}")
    print(f"{'='*70}")

    # 逐条对比
    cur.execute("""
        SELECT r1.test_case_id, tc.question, tc.topic, tc.query_type, tc.difficulty,
               r1.ndcg_3 as ndcg_1, r2.ndcg_3 as ndcg_2,
               r1.precision_3 as p_1, r2.precision_3 as p_2,
               r1.recall_3 as r_1, r2.recall_3 as r_2,
               r1.mrr as mrr_1, r2.mrr as mrr_2,
               r1.latency_ms as lat_1, r2.latency_ms as lat_2,
               r1.evidence_level as ev_1, r2.evidence_level as ev_2
        FROM eval_result r1
        JOIN eval_result r2 ON r1.test_case_id = r2.test_case_id
            AND r2.run_id = %s
        JOIN eval_test_case tc ON r1.test_case_id = tc.id
        WHERE r1.run_id = %s
        ORDER BY tc.id
    """, (r2['id'], r1['id']))

    rows = cur.fetchall()
    ndcg_diffs = []
    for row in rows:
        ndcg_diff = (row['ndcg_2'] or 0) - (row['ndcg_1'] or 0)
        ndcg_diffs.append(ndcg_diff)
        marker = "▲" if ndcg_diff > 0.01 else ("▼" if ndcg_diff < -0.01 else "─")
        label = f"{marker} [{row['test_case_id']:2d}]"
        lat_diff = (row['lat_2'] or 0) - (row['lat_1'] or 0)
        lat_str = f"lat={row['lat_1'] or 0}→{row['lat_2'] or 0}ms ({lat_diff:+d})" if abs(lat_diff) > 10 else ""
        print(f"  {label} {row['question'][:45]:45s}  "
              f"NDCG: {row['ndcg_1'] or 0:.3f}→{row['ndcg_2'] or 0:.3f} ({ndcg_diff:+.3f})  "
              f"EV: {row['ev_1'] or 'NONE'}→{row['ev_2'] or 'NONE'}  "
              f"{lat_str}")

    # 聚合对比
    avg_ndcg_1 = sum(r['ndcg_1'] or 0 for r in rows) / len(rows) if rows else 0
    avg_ndcg_2 = sum(r['ndcg_2'] or 0 for r in rows) / len(rows) if rows else 0
    avg_lat_1 = sum(r['lat_1'] or 0 for r in rows) / len(rows) if rows else 0
    avg_lat_2 = sum(r['lat_2'] or 0 for r in rows) / len(rows) if rows else 0
    improved = sum(1 for d in ndcg_diffs if d > 0.01)
    degraded = sum(1 for d in ndcg_diffs if d < -0.01)
    unchanged = sum(1 for d in ndcg_diffs if -0.01 <= d <= 0.01)

    print(f"\n  --- Summary ---")
    print(f"  Avg NDCG: {avg_ndcg_1:.4f} → {avg_ndcg_2:.4f} ({avg_ndcg_2 - avg_ndcg_1:+.4f})")
    print(f"  Avg Lat:  {avg_lat_1:.0f} → {avg_lat_2:.0f}ms ({avg_lat_2 - avg_lat_1:+.0f}ms)")
    print(f"  Cases:    {improved} improved, {degraded} degraded, {unchanged} unchanged")

    # 切片对比
    print(f"\n  --- Slice Comparison ---")
    cur.execute("""
        SELECT tc.query_type, tc.difficulty,
               AVG(r1.ndcg_3) as ndcg_1, AVG(r2.ndcg_3) as ndcg_2,
               AVG(r1.latency_ms) as lat_1, AVG(r2.latency_ms) as lat_2
        FROM eval_result r1
        JOIN eval_result r2 ON r1.test_case_id = r2.test_case_id
            AND r2.run_id = %s
        JOIN eval_test_case tc ON r1.test_case_id = tc.id
        WHERE r1.run_id = %s
        GROUP BY tc.query_type, tc.difficulty
        ORDER BY tc.query_type, tc.difficulty
    """, (r2['id'], r1['id']))
    for row in cur.fetchall():
        diff = (row['ndcg_2'] or 0) - (row['ndcg_1'] or 0)
        marker = "▲" if diff > 0.01 else ("▼" if diff < -0.01 else "─")
        print(f"  {marker} [{row['query_type']:12s} | {row['difficulty']:6s}]  "
              f"NDCG: {row['ndcg_1'] or 0:.4f}→{row['ndcg_2'] or 0:.4f} ({diff:+.4f})  "
              f"Lat: {row['lat_1'] or 0:.0f}→{row['lat_2'] or 0:.0f}ms")

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()