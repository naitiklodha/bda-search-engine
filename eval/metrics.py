"""WP11 step 4: MRR@10, P@10 and R@100 per model on the fixed 1,000-query MS MARCO dev/small sample.
Usage (from ~/bda/repo): ~/bda/venv/bin/python eval/metrics.py [runs_dir=eval/runs] [runtime_note]
Writes eval/results.md and eval/per_query_rr.csv. Every sample query counts: one with no results scores 0
(pytrec_eval only scores the queries that appear in a run).
"""
import math
import statistics
import sys

import pytrec_eval
from scipy import stats

runs_dir = sys.argv[1] if len(sys.argv) > 1 else "eval/runs"
runtime_note = sys.argv[2] if len(sys.argv) > 2 else ""
MODELS = [("bm25", "BM25", "k1 = 0.9, b = 0.4"),
          ("bm25_default", "BM25", "k1 = 1.2, b = 0.75"),
          ("tfidf", "TF-IDF", "Σ idf² (1 + ln tf) / norm"),
          ("boolean", "Boolean AND", "all terms, docID order")]

qrels = {}
for line in open("eval/qrels_sample.tsv"):
    q, _, d, rel = line.split()
    qrels.setdefault(q, {})[d] = int(rel)
qids = [l.strip() for l in open("eval/sample_qids.txt") if l.strip()]
assert len(qids) == 1000 and set(qids) == set(qrels), (len(qids), len(qrels))


def load(path):
    run = {}
    for line in open(path):
        q, _, d, rank, score, _ = line.split()
        run.setdefault(q, []).append((int(rank), d, float(score)))
    return run


def cut(run, n):
    return {q: {d: s for _, d, s in sorted(v)[:n]} for q, v in run.items()}


per_query, rows = {}, []
for key, name, params in MODELS:
    run = load(f"{runs_dir}/{key}.trec")
    e10 = pytrec_eval.RelevanceEvaluator(qrels, {"recip_rank", "P_10"}).evaluate(cut(run, 10))
    e100 = pytrec_eval.RelevanceEvaluator(qrels, {"recall_100"}).evaluate(cut(run, 100))
    rr = [e10.get(q, {}).get("recip_rank", 0.0) for q in qids]
    p10 = [e10.get(q, {}).get("P_10", 0.0) for q in qids]
    r100 = [e100.get(q, {}).get("recall_100", 0.0) for q in qids]
    zero = sum(1 for q in qids if not run.get(q))
    per_query[key] = rr
    rows.append((key, name, params, statistics.mean(rr), statistics.stdev(rr) / math.sqrt(len(rr)),
                 statistics.mean(p10), statistics.mean(r100), 100.0 * zero / len(qids)))

tests = []
for a, b in [("bm25", "tfidf"), ("bm25", "bm25_default"), ("tfidf", "boolean")]:
    t, p = stats.ttest_rel(per_query[a], per_query[b])
    tests.append((a, b, statistics.mean(per_query[a]) - statistics.mean(per_query[b]), t, p))

m = {r[0]: r[3] for r in rows}
s5 = 0.15 <= m["bm25"] <= 0.20 and m["bm25"] > m["tfidf"] > m["boolean"]
with open("eval/results.md", "w", encoding="utf-8") as f:
    f.write("# MS MARCO passage ranking: results (WP11)\n\n")
    f.write("Fixed sample of **1,000 dev/small queries** (seed 42; `eval/sample_qids.txt`) over the full collection "
            "of **8,841,823 passages**; exhaustive scoring in Spark (`spark/eval_msmarco.py`); metrics by pytrec_eval. "
            "Queries with no results count as 0.\n\n")
    f.write("| Model | Parameters | MRR@10 | ± s.e. | P@10 | R@100 | Queries with zero results |\n|---|---|---|---|---|---|---|\n")
    for key, name, params, mrr, se, p10, r100, z in rows:
        f.write(f"| {name} | {params} | **{mrr:.4f}** | {se:.4f} | {p10:.4f} | {r100:.4f} | {z:.1f}% |\n")
    f.write("\n**Paired t-tests on per-query reciprocal rank** (n = 1,000):\n\n| A vs B | ΔMRR@10 | t | p |\n|---|---|---|---|\n")
    for a, b, d, t, p in tests:
        f.write(f"| {a} vs {b} | {d:+.4f} | {t:.2f} | {p:.2e} |\n")
    f.write(f"\n**S5 (BM25 MRR@10 in 0.15–0.20 and BM25 > TF-IDF > Boolean): {'PASS ✅' if s5 else 'NOT MET'}**\n\n")
    f.write("Reference point: Anserini reports BM25 MRR@10 = 0.187 on the full 6,980-query dev/small set "
            "(k1 = 0.82, b = 0.68). Our sample and parameters differ, so the plan allows 0.15–0.20.\n")
    if runtime_note:
        f.write(f"\nRuntime: {runtime_note}\n")
with open("charts/data/eval_metrics.tsv", "w") as f:
    for key, name, params, mrr, se, p10, r100, z in rows:
        f.write(f"{key}\t{name} ({params})\t{mrr:.6f}\t{se:.6f}\t{p10:.6f}\t{r100:.6f}\t{z:.2f}\n")
with open("eval/per_query_rr.csv", "w") as f:
    f.write("qid," + ",".join(k for k, _, _ in MODELS) + "\n")
    for i, q in enumerate(qids):
        f.write(q + "," + ",".join(f"{per_query[k][i]:.6f}" for k, _, _ in MODELS) + "\n")
print(open("eval/results.md", encoding="utf-8").read())
