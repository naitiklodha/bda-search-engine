# MS MARCO passage ranking: results (WP11)

Fixed sample of **1,000 dev/small queries** (seed 42; `eval/sample_qids.txt`) over the full collection of **8,841,823 passages**; exhaustive scoring in Spark (`spark/eval_msmarco.py`); metrics by pytrec_eval. Queries with no results count as 0.

| Model | Parameters | MRR@10 | ± s.e. | P@10 | R@100 | Queries with zero results |
|---|---|---|---|---|---|---|
| BM25 | k1 = 0.9, b = 0.4 | **0.1978** | 0.0104 | 0.0411 | 0.6452 | 0.0% |
| BM25 | k1 = 1.2, b = 0.75 | **0.1928** | 0.0101 | 0.0410 | 0.6507 | 0.0% |
| TF-IDF | Σ idf² (1 + ln tf) / norm | **0.1270** | 0.0084 | 0.0292 | 0.5733 | 0.0% |
| Boolean AND | all terms, docID order | **0.0575** | 0.0063 | 0.0110 | 0.1513 | 40.5% |

**Paired t-tests on per-query reciprocal rank** (n = 1,000):

| A vs B | ΔMRR@10 | t | p |
|---|---|---|---|
| bm25 vs tfidf | +0.0708 | 8.99 | 1.22e-18 |
| bm25 vs bm25_default | +0.0049 | 1.29 | 1.98e-01 |
| tfidf vs boolean | +0.0695 | 7.79 | 1.63e-14 |

**S5 (BM25 MRR@10 in 0.15–0.20 and BM25 > TF-IDF > Boolean): PASS ✅**

Reference point: Anserini reports BM25 MRR@10 = 0.187 on the full 6,980-query dev/small set (k1 = 0.82, b = 0.68). Our sample and parameters differ, so the plan allows 0.15–0.20.

Runtime: Spark eval_msmarco.py: 5,689 s (~95 min) for 1,000 queries x 4 models over 8,841,823 passages (0.97 billion query-passage pairs; Profile B, 2 executors x 2 GB, 128 shuffle partitions)
