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
  - The jar is 7.3 MB since WP4: wikiclean's CLI-only dependencies (Stanford CoreNLP, Ant, args4j) are excluded.

## WP4 — Job 0: XmlInputFormat + page parsing (20 Oct 2026)
- Code: `bda.ingest.{XmlInputFormat, XmlRecordReader, WikiPageMapper, WikiText, WikiPageJob}`, run by `bin/run-job0.sh`.
- Unit tests (22 in the project now):
  - `XmlRecordReaderTest`: a 5-page file read with forced splits of 37/61/200/1000 bytes and whole-file gives exactly 5 records, no duplicates.
  - `WikiPageMapperTest`: entities, link normalisation, field extraction, wikiclean on the current dump format, footer cut, orphan table rows.
- **S1 ✅ in both split modes**, final run (`bin/run-wiki-pipeline.sh`, steps run one after another, nothing else on the cluster):

| Mode | Mappers | Wall time | PAGES (ground truth 562,214) |
|---|---|---|---|
| `split.maxsize = 32 MB` (bzip2 split by Hadoop) | 12 | **234 s** | 562,214 ✅ |
| `bda.xml.splittable=false` (one split) | 1 | **427 s** | 562,214 ✅ |

  - Splitting the bz2 dump gives a **1.8× speed-up** with identical output.
- Counters (final run):
  - PAGES 562,214 · NS0 400,692 · REDIRECTS 124,469 · LINKS 5,950,147.
  - EMPTY_TEXT 237,801: mostly redirects and other namespaces; only 114 ns-0 articles were empty in the first run.
  - **CLEAN_FALLBACK 143 = 0.03% of pages** ✅ (< 1%).
- Output: `/bda/wiki/job0/pages` 367 MB, `/bda/wiki/job0/links` 132 MB. A random sample of 20 articles reads as clean prose; links look like real titles.

## WP5 — Pig filter (20 Oct 2026)
- `pig/filter_pages.pig` via `bin/run-pig.sh 200`: **43 s**.
- `by_category`: article **210,106** · stub (< 200 chars) 75,372 · redirect 115,214 · other_ns 161,522. **Sum = 562,214 = PAGES ✅**.
- `docs` = `titles` = 210,106 ✅; `redirects` = 115,214.
- **MINLEN = 200 kept:** the length histogram (charts/data via `summary/length_hist`) shows a smooth long tail with no artefact at 200. 89,614 articles are 200–499 chars; 23k+ are > 1,000 chars.

## WP6 — Index build, Jobs 1–3 (20 Oct 2026, simplewiki)
- Code: `bda.index.{TermDocKey (+ raw comparator), TermPartitioner, TermGroupingComparator (+ raw), PostingsJob, DocStatsJob, LexiconJob}`, `bda.common.Meta`.
  - `PostingsReducerTest` runs Job 1 in Hadoop's local runner with combiner on/off and 1/3 reducers, and checks the raw comparators against compareTo.
- `bin/run-index.sh wiki`:
  - **Job 2** 38 s → N = **210,106**, totalTokens **30,510,204**, avgdl **145.21**.
  - **Job 1** 107 s (3 maps, 4 reduces).
  - **Job 3** 26 s → **570,619 terms**.
- **S2 ✅** (`bin/validate-index.sh`):
  - Σcf = 30,510,204 = totalTokens.
  - Lexicon rows = postings lines = 570,619.
  - A random sample of 4,963 posting lists: docIDs strictly increasing; df = number of entries; cf = Σtf; **0 bad**.
  - Σdf = 18,317,155 (term, doc) pairs.
  - Reducer input was 18,317,462: 307 more than the pairs, because the combiner can't merge a (term, doc) pair split across two spill files. The reducer sums them, as designed.
- **S3 ✅ combiner experiment** (`bin/run-combiner-exp.sh`, same input, 4 reducers; postings **identical** either way):

| Counter | Combiner ON | Combiner OFF | Change |
|---|---|---|---|
| Map output records | 30,510,204 | 30,510,204 | — |
| Map output bytes | 553,930,304 | 553,930,304 | — |
| Combine input → output records | 30,510,204 → 18,317,462 | 0 | — |
| Reduce shuffle bytes | **370,956,597** | **614,950,784** | **−39.7%** |
| Reduce input records | 18,317,462 | 30,510,204 | −40.0% |
| Spilled records | 54,952,386 | 91,530,612 | −40.0% |
| CPU time (ms) | 288,030 | 299,170 | −3.7% |
| Wall time (s) | 107 | 118 | −9% |

## WP7a — Hive statistics + split keys (20 Oct 2026, simplewiki)
- `bin/run-hive.sh hive/ddl.sql | hive/stats.sql wiki | hive/split_keys.sql`. All 8 queries ran as Tez DAGs (0.8–14.5 s each; about 15 s of embedded HiveServer2 start-up per session).
- **Headline:**
  - Vocabulary **570,619**; hapax (cf = 1) **297,382 = 52.12%**.
  - Tokens 30,510,204; postings 18,317,155.
  - Average doc length **145.21** tokens (min 2, max 30,043).
  - Integrity: 210,106 docs = 210,106 distinct docIDs ✅.
- **Top terms by cf:** he, from, hi(s), also, were, ha(s), she, first, american, us, on(e), peopl(e), which, have, state, born, year, new, other, after.
- **df magnitudes:** 10⁰: 511,180 · 10¹: 46,554 · 10²: 10,022 · 10³: 2,564 · 10⁴: 298 · 10⁵: 1.
- **Exports** (`charts/data/`): `wiki_headline`, `wiki_docstats_summary`, `wiki_top50_cf`, `wiki_top50_df`, `wiki_zipf` (6,696 rows), `wiki_doclen_hist`, `wiki_df_magnitude`.
- **15 HBase split keys** (`hive/split_keys.txt`): 232, anger, border, come, dobzhanski, first, hamam, itali, london, multipl, parliament, rebelli, she, system, utah.

## WP8 — Spark: TF-IDF norms, PageRank, K-means (20 Oct 2026, simplewiki)
- Run with `bin/run-spark.sh`, Profile B (2 executors × 2 GB × 2 cores, driver 1.5 GB).
- **TF-IDF norms** (`spark/tfidf_norms.py wiki`): 210,106 documents with a norm (= N), **0 zero norms**; 35 s of Spark work (79 s with start-up).
- **PageRank** (`spark/pagerank.py`):
  - Graph: **210,106 nodes**; 5,950,147 raw links resolve (with up to 3 redirect hops) to **3,267,154 distinct edges**; 207,943 nodes have out-links.
  - 10 iterations, d = 0.85, dangling mass spread evenly (dangling mass 0.0103 in iteration 1, then 0.0027).
  - **Σpr = 1.000000000 ✅**.
  - L1 change by iteration: 1.17, 0.419, 0.151, 0.0700, 0.0398, 0.0247, 0.0159, 0.0105, 0.00703, **0.00480** (`eval/pagerank_log.csv`).
  - **Time per iteration ~10 s, flat** (9.8–11.0 s); 143 s in total.
  - **Top 10 (S6 ✅ hub pages):** United States, United Kingdom, France, Germany, Europe, England, India, Japan, English language, Canada (`eval/pagerank_top50.tsv`).
- **K-means** (`spark/kmeans.py 20 20000 20 10`):
  - Vocabulary 20,000 terms (5 ≤ df ≤ 0.1·N, top by df); (1 + ln tf)·idf, L2-normalised; k = 20, maxIter 20, seed 42.
  - **209,083 articles clustered**; 1,023 with fewer than 10 vocabulary terms excluded.
  - Fit 43 s (115 s in total). SSE 200,834.6; silhouette (20k sample) **0.0132**, which is typical for sparse text.
  - **S6 ✅ readable clusters:** chemistry, TV series, actors, basketball, football, albums/songs, films, frogs, French communes, Swiss municipalities, video games, US counties, medicine, war/empires, politicians, volleyball, geography. Also a general-vocabulary cluster (31,735), a date-heavy biography/events cluster (49,270), and one 2-article outlier cluster. Details in `eval/kmeans_k20.md`.

## WP7b — Hive join `docs_full` (20 Oct 2026)
- `bin/run-hive.sh hive/docs_full.sql wiki`: titles ⋈ docstats ⟕ norms ⟕ pagerank, as a Tez DAG in 16.3 s.
- **`docs_full` rows 210,106 = `titles` rows ✅**. All 210,106 have a norm and a PageRank > 0; Σpr = 1.0.

## WP11 step 1 — MS MARCO index + TF-IDF norms (21 Oct 2026, 23:23–23:44 IST, unattended)
- Run with `bin/bda-run.sh msmarco-overnight bash bin/run-msmarco-overnight.sh`; log `~/bda/logs/msmarco-overnight.log`, ending `OVERNIGHT_DONE`. **Wall time about 21 min** in total. Profile B; HBase off.
- **Job 2** 200 s, 24 maps:
  - N = **8,841,823** passages (= collection.tsv lines ✅); **340,895,003 tokens**; avgdl **38.55**.
  - 20 passages are empty after tokenising (EMPTY_DOCS = 20).
- **Job 1** 692 s, 23 maps, 6 reducers (7 reduce attempts launched):
  - Map output **340,895,003 records / 6.15 GB**; shuffle **5.15 GB**; reduce input **257,026,270**.
  - Combine counters 588,608,631 in → 504,739,898 out, counted across spill/merge passes.
  - Spilled 771,078,964 records; CPU 2,349.7 s; GC 10.7 s.
  - The combiner helps far less here than on Wikipedia (map output materialised 5.15 GB vs 6.15 GB raw). MS MARCO passages are short (38.6 tokens), so a word rarely repeats inside one passage.
- **Job 3** 58 s → **1,370,456 terms**.
- **S2 ✅:**
  - Σcf = 340,895,003 = totalTokens.
  - Lexicon rows = postings lines = 1,370,456.
  - A random sample of 5,069 posting lists has 0 bad.
  - Σdf = **257,026,270** (term, doc) pairs, exactly the reduce input, so the combiner merged every repeat this time.
- **TF-IDF norms** (Spark, `spark.sql.shuffle.partitions=32`): 223 s; **8,841,803** passages with a norm (= N − the 20 empty ones); 0 zero norms.
- Data disk afterwards: `/data` 264 GB free of 295 GB.

## WP9 — HBase serving tables + loader (22 Oct 2026, ~00:37–00:44 IST)
- Code: `bda.hbase.HBaseLoadJob`, map-only through `TableOutputFormat`. Every Put uses `Durability.SKIP_WAL`, since the data can always be reloaded from HDFS. Speculative maps are off.
- Scripts: `hbase/create_tables.sh`, `bin/run-hbase-load.sh`, `bin/verify-hbase.sh`.
- **Tables:**
  - `postings`: rowkey `term#%010d`, family `p`, 1 version, row Bloom filter, FAST_DIFF block encoding. **Pre-split into 16 regions at the 15 Hive split keys** (232, anger, border, come, dobzhanski, first, hamam, itali, london, multipl, parliament, rebelli, she, system, utah).
  - `docs`: rowkey `%010d`, family `d`.
  - `terms`: rowkey term, family `t`, row Bloom filter.

| Table | Source | Rows | Expected | Load time | Rows/s |
|---|---|---|---|---|---|
| terms | `/bda/wiki/index/lexicon` | 570,619 | 570,619 ✅ | 50 s (4 maps) | 11,435 |
| docs | `/bda/wiki/hive/docs_full` | 210,106 | 210,106 ✅ | 27 s (3 maps) | 7,879 |
| postings | `/bda/wiki/index/postings` | **18,317,155** | Σdf = 18,317,155 ✅ | **86 s** (4 maps) | **212,814** |

  - Row counts come from `hbase-mapreduce rowcounter` on YARN; 0 bad lines in every load.
  - The small tables' rates are dominated by job start-up. 86 s for postings is far below the 30-minute threshold for the HFile bulk-load fallback.
- **Prefix scan** `scan 'postings', {ROWPREFIXFILTER => 'photosynthesi#', LIMIT => 5}` returns docIDs 0000000101, 0000000108, 0000000218, 0000000219, 0000000425: **ascending ✅**.
- **`get 'docs', '0000000101'`:** Biology, with title, snippet, len, norm and pr all present ✅.
- **Region balance ✅:** all 16 `postings` regions hold **14–15 MB** after flush (FAST_DIFF), with 2.25–2.33 M write requests each. The Hive cumulative-df split keys spread the load evenly.
- Flush and major compaction were requested after loading. HBase was stopped afterwards; it runs only for WP10 and the demo.

---

## Issues & fixes
See `docs/setup-log.md` § "Issues & fixes" for WP1.

- **WP9 — JDK 21 module warning in the HBase client.** Each load job's client logged `InaccessibleObjectException: Unable to make static boolean java.nio.Bits.unaligned() accessible: module java.base does not "opens java.nio"`. The loads all succeeded: HBase falls back to its non-Unsafe code path. For the QueryServer (WP10), add `--add-opens java.base/java.nio=ALL-UNNAMED` (the plan's documented fix for module errors).

- **WP11 — overnight launcher.** The first `bda-run.sh` started the job with `setsid … &` and exited immediately. WSL ended the calling session before `setsid` took effect, so the job died before even creating its log (21 Oct, both of the user's attempts). The fix: the launcher now waits until the child's session ID differs from its own, then confirms it's alive. A clean run followed.

- **WP8 — PageRank slowed down every iteration** (first run: 27, 45, 62, 85, 139, 137, 175, 217, 258, 238 s; 24 min in total). Spark kept re-deriving earlier ranks, and every join reshuffled the graph.
  - PySpark's `localCheckpoint()` returns `None`; the first version assigned its result and crashed in iteration 3.
  - Fix: hash-partition `links`, `nodes` and `ranks` the same way (P = 8), so joins don't shuffle the 3.3M-edge graph, and `localCheckpoint()` every iteration.
  - Result: **~10 s per iteration, flat; 143 s in total (10× faster)**, with identical L1 values and ranking.
- **WP8 — K-means outlier clusters.** With no minimum, two near-empty articles each took a whole cluster ("NCAA Award of Valor", "Mount Price"; silhouette 0.0093). Articles with fewer than 10 vocabulary terms are now not clustered (1,023 of 210,106), so 19 of the 20 clusters are real topics.
- **Known limitation:** EasyTimeline chart markup (`<timeline>` blocks: "BarData", "fontsize", "till") isn't removed by wikiclean. It shows up in a few hundred French arrondissement/commune articles. Left as is.

- **WP4 — wikiclean found no text in the current dump format.** wikiclean 1.2 expects `<text xml:space="preserve">`, but the 2026 dumps write `<text bytes="…" sha1="…" xml:space="preserve">`. On the first run, **285,478 of 562,214 pages fell back** to the regex cleaner.
  - Fix: give wikiclean a minimal page in the old format (`WikiPageMapper.wikicleanInput`). That brought fallbacks down to 360. A unit test pins both the root cause and the fix.
- **WP4 — Simple Wikipedia footers.** "Other websites" / "Related pages" / "References" sections aren't recognised by wikiclean, so "Other websites *" stayed in the text. Fix: cut the wikitext at the first footer heading (`dropFooter`). Afterwards 25 of 285k articles still contained the phrase.
- **WP4 — table markup leaking into the vocabulary.** About 190 "List of minor planets" pages (~120k chars each) open their table inside one template and close it in another. wikiclean never saw a whole table and left ~1,000 bare rows per page (`|-bgcolor=#E9E9E9 | 1 Ceres || … || align=right | 939 km`). That put **class, right, km, bgcolor, align** in the corpus top-10 by cf; bgcolor alone had cf 186k in only 219 docs.
  - Fix: `preprocess()` removes templates and whole tables with a depth counter, then drops any leftover table-row lines (starting with `|` or `!`).
  - Result: vocabulary **743,106 → 570,619**, and the top terms are real words. Links are still taken from the *raw* wikitext (plan WP4), so table and footer links count for PageRank.
- **WP4 — no-op job runs.** I stopped the single-split comparison twice so that both modes would run the same final code. The final numbers above come from one clean sequential run.
- **WP7a — Hive 4.2.1 beeline:**
  - Inside the pseudo-terminal, `-f script.sql` only *echoes* the script without running it. `bin/run-hive.sh` therefore passes the statements with `-e`.
  - `--hivevar` substitution didn't reach the `-e` text (`cannot recognize input near '$' '{' 'C'`), so the runner fills in `${OUTDIR}` and `${C}` itself.
  - Beeline's terminal control codes (screen switching) hid later console output; `bda-beeline.sh` now strips them.
