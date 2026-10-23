# Code guide: what each file does, and how to open it

*Every important file in the project, in the order the data flows through them. For the plain-English story of what each step achieved, see `project-explained.md`; for the numbers, see `docs/build-log.md`.*

## How to open the files

The code lives in Linux, at `~/bda/repo` (full path `/home/mit/bda/repo`). There are four ways to look at it:

1. **Windows File Explorer** (easiest):
   - Paste `\\wsl.localhost\Ubuntu\home\mit\bda\repo` into the address bar.
   - This wakes Linux up in the background if it's off. Hadoop does **not** start.
   - Open any file with Notepad or VS Code.
2. **VS Code connected to Linux** (best for reading code):
   - Open a Linux terminal with `wsl -d Ubuntu --cd /home/mit/bda/repo`, then type `code .`.
   - VS Code offers to install its "WSL" extension the first time.
3. **GitHub:** once the repo is pushed, every file can be read in the browser.
4. **`repo-mirror/`** in the project folder holds Windows copies of the docs, eval results, charts, Hive SQL and the report. It does **not** include the Java or Python code.

**Two cautions when editing from Windows:**
- A script saved from Windows can lose its "executable" flag. If a script says `Permission denied`, run `chmod +x ~/bda/repo/bin/*.sh`.
- Don't change Java files while a job is running. After any Java change, rebuild the jar: `cd ~/bda/repo/java && mvn -q package`. This also runs the 25 tests.

## The repo at a glance

| Folder | What's in it | Language |
|---|---|---|
| `java/` | MapReduce jobs, HBase loader, search server, tokeniser, tests | Java 17, built with Maven into `bda-search-1.0.jar` |
| `pig/` | The cleaning filter | Pig Latin |
| `hive/` | Statistics, HBase split keys, the `docs_full` join | HiveQL |
| `spark/` | TF-IDF norms, PageRank, K-means, the MS MARCO evaluation | PySpark |
| `hbase/` | Creating the three tables | HBase shell, in bash |
| `eval/` | Metrics, latency and PageRank-example scripts, plus their results | Python |
| `charts/` | The chart script, its data (`charts/data/`) and the 11 PNGs | Python + matplotlib |
| `bin/` | Everything you type: start, stop, run, demo | bash |
| `setup/` | One-time installation scripts (WP1). Not needed again. | bash |
| `docs/` | Plan, preflight, build/setup logs, guides | Markdown |
| `report/` | The report | Markdown |

```
 Wikipedia dump (.bz2) ─► Job 0 (read XML) ─► Pig (clean) ─► Job 2 (lengths) ─► Job 1 (postings) ─► Job 3 (lexicon)
                                                                                     │
                       Hive (statistics, split keys, join) ◄──────────────────────────┤
                       Spark (TF-IDF norms, PageRank, K-means) ◄─────────────────────┤
                                                                                     ▼
                                                     HBase (postings │ docs │ terms) ─► QueryServer ─► search page
 MS MARCO passages ─► Jobs 2, 1, 3 (same code) ─► Spark norms ─► Spark evaluation ─► metrics.py ─► results.md
```

---

## 1. The shared core: `java/src/main/java/bda/common/`

**`Tokenizer.java`** turns text into index terms. It's the most important small file in the project.
- **The steps:**
  1. Remove accents (café → cafe).
  2. Lowercase.
  3. Turn everything except a–z and 0–9 into spaces, then split.
  4. Drop tokens shorter than 2 or longer than 40 characters.
  5. Drop Lucene's 33 stopwords.
  6. Porter-stem ("running" → `run`, "photosynthesis" → `photosynthesi`).
- **Why it matters:** the *same* code runs when building the index, when answering a search and when tokenising the MS MARCO questions, so a query word always matches the index word.
- **Version check:** its version string `v1-lucene33-porter` is saved with the index, and the search server refuses to start if the two don't match.
- **Tests:** `TokenizerTest` (11 tests), covering stemming, accents, hyphens, possessives, and exactly 33 stopwords.

**`Meta.java`** reads and writes the index's `meta.tsv`: number of documents N, total tokens, average length, tokeniser version, build time.

**`Scorer.java`** holds the ranking formulas: BM25 (k1 = 0.9, b = 0.4), TF-IDF, and the PageRank boost w·ln(pr·N).
- `ScorerTest` pins a hand-worked toy example.
- `spark/eval_msmarco.py` checks the same numbers before it runs. So the Java search server and the Spark evaluation provably score identically.

## 2. Reading Wikipedia, Job 0: `java/src/main/java/bda/ingest/`

**`XmlInputFormat.java`** tells Hadoop that one record is a whole `<page>…</page>` block, not one line. Reading line by line would cut articles apart.
- It marks the bz2 file as *splittable*: bzip2 can be cut into pieces, gzip can't. So 12 mappers read one compressed file in parallel.

**`XmlRecordReader.java`** does the actual splitting, and is the trickiest code in the project.
- **The rule:** a page belongs to the split in which its `<page>` tag *starts*. The reader keeps reading past the end of its split to finish that page, and the next split skips the partial page at its start.
- For bzip2 it works with compressed block positions.
- **Test:** `tinySplitsGiveEveryRecordExactlyOnce` cuts a file into tiny splits and checks that every page comes out exactly once.
- **The real proof (S1):** 562,214 pages with 12 splits and with 1 split.

**`WikiPageMapper.java`** handles one page at a time and writes two outputs:
- **`pages`:** docID, title, namespace, redirect, length, cleaned text.
- **`links`:** source page → target title. Links are taken from the *raw* wikitext, so links inside tables still count for PageRank.
- **Cleaning:**
  - The `wikiclean` library does the main cleaning.
  - `wikicleanInput` works around the 2026 dump format.
  - `dropFooter` cuts "Other websites / References" sections.
  - `preprocess` removes templates and tables.
- **Counters:** PAGES, REDIRECTS, CLEAN_FALLBACK and others.
- **Tests:** `WikiPageMapperTest` (8 tests), each pinning one of the problems found in the logs.

**`WikiText.java`** is the backup cleaner (regular expressions), used when wikiclean fails.

**`WikiPageJob.java`** is the job's driver (map-only). Adding `-D bda.xml.splittable=false` forces one mapper, for the split comparison.

## 3. Cleaning with Pig: `pig/filter_pages.pig`

Keeps real articles only: main namespace, not a redirect, at least 200 characters. It writes three outputs:
- `docs` (docID, text): fed to the index.
- `titles` (docID, title, 300-character snippet): used by HBase and PageRank.
- `redirects`: used by PageRank.

It also counts how many pages fell into each category.

## 4. Building the inverted index, Jobs 2, 1 and 3: `java/src/main/java/bda/index/`

**`DocStatsJob.java` (Job 2, map-only)** gives docID → length in tokens, and writes `meta.tsv` (N, total tokens, average length).

**`PostingsJob.java` (Job 1)** is the heart of the project:
- **Map:** emits ((term, docID), 1) for *every* word occurrence.
- **Combiner:** adds those up on the same machine before anything is sent. `-D bda.combiner=false` switches it off for the experiment (S3: shuffle −39.7%).
- **Partitioner:** `TermPartitioner` sends every (term, ·) key to the same reducer.
- **Shuffle sort:** by (term, docID), so each word's documents arrive *already in docID order*. This is the **secondary sort**.
- **Grouping:** `TermGroupingComparator` makes one `reduce()` call per term.
- **Reduce output:** `term \t df \t cf \t d1:tf1,d2:tf2,…`. The reducer never sorts anything itself.

**`TermDocKey.java`** is the composite key (term, docID). Its raw comparator sorts the serialised bytes directly without rebuilding objects, which matters for MS MARCO's 340 million map outputs.

**`TermPartitioner.java`** (15 lines) and **`TermGroupingComparator.java`** (37 lines) are the two small halves of the secondary sort. Both look at the term only.

**`LexiconJob.java` (Job 3)** turns postings into `term \t df \t cf`. It reads only the bytes up to the third tab, so huge posting lists are never loaded as text.

**Tests:** `PostingsReducerTest` checks that the combiner on or off, and one or several reducers, give identical postings. It also checks that the byte comparator agrees with the normal one.

## 5. Statistics with Hive: `hive/`

Run with `bin/run-hive.sh <file.sql> wiki|msmarco`. Every result lands in `charts/data/<name>.tsv`.

| File | What it does |
|---|---|
| `ddl.sql` | "External" tables over the HDFS outputs, so Hive queries the files where they are, with no copying |
| `stats.sql` | Vocabulary and hapax counts, length summary, top-50 terms, Zipf curve, length histogram, df magnitudes |
| `split_keys.sql` | A running total of df (window function) to pick 15 words that cut the index into 16 equal parts → `hive/split_keys.txt` → balanced HBase regions |
| `docs_full.sql` | Joins titles + lengths + TF-IDF norms + PageRank into one row per article for HBase's `docs` table, then checks the row counts |
| `doclen_fine.sql` | 5-token length bins for the MS MARCO chart (C2) |

## 6. Analytics with Spark: `spark/`

**`tfidf_norms.py`** computes each document's vector length, which TF-IDF's cosine score divides by.

**`pagerank.py`** builds the link graph:
- Links are resolved to articles, following redirects up to 3 hops.
- It runs 10 iterations with damping 0.85, spreading the rank of pages that have no links out ("dangling" pages) evenly.
- **The key trick:** `links`, `nodes` and `ranks` are partitioned the same way, and the ranks are checkpointed every iteration. That took it from 258 s per iteration down to ~10 s.

**`kmeans.py`** groups articles into 20 topics:
- It builds TF-IDF vectors over a 20,000-word vocabulary and runs K-means (k = 20, seed 42).
- Articles with fewer than 10 vocabulary words are skipped.
- Output: `eval/kmeans_k20.md`.

**`eval_msmarco.py`** is the evaluation: 1,000 questions × 4 models over all 8.8 million passages, with no shortcuts.
- It first runs a self-test against `ScorerTest`'s numbers.
- All (question, passage) pairs are shuffled by question **once**, then scored and ranked in a single pass.
- It writes TREC run files to `eval/runs/`.

**`hello.py`** is the setup check from WP1.

## 7. The serving tables: `hbase/create_tables.sh` and `java/src/main/java/bda/hbase/HBaseLoadJob.java`

| Table | Rowkey | Holds |
|---|---|---|
| `postings` | `term#docID` (docID padded to 10 digits) | tf: how often the word is in that doc |
| `docs` | `docID` | title, snippet, length, norm, PageRank |
| `terms` | `term` | df, cf |

- **Why `term#docID`:** one word's documents are one *contiguous, sorted* range, so a search is one fast prefix scan. The padding makes the text order equal the number order.
- `create_tables.sh` pre-splits `postings` at the 15 Hive split keys. All 16 regions came out at 14–15 MB.
- `HBaseLoadJob` is a map-only MapReduce job that writes through `TableOutputFormat`. It skips HBase's write-ahead log, because the data can always be reloaded from HDFS. It loads 18.3 million rows in 86 s.

## 8. The search engine: `java/src/main/java/bda/serve/` and `java/src/main/resources/static/index.html`

**`SearchService.java`** is the engine.
- **At start-up** it loads every word's df and every article's length, norm and PageRank into memory (6–16 s).
- **For each search:**
  1. Tokenise the query.
  2. Do one HBase prefix scan per query word.
  3. Add up the scores.
  4. Keep the top k.
  5. Fetch the titles and snippets in one batch.
- It times each stage; that's the status line on the page.
- It gives friendly messages for empty, stopword-only and unknown-word queries.

**`QueryServer.java`** is a small web server on port 8090:
- `/` serves the page.
- `/search?q=…&model=bm25|tfidf|boolean&pr=0|1` returns JSON.

**`index.html`** is the search page itself: plain HTML and JavaScript, with no framework.

**`QueryCli.java`** is the terminal version, the demo's fallback. `demo-live.sh search` uses the server's JSON instead.

## 9. Evaluation and charts

| File | What it does |
|---|---|
| `eval/metrics.py` | Reads the TREC runs and MS MARCO's answers; computes MRR@10, P@10 and R@100 with `pytrec_eval`, plus paired t-tests → `eval/results.md`, `eval/per_query_rr.csv` |
| `eval/latency.py` | 50 test queries (`eval/latency_queries.txt`) × 3 models against the running server → p50/p95 latency (S4) |
| `eval/pagerank_examples.py` | 20 candidate queries with and without the PageRank boost → `eval/pagerank_examples.md` |
| `charts/make_charts.py` | Draws charts C1–C11 from `charts/data/` and the eval outputs |

## 10. The scripts you type: `bin/`

| Script | Use |
|---|---|
| `bda-start.sh [core\|hbase\|all]` | Mount the data disk, then start HDFS, YARN, JobHistory (and HBase with `all`) |
| `bda-stop.sh [hbase\|all]` | Stop the search server, HBase, then everything else |
| `bda-status.sh [--watch]` | What's running, plus disk and memory |
| `bda-mount.sh` | Re-attach the D: data disk if Windows dropped it |
| `bda-run.sh <name> <command…>` | Run a long job detached, so it survives closing the terminal; log in `~/bda/logs/<name>.log` |
| `demo-start.sh` | One command for the demo: start everything + the search server, then open the browser tabs |
| `demo-live.sh mr\|search\|hbase\|hive\|pagerank\|clusters\|term\|status` | The live demo steps |
| `run-wiki-pipeline.sh` | WP4–WP7a end to end on Wikipedia |
| `run-job0.sh`, `run-pig.sh`, `run-index.sh`, `validate-index.sh`, `run-combiner-exp.sh` | The individual pipeline steps and checks |
| `run-hive.sh`, `bda-beeline.sh` | Run a Hive SQL file (beeline wrapped so it works without a real terminal) |
| `run-spark.sh` | Submit a Spark script to YARN |
| `run-msmarco-overnight.sh`, `prep-eval.sh` | The MS MARCO index and the evaluation prep |
| `run-hbase-load.sh`, `verify-hbase.sh` | Load and check the HBase tables |

`setup/` holds the one-time WP1 installation and configuration scripts (Hadoop, Hive, Tez, HBase, Spark, the data disk). You won't need them again.

## If you only have time to read five files

1. `Tokenizer.java`: how text becomes terms.
2. `XmlRecordReader.java` (the comment at the top): how one compressed file is split safely.
3. `PostingsJob.java` with `TermDocKey.java`: the inverted index, the combiner and the secondary sort.
4. `SearchService.java`: how a search is answered from HBase.
5. `spark/eval_msmarco.py`: how 1,000 questions were scored against 8.8 million passages.
