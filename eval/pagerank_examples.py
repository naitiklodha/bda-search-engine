"""WP10 acceptance: find queries where the PageRank boost visibly changes the BM25 top results.
Usage (from ~/bda/repo, QueryServer running): ~/bda/venv/bin/python eval/pagerank_examples.py
Writes eval/pagerank_examples.md (the queries whose top 5 change, with both rankings side by side).
"""
import json
import urllib.parse
import urllib.request

CANDIDATES = ["paris", "football club", "rock band", "river", "university", "language", "king of england",
              "computer", "war", "music", "capital city", "president", "planet", "church", "olympic games",
              "ocean", "democracy", "film director", "island", "mountain"]


def top(q, pr):
    url = f"http://localhost:8090/search?q={urllib.parse.quote(q)}&model=bm25&pr={pr}&k=5"
    return [h["title"] for h in json.load(urllib.request.urlopen(url, timeout=60))["results"]]


changed = []
for q in CANDIDATES:
    a, b = top(q, 0), top(q, 1)
    if a != b:
        changed.append((q, a, b, len(set(a) - set(b))))
changed.sort(key=lambda x: -x[3])
with open("eval/pagerank_examples.md", "w", encoding="utf-8") as f:
    f.write("# PageRank boost examples (BM25 top 5, boost weight w = 0.5)\n\n")
    f.write(f"{len(changed)} of {len(CANDIDATES)} candidate queries change their top 5 when the boost is on.\n\n")
    for q, a, b, n in changed[:8]:
        f.write(f"## `{q}`  ({n} of 5 results replaced)\n\n| # | BM25 | BM25 + PageRank |\n|---|---|---|\n")
        for i in range(5):
            f.write(f"| {i + 1} | {a[i] if i < len(a) else ''} | {b[i] if i < len(b) else ''} |\n")
        f.write("\n")
print(f"PRBOOST changed={len(changed)}/{len(CANDIDATES)}: " + "; ".join(q for q, *_ in changed[:8]))
