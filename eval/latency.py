"""WP10 latency test (plan S4: p50 < 300 ms and p95 < 1 s over 50 queries, excluding the first, cold query).
Usage (from ~/bda/repo, QueryServer running on :8090): ~/bda/venv/bin/python eval/latency.py
Writes charts/data/latency.tsv: model, query_type, n, p50_ms, p95_ms, mean_ms (server-side total time).
"""
import json
import statistics
import time
import urllib.parse
import urllib.request

BASE = "http://localhost:8090/search"
queries = [l.rstrip("\n").split("\t", 1) for l in open("eval/latency_queries.txt", encoding="utf-8") if l.strip()]
assert len(queries) == 50, len(queries)


def ask(q, model):
    url = f"{BASE}?q={urllib.parse.quote(q)}&model={model}&k=10"
    t0 = time.perf_counter()
    r = json.load(urllib.request.urlopen(url, timeout=60))
    return r["timings"]["total"], (time.perf_counter() - t0) * 1000, r["total"]


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, round(p / 100 * len(xs)) - 1))]


ask("warm up", "bm25")                        # the cold first query is excluded (plan S4)
rows, overall_ok = [], True
for model in ["bm25", "tfidf", "boolean"]:
    res = [(t, *ask(q, model)) for t, q in queries]
    for qtype in ["rare", "common", "multi", "all"]:
        xs = [srv for t, srv, wall, n in res if qtype == "all" or t == qtype]
        rows.append((model, qtype, len(xs), pct(xs, 50), pct(xs, 95), statistics.mean(xs)))
    allx = [srv for _, srv, _, _ in res]
    walls = [wall for _, _, wall, _ in res]
    ok = pct(allx, 50) < 300 and pct(allx, 95) < 1000
    overall_ok &= ok
    print(f"LATENCY {model:8s} server p50={pct(allx, 50):7.1f} ms  p95={pct(allx, 95):7.1f} ms  max={max(allx):7.1f} ms  "
          f"(client wall p50={pct(walls, 50):.1f} ms)  S4 {'PASS' if ok else 'FAIL'}")
with open("charts/data/latency.tsv", "w") as f:
    for r in rows:
        f.write("\t".join([r[0], r[1], str(r[2])] + [f"{x:.2f}" for x in r[3:]]) + "\n")
print(f"LATENCY S4 overall: {'PASS' if overall_ok else 'FAIL'}  (charts/data/latency.tsv)")
