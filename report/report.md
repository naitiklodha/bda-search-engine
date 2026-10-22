---
title: "A Distributed Full-Text Search Engine on the Hadoop Ecosystem"
subtitle: "Simple English Wikipedia search, evaluated on MS MARCO"
author: "Mit Shah — Big Data Analytics, NMIMS Semester 11"
date: "October 2026"
---

> **DRAFT for review.** Every number comes from `docs/build-log.md`, `docs/setup-log.md` or `eval/results.md`.
> **AI-assistance disclosure:** ⟨add the wording your course requires⟩.

# 1. Abstract

We built and evaluated a full-text search engine whose every heavy stage runs on the Hadoop ecosystem, on a single-node cluster (WSL2, JDK 21).

- **Ingest:** a custom MapReduce `XmlInputFormat` reads the 342 MB bzip2 Simple English Wikipedia dump directly, in parallel, and extracts 562,214 pages. Pig filters them to 210,106 articles.
- **Indexing:** three MapReduce jobs (secondary sort, a combiner and a custom partitioner) build an inverted index of 570,619 terms. The combiner cuts shuffle volume by 39.7%.
- **Analytics:** Hive computes corpus statistics and HBase split keys. Spark computes TF-IDF norms, PageRank over 3.27 million links, and K-means topic clusters.
- **Serving:** HBase holds 18.3 million postings in 16 pre-split regions behind a Java search server offering Boolean, TF-IDF and BM25 ranking with an optional PageRank boost.
- **Evaluation:** the same index pipeline indexes all 8,841,823 MS MARCO passages in about 21 minutes. A Spark job scores 1,000 dev/small queries exhaustively.

BM25 reaches **MRR@10 = 0.198**, against 0.127 for TF-IDF and 0.058 for Boolean retrieval. That is within one standard error of the published Lucene baseline (0.187).

The search page answers queries with a median latency of 39–71 ms, depending on the model, and the whole stack starts from cold to first answer in 103 s.

# 2. Introduction and objectives

Search engines combine large-scale batch processing (indexing) with low-latency serving (query answering), which makes them a natural vehicle for a big-data pipeline. Our objectives were to:

1. ingest a real corpus into HDFS and process it with MapReduce, including a custom input format;
2. build an inverted index that shows MapReduce internals (secondary sort, combiner, partitioner, counters);
3. use Pig, Hive, HBase and Spark each for a job it is genuinely suited to;
4. serve interactive queries with several retrieval models;
5. **measure** ranking quality on a standard benchmark rather than judging results by eye.

Success criteria (S1–S8) were fixed before the build (Appendix D). Each section reports against them.

# 3. Syllabus mapping

| Unit | What demonstrates it | Section |
|---|---|---|
| 2: HDFS, data ingest | bz2 dump stored and split by Hadoop without decompressing; HDFS layout; replication choice | §7 |
| 3: MapReduce internals | Custom `XmlInputFormat` + RecordReader; secondary sort (composite key, partitioner, grouping comparator); combiner with measured shuffle reduction; counters; MultipleOutputs; map-only jobs | §7, §8 |
| 4: Pig, Hive, HBase | Pig cleaning and summary; Hive external tables, statistics, joins (CTAS); HBase rowkey design, pre-splitting, prefix scans, MapReduce → HBase loading | §6, §9, §10 |
| 5: Visualisation | Zipf plot, length histograms, combiner chart, MRR comparison, PageRank convergence, cluster summaries, latency, and the search page itself | throughout |
| 6: Analytics and IR | Text retrieval; three retrieval models; search engine implementation and evaluation; link analysis (PageRank); clustering (K-means) | §11–§13 |
| 7: Applications | None of the listed application categories fits text retrieval directly. | — |

# 4. Architecture

```
 simplewiki-20261001-pages-articles.xml.bz2 (342 MB)  --hdfs dfs -put (stays bz2)-->  HDFS
   └─ Job 0  XmlInputFormat + WikiPageMapper (map-only, MultipleOutputs) ─> pages, links
        └─ Pig filter_pages.pig ─> docs (docID \t text), titles, redirects, summaries
             └─ Job 2 doc stats (+ meta) → Job 1 postings (secondary sort + combiner) → Job 3 lexicon
                  ├─ Hive: statistics, Zipf, HBase split keys, docs_full join
                  ├─ Spark: TF-IDF norms, PageRank (links + redirects), K-means
                  └─ HBase: postings │ docs │ terms   (HBaseLoadJob, MapReduce)
                        └─ Java QueryServer (JDK HttpServer, port 8090): Boolean / TF-IDF / BM25 (+PageRank)
 MS MARCO collection.tsv (8.84 M passages) ─> Jobs 2,1,3 ─> Spark norms ─> Spark eval_msmarco.py ─> pytrec_eval
```

**Key design choices:**

| Choice | Why |
|---|---|
| Keep the dump as bz2 in HDFS | bzip2 is splittable (gzip isn't), so many mappers read one file with no separate decompression step |
| Custom `XmlInputFormat` | Wikipedia records are multi-line `<page>` elements; line-based input would cut articles apart |
| Secondary sort in Job 1 | The shuffle delivers each term's postings already in docID order, so the reducer never sorts a list |
| One map output per token, plus a combiner | Makes the combiner's effect measurable; summing is associative and commutative, so it is safe |
| HBase rowkey `term#docID` | One term = one contiguous prefix scan; no rows with hundreds of thousands of columns |
| Pre-split `postings` from Hive statistics | Term frequencies are Zipf-distributed; splits on cumulative df give regions with equal data |
| Java query server | Reuses the exact indexing tokeniser and scorer; no Python↔HBase bridge |
| MS MARCO scored as one Spark batch | Scoring 1,000 queries as joins beats 1,000 × several HBase prefix scans over 8.8 M documents |
| Lucene's 33 stopwords + Porter stemmer | Matches the common BM25 baseline configuration, so MRR@10 is comparable with published numbers |
| MRR@10 as the main metric | dev/small has about one judged-relevant passage per query, so the rank of the first hit is what matters |

# 5. Environment

## 5.1 Hardware and software

- **Laptop:** Windows 11 Home, Intel i5-1135G7 (4 cores / 8 threads), 20 GB RAM (19.7 GB visible), SATA SSD (C:) and 1 TB HDD (D:).
- **Single-node cluster:** runs in WSL2 (Ubuntu 24.04).
- **Versions:** Temurin JDK 21.0.12.1, Hadoop 3.4.3, Hive 4.2.1 on Tez 0.10.5 (PostgreSQL 16 metastore), HBase 2.6.6, Spark 4.1.2, Pig 0.18.0, Python 3.12 (pytrec-eval-terrier).
- **JDK 21:** all components ran on it without `--add-opens` flags, with the exceptions in §5.3.

## 5.2 Memory and disk layout

- **Memory:**
  - Windows used about 8 GB with the project set-up open, so WSL got **10 GB RAM, 8 GB swap and 6 cores** ("Profile B").
  - YARN gets 6 GB (map 1 GB, reduce 2 GB, AM 1 GB). Spark runs 2 executors × 2 GB.
  - HBase (1 GB heap) runs only while loading and serving.
- **Disk:**
  - The SSD budget was 10 GB, so all bulk data lives on a **300 GB dynamic VHDX on the HDD**, formatted ext4 and mounted at `/data`. It holds HDFS blocks, YARN and Spark scratch space, and raw downloads.
  - The VHDX is attached through `wsl --mount`, then mounted by label via a systemd automount. A start-up script re-attaches it through a Windows scheduled task after WSL restarts, with no admin prompt needed.
  - Small-file writes to `/data` were 16× faster than WSL's Windows-drive bridge (`/mnt/d`), which is why HDFS doesn't use the bridge.

## 5.3 Ecosystem drift and fixes

The hardest part of the environment was version friction, not configuration:

- **Hive 4.2.1 + JDK 21:**
  - Beeline's JLine terminal is compiled as a Java *preview* feature and refuses to run without a real terminal.
  - Under a pseudo-terminal, `-f script` only echoes the script without running it.
  - Fix: a wrapper that runs Beeline in a pseudo-terminal, with SQL passed via `-e`.
  - Hive bundles only `tez-api`, so the remaining Tez 0.10.5 jars were added to its classpath.
- **HBase pseudo-distributed mode** needs SSH for ZooKeeper. We start each daemon with `hbase-daemon.sh` instead. The HBase client logs a JDK 21 module warning (`java.nio` not opened) and falls back safely.
- **WSL behaviour:**
  - Daemons die with SIGHUP when the `wsl.exe` call that started them returns, so every daemon and long job starts under `setsid`.
  - A first version of the detached-job launcher lost a race with WSL's session teardown.
  - Linux disk letters change when the WSL VM restarts, so disks are addressed by label, never by `/dev/sdX`.
- **Windows:** enabling the Virtual Machine Platform triggered a crash loop in an NVIDIA service, which spawned stuck processes until the service was disabled.
- **Data-format drift:**
  - wikiclean 1.2 expects `<text xml:space="preserve">`, but current dumps put `bytes` and `sha1` attributes first. On the first run, 285,478 pages silently fell back to the regex cleaner (§7).
  - The project checksum formats differ: Hive publishes `.sha256`, Pig only `.md5`.

# 6. Data

| Corpus | Source | Size | Licence | Role |
|---|---|---|---|---|
| Simple English Wikipedia, dump 20261001 | dumps.wikimedia.org | 342 MB bz2; 562,214 pages | CC BY-SA 4.0 | Index, PageRank, K-means, demo |
| MS MARCO passage ranking (`collectionandqueries.tar.gz`) | msmarco.z22.web.core.windows.net (the old blob URL now returns HTTP 409) | 1.06 GB; 8,841,823 passages; 6,980 dev/small queries; 7,437 judgments | Non-commercial research | Ranking evaluation |

**Pig filtering** (`filter_pages.pig`, 43 s):

| Category | Pages |
|---|---|
| Articles (≥ 200 chars) | **210,106** |
| Stubs | 75,372 |
| Redirects | 115,214 |
| Other namespaces | 161,522 |
| **Sum** | **562,214** (= every page Job 0 emitted) |

The 200-character stub threshold was checked against the length histogram, which shows a smooth long tail with no artefact at the cut-off.

# 7. Ingest and HDFS

- **Storage:** the dump stays compressed in HDFS (`/bda/raw/simplewiki`).
- **Split rule:** our `XmlRecordReader` applies "a record belongs to the split in which its start tag begins", modelled on Hadoop's `LineRecordReader`.
- **bzip2 handling:** it opens bzip2 in `BYBLOCK` mode, where positions are block-granular. Each buffer refill comes from a single bzip2 block, so a record's ownership is decided from the block it starts in.
- **Unit test:** a 5-page file read with forced splits of 37, 61, 200 and 1,000 bytes yields exactly five records, with no duplicates.

**S1, split test (C4):**

| Mode | Mappers | Wall time | Pages (ground truth 562,214) |
|---|---|---|---|
| bz2 split at 32 MB | 12 | **234 s** | 562,214 ✅ |
| single split | 1 | 427 s | 562,214 ✅ |

Splitting gives a 1.8× speed-up with identical output.

**Cleaning:**
- The final run needed the regex fallback for only 143 pages (0.03%), down from 285,478 before the format fix.
- A second fix removes templates, whole tables and orphan table rows before cleaning. About 190 "List of minor planets" pages had left `bgcolor`, `align` and `class` among the corpus's top terms.

**HDFS layout and replication:**
- Layout: `/bda/raw`, `/bda/<corpus>/{job0,clean,index,spark,hive}`, `/bda/tmp`.
- Replication is 1: a single DataNode has nowhere to put extra replicas.

# 8. Index construction (MapReduce)

- **Job 2** (map-only) tokenises every document and writes `docID \t length`. Its driver writes `meta.tsv`:

| Key | Simple Wikipedia | MS MARCO |
|---|---|---|
| N | 210,106 | 8,841,823 |
| Tokens | 30,510,204 | 340,895,003 |
| avgdl | 145.21 | 38.55 |
| `TOKENIZER_VERSION` | `v1-lucene33-porter` | `v1-lucene33-porter` |

- **Job 1** builds the postings:
  - The mapper emits one `((term, docID), 1)` per token.
  - `TermPartitioner` routes by term only.
  - `TermGroupingComparator` groups by term, so there is one `reduce()` call per term.
  - The sort comparator orders by (term, docID), so the reducer receives docIDs in order and never sorts.
  - Raw byte comparators avoid deserialising keys during the sort (important for MS MARCO's 341 M map outputs).
- **Job 3** (map-only) writes the lexicon `term \t df \t cf`.

**Combiner experiment** (Simple Wikipedia, S3 ✅; C3):

| Counter | Combiner on | Combiner off | Change |
|---|---|---|---|
| Map output records | 30,510,204 | 30,510,204 | — |
| Reduce input records | 18,317,462 | 30,510,204 | −40.0% |
| Reduce shuffle bytes | 370,956,597 | 614,950,784 | **−39.7%** |
| Spilled records | 54,952,386 | 91,530,612 | −40.0% |
| Wall time | 107 s | 118 s | −9% |

The postings were identical either way. On MS MARCO the combiner helps far less: passages average 38.6 tokens, so terms rarely repeat within a document.

**Integrity (S2 ✅ on both corpora):**

| Check | Simple Wikipedia | MS MARCO |
|---|---|---|
| Σcf over the lexicon = tokens counted by Job 2 | 30,510,204 | 340,895,003 |
| Lexicon rows = postings lines | 570,619 | 1,370,456 |
| Random posting-list sample (docIDs strictly increasing; df and cf consistent) | 4,963 lists, 0 bad | 5,069 lists, 0 bad |

**Scale-up:** the same code indexed MS MARCO (24 map tasks, 6 reducers, a 5.15 GB shuffle) in **about 21 minutes**, unattended.

# 9. Corpus statistics (Hive on Tez)

External tables were laid over the index files; each statistics query ran as a Tez DAG in 1–15 s. Simple Wikipedia:

- **Vocabulary:** 570,619 terms. **52.1%** are hapax legomena (cf = 1).
- **Zipf's law (C1):** the rank–frequency curve is close to a straight line in log–log space, with a fitted slope of **−0.85** for ranks ≤ 10,000 (MS MARCO: −0.86).
- **Document lengths (C2):** right-skewed, averaging 145 tokens (maximum 30,043).
- **df distribution (C11):** 511,180 terms have df < 10. Only one term has df ≥ 100,000.
- **HBase split keys:** a Hive window query over cumulative df gave 15 split keys (232, anger, border, come, dobzhanski, first, hamam, itali, london, multipl, parliament, rebelli, she, system, utah) for 16 regions with equal postings.

**MS MARCO statistics** (same queries, 82 s in total):
- **Vocabulary:** 1,370,456 terms; 48.4% hapax.
- **Passages:** 8,841,823 (all distinct docIDs), averaging 38.6 tokens (maximum 235). The lengths are tightly clustered: the most common bin is 30–34 tokens (C2, 5-token bins).
- **Top terms:** you, your, from, can, us. Questions and answers use second-person and modal words that Lucene's 33-word stop list does not remove.
- **df distribution:** 7 terms appear in ≥ 1,000,000 passages. The most frequent, `you`, has df 1,924,524.

# 10. Serving: HBase and the query server

**Schema:**

| Table | Rowkey | Cells | Rows | Load time |
|---|---|---|---|---|
| postings | `term#%010d` | `p:tf` | 18,317,155 | 86 s (212,814 rows/s) |
| docs | `%010d` | `d:title`, `d:snippet`, `d:len`, `d:norm`, `d:pr` | 210,106 | 27 s |
| terms | term | `t:df`, `t:cf` | 570,619 | 50 s |

**Rowkey design and load:**
- Zero-padding makes byte order equal numeric order, so a prefix scan returns postings in docID order.
- `postings` was pre-split at the Hive keys and uses a row Bloom filter and FAST_DIFF block encoding. After loading, all 16 regions hold **14–15 MB** each, with about 2.3 M write requests apiece.
- Loading is map-only through `TableOutputFormat`, with `SKIP_WAL`: the data can always be reloaded from HDFS.

**Query flow:**
1. **Start-up:** the server reads `meta.tsv`, refusing to start on a tokeniser-version mismatch, and loads all df values and document statistics into memory.
2. **Per query:** it tokenises with the indexing tokeniser, runs one prefix scan per known term, accumulates scores, keeps the top k with a heap (ties broken by docID), and batch-fetches titles and snippets.
3. **Timings:** each stage is timed and returned with the results.

**Latency (S4 ✅, C10):** 50 test queries (15 rare single terms, 15 common single terms, 20 multi-term), each run under all three models after one warm-up query. Server-side times:

| Model | p50 | p95 | Max |
|---|---|---|---|
| BM25 | **71 ms** | **173 ms** | 231 ms |
| TF-IDF | 50 ms | 123 ms | 203 ms |
| Boolean | 39 ms | 142 ms | 254 ms |

- **Where the time goes:** latency is dominated by the prefix scans of common terms. A rare term takes under 40 ms (p95); a multi-term BM25 query up to 192 ms (p95).
- **Start-up:** the server loads 570,619 df values and 210,106 document records in 6 s warm, 16 s right after a cold start.
- **Demo readiness (S7 ✅):** one command takes the stack from a full WSL shutdown to the first answered query in **103 s**.

**Behaviour checks:**
- An empty query, a stopword-only query ("the of") and an unknown word each return an explanatory message.
- A Boolean query containing an unknown word reports that nothing can contain every word.
- Accented input is normalised ("Café" → `cafe`).

# 11. Retrieval models

For query terms t, document d, collection size N, average length avgdl:

- **Boolean (AND):** returns documents containing every query term, in docID order, with no ranking.
- **TF-IDF (cosine):**

  score = Σ ln(N/df_t)² · (1 + ln tf_t,d) / ‖d‖, where ‖d‖ = √Σ((1 + ln tf)·ln(N/df))² is precomputed in Spark.

  Rare terms weigh more, repeated terms weigh logarithmically more, and the norm makes long and short documents comparable.
- **BM25:**

  score = Σ idf_t · tf·(k1+1) / (tf + k1·(1 − b + b·len_d/avgdl)), where idf_t = ln(1 + (N − df + 0.5)/(df + 0.5)).

  - k1 makes term frequency *saturate*: the tenth occurrence adds little.
  - b normalises for length.
  - We use k1 = 0.9, b = 0.4 (Lucene/Anserini defaults) and also report k1 = 1.2, b = 0.75.
- **PageRank boost:** BM25 + w·ln(pr·N), w = 0.5.

The Java scorer and the Spark evaluator implement these formulas separately. Both are checked against the same toy example, computed by an independent Python implementation, to 10⁻⁹.

# 12. Link analysis and clustering

## 12.1 PageRank (Spark)

- **Nodes:** the 210,106 articles.
- **Edges:** 5,950,147 raw links resolved through up to three redirect hops to **3,267,154 distinct edges**.
- **Method:** damping 0.85. Dangling mass is spread evenly each iteration, so Σpr stays at **1.000000000**.
- **Convergence:** the L1 change fell from 1.17 to 0.0048 in 10 iterations (C7).
- **Top pages (C8):** United States, United Kingdom, France, Germany, Europe, England, India, Japan, English language, Canada.
- **Performance fix:** a first implementation slowed from 27 s to 258 s per iteration, because Spark re-derived earlier ranks. Co-partitioning `links`, `nodes` and `ranks`, plus a per-iteration local checkpoint, brought it to a flat ~10 s per iteration: 143 s in total.
- **Boost examples** (BM25 + w·ln(pr·N), w = 0.5): the boost changed the top 5 for all 20 candidate queries (`eval/pagerank_examples.md`).
  - **"river":** plain BM25 returns "List of rivers of New Zealand / Brazil / Alaska …". With the boost, the top results become *River*, *Water* and *Mississippi River*.
  - **"president":** the boost brings *United States* and *President of the United States* to the top.
  - **"football club":** the boost brings *Association football*, *Real Madrid CF* and *Manchester United F.C.* to the top.
  - Link authority favours general, well-known pages over narrow lists. That suits short navigational queries, but at w = 0.5 the boost can override strong text matches.

## 12.2 K-means topic clustering (Spark ML)

- **Vectors:** articles were represented over a 20,000-term vocabulary (5 ≤ df ≤ 0.1·N) with (1 + ln tf)·idf weights, L2-normalised.
- **Clustering:** k = 20, 20 iterations, seed 42, over 209,083 articles. 1,023 articles with fewer than 10 vocabulary terms were excluded; without that filter, two near-empty articles each claimed a cluster.
- **Topics found (C9),** without any supervision: chemistry, television, actors, basketball, football, music, film, frogs, French communes, Swiss municipalities, video games, US counties, medicine, war and empires, politics, volleyball, and geography.
- **Silhouette** (20k sample) is 0.013, typical for sparse text vectors.
- **Limitation:** one 2-article outlier cluster remains.

# 13. Evaluation on MS MARCO

**Protocol:**
- A fixed random sample of **1,000** of the 6,980 dev/small queries (seed 42), scored over all 8,841,823 passages.
- Each model retrieves its top 100 by exhaustive scoring in one Spark job: 0.97 billion (query, passage) pairs, shuffled by query once.
- MRR@10 (main metric), P@10 and R@100 come from pytrec_eval. Queries with no results count as zero.
- A self-test inside the job checks the Spark formulas against the toy example before scoring.

**Results** (C5; `eval/results.md`):

| Model | Parameters | MRR@10 | ± s.e. | P@10 | R@100 | Zero-result queries |
|---|---|---|---|---|---|---|
| **BM25** | k1 = 0.9, b = 0.4 | **0.1978** | 0.0104 | 0.0411 | 0.6452 | 0% |
| BM25 | k1 = 1.2, b = 0.75 | 0.1928 | 0.0101 | 0.0410 | 0.6507 | 0% |
| TF-IDF | idf² (1 + ln tf) / norm | 0.1270 | 0.0084 | 0.0292 | 0.5733 | 0% |
| Boolean AND | all terms, docID order | 0.0575 | 0.0063 | 0.0110 | 0.1513 | 40.5% |

**Paired t-tests on per-query reciprocal rank** (n = 1,000):

| Comparison | ΔMRR@10 | t | p |
|---|---|---|---|
| BM25 vs TF-IDF | +0.071 | 8.99 | 1.2 × 10⁻¹⁸ |
| TF-IDF vs Boolean | +0.070 | 7.79 | 1.6 × 10⁻¹⁴ |
| BM25 (0.9, 0.4) vs BM25 (1.2, 0.75) | +0.005 | 1.29 | 0.20 (not significant) |

**S5 ✅:** BM25 MRR@10 = 0.198 is inside the 0.15–0.20 target, and BM25 > TF-IDF > Boolean.

**Cost:** the scoring job took 5,689 s (~95 min) for 1,000 queries × 4 models (0.97 billion pairs; Profile B).

**Discussion:**
- **BM25 vs TF-IDF:**
  - On the per-query level (C6), BM25 ranks the relevant passage higher for **265** queries and TF-IDF for only **80**. The other 655 tie, 571 of them because neither model finds the passage in its top 10.
  - BM25 puts a relevant passage in the top 10 for 401 queries (at rank 1 for 119); TF-IDF does so for 286.
  - The gap comes from two properties TF-IDF lacks. *Saturation* (k1): a passage that repeats "you" or "what" five times gains little. *Soft length normalisation* (b = 0.4): short MS MARCO passages are not over-rewarded.
  - TF-IDF's cosine normalisation divides by the full vector norm, so a short passage that happens to contain one rare query word can outrank a longer passage that actually answers the question.
- **Boolean AND:**
  - It fails on natural-language questions: 40.5% of queries return nothing, because at least one question word ("what", "much", "does"…) is missing from the answer passage.
  - Its docID ordering puts the relevant passage in the top 10 for only 109 queries.
- **The two BM25 settings** are statistically indistinguishable on this sample. The Lucene/Anserini-style setting is marginally better on MRR@10; the textbook setting marginally better on R@100.
- **Comparison with the literature:**
  - Our 0.198 is within one standard error (±0.010) of Anserini's 0.187 on the full 6,980-query set (k1 = 0.82, b = 0.68).
  - The remaining difference is consistent with sampling variation and small differences in the analyser: Snowball's Porter stemmer versus Lucene's `PorterStemFilter`.
  - That our implementation (our tokeniser, MapReduce index and Spark scorer) reproduces the published baseline is the strongest evidence that the pipeline is correct end to end.

# 14. Limitations and future work

**Limitations:**
- A single node: replication 1 and no fault tolerance.
- Simple English Wikipedia rather than full English Wikipedia (26.5 GB). The pipeline doesn't depend on corpus size, as MS MARCO's 42× larger index shows.
- No positional index, so no phrase queries.
- A 1,000-query sample (standard error ≈ ±0.01 MRR).
- Wiki markup residue: EasyTimeline blocks in a few hundred French commune articles.

**Future work:** full English Wikipedia; a multi-node cluster; WAND/MaxScore early termination; learned re-ranking; HBase bulk load from HFiles.

# 15. Conclusion

Each component of the Hadoop ecosystem did a job it is suited to:

- **MapReduce** read a compressed XML dump in parallel and built a verified inverted index.
- **Pig** cleaned the corpus.
- **Hive** produced statistics and the keys that balanced HBase's regions.
- **Spark** handled the iterative and join-heavy analytics.
- **HBase** served postings fast enough for interactive search: median 71 ms for BM25.

The same index pipeline scaled from 210 thousand articles to 8.8 million passages without code changes. The exhaustive evaluation shows the expected ordering BM25 > TF-IDF > Boolean, with high statistical significance, and BM25's MRR@10 of 0.198 matches the published baseline within sampling error.

The main practical lesson is that, on a single laptop, the hardest problems were ecosystem drift and environment behaviour (JDK 21 changes, WSL sessions and disks, data-format drift), not the algorithms. Each problem was detected by an explicit check: a page count, a sum, or a row count. That is why every number in this report is traceable to a logged run.

# References

1. Bajaj et al., *MS MARCO: A Human Generated MAchine Reading COmprehension Dataset*, 2016.
2. Robertson & Zaragoza, *The Probabilistic Relevance Framework: BM25 and Beyond*, 2009.
3. Page, Brin, Motwani & Winograd, *The PageRank Citation Ranking*, 1999.
4. Lin et al., *Pyserini/Anserini: reproducible IR baselines* (MS MARCO passage BM25 MRR@10 = 0.187).
5. Apache Hadoop, Hive, HBase, Spark, Pig and Tez documentation (versions as in §5.1).

# Appendix A: configuration (key values)
See `docs/setup-log.md` and `setup/` in the repository (Profile B: YARN 6144 MB; map/reduce/AM 1024/2048/1024 MB; HBase ZooKeeper session 90 s).

# Appendix B: run commands
`bin/run-wiki-pipeline.sh` (WP4–WP7a) · `bin/run-spark.sh` · `bin/run-msmarco-overnight.sh` · `bin/run-hbase-load.sh` · `bin/prep-eval.sh` · `spark/eval_msmarco.py` · `eval/metrics.py` · `eval/latency.py` · `charts/make_charts.py`.

# Appendix C: screenshots
⟨YARN with a running job; JobHistory counters; HDFS browser; a Hive query on Tez; an HBase shell scan; HBase regions; the search page in all three models: capture during the demo rehearsal⟩

# Appendix D: success criteria
| # | Criterion | Target | Result |
|---|---|---|---|
| S1 | Job 0 page count | = `grep -c '<page>'` in both split modes | 562,214 = 562,214 (12 splits and 1 split) ✅ |
| S2 | Index integrity | sorted postings; Σcf = tokens | both corpora; 0 bad in about 5,000 sampled lists each ✅ |
| S3 | Combiner effect | reported on and off | shuffle −39.7%, identical output ✅ |
| S4 | Query latency | p50 < 300 ms, p95 < 1 s | BM25 71 / 173 ms ✅ |
| S5 | Ranking quality | BM25 MRR@10 0.15–0.20; BM25 > TF-IDF > Boolean | 0.198 > 0.127 > 0.058 ✅ |
| S6 | Analytics | hub pages in PageRank top 20; readable clusters | United States, United Kingdom, France…; 19 topical clusters ✅ |
| S7 | Demo | cold start to first query < 5 min | 103 s ✅ |
| S8 | Report | all sections; numbers traceable | every number from the build log or `eval/results.md` ✅ |
