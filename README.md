# BDA Search Engine

A full-text search engine for Simple English Wikipedia, built end to end on the Hadoop ecosystem on a single laptop (WSL2): MapReduce, Pig, Hive on Tez, Spark on YARN and HBase. Its ranking quality is measured on the MS MARCO passage-ranking benchmark.

*Big Data Analytics project, NMIMS, Semester 11 (October 2026).*

## Results

| | |
|---|---|
| Corpus | 210,106 Simple English Wikipedia articles (562,214 pages read from the bz2 dump); 8,841,823 MS MARCO passages |
| Index | 570,619 terms / 18.3 M postings (Wikipedia); 1,370,456 terms / 257 M postings (MS MARCO) |
| Ranking quality (1,000 MS MARCO dev/small queries) | BM25 MRR@10 **0.198** > TF-IDF 0.127 > Boolean 0.058; published Lucene baseline 0.187 |
| Search latency (HBase-backed) | BM25 p50 71 ms, p95 173 ms |
| Demo | cold start to first answered query in 103 s |

## How it works

```
 Wikipedia .bz2 ─► Job 0 XmlInputFormat ─► Pig filter ─► Job 2 lengths ─► Job 1 postings (secondary sort + combiner) ─► Job 3 lexicon
                     Hive: statistics, Zipf, HBase split keys, docs join      Spark: TF-IDF norms, PageRank, K-means
                     HBase: postings (term#docID) │ docs │ terms  ─►  Java QueryServer :8090 (Boolean / TF-IDF / BM25 + PageRank)
 MS MARCO ─► the same Jobs 2, 1, 3 ─► Spark norms ─► exhaustive Spark evaluation ─► pytrec_eval
```

## Repository layout

`java/` (MapReduce, HBase loader, search server; Maven) · `pig/` · `hive/` · `spark/` · `hbase/` · `eval/` · `charts/` · `bin/` (run scripts) · `setup/` (one-time install) · `docs/` · `report/`

**Where to read next:**
- [docs/code-guide.md](docs/code-guide.md): what every important file does.
- [docs/project-explained.md](docs/project-explained.md): the whole project in plain English.
- [docs/build-log.md](docs/build-log.md): every run, with its numbers and the problems fixed.
- [report/report.md](report/report.md): the report.

## Running it

The stack is Temurin JDK 21, Hadoop 3.4.3, Hive 4.2.1 + Tez 0.10.5, HBase 2.6.6, Spark 4.1.2 and Pig 0.18.0, installed by the scripts in `setup/` (see [docs/setup-log.md](docs/setup-log.md)). With that in place:

```bash
cd java && mvn -q package            # builds bda-search-1.0.jar and runs the 25 unit tests
bin/bda-start.sh all                 # HDFS, YARN, JobHistory, HBase
bin/demo-start.sh                    # + the search server on http://localhost:8090
```

The daily routine is in [docs/startup-commands.md](docs/startup-commands.md), and the demo walkthrough in [docs/live-demo-guide.md](docs/live-demo-guide.md).

## Data (not included)

- **Simple English Wikipedia:** the `simplewiki-20261001-pages-articles.xml.bz2` dump from dumps.wikimedia.org (CC BY-SA). It's downloaded by `setup/wp2-download.sh`.
- **MS MARCO passage ranking:** the collection, queries and qrels are under a non-commercial research licence, so they're not in this repository. Only the sampled query IDs (`eval/sample_qids.txt`) and the derived per-query scores are committed.
