# Build log

One entry per work package: what ran, how long it took, counters and numbers, and fixes. Every number the report quotes must trace back to this file or to `eval/results.md` (plan S8).

---

## WP1 — Environment setup (17 & 20 Oct 2026)
- Full details: `docs/setup-log.md`.
- **Memory:** Profile B (Windows 8.03 GB → WSL 10 GB, YARN 6144 MB).
- **Stack:** JDK 21.0.12.1, Hadoop 3.4.3, Hive 4.2.1 + Tez 0.10.5 (PostgreSQL 16.15 metastore), HBase 2.6.6, Spark 4.1.2, Pig 0.18.0.
- **Checks:** every WP1 check passed. MR Pi 24 s · Hive/Tez DAG 6.03 s · Pig 27 s · SparkPi 26 s · PySpark/venv 39 s · our jar on YARN 20 s.
- **Fixes:** 12 issues, listed under "Issues & fixes" in `setup-log.md`.

## WP2 — Data acquisition (20 Oct 2026)
- **Simple English Wikipedia** dump `20261001` (newest complete dump at the time):
  - `simplewiki-20261001-pages-articles.xml.bz2`: 358,258,425 bytes. SHA-1 OK against `simplewiki-20261001-sha1sums.txt`.
  - Download 285 s, alongside other downloads.
  - **S1 ground truth: `bzcat … | grep -c '<page>'` = 562,214** (66 s).
  - In HDFS: `/bda/raw/simplewiki/` (341.7 MB, kept as bz2).
- **MS MARCO passage** `collectionandqueries.tar.gz`:
  - Source: `https://msmarco.z22.web.core.windows.net/msmarcoranking/collectionandqueries.tar.gz` (first source in §6.2; HTTP 206).
  - 1,057,717,952 bytes, 389 s.
  - `collection.tsv` = **8,841,823** lines (expected ✅) · `qrels.dev.small.tsv` = **7,437** ✅ · `queries.dev.tsv` = 101,093.
  - dev/small queries via the `awk` filter = **6,980** ✅. The archive also ships `queries.dev.small.tsv`, and it matches the awk output exactly.
  - In HDFS: `/bda/raw/msmarco/collection.tsv` (2.9 GB).
- **Fixed evaluation sample:** `eval/sample_qids.txt`, 1,000 qids, `random.Random(42).sample(sorted(qids), 1000)`; first three are 1000017, 1000030, 1000459. **Must never change.**
  - `queries.dev.small.tsv` / `qrels.dev.small.tsv` are copied into `eval/` but not committed (MS MARCO licence: non-commercial research; re-download from the source above).

## WP3 — Tokeniser (20 Oct 2026)
- `bda.common.Tokenizer`, `TOKENIZER_VERSION = "v1-lucene33-porter"`: NFKD + strip marks → lowercase (ROOT) → `[^a-z0-9]` → space → length 2–40 → Lucene's 33 stopwords → Snowball `PorterStemmer` (lucene-analysis-common 9.12.3).
- **`mvn package`: 11 JUnit tests, 0 failures**. These cover every §7.1 case; Porter output for "Olympics" is pinned as `olymp`.
- **Throughput:** 10,000 MS MARCO passages in 0.451 s = **22,178 docs/s, 799,079 tokens/s** (one thread, JDK 21).
- `bda.tools.TokenizeQueries` on all 6,980 dev/small queries: **0 zero-term queries**.
- Shaded jar `bda-search-1.0.jar` (25 MB): it contains the Porter stemmer and no Hadoop classes. `TokenWordCount` over 2,000 passages ran on YARN in 20 s.

---

## Issues & fixes
See `docs/setup-log.md` § "Issues & fixes" for WP1. Later work packages add theirs here.
