import json
import time
import argparse
from dataclasses import dataclass, field
from typing import Optional
import psycopg2
import psycopg2.extras
import requests
import sys


DB_URL = "postgresql://user:password@localhost:5432/meeting_agent"
SEARCH_API = "http://localhost:8080/api/search"


@dataclass
class TestCase:
    id: int
    question: str
    ground_truth_doc_ids: list[int]
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


def create_eval_run(db_url: str, run_name: str) -> int:
    conn = psycopg2.connect(db_url)
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO eval_run (run_name, config) VALUES (%s, %s) RETURNING id",
        (run_name, json.dumps({"note": "基础层评估"})),
    )
    run_id = cur.fetchone()[0]
    conn.commit()
    cur.close()
    conn.close()
    return run_id


def save_eval_result(db_url: str, run_id: int, test_case_id: int, metrics: Metrics):
    conn = psycopg2.connect(db_url)
    cur = conn.cursor()
    cur.execute(
        """INSERT INTO eval_result
           (run_id, test_case_id, ndcg_3, precision_3, recall_3, mrr, latency_ms,
            evidence_level, total_candidates, retrieved_doc_ids)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)""",
        (run_id, test_case_id, metrics.ndcg_3, metrics.precision_3, metrics.recall_3,
         metrics.mrr, metrics.latency_ms, metrics.evidence_level,
         metrics.total_candidates, metrics.retrieved_doc_ids),
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
    cur.close()
    conn.close()
    return dict(row) if row else {}


def print_report(run_name: str, summary: dict):
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
    print(f"{'='*50}\n")


def main():
    parser = argparse.ArgumentParser(description="RAG 检索质量评估")
    parser.add_argument("--run-name", required=True, help="评估运行名称")
    parser.add_argument("--topic", help="按主题过滤测试用例")
    parser.add_argument("--db-url", default=DB_URL, help="数据库连接字符串")
    parser.add_argument("--api-url", default=SEARCH_API, help="搜索 API 地址")
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
    run_id = create_eval_run(db_url, args.run_name)

    for case in cases:
        t0 = time.time()
        search_result = call_search_api(case.question, api_url)
        elapsed = int((time.time() - t0) * 1000)

        metrics = compute_metrics(search_result, case.ground_truth_doc_ids, elapsed)
        save_eval_result(db_url, run_id, case.id, metrics)
        print(f"  [{case.id:2d}] {case.question[:40]:40s} P@3={metrics.precision_3:.2f} MRR={metrics.mrr:.2f}")

    summary = aggregate_metrics(db_url, run_id)
    print_report(args.run_name, summary)


def compare_runs(db_url: str, run1: str, run2: str):
    conn = psycopg2.connect(db_url)
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    for run_name in (run1, run2):
        cur.execute("SELECT metrics FROM eval_run WHERE run_name = %s ORDER BY created_at DESC LIMIT 1", (run_name,))
        row = cur.fetchone()
        if row:
            print(f"\n  {run_name}: {json.dumps(row['metrics'], indent=4)}")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()