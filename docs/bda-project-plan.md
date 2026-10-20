# BDA Search Engine — Full Project Plan

**Version:** 1.1 · 17 Oct 2026
**Owner:** Mit Shah (plus team)
**Readers:** Mit, teammates, and Claude Code (which runs the setup and build on Mit's laptop)
**Read with:** `bda-preflight-check.md` and `bda-preflight-report.md`.

**Changes in v1.1** (from the preflight report and Mit's answers):
- Deadline: **Fri 23 Oct**. Wed–Thu are now buffer days (§12).
- Memory: two profiles, chosen on setup day (§5.1). **Profile B (WSL 10 GB / YARN 6 GB) is the default**; Profile A (12 / 8) only if Windows is lean enough.
- Background services start with `setsid`, and long jobs are launched detached. Daemons started from `wsl.exe` otherwise die from SIGHUP (§5.10).
- HBase: longer ZooKeeper timeouts, and scripts wait for the master to be ready (§5.6).
- MS MARCO: the old `msmarco.blob.core.windows.net` URL now refuses access (HTTP 409). Use `collectionandqueries.tar.gz` from the `z22` endpoint, with a University of Waterloo mirror as fallback. **No `ir_datasets` dependency** (§6).
- JDK 21 passed the Hadoop, Spark and HBase smoke tests with **no extra flags**. Hive stays on 4.2.1. HBase stays on 2.6.6 (smoke-tested; from `archive.apache.org`).
- Linux steps always run as `.sh` files, never as inline bash through PowerShell (§0).
- New risks R15–R20 (§13).

---

## Contents

0. How to use this plan (Claude Code: read first)
1. Summary
2. Open items — Mit to fill in
3. Goals, scope and success criteria
4. Architecture
5. Environment and configuration
6. Data
7. Shared conventions (tokeniser, IDs, formats, HDFS layout, repo)
8. Work packages WP1–WP12 (the build)
9. Demo script
10. Report outline and charts
11. Viva preparation
12. Schedule (4 days, ~24 hours)
13. Risk register
14. Team and roles
15. Definition of done

---

## 0. How to use this plan

**For Claude Code:**

1. Work through the work packages **in order**. Each one lists inputs, outputs, implementation notes, commands, **acceptance checks** and a **fallback**. A work package is done only when all of its acceptance checks pass.
2. Before starting a work package, re-read its acceptance checks. When you finish it, append a short entry to `docs/build-log.md`: what ran, how long it took, counters and numbers, and any fixes applied.
3. **Time-box:** any single blocker gets 30 minutes (JDK problems get 20). Then apply the documented fallback, log it under "Issues & fixes", and move on.
4. **Ask Mit before:** any `mkfs`, deleting anything under `/data` or in HDFS outside `/bda/tmp`, changing Windows settings, rebooting, or switching to a fallback that changes what the report claims.
5. Never type passwords. Admin and root steps follow the decisions in §2.
6. Record numbers **as they happen**. The report depends on them, and re-running jobs to recover them costs hours.
7. **The WSL distro is `Ubuntu`** (24.04.4 LTS), user `mit`, systemd on. Use `-d Ubuntu`; don't create an `Ubuntu-24.04` distro.
8. **Every Linux step lives in a `.sh` file** with LF line endings, run as `wsl -d Ubuntu -- bash -l ~/path/script.sh`. Never pass inline `bash -lc '…'` through PowerShell 5.1: `wsl.exe` re-parses quotes, pipes, `\` and `$`. That silently corrupted a `sed` command during the preflight.
9. **Anything that must keep running after the command returns** (daemons, the query server, overnight jobs) is started with `setsid` (§5.10). Anything started without it dies when the `wsl.exe` call exits.

**For Mit:** fill in §2. Everything else has a default.

---

## 1. Summary

We're building a **distributed full-text search engine** over **Simple English Wikipedia** (about 250k articles). It runs on a single-node Hadoop stack inside WSL2 on Mit's laptop, and we **measure** its ranking quality on **MS MARCO** (8.84M passages, with human relevance labels).

How the pieces fit:

- **MapReduce (Java)** builds the inverted index. It shows off the parts of Hadoop the syllabus cares about: a custom InputFormat, secondary sort, a combiner and a custom partitioner.
- **Pig** cleans the corpus. **Hive** computes corpus statistics. **HBase** serves the index to the search page.
- **Spark** runs PageRank, K-means topic clustering and the large MS MARCO evaluation.
- A small **Java web server** serves the search page with three ranking models (Boolean, TF-IDF, BM25) and an optional PageRank boost.

The deliverables are a working demo, an evaluation table (MRR@10 per model), the charts, and a report that ties each component to a syllabus unit.

---

## 2. Open items — Mit to fill in

| # | Item | Default if not filled | Mit's answer |
|---|---|---|---|
| 1 | Deadline (date and time) | — | **Fri 23 Oct 2026** (time of day: `<Mit>`) |
| 2 | Deliverables: report, demo, viva, code, slides? Page limit? Format? | Report (PDF) + live demo + code | |
| 3 | Official brief or rubric | None. If one exists, it overrides this plan wherever they disagree. | |
| 4 | Team members and roles | Mit runs the stack; teammates as in §14 | |
| 5 | Windows admin steps on setup day (create the VHDX, startup task) | Claude Code runs as administrator **for that session only** | |
| 6 | Linux root steps (installs, formatting the data disk, PostgreSQL) | `wsl -u root` allowed on setup day; Claude Code asks before each command; any `mkfs` needs Mit's explicit yes after he sees `lsblk` | |
| 7 | Email in the Wikimedia User-Agent for the real download | **No default: Claude Code asks Mit and waits.** Recommended: yes. Wikimedia's policy asks for contact details, and the email only goes in the request header. If Mit says no, leave the contact part out. | |
| 8 | Hive metastore database | PostgreSQL inside Ubuntu | |
| 9 | HBase version | 2.6.6 (`-hadoop3` build), already smoke-tested. It came from `archive.apache.org` because 2.6.7 has replaced it on the CDN; don't switch mid-project. | |
| 10 | Memory profile (§5.1) | Profile B (WSL 10 GB) unless the setup-day measurement allows Profile A | |

---

## 3. Goals, scope and success criteria

### 3.1 Objective

Show a complete big-data pipeline (ingest → distributed processing → storage → analytics → serving → evaluation) on a real corpus. Every syllabus unit gets a genuine job rather than a bolted-on one.

### 3.2 Syllabus coverage

| Unit | What demonstrates it | Where in this plan |
|---|---|---|
| 2 — HDFS, data ingest | bz2 dump stored in HDFS and split by Hadoop without decompressing; HDFS layout; replication choice | WP2, WP4, §7.4 |
| 3 — MapReduce internals | Custom `XmlInputFormat` + RecordReader; secondary sort (composite key, partitioner, grouping comparator); combiner with measured shuffle reduction; counters; MultipleOutputs; map-only jobs | WP4, WP6 |
| 4 — Pig, Hive, HBase | Pig cleaning and summary; Hive external tables, statistics, joins (CTAS); HBase rowkey design, pre-splitting, prefix scans, MapReduce→HBase loading | WP5, WP7, WP9 |
| 5 — Visualisation | Zipf plot, document-length histogram, combiner shuffle chart, MRR comparison, PageRank convergence, cluster summaries, latency distribution, and the search page itself | WP12, §10 |
| 6 — Analytics and IR | Text retrieval; three retrieval models; search engine implementation and evaluation; link analysis (PageRank); clustering (K-means) | WP8, WP10, WP11 |
| 7 — Applications | No listed category fits text retrieval. Say so in one line. | §10 |

### 3.3 Scope

**In scope (must have):**
- Environment setup.
- Simplewiki ingest; Job 0 with the custom InputFormat; Pig filtering; tokeniser with tests.
- Index Jobs 1–3 on both corpora.
- Hive statistics.
- Spark TF-IDF norms, PageRank and K-means.
- HBase tables and loader; Java query server and web page.
- MS MARCO evaluation on a **fixed 1,000-query sample** of dev/small.
- Charts, report and demo.

**Stretch (only after the MS MARCO numbers exist), in this order:**
1. Evaluate all 6,980 dev/small queries (run overnight).
2. PageRank in MapReduce, plus the per-iteration timing chart against Spark.
3. In-mapper combining as a third variant in the combiner experiment.
4. A Hive BM25 UDF.
5. HBase bulk load from HFiles.
6. Article category classification.

**Out of scope:**
- Full English Wikipedia (the report notes the pipeline doesn't depend on corpus size).
- Multi-node cluster.
- ORCAS and collaborative filtering.
- Storage format and compression benchmarks.
- A Spark version of the index.
- Sqoop.
- Sharing the Hive metastore with Spark.
- Phrase queries, wildcards, spelling correction.

### 3.4 Success criteria (measurable)

| # | Criterion | Target |
|---|---|---|
| S1 | Job 0 page count | Exactly equals `bzcat <dump> \| grep -c '<page>'`, with both 1 split and several splits |
| S2 | Index integrity | Posting lists strictly increasing by docID; Σdf = total (term, doc) pairs; Σcf = token total from Job 2 |
| S3 | Combiner effect | Shuffle bytes and record counts reported with the combiner on and off |
| S4 | Query latency (simplewiki) | p50 < 300 ms and p95 < 1 s over a 50-query test set (excluding the first, cold query) |
| S5 | Ranking quality (MS MARCO, 1,000 queries) | BM25 MRR@10 in **0.15–0.20**, and BM25 > TF-IDF > Boolean |
| S6 | Analytics | PageRank top 20 dominated by hub pages; K-means clusters have readable top terms |
| S7 | Demo | Cold start to first query in under 5 minutes, using one script |
| S8 | Report | All sections in §10 written; every number traceable to `docs/build-log.md` or `eval/results.md` |

---

## 4. Architecture

### 4.1 Data flow

```
   ┌──────────────────────────────┐
   │ simplewiki-<DATE>-pages-      │
   │ articles.xml.bz2  (~300 MB)   │
   └──────────────┬───────────────┘
                  │ hdfs dfs -put (stays bz2; Hadoop splits it)
                  ▼
   ┌──────────────────────────────────────────────┐
   │ JOB 0  XmlInputFormat + WikiPageMapper        │  map-only, MultipleOutputs
   │  → pages  (docID,title,ns,isRedirect,         │
   │            redirectTarget,len,text)           │
   │  → links  (srcDocID, targetTitle)             │
   └──────────────┬───────────────────────┬───────┘
                  ▼                       │
   ┌──────────────────────────────┐       │
   │ PIG  filter_pages.pig         │       │
   │  → docs (docID \t text)       │       │
   │  → titles (docID,title,snip)  │       │
   │  → redirects (title,target)   │       │
   │  → summary                    │       │
   └──────────────┬───────────────┘       │
                  │                       │
  MS MARCO        │ (both corpora share   │
  collection.tsv ─┤  the docID \t text    │
  (docID \t text) │  format from here on) │
                  ▼                       │
   ┌──────────────────────────────────────────────┐
   │ JOB 1 postings (secondary sort + combiner)    │
   │ JOB 2 doc stats (len, N, avgdl → meta)        │
   │ JOB 3 lexicon (term, df, cf)                  │
   └──────┬───────────────┬───────────────┬───────┘
          ▼               ▼               ▼
   ┌────────────┐  ┌─────────────────┐  ┌──────────────────────────────┐
   │ HIVE       │  │ SPARK           │  │ SPARK (MS MARCO only)         │
   │ stats,     │  │ norms, PageRank │◄─┤ eval_msmarco.py → run files   │
   │ Zipf,      │  │ (uses links,    │  │ → pytrec_eval → results.md    │
   │ split keys,│  │ titles,         │  └──────────────────────────────┘
   │ docs_full  │  │ redirects),     │
   │ (join)     │  │ K-means         │
   └─────┬──────┘  └────────┬────────┘
         ▼                  ▼
   ┌──────────────────────────────────────────────┐
   │ HBASE  postings │ docs │ terms   (simplewiki) │◄── HBaseLoadJob (MapReduce)
   └──────────────────────┬───────────────────────┘
                          ▼
   ┌──────────────────────────────────────────────┐
   │ Java QueryServer (JDK HttpServer)             │
   │  same Tokenizer + Scorer → JSON /search       │
   │  index.html: box, model, PageRank, results    │
   └──────────────────────────────────────────────┘
```

### 4.2 Key design choices (each is a report point and a likely viva question)

| Choice | Why |
|---|---|
| Keep the dump as bz2 in HDFS | bzip2 is splittable (gzip isn't), so many mappers can read one file without a decompression step |
| Custom `XmlInputFormat` | Wikipedia records are multi-line `<page>` elements; line-based input would cut articles apart |
| Secondary sort in Job 1 | The shuffle delivers each term's postings already sorted by docID, so the reducer never sorts a list itself. A plain `term → [docIDs]` reducer would have to sort the whole list in memory. |
| Mapper emits one record per token, plus a combiner | Makes the combiner's value measurable. Summing is associative and commutative, so the combiner is safe. |
| HBase rowkey `term#docID` (one row per posting) | A term's postings are one contiguous prefix scan. A row-per-term design would create rows with hundreds of thousands of columns for common terms. |
| Pre-split the `postings` table using Hive statistics | Term frequencies are Zipf-distributed; split points come from the cumulative document frequency, so regions hold similar amounts of data |
| Java query server, not Flask | Reuses the exact indexing tokeniser and scorer; avoids a Python↔HBase bridge |
| MS MARCO scored in a Spark batch job | Scoring 1,000 queries at once as joins is far cheaper than 1,000 × several HBase prefix scans over 8.8M documents |
| Tokeniser = Lucene's 33 English stopwords + Porter stemmer | Matches the widely used BM25 baseline configuration, so our MRR@10 can be compared with published numbers (~0.18) |
| MRR@10 as the main metric | MS MARCO dev/small has about 1 relevant passage per query, which makes rank-of-first-hit the meaningful measure |

---

## 5. Environment and configuration

### 5.1 Machine (from the preflight report)

| Item | Value |
|---|---|
| Windows | 11 Home Single Language, build 26200, x64. **Home edition:** no Hyper-V cmdlets, so use `diskpart` for the VHDX. |
| CPU | Intel i5-1135G7, 4 cores / 8 threads → `processors=6` for WSL |
| RAM | 20 GB installed, 19.7 GB visible. Windows uses **~11.6 GB in a normal session**. |
| SSD | C: (Kingston SATA, 446 GB), **78 GB free** |
| HDD | D: (Toshiba 1 TB), 826 GB free |
| WSL | Store WSL 2.6.3; distro **`Ubuntu` 24.04.4 LTS**, user `mit`, systemd on; `wsl --mount --vhd` works |
| Smoke test | Hadoop 3.4.3 MR on YARN, Spark 4.1.2 (local and YARN) and HBase 2.6.6 all **passed on Temurin 21.0.12 with no extra flags**. Installs kept in `~/smoke` (4.6 GB). |
| Network | Home Wi-Fi, no proxy; 2.8–3.6 MB/s inside WSL with qBittorrent off |

**Memory profiles.** On setup day, close everything that isn't needed, then measure Windows' RAM use with only the project setup open. The Claude app (~2.7 GB) has to stay open while it drives the setup, so it counts. Pick the profile from the measurement:

| Windows usage (measured) | Profile | `.wslconfig` memory | YARN `memory-mb` |
|---|---|---|---|
| ≤ 6.7 GB | **A** | 12 GB | 8192 |
| 6.8–8.5 GB | **B (default)** | 10 GB | 6144 |
| > 8.5 GB | — | Close more apps and measure again. 7 GB is too small for Hive/Tez plus Spark. | — |

**The lean-Windows checklist**, for setup day and every working session:
- Quit ChatGPT, Copilot, WhatsApp (and its WebView2), qBittorrent and Docker Desktop.
- Stop the Windows `MySQL80` and `postgresql-x64-18` services.
- Close Chrome tabs that aren't needed.
- Laptop plugged in, sleep off, lid kept open (or lid-close action set to "do nothing" on AC power, which Mit sets himself).

**The memory profile is fixed for the session.** Changing `.wslconfig` takes effect only after `wsl --shutdown`, which stops every service.

### 5.2 Software

| Component | Version | Install dir (inside WSL) | Notes |
|---|---|---|---|
| JDK | Temurin 21.0.12 (fallback: 17) | `~/bda/opt/jdk` (symlink) | Promote `~/smoke/jdk-21`; it passed the smoke test with no flags |
| Hadoop | 3.4.3 | `~/bda/opt/hadoop` | Promote from `~/smoke` |
| Tez | 0.10.5 | `~/bda/opt/tez` | Tarball also uploaded to HDFS `/apps/tez/` |
| Hive | 4.2.1 (4.1.0 if on JDK 17) | `~/bda/opt/hive` | Metastore on PostgreSQL |
| PostgreSQL | Ubuntu 24.04 package (16.x) | system (apt, root) | Database `metastore`, user `hive` |
| HBase | 2.6.6 `-hadoop3` | `~/bda/opt/hbase` | Promote from `~/smoke`. Pseudo-distributed on HDFS. Maven `hbase-*` dependencies must also be 2.6.6. |
| Spark | 4.1.2 (`-bin-hadoop3`) | `~/bda/opt/spark` | Runs on YARN |
| Pig | 0.18.0 | `~/bda/opt/pig` | Fallback: run the Pig client on JDK 8 |
| Maven | Ubuntu package | system | Compile with `--release 17`, so jars run on both 17 and 21 |
| Python | 3.12 (Ubuntu) + venv at `~/bda/venv` | — | Needs `python3-venv` (root) |

Install directories are versionless symlinks (`~/bda/opt/hadoop -> hadoop-3.4.3`), so paths and scripts survive version changes. Environment variables go in `~/bda/env.sh`, which `~/.bashrc` sources:

```bash
export BDA=~/bda
export JAVA_HOME=$BDA/opt/jdk
export HADOOP_HOME=$BDA/opt/hadoop  HADOOP_CONF_DIR=$BDA/opt/hadoop/etc/hadoop
export HIVE_HOME=$BDA/opt/hive  TEZ_HOME=$BDA/opt/tez  HBASE_HOME=$BDA/opt/hbase
export SPARK_HOME=$BDA/opt/spark  PIG_HOME=$BDA/opt/pig
export PYSPARK_PYTHON=$BDA/venv/bin/python
export PATH=$JAVA_HOME/bin:$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$HIVE_HOME/bin:$HBASE_HOME/bin:$SPARK_HOME/bin:$PIG_HOME/bin:$PATH
```

### 5.3 Disk layout

| What | Where | Disk |
|---|---|---|
| WSL distro, binaries, repo, venv | `~` (`/home/mit`) | SSD |
| NameNode metadata | `~/bda/hdfs/nn` | SSD |
| YARN local dirs + Spark scratch (shuffle spill) | `~/bda/yarn/local`, `~/bda/spark-tmp` | SSD (≈30 GB budget) |
| DataNode blocks | `/data/hdfs/dn` | HDD (VHDX) |
| Raw downloads | `/data/raw` | HDD (VHDX) |
| Hadoop tmp, logs | `~/bda/tmp`, `~/bda/logs` | SSD |

**The data disk:**
- A dynamically expanding VHDX at `D:\BDA\hdfs.vhdx`, maximum 300 GB (it only grows as data is written). Create it with `diskpart` (Home edition).
- Formatted ext4 with no partition table, attached with:
  `wsl --mount --vhd D:\BDA\hdfs.vhdx --type ext4 --name bdadata`
  This mounts it at `/mnt/wsl/bdadata`. `/data` is a symlink to that path.

**Re-attaching after reboots:** `wsl --mount` doesn't survive a reboot or `wsl --shutdown`.
- Create a Task Scheduler task `bda-mount-vhdx` (runs at logon, highest privileges) that runs the mount command.
- This is Windows 11, so also add a `[boot] command` to `/etc/wsl.conf` that triggers the task with `/mnt/c/Windows/System32/schtasks.exe /run /tn bda-mount-vhdx`. Keep the existing `systemd=true` and default-user lines in that file.
- If the mount is still missing, run the task by hand before starting services.

**SSD watch:** WSL's own disk lives on C: (78 GB free). It grows but never shrinks on its own. `bda-status.sh` prints free space on `/`; below 15 GB, clear `/bda/tmp`, `~/bda/spark-tmp` and the `~/smoke` tarballs. After the project, compact the distro's `ext4.vhdx` with `diskpart` (`compact vdisk`) to give the space back to Windows.

**Guard:** `bda-start.sh` refuses to start the DataNode unless `mountpoint -q /mnt/wsl/bdadata` succeeds. This stops HDFS from failing, or writing to the wrong disk, when the VHDX isn't attached.

### 5.4 `.wslconfig` (Windows user folder)

```ini
[wsl2]
memory=10GB        # Profile B (default). Profile A: 12GB. See §5.1.
swap=8GB
processors=6
```

### 5.5 Hadoop configuration (key values)

| File | Property | Value |
|---|---|---|
| core-site | `fs.defaultFS` | `hdfs://localhost:9000` |
| core-site | `hadoop.tmp.dir` | `~/bda/tmp` (absolute path) |
| hdfs-site | `dfs.replication` | `1` (single node, so replicas add nothing) |
| hdfs-site | `dfs.namenode.name.dir` | `file://~/bda/hdfs/nn` (absolute) |
| hdfs-site | `dfs.datanode.data.dir` | `file:///data/hdfs/dn` |
| yarn-site | `yarn.nodemanager.resource.memory-mb` | B: `6144` · A: `8192` (YARN = WSL − 4 GB) |
| yarn-site | `yarn.scheduler.maximum-allocation-mb` | Same as above |
| yarn-site | `yarn.scheduler.minimum-allocation-mb` | `512` |
| yarn-site | `yarn.nodemanager.resource.cpu-vcores` | `6` |
| yarn-site | `yarn.nodemanager.local-dirs` | `~/bda/yarn/local` (absolute) |
| yarn-site | `yarn.nodemanager.vmem-check-enabled` | `false` |
| yarn-site | `yarn.nodemanager.aux-services` | `mapreduce_shuffle` |
| mapred-site | `mapreduce.framework.name` | `yarn` |
| mapred-site | `mapreduce.map.memory.mb` / `java.opts` | B: `1024` / `-Xmx819m` · A: `1536` / `-Xmx1228m` |
| mapred-site | `mapreduce.reduce.memory.mb` / `java.opts` | B: `2048` / `-Xmx1638m` · A: `3072` / `-Xmx2457m` |
| mapred-site | `yarn.app.mapreduce.am.resource.mb` | `1024` |
| mapred-site | `mapreduce.task.io.sort.mb` | `256` |
| mapred-site | `mapreduce.application.classpath`, `*.env HADOOP_MAPRED_HOME` | Same as the smoke test |
| mapred-site | JobHistory server | Enabled: `mapred --daemon start historyserver`. Needed for counters and screenshots. |

Leave map-output compression **off**, so the combiner experiment's byte counts are easy to interpret.

**`hadoop-env.sh` additions:**
- `export HADOOP_HEAPSIZE_MAX=1g`. This caps each daemon's heap. Without it, every daemon may grow to a quarter of WSL's RAM.
- `export HADOOP_STOP_TIMEOUT=30`. In the smoke test, NodeManager didn't stop within the default 5 seconds and was killed with `kill -9`.

No `--add-opens` flags are needed on JDK 21 (smoke test). Add them only if a module error shows up later.

### 5.6 HBase (pseudo-distributed on HDFS)

- `hbase.cluster.distributed=true`
- `hbase.rootdir=hdfs://localhost:9000/hbase`
- `HBASE_MANAGES_ZK=true`
- `HBASE_HEAPSIZE`: B `1g` · A `1536m`
- `hbase.unsafe.stream.capability.enforce=false`
- **`zookeeper.session.timeout=90000`** and **`hbase.zookeeper.property.tickTime=6000`.** ZooKeeper caps sessions at 20 ticks, so the higher tick is what allows 90 s. In the smoke test, HBase's master aborted when its 6.7-second ZooKeeper session expired under CPU load.
- Same JDK as Hadoop.
- **Wait for the master before using it.** It needed about 19 seconds in the smoke test. After `start-hbase.sh`, poll `echo "status" | hbase shell -n` every 5 seconds (up to 120 s) until it succeeds. Scripts and the QueryServer must not issue commands before that.
- **Only run HBase for loading (WP9) and serving (WP10, demo).** Stop it during large MapReduce and Spark runs, and never run Spark while HBase is loading.

### 5.7 Hive + Tez + PostgreSQL

**PostgreSQL (as root):**
- `apt install postgresql`
- Create role `hive` with a password generated by Claude Code and stored in `~/bda/secrets/hive-db.txt` (mode 600). Mit doesn't type it.
- Create database `metastore` owned by `hive`.
- Put the JDBC driver `postgresql-42.x.jar` in `$HIVE_HOME/lib`.

**`hive-site.xml`:**
- `javax.jdo.option.ConnectionURL=jdbc:postgresql://localhost:5432/metastore`, with the driver `org.postgresql.Driver`, user and password.
- `hive.metastore.warehouse.dir=/user/hive/warehouse`
- `hive.execution.engine=tez`
- `hive.tez.container.size=1536`
- `tez.am.resource.memory.mb=1024`

**Tez:**
- Upload `share/tez.tar.gz` to `/apps/tez/` in HDFS.
- In `tez-site.xml`, set `tez.lib.uris=${fs.defaultFS}/apps/tez/tez.tar.gz`.

**Initialise:** `schematool -dbType postgres -initSchema`.

**Using Hive:** run it embedded, only when needed: `beeline -u jdbc:hive2:// -f hive/<script>.sql`. **No long-running HiveServer2.**

### 5.8 Spark (`spark-defaults.conf`)

```
spark.master                     yarn
spark.submit.deployMode          client
spark.driver.memory              1536m    # Profile A: 2g
spark.executor.memory            2g       # 2 executors × (2g + 384m overhead) + AM ≈ 5.7 GB, which fits YARN 6144
spark.executor.cores             2
spark.executor.instances         2
spark.sql.shuffle.partitions     8        # override to 64 for eval_msmarco.py
spark.serializer                 org.apache.spark.serializer.KryoSerializer
spark.local.dir                  /home/mit/bda/spark-tmp
spark.yarn.appMasterEnv.PYSPARK_PYTHON  /home/mit/bda/venv/bin/python
spark.eventLog.enabled           true
spark.eventLog.dir               hdfs://localhost:9000/spark-logs
```

On Profile B, stop the JobHistory server during the MS MARCO evaluation (WP11) to free memory.

### 5.9 Python venv

`pip install pyspark==4.1.2 pandas matplotlib numpy scipy tqdm pytrec_eval`

`ir_datasets` is no longer needed. The MS MARCO queries and qrels come from the same archive as the passages (§6.2).

If `pytrec_eval` fails to build, use `pytrec-eval-terrier` (same API, prebuilt wheels).

### 5.10 Service scripts (`~/bda/bin/`)

| Script | Does |
|---|---|
| `bda-start.sh [core\|hbase\|all]` | Checks the `/data` mount. Then starts, **each with `setsid`**: NameNode → DataNode → `safemode wait` → ResourceManager → NodeManager → JobHistory. With `hbase`/`all`, also `setsid start-hbase.sh` and waits for the master (§5.6). |
| `bda-stop.sh [hbase\|all]` | Stops in reverse order, with `HADOOP_STOP_TIMEOUT=30` |
| `bda-status.sh [--watch]` | `jps`, a short `hdfs dfsadmin -report`, `yarn node -list`, free space on `/` and `/data`, memory. `--watch` repeats every 60 seconds. |
| `bda-run.sh <name> <command…>` | Runs a long job **detached**: `setsid nohup <command> > ~/bda/logs/<name>.log 2>&1 &`. Prints the PID and the log path. Use it for every job longer than a few minutes, and for the QueryServer. |

**Why `setsid`:** in the smoke test, every Hadoop daemon logged `RECEIVED SIGNAL 1: SIGHUP` and died the moment the `wsl.exe` call that started it returned. Hadoop's JVM installs its own HUP handler, which defeats `nohup`. Starting under `setsid` (verified in the preflight) or as systemd services avoids this. `setsid` is simpler, so use it.

**Overnight runs:** start them with `bda-run.sh`. Leave a Windows Terminal tab open running `wsl -d Ubuntu -- bash -l ~/bda/bin/bda-status.sh --watch`. That keeps an active WSL session, so WSL doesn't shut the distro down as idle. It also shows progress at a glance in the morning. Then confirm the job finished in JobHistory or the log.

**Which services each work package needs:**

| Work package | HDFS + YARN | JobHistory | HBase |
|---|---|---|---|
| WP4–WP8, WP11 | ✓ | ✓ | ✗ (stopped) |
| WP9 load, WP10 serve, demo | ✓ | optional | ✓ |

---

## 6. Data

### 6.1 Datasets

| Dataset | Source | Size | Licence | Used for |
|---|---|---|---|---|
| Simple English Wikipedia, `pages-articles.xml.bz2`, **newest dated dump** (not `latest`) | dumps.wikimedia.org/simplewiki/`<DATE>`/ | ~300 MB | CC BY-SA 4.0 | Main corpus: index, PageRank, K-means, demo |
| MS MARCO passage `collectionandqueries.tar.gz` | Microsoft's `msmarco.z22.web.core.windows.net` endpoint (the old `blob` URL returns HTTP 409); University of Waterloo mirror as fallback | ~1 GB download | Non-commercial research | `collection.tsv` (8,841,823 passages) = evaluation corpus; `queries.dev.tsv` + `qrels.dev.small.tsv` = the dev/small set (6,980 queries, 7,437 judgments); we use a fixed sample of 1,000 |

### 6.2 Acquisition (WP2)

```bash
mkdir -p /data/raw/simplewiki /data/raw/msmarco && cd /data/raw/simplewiki
UA="BDA-coursework/1.0 (student project; contact: <EMAIL if §2 #7 = yes>)"
wget -c --user-agent="$UA" https://dumps.wikimedia.org/simplewiki/<DATE>/simplewiki-<DATE>-pages-articles.xml.bz2
wget -c --user-agent="$UA" https://dumps.wikimedia.org/simplewiki/<DATE>/simplewiki-<DATE>-sha1sums.txt
grep pages-articles.xml.bz2 simplewiki-<DATE>-sha1sums.txt | sha1sum -c -
bzcat simplewiki-<DATE>-pages-articles.xml.bz2 | grep -c '<page>'   # ground truth for S1 → build-log
hdfs dfs -mkdir -p /bda/raw/simplewiki && hdfs dfs -put simplewiki-<DATE>-pages-articles.xml.bz2 /bda/raw/simplewiki/

cd /data/raw/msmarco
# Try the sources in order; the first one that answers 200/206 to a 1-byte range request wins.
for U in https://msmarco.z22.web.core.windows.net/msmarcoranking/collectionandqueries.tar.gz \
         https://rgw.cs.uwaterloo.ca/JIMMYLIN-bucket0/data/collectionandqueries.tar.gz; do
  code=$(curl -s -L --connect-timeout 15 -r 0-0 -o /dev/null -w '%{http_code}' "$U")
  echo "$code $U"; [ "$code" = 206 ] || [ "$code" = 200 ] && { wget -c "$U"; break; }
done
tar -xzf collectionandqueries.tar.gz
wc -l collection.tsv qrels.dev.small.tsv     # expect 8841823 and 7437
# dev/small queries = queries.dev.tsv rows whose qid appears in qrels.dev.small.tsv
awk -F'\t' 'NR==FNR{q[$1]=1; next} ($1 in q)' qrels.dev.small.tsv queries.dev.tsv > queries.dev.small.tsv
wc -l queries.dev.small.tsv                  # expect 6980
hdfs dfs -mkdir -p /bda/raw/msmarco && hdfs dfs -put collection.tsv /bda/raw/msmarco/
```

- Pick `<DATE>` as the newest *complete* folder on the simplewiki dumps page; record it in the build log.
- The Wikimedia checksum filename pattern can vary. If `sha1sums.txt` isn't present, use the `md5sums` file.
- **Download speeds measured in the preflight:** Wikimedia about 2.1 MB/s, MS MARCO about 2 MB/s. Both files take about 15 minutes in total.
- **MS MARCO fallbacks, in order:**
  1. The two archive URLs above.
  2. `https://msmarco.z22.web.core.windows.net/msmarcoranking/collection.tar.gz` (confirmed working in the preflight) for the passages, plus `queries.dev.tsv` and `qrels.dev.small.tsv` from whichever archive source answers.
  3. Email ms-marco@microsoft.com.
  Record which source was used.
- **`collectionandqueries.tar.gz` contents:** the archive contains more files than listed here. Use only the three named ones. If the archive already contains a `queries.dev.small.tsv`, check it matches the `awk` output, then keep either.

---

## 7. Shared conventions

### 7.1 Tokeniser (normative: one Java class, `bda.common.Tokenizer`)

Applied identically at index time (Jobs 1–2), at query time (QueryServer), and to the evaluation queries (`TokenizeQueries`). **No other tokeniser exists in the project.**

1. Unicode NFKD normalisation, then remove combining marks (`café` → `cafe`).
2. Lowercase with `Locale.ROOT`.
3. Replace every character not in `[a-z0-9]` with a space, then split on whitespace.
4. Drop tokens shorter than 2 or longer than 40 characters.
5. Drop stopwords. The list is fixed at **Lucene's 33-word English set**, bundled as `stopwords.txt`:
   `a an and are as at be but by for if in into is it no not of on or such that the their then there these they this to was will with`
6. Stem with the **Porter** stemmer: `org.tartarus.snowball.ext.PorterStemmer` from `lucene-analysis-common` **9.x** (Java 11+; Lucene 10 would need JDK 21). **Use only this class from Lucene. No IndexWriter or IndexSearcher anywhere.**
7. Expose `List<String> tokenize(String)` (order preserved, duplicates kept) and `Set<String> uniqueTerms(String)`.
8. Constant `TOKENIZER_VERSION = "v1-lucene33-porter"`. It's written into every index's `meta.tsv`, and the QueryServer refuses to start if the versions differ.

**Required JUnit tests:**

| Input | Expected output |
|---|---|
| `"The Running Dogs"` | `[run, dog]` |
| `"state-of-the-art"` | `[state, art]` |
| `"Café"` | `[cafe]` |
| `"1984 Olympics"` | `[1984, olymp]` (Porter's output, whatever it is, pinned in the test) |
| `"dog's"` | `[dog]` |
| `""` and `"   "` | `[]` |
| A 41-character token | dropped |
| Mixed punctuation `"U.S.A. (2020)!"` | `[2020]` (single letters dropped; documented limitation) |

One determinism test: the same input gives identical output twice.

### 7.2 IDs and keys

- **Wikipedia docID:** the page `<id>`, meaning the *first* `<id>` inside `<page>`, not the revision id. **MS MARCO docID:** `pid`. Both are stored as `long`.
- **HBase rowkeys:** docIDs are zero-padded to 10 digits (`String.format("%010d", id)`), so byte order matches numeric order.
- **Article URL:** `https://simple.wikipedia.org/wiki/` + the title with spaces replaced by `_` (URL-encoded).

### 7.3 Record formats (UTF-8, tab-separated, no header, one record per line; **no tabs or newlines inside fields**)

| Name | HDFS path (`<c>` = `wiki` or `msmarco`) | Fields | Producer → consumers |
|---|---|---|---|
| pages | `/bda/wiki/job0/pages` | docID, title, ns, isRedirect (0/1), redirectTarget (may be empty), len (characters of text), text | Job 0 → Pig |
| links | `/bda/wiki/job0/links` | srcDocID, targetTitle (normalised) | Job 0 → Spark PageRank |
| docs | `/bda/wiki/clean/docs` · `/bda/raw/msmarco/collection.tsv` | docID, text | Pig / raw → Jobs 1, 2 |
| titles | `/bda/wiki/clean/titles` | docID, title, snippet (≤300 characters) | Pig → Hive `docs_full`, PageRank |
| redirects | `/bda/wiki/clean/redirects` | title, redirectTarget | Pig → PageRank |
| postings | `/bda/<c>/index/postings` | term, df, cf, `d1:tf1,d2:tf2,…` (docIDs ascending) | Job 1 → Job 3, Hive, Spark, HBase loader |
| docstats | `/bda/<c>/index/docstats` | docID, len (tokens after tokenising) | Job 2 → Spark, Hive |
| meta | `/bda/<c>/index/meta/meta.tsv` | key, value rows: N, totalTokens, avgdl, tokenizerVersion, builtAt | Job 2 driver → everyone |
| lexicon | `/bda/<c>/index/lexicon` | term, df, cf | Job 3 → Hive, Spark, HBase `terms` |
| norms | `/bda/<c>/spark/norms` | docID, tfidfNorm | Spark → Hive `docs_full`, eval |
| pagerank | `/bda/wiki/spark/pagerank` | docID, pr | Spark → Hive `docs_full` |
| docs_full | Hive table → `/bda/wiki/hive/docs_full` | docID, title, snippet, len, norm, pr | Hive CTAS → HBase `docs` |
| runs | `eval/runs/<model>.trec` (local) | `qid Q0 docID rank score tag` | Spark eval → `metrics.py` |

### 7.4 HDFS layout

```
/bda/raw/simplewiki/   /bda/raw/msmarco/
/bda/wiki/job0/{pages,links}   /bda/wiki/clean/{docs,titles,redirects,summary}
/bda/wiki/index/{postings,docstats,lexicon,meta}   /bda/wiki/spark/{norms,pagerank,kmeans}
/bda/wiki/hive/docs_full
/bda/msmarco/index/{postings,docstats,lexicon,meta}   /bda/msmarco/spark/norms   /bda/msmarco/eval/
/bda/tmp/  (scratch — safe to delete)   /user/hive/warehouse   /hbase   /apps/tez   /spark-logs
```

### 7.5 Repository and build

```
~/bda/repo/  (git init; commit after every WP)
  java/  pom.xml  → target/bda-search-1.0.jar (shaded)
    src/main/java/bda/common/   Tokenizer, Scorer, Meta
    src/main/java/bda/ingest/   XmlInputFormat, XmlRecordReader, WikiPageJob, WikiPageMapper, WikiText (fallback cleaner)
    src/main/java/bda/index/    TermDocKey, TermPartitioner, TermGroupingComparator, PostingsJob, DocStatsJob, LexiconJob
    src/main/java/bda/hbase/    HBaseLoadJob
    src/main/java/bda/serve/    QueryServer, QueryCli, SearchService
    src/main/java/bda/tools/    TokenizeQueries
    src/main/resources/         stopwords.txt, static/index.html
    src/test/java/...           TokenizerTest, ScorerTest, XmlRecordReaderTest, PostingsReducerTest
  pig/filter_pages.pig
  hive/ddl.sql  hive/stats.sql  hive/docs_full.sql  hive/split_keys.sql
  spark/tfidf_norms.py  spark/pagerank.py  spark/kmeans.py  spark/eval_msmarco.py
  eval/sample_qids.txt  eval/metrics.py  eval/latency.py  eval/runs/  eval/results.md
  charts/make_charts.py  charts/*.png
  bin/  (bda-start.sh, bda-stop.sh, bda-status.sh, run-*.sh — one script per job, with the exact parameters)
  docs/  bda-project-plan.md, bda-preflight-check.md, bda-preflight-report.md, build-log.md, setup-log.md
  report/
```

**Maven:**
- `maven.compiler.release=17`.
- `hadoop-client` 3.4.3 and `hbase-client` / `hbase-mapreduce` 2.6.6 at scope `provided`.
- Bundled into the jar with `maven-shade-plugin`: `lucene-analysis-common` 9.x, `wikiclean`, `commons-lang3`.
- Test dependencies: `junit-jupiter`, plus `mrunit`-style tests done by hand (MRUnit is retired).

**Job conventions:**
- Every job's driver uses `ToolRunner`, so `-D key=value` overrides work.
- Every job is launched from `bin/run-<job>.sh <corpus>`, so the exact command is reproducible and goes in the report appendix.

---

## 8. Work packages

Every work package has the same parts: **Goal · Depends on · Inputs → Outputs · Steps / implementation notes · Acceptance · Record for the report · Fallback · Estimate.**

### WP1 — Environment setup (3.5 h)

**Goal:** the full stack from §5, running and verified, built on the smoke-test installs.
**Depends on:** preflight report; decisions in §2 (#5, #6, #8, #9).

**Steps:**

0. **Before starting.**
   1. Stop any smoke-test daemons still running: `~/smoke/run.sh teardown`.
   2. Run the lean-Windows checklist (§5.1).
   3. Get Mit to confirm §2 #5–#7.
1. **Windows, admin session.**
   1. Measure Windows' RAM use (§5.1) and choose Profile A or B. Record the number and the choice in `setup-log.md`.
   2. Write `.wslconfig` (§5.4), then `wsl --shutdown`.
   3. Create the VHDX with `diskpart` (this is Home edition, so there's no `New-VHD`). Write a script file, as in the preflight's test 2.8:
      `create vdisk file="D:\BDA\hdfs.vhdx" maximum=307200 type=expandable`
      (307200 MB = 300 GB.) Create `D:\BDA` first.
   4. Attach it raw: `wsl --mount --vhd D:\BDA\hdfs.vhdx --bare`.
2. **Format the data disk (root, guarded).**
   1. Run `wsl -d Ubuntu -u root -- lsblk -o NAME,SIZE,TYPE,MOUNTPOINTS`. Identify the new disk: about 300 GB, no mountpoint. The preflight showed `sda`–`sdd` already in use (`sdd` is the 1 TB distro root), so expect `sde`.
   2. **Show the output to Mit and get an explicit yes.**
   3. `mkfs.ext4 -L bdadata /dev/sdX` (the whole disk; no partition table).
   4. `wsl --unmount D:\BDA\hdfs.vhdx`.
3. **Mount the data disk and make it persistent.**
   1. Mount it: `wsl --mount --vhd D:\BDA\hdfs.vhdx --type ext4 --name bdadata`, then check `/mnt/wsl/bdadata` exists.
   2. As root: `ln -s /mnt/wsl/bdadata /data` and `chown mit: /mnt/wsl/bdadata`.
   3. Create the scheduled task:
      `schtasks /create /tn bda-mount-vhdx /sc onlogon /rl highest /tr "wsl.exe --mount --vhd D:\BDA\hdfs.vhdx --type ext4 --name bdadata"`
   4. Add the `[boot] command` to `/etc/wsl.conf` (§5.3).
   5. Test: `wsl --shutdown`, start the distro, and confirm `/data` comes back. If it doesn't, `schtasks /run /tn bda-mount-vhdx` must bring it back.
4. **System packages (root).**
   `apt update && apt install -y postgresql python3-venv python3-pip maven git build-essential unzip`
5. **Promote the smoke-test installs.**
   1. `mkdir -p ~/bda/opt` and move `jdk-21`, `hadoop-3.4.3`, `hbase-2.6.6-hadoop3` and `spark-4.1.2-bin-hadoop3` there from `~/smoke`.
   2. Download Tez 0.10.5, Hive 4.2.1 and Pig 0.18.0 from `dlcdn.apache.org`, falling back to `archive.apache.org/dist/`. Verify checksums.
   3. Create the versionless symlinks and `~/bda/env.sh` (§5.2).
   4. **Ask Mit** before deleting what's left in `~/smoke`. Its old HDFS data isn't reused; the tarballs (~1.7 GB) can go once everything is extracted.
6. **Hadoop and the service scripts.**
   1. Write the configs from §5.5, including the `hadoop-env.sh` additions.
   2. Write `bda-start.sh`, `bda-stop.sh`, `bda-status.sh` and `bda-run.sh` (§5.10).
   3. `hdfs namenode -format` (a fresh NameNode in `~/bda/hdfs/nn`).
   4. `bda-start.sh core` (from a `.sh` call, per §0 rule 8).
   5. **Detach check:** wait 30 seconds, then from a *new* `wsl -d Ubuntu -- bash -l ~/bda/bin/bda-status.sh` call, confirm all five daemons are still alive. No `SIGHUP` lines should appear in their logs.
   6. `hdfs dfs -mkdir -p /bda /user/mit /tmp /spark-logs /apps/tez`.
   7. Run the Pi example. Check that the JobHistory UI (port 19888) shows the job.
7. **Tez + PostgreSQL + Hive.** Follow §5.7, then run:
   `beeline -u jdbc:hive2:// -e "create table t(x int); insert into t values (1); select * from t; drop table t;"`
   The `insert` launches a Tez DAG, which proves Tez works.
8. **HBase.** Configure per §5.6, including the ZooKeeper timeouts. Run `bda-start.sh hbase`, which waits for the master. Then do a shell `create`/`put`/`scan`/`disable`/`drop` on a test table. Repeat the detach check from step 6.
9. **Pig.** Run `pig -x mapreduce` on a three-line script (load a small HDFS file, group, store).
10. **Spark.**
    1. Write `spark-defaults.conf` (§5.8).
    2. Run SparkPi on YARN.
    3. Run `spark-submit spark/hello.py` (`spark.range(10**7).count()`) to prove PySpark works with the venv Python.
11. **Python venv** (§5.9).
12. **Repo.**
    1. `git init`; create the Maven skeleton with the shade plugin.
    2. `mvn -q package`.
    3. Run a trivial driver from our jar (e.g. a WordCount copy) on YARN. This proves the `provided`/shaded split is right.
13. **Full cycle test.** `bda-stop.sh all` → `wsl --shutdown` → start the distro → confirm `/data` is mounted → `bda-start.sh all` → `bda-status.sh` is clean. This is exactly what happens every morning, so it has to work.

**Acceptance:**
- Every check in steps 6–12 passes.
- `bda-status.sh` is clean.
- `/data` survives `wsl --shutdown` plus running the scheduled task.
- `docs/setup-log.md` lists the output of `java -version`, `hadoop version`, `hive --version`, `hbase version`, `spark-submit --version` and `pig -version`.

**Record:** versions; every fix applied (the report's "ecosystem drift" paragraph: Hive 4.2 requires JDK 21 while HBase is only officially tested to 17, and so on); memory settings and why.

**Fallbacks** (each time-boxed):
- JDK 21 problems → JDK 17 and Hive 4.1.0. (Unlikely: the smoke test passed with no flags.)
- `setsid` daemons still die → run them as systemd units (systemd is on in this distro).
- A download from GitHub hangs in `SYN-SENT` → see R17 (§13).
- Hive/Tez won't run after 30 minutes → try `hive.execution.engine=mr`, if this Hive build still accepts it (it's deprecated). Otherwise run the WP7 statistics as Spark SQL, and say so in the report.
- Pig fails on JDK 21 → run only the Pig client on JDK 8: `JAVA_HOME=~/bda/opt/jdk8 pig …` (the cluster stays on 21).
- Pig still fails → `pig -x local` on a local copy for WP5, and document it.

### WP2 — Data acquisition (0.25 h active; downloads run in the background)

**Goal:** both corpora and the evaluation queries in place and verified.
**Depends on:** WP1 step 6 (HDFS up, `/data` mounted). Start it as soon as HDFS works; the downloads run while WP1 continues.

**Steps:**
1. Follow §6.2.
2. Copy `queries.dev.small.tsv` and `qrels.dev.small.tsv` into `eval/`.
3. Create the fixed query sample:
   ```python
   import random
   qids = sorted({line.split("\t")[0] for line in open("eval/queries.dev.small.tsv", encoding="utf-8")})
   assert len(qids) == 6980
   sample = sorted(random.Random(42).sample(qids, 1000))
   open("eval/sample_qids.txt", "w").write("\n".join(sample) + "\n")
   ```
4. Commit `sample_qids.txt`. It must never change afterwards.

**Acceptance:**
- Checksum OK.
- `<page>` count recorded (ground truth for S1).
- `collection.tsv` has 8,841,823 lines; `qrels.dev.small.tsv` has 7,437; `queries.dev.small.tsv` has 6,980.
- Both corpora show in `hdfs dfs -ls -h`.
- The MS MARCO source used is recorded in the build log.

**Fallback:**
- MS MARCO: the source order in §6.2.
- Wikimedia slow → use a mirror from the "Mirroring Wikimedia project XML dumps" list.

### WP3 — Tokeniser (1 h; a teammate can do this in parallel)

**Goal:** `bda.common.Tokenizer` exactly as specified in §7.1, with tests.
**Also build here:** `bda.tools.TokenizeQueries`. It reads `qid \t text` and writes `qid \t term1 term2 …` (unique terms, in first-occurrence order).

**Acceptance:**
- `mvn test` is green, with every case from §7.1.
- A throughput check: tokenise 10,000 lines of any text and print documents per second, for the log.

### WP4 — Job 0: `XmlInputFormat` + page parsing (2 h)

**Goal:** turn the bz2 XML dump into `pages` and `links` records.
**Depends on:** WP1, WP2, WP3 (only for packaging).

**`XmlInputFormat` / `XmlRecordReader`:**
- `FileInputFormat<LongWritable, Text>` with configurable start and end tags (`bda.xml.start=<page>`, `bda.xml.end=</page>`).
- `isSplitable()` returns true when the file isn't compressed, or its codec is a `SplittableCompressionCodec` (bzip2 is). Config `bda.xml.splittable=false` forces a single split, for the comparison test.
- **The split rule:** a record belongs to the split in which its start tag begins. The reader emits a record only if the start tag starts at a position ≤ the split end, and it keeps reading past the split end until the end tag.
- **For bz2:**
  - `in = ((SplittableCompressionCodec) codec).createInputStream(fileIn, decompressor, start, end, READ_MODE.BYBLOCK)`
  - Then `start = in.getAdjustedStart()` and `end = in.getAdjustedEnd()`.
  - Use `in.getPos()` (block-granular) for the "position ≤ end" check.
  - **Follow Hadoop's `LineRecordReader` source as the reference.** It solves exactly this boundary problem for bz2.
- The value is the bytes from `<page>` through `</page>` inclusive. Read through a buffered stream with a simple byte-sequence matcher.
- **Unit test:** a small uncompressed XML file with 5 pages, read with tiny forced splits (e.g. 200 bytes), must yield exactly 5 records and no duplicates.

**`WikiPageMapper` (map-only, `MultipleOutputs`):**
1. **Extract fields with string operations, not a DOM parser:**
   - `<title>`, `<ns>`, and the **first** `<id>` (the page id; the second one belongs to the revision).
   - The redirect target from `<redirect title="…"/>`.
   - The raw wikitext inside `<text …>…</text>` (it may be self-closing `<text … />`, i.e. empty).
2. **XML-unescape** the title, the redirect target and the wikitext (`&amp; &lt; &gt; &quot; &#39;`).
3. **Clean the text with `wikiclean`:**
   `new WikiClean.Builder()…build().clean(pageXml)`. Check its README for the exact builder options (no title, no footer).
   - On an exception or empty output, fall back to `bda.ingest.WikiText.strip(wikitext)`, which:
     - removes `<ref>…</ref>`;
     - removes `{{…}}` using a brace-depth counter, and `{|…|}` tables;
     - removes File/Image/Category links;
     - turns `[[a|b]]` into `b`, `[[a]]` into `a`, and `[url text]` into `text`;
     - strips `'''` and `''`, `==` heading markers, and HTML tags.
   - Count fallbacks in the `CLEAN_FALLBACK` counter.
4. **Normalise the output text:** replace every run of `[\t\r\n]` with a space, collapse repeated spaces, trim. Set `len` = character length.
5. **Extract links** only from `ns == 0` non-redirect pages, from the raw wikitext. Regex:
   `\[\[([^\[\]|#]+)(?:#[^\[\]|]*)?(?:\|[^\[\]]*)?\]\]`
   - Skip targets containing `:`. That removes namespaces (File, Category, Template…), at the cost of a few real titles with colons; document it.
   - Normalise each target: `_` → space, collapse spaces, trim, uppercase the first character.
   - De-duplicate within the page.
6. **Outputs:** named outputs `pages` and `links` (NullWritable key, Text line). Use `LazyOutputFormat`, so there are no empty default part files.
7. **Counters:** `PAGES`, `NS0`, `REDIRECTS`, `EMPTY_TEXT`, `CLEAN_FALLBACK`, `LINKS`.

**Run:** `bin/run-job0.sh` → `hadoop jar …/bda-search-1.0.jar bda.ingest.WikiPageJob /bda/raw/simplewiki /bda/wiki/job0`
- Run twice: once with `-D mapreduce.input.fileinputformat.split.maxsize=33554432` (32 MB, so about 10 splits), and once with `-D bda.xml.splittable=false`.

**Acceptance:**
- **S1:** `PAGES` equals the grep ground truth in both runs.
- `CLEAN_FALLBACK` < 1% of pages.
- 20 random records (`hdfs dfs -cat …/pages-m-* | shuf -n 20 | cut -c1-300`) read as clean prose.
- The links sample looks like real article titles.

**Record:** counters, number of mappers and wall time for both split modes (a concrete Unit 2/3 result: same output, N mappers versus 1).

**Fallback:**
- bz2 split handling still wrong after the time-box → ship with `bda.xml.splittable=false` (one mapper, a few minutes on simplewiki) and state it in the report.
- `wikiclean` unusable → use `WikiText.strip` for everything.

### WP5 — Pig: filter and summarise (0.75 h)

**Goal:** clean article records, plus the side files PageRank and HBase need.
**Depends on:** WP4.

`pig/filter_pages.pig`:
```pig
%default MINLEN 200
pages    = LOAD '$IN' USING PigStorage('\t')
           AS (docid:long, title:chararray, ns:int, redir:int, rtarget:chararray, len:int, text:chararray);
articles = FILTER pages BY ns == 0 AND redir == 0 AND len >= $MINLEN;
docs     = FOREACH articles GENERATE docid, text;
titles   = FOREACH articles GENERATE docid, title, (len > 300 ? SUBSTRING(text, 0, 300) : text) AS snippet;
redirs0  = FILTER pages BY ns == 0 AND redir == 1;
redirs   = FOREACH redirs0 GENERATE title, rtarget;
STORE docs   INTO '$OUT/docs'      USING PigStorage('\t');
STORE titles INTO '$OUT/titles'    USING PigStorage('\t');
STORE redirs INTO '$OUT/redirects' USING PigStorage('\t');

tagged   = FOREACH pages GENERATE (ns != 0 ? 'other_ns' : (redir == 1 ? 'redirect' : (len < $MINLEN ? 'stub' : 'article'))) AS c;
grp      = GROUP tagged BY c;
bycat    = FOREACH grp GENERATE group AS category, COUNT(tagged) AS n;
STORE bycat INTO '$OUT/summary/by_category' USING PigStorage('\t');
bucketed = FOREACH articles GENERATE (len / 500) * 500 AS bucket;
hgrp     = GROUP bucketed BY bucket;
hist     = FOREACH hgrp GENERATE group AS bucket, COUNT(bucketed) AS n;
STORE hist INTO '$OUT/summary/length_hist' USING PigStorage('\t');
```

**Run:** `pig -x mapreduce -param IN=/bda/wiki/job0/pages -param OUT=/bda/wiki/clean pig/filter_pages.pig`

`MINLEN = 200` characters is the stub cut-off. Check the length histogram, adjust once if it's clearly wrong, and record the final value and why.

**Acceptance:**
- Article + redirect + stub + other_ns counts sum to `PAGES`.
- Row count of `docs` = number of distinct docIDs (check in WP7).
- `titles` row count = `docs` row count.

**Record:** category counts, the length histogram, Pig job times.

### WP6 — Index build: Jobs 1–3 (2.5 h)

**Goal:** postings, document stats, meta and lexicon for a corpus (simplewiki now, MS MARCO in WP11).
**Depends on:** WP3, WP5 (Wikipedia) / WP2 (MS MARCO).

**Job 1 — `PostingsJob`:**

Settings:
- `KeyValueTextInputFormat` (key = docID, value = text).
- 4 reducers (`-D mapreduce.job.reduces`).
- Combiner on unless `-D bda.combiner=false`.

The mapper emits **one `(TermDocKey(term, docID), 1)` per token occurrence**, on purpose, so the combiner has measurable work. Core classes:

```java
public class TermDocKey implements WritableComparable<TermDocKey> {
  private final Text term = new Text(); private long docId;
  public void set(String t, long d) { term.set(t); docId = d; }
  public Text getTerm() { return term; }  public long getDocId() { return docId; }
  public void write(DataOutput out) throws IOException { term.write(out); out.writeLong(docId); }
  public void readFields(DataInput in) throws IOException { term.readFields(in); docId = in.readLong(); }
  public int compareTo(TermDocKey o) { int c = term.compareTo(o.term); return c != 0 ? c : Long.compare(docId, o.docId); }
  public int hashCode() { return term.hashCode(); }
  public boolean equals(Object o) { return o instanceof TermDocKey k && term.equals(k.term) && docId == k.docId; }
}
public class TermPartitioner extends Partitioner<TermDocKey, IntWritable> {   // route by term only
  public int getPartition(TermDocKey k, IntWritable v, int n) { return (k.getTerm().hashCode() & Integer.MAX_VALUE) % n; }
}
public class TermGroupingComparator extends WritableComparator {             // one reduce() call per term
  protected TermGroupingComparator() { super(TermDocKey.class, true); }
  public int compare(WritableComparable a, WritableComparable b) { return ((TermDocKey) a).getTerm().compareTo(((TermDocKey) b).getTerm()); }
}
// Reducer: Hadoop updates the key object as values advance, so read docId INSIDE the loop.
// The combiner may not have merged everything, so the same docId can appear several times in a row: sum them.
protected void reduce(TermDocKey key, Iterable<IntWritable> vals, Context ctx) throws IOException, InterruptedException {
  StringBuilder sb = new StringBuilder(); long prev = -1, cf = 0; int tf = 0, df = 0;
  for (IntWritable v : vals) {
    long d = key.getDocId();
    if (d != prev && prev != -1) { sb.append(sb.length() > 0 ? "," : "").append(prev).append(':').append(tf); df++; tf = 0; }
    prev = d; tf += v.get(); cf += v.get();
  }
  sb.append(sb.length() > 0 ? "," : "").append(prev).append(':').append(tf); df++;
  ctx.write(new Text(key.getTerm()), new Text(df + "\t" + cf + "\t" + sb));
}
```

Rules for the driver:
- Call `job.setPartitionerClass(TermPartitioner.class)` and `job.setGroupingComparatorClass(TermGroupingComparator.class)`.
- Leave the sort comparator at its default (`TermDocKey.compareTo`).
- **Never set a combiner grouping comparator.** The combiner must group by the full `(term, docID)` key, which is the default.
- The combiner is a plain reducer that sums the values for one `(term, docID)` key.

**Job 2 — `DocStatsJob`** (map-only):
- Tokenise each document and write `docID \t len`.
- Counters: `DOCS`, `TOKENS`, `EMPTY_DOCS`.
- When the job finishes, the driver writes `meta.tsv`: N = `DOCS`, totalTokens, avgdl = totalTokens / N, `TOKENIZER_VERSION`, timestamp.

**Job 3 — `LexiconJob`** (map-only): reads `postings` and writes `term \t df \t cf`.

**Order per corpus:** Job 2 → Job 1 → Job 3, via `bin/run-index.sh <corpus>`.

**The combiner experiment (simplewiki):** run Job 1 with the combiner on and with it off (output to `/bda/tmp`). Record from the job counters:
- Map output records and bytes
- Combine input and output records
- Reduce shuffle bytes
- Spilled records
- CPU time and wall time

**Integrity checks** (`spark/validate_index.py`, or Hive in WP7):
1. A random sample of 5,000 postings lines has strictly increasing, unique docIDs.
2. **Σcf over the lexicon == `TOKENS`** from Job 2. This proves Jobs 1 and 2 tokenised identically.
3. Lexicon row count == postings line count.

**Acceptance:** S2 and S3, on simplewiki.

**Record:** both counter tables, job wall times, vocabulary size.

**Fallback:**
- Reducer runs out of memory on very long posting lists (MS MARCO) → `-D mapreduce.reduce.memory.mb=4096` (with `-Xmx3276m`) and 6 reducers.
- If that still fails, write long lists in chunks: the same term on several lines. Consumers must handle that; document it.

### WP7 — Hive: corpus statistics and joins (0.75 h, split into WP7a and WP7b)

**Goal:** the corpus statistics for the report, the HBase split keys, and the joined `docs_full` table.
**Depends on:** WP6 (7a); WP8 (7b).

**`hive/ddl.sql`** (external tables; Hive skips `_SUCCESS` files):
```sql
CREATE DATABASE IF NOT EXISTS bda; USE bda;
CREATE EXTERNAL TABLE IF NOT EXISTS wiki_lexicon  (term STRING, df BIGINT, cf BIGINT)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t' LOCATION '/bda/wiki/index/lexicon';
CREATE EXTERNAL TABLE IF NOT EXISTS wiki_docstats (docid BIGINT, len INT)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t' LOCATION '/bda/wiki/index/docstats';
CREATE EXTERNAL TABLE IF NOT EXISTS wiki_titles   (docid BIGINT, title STRING, snippet STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t' LOCATION '/bda/wiki/clean/titles';
CREATE EXTERNAL TABLE IF NOT EXISTS wiki_norms    (docid BIGINT, norm DOUBLE)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t' LOCATION '/bda/wiki/spark/norms';
CREATE EXTERNAL TABLE IF NOT EXISTS wiki_pagerank (docid BIGINT, pr DOUBLE)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t' LOCATION '/bda/wiki/spark/pagerank';
CREATE EXTERNAL TABLE IF NOT EXISTS wiki_docs_full (docid BIGINT, title STRING, snippet STRING, len INT, norm DOUBLE, pr DOUBLE)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t' LOCATION '/bda/wiki/hive/docs_full';
-- msmarco_lexicon, msmarco_docstats: same shape, /bda/msmarco/index/...
```

**WP7a — `hive/stats.sql`.** Each query is exported with `beeline -u jdbc:hive2:// --silent=true --showHeader=false --outputformat=tsv2 -f … > charts/data/<name>.tsv`.
- Vocabulary size; hapax share (`cf = 1`); top 50 terms by cf and by df.
- **Zipf:** `SELECT ROW_NUMBER() OVER (ORDER BY cf DESC) AS rnk, cf FROM wiki_lexicon`. Keep all ranks ≤ 1000 plus every 100th rank after that.
- Document-length histogram (`FLOOR(len/50)*50`); df distribution by order of magnitude (`FLOOR(LOG10(df))`).
- Integrity: `SELECT SUM(cf) FROM wiki_lexicon` must match `TOKENS`, and `COUNT(DISTINCT docid)` must equal `COUNT(*)` on `wiki_docstats`.
- The same headline statistics for MS MARCO (after WP11 step 1).

**WP7a — `hive/split_keys.sql`** (15 split points for 16 regions with roughly equal posting counts):
```sql
WITH c AS (SELECT term, SUM(df) OVER (ORDER BY term ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum,
                  SUM(df) OVER () AS tot FROM bda.wiki_lexicon)
SELECT MIN(term) FROM c WHERE FLOOR(cum * 16 / tot) BETWEEN 1 AND 15
GROUP BY FLOOR(cum * 16 / tot) ORDER BY 1;
```
Save the result to `hive/split_keys.txt`.

**WP7b — `hive/docs_full.sql`** (after WP8):
```sql
INSERT OVERWRITE TABLE bda.wiki_docs_full
SELECT t.docid, t.title, t.snippet, d.len, n.norm, COALESCE(p.pr, 0.0)
FROM bda.wiki_titles t JOIN bda.wiki_docstats d ON t.docid = d.docid
LEFT JOIN bda.wiki_norms n ON t.docid = n.docid LEFT JOIN bda.wiki_pagerank p ON t.docid = p.docid;
```

**Acceptance:**
- The queries run on Tez (visible in the YARN UI; take a screenshot).
- The exports exist in `charts/data/`.
- 15 split keys saved.
- `wiki_docs_full` row count = `wiki_titles` row count.

**Record:** headline numbers (vocabulary, hapax %, average length), query runtimes, the integrity results.

### WP8 — Spark: TF-IDF norms, PageRank, K-means (2 h)

**Depends on:** WP6 (and WP5 for titles and redirects).

**`spark/tfidf_norms.py <corpus>`:**
- Explode the postings into `(term, docid, tf)`, join the lexicon to get df, and read N from meta.
- Weight w = (1 + ln tf) × ln(N / df). Norm per document = √(Σ w²).
- Write `docID \t norm` to `/bda/<c>/spark/norms`.
- Run for `wiki` now and `msmarco` in WP11.

**`spark/pagerank.py`:**
1. **Nodes:** all docIDs in `titles` (the articles). **Title map:** title → docID.
2. **Redirects:** title → target. Follow up to 3 hops, using the same title normalisation as Job 0.
3. **Edges:** `links` → resolve each target through redirects to a docID; drop unresolvable targets and self-links; `distinct()`.
4. **PageRank** with damping d = 0.85 and 10 iterations:
   - `links = edges.groupByKey().cache()`; start every rank at 1/N.
   - Each iteration:
     - contributions = `links.join(ranks)` spread across each node's out-links;
     - dangling mass = sum of ranks of nodes with no out-links;
     - new rank = (1 − d)/N + d × (contribution + dangling mass / N), with nodes that receive nothing getting the base value through a left outer join from the nodes RDD.
   - Force each iteration with an action. Log the iteration number, wall time and L1 change to `eval/pagerank_log.csv`.
   - Call `localCheckpoint()` every 3 iterations to keep the lineage short.
5. **Output:**
   - `docID \t pr` → `/bda/wiki/spark/pagerank`.
   - The top 50 with titles → `eval/pagerank_top50.tsv`.
   - Check that Σpr ≈ 1 (within ±1e-6).

**`spark/kmeans.py`:**
1. **Vocabulary:** terms with 5 ≤ df ≤ 0.1·N; keep the top 20,000 by df.
2. **Vectors:** document vectors with weights (1 + ln tf) × idf, as `SparseVector`, L2-normalised with `pyspark.ml.feature.Normalizer`.
3. **Clustering:** `KMeans(k=20, maxIter=20, seed=42)`.
4. **Output** to `eval/kmeans_k20.md`:
   - Cluster sizes.
   - The top 10 terms of each centroid.
   - 5 random titles per cluster.
   - Optional: silhouette on a 20k-document sample (`ClusteringEvaluator`).
5. If there's time, re-run with k = 10 for comparison.

**Acceptance:** S6; Σpr check passes; outputs written; runtimes logged.

**Fallback:**
- K-means slow → vocabulary 10,000, k = 10, maxIter = 10.
- Memory pressure → `persist(StorageLevel.DISK_ONLY)` for the edges, and confirm HBase is stopped.

### WP9 — HBase serving tables and loader (1.5 h)

**Depends on:** WP6, WP7b. Start HBase now (`bda-start.sh hbase`).

**Tables** (`hbase/create_tables.sh`, which pastes in the split keys from `hive/split_keys.txt`):
```
create 'postings', {NAME => 'p', VERSIONS => 1, BLOOMFILTER => 'ROW'}, SPLITS => ['<k1>', ..., '<k15>']
create 'docs',     {NAME => 'd', VERSIONS => 1}
create 'terms',    {NAME => 't', VERSIONS => 1}
```

| Table | Rowkey | Cells |
|---|---|---|
| postings | `term#` + `%010d` docID | `p:tf` (int, 4 bytes) |
| docs | `%010d` docID | `d:title`, `d:snippet` (UTF-8), `d:len` (int), `d:norm` (double), `d:pr` (double) |
| terms | term | `t:df` (long), `t:cf` (long) |

**`HBaseLoadJob <postings|docs|terms> <input>`:**
- Map-only, with `TableOutputFormat`: `TableMapReduceUtil.initTableReducerJob(table, null, job); job.setNumReduceTasks(0); TableMapReduceUtil.addDependencyJars(job)`.
- The mapper emits `(ImmutableBytesWritable(row), Put)`. Postings explode one line into many Puts.
- Every Put uses `setDurability(Durability.SKIP_WAL)`. The data can always be reloaded from HDFS, and skipping the write-ahead log speeds up loading. Say so in the report.
- Launch with `HADOOP_CLASSPATH=$(hbase mapredcp) hadoop jar …`.
- Inputs: postings ← `/bda/wiki/index/postings`; terms ← `/bda/wiki/index/lexicon`; docs ← `/bda/wiki/hive/docs_full`.

**Acceptance:**
- `hbase org.apache.hadoop.hbase.mapreduce.RowCounter postings` = Σdf; `terms` rows = lexicon rows; `docs` rows = `docs_full` rows.
- `scan 'postings', {ROWPREFIXFILTER => '<stemmed term>#', LIMIT => 5}` returns ascending docIDs. Get the stemmed form from `QueryCli --tokenize "photosynthesis"`.
- `get 'docs', '<padded id>'` shows all fields.
- Region sizes are roughly balanced (take an HBase UI screenshot).

**Record:** load time and rows per second for each table; the region list.

**Fallback:** if the postings load is projected to take over 30 minutes, switch to bulk load: `HFileOutputFormat2.configureIncrementalLoad` and then `hbase completebulkload`.

### WP10 — Query server and search page (2 h)

**Depends on:** WP9.

**`SearchService` startup:**
1. Read `meta.tsv` from HDFS. **Refuse to start if `tokenizerVersion` ≠ `Tokenizer.TOKENIZER_VERSION`.**
2. Scan `terms` into a `HashMap<String, Long>` holding df.
3. Scan `docs` (only `len`, `norm`, `pr`) into a `HashMap<Long, DocStat>`. Simplewiki has about 250k documents, so this takes seconds.

**`search(q, model, pr, k)`:**
1. `terms = tokenizer.uniqueTerms(q)`. Split them into known terms (present in the lexicon) and unknown terms; report the unknown ones.
2. For each known term, run a prefix scan on `postings`:
   - `setStartStopRowForPrefixScan` (or `setRowPrefixFilter`), caching 5000, family `p` only.
   - Parse the docID from the rowkey suffix and tf from the value.
3. Accumulate per document: matched-term count, TF-IDF sum and BM25 sum.
4. **Scoring** (`bda.common.Scorer`, identical to `eval_msmarco.py`):
   - **Boolean:** documents where matched = number of known terms, ordered by docID. Return the total count. (Unknown terms make an AND query return nothing; say so in the response.)
   - **TF-IDF:** Σ ln(N/df)² × (1 + ln tf) / norm. That's cosine with query weight = idf; the query norm is dropped because it's constant per query.
   - **BM25:** Σ idf × tf × (k1 + 1) / (tf + k1 × (1 − b + b × len / avgdl)), with idf = ln(1 + (N − df + 0.5)/(df + 0.5)). Defaults k1 = 0.9, b = 0.4; both can be overridden per request.
   - **PageRank boost** (BM25 only, when `pr=1`): add w × ln(pr × N), with w = 0.5 (a request parameter). Skip it when pr = 0.
5. Take the top k with a `PriorityQueue`, ties broken by docID ascending.
6. Batch-`Get` `d:title` and `d:snippet` for those results.
7. Return JSON including timings (tokenise, scan, score, fetch, total in ms) and the number of candidate documents. A small hand-written JSON writer avoids dependency clashes.

**Cross-language consistency test:** `ScorerTest` pins exact values for a toy example (N = 10, avgdl = 5, two terms with given df/tf/len/norm). `eval_msmarco.py` runs the same toy example at startup and asserts it gets the same values within 1e-9.

**`QueryServer`:**
- `com.sun.net.httpserver.HttpServer` on port 8080, with a 4-thread pool.
- `GET /` → `index.html`.
- `GET /search?q=&model=bm25|tfidf|boolean&pr=0|1&k=10[&k1=&b=&w=]` → JSON.
- **Run:** `bda-run.sh queryserver java -cp target/bda-search-1.0.jar:$(hbase classpath) bda.serve.QueryServer --port 8080`. It must be detached, or it dies when the `wsl.exe` call returns. Open `http://localhost:8080` in the Windows browser (WSL2 forwards localhost).

**`index.html`** (vanilla HTML and JavaScript, no framework):
- Query box, model dropdown, PageRank checkbox, and 5 example-query chips.
- Results: the title links to the Simple English Wikipedia URL, with the snippet and the score.
- A status line: "*n* results · *t* ms (scan *a* · score *b* · fetch *c*)". A clear message for empty, stopword-only and no-match queries.

**`QueryCli`:** same service, printing to the terminal; `--tokenize "<text>"` mode. This is the demo fallback.

**Latency test:** `eval/latency.py`. Uses 50 queries from `eval/latency_queries.txt` (written by Claude Code: rare single terms, common single terms, and 2–4-term queries), times 3 models, does one warm-up query, and records p50/p95 to `charts/data/latency.tsv`.

**Acceptance:**
- S4 is met.
- Edge cases behave: an empty query, a stopword-only query ("the of"), and a query with unknown terms.
- The PageRank toggle visibly changes the ranking for at least 3 queries. Save them to `eval/pagerank_examples.md`.

**Fallback:** common terms too slow → keep the posting lists of the 1,000 highest-df terms in memory, warmed at startup. Document it.

### WP11 — MS MARCO evaluation (3 h active, plus unattended runs)

**Depends on:** WP6 code, WP3 (`TokenizeQueries`), WP8 (`tfidf_norms.py`). HBase stays stopped.

**Steps:**
1. **Index MS MARCO**, unattended: `bda-run.sh index-msmarco bin/run-index.sh msmarco` (Jobs 2 → 1 → 3, 6 reducers; on Profile B only two or three run at a time, which is fine), then `bda-run.sh norms-msmarco spark-submit spark/tfidf_norms.py msmarco`. Expect 30–90 minutes on Profile A and longer on B. Keep the laptop awake and a `bda-status.sh --watch` tab open (§5.10). Record the times and counters.
2. **Prepare the queries:**
   1. Filter `eval/queries.dev.small.tsv` and `eval/qrels.dev.small.tsv` to the qids in `eval/sample_qids.txt`. Write `eval/queries_sample.tsv` (`qid \t text`; expect 1,000 lines) and `eval/qrels_sample.tsv` (`qid 0 pid rel`, TREC qrels format with spaces).
   2. Tokenise with `java -cp target/bda-search-1.0.jar bda.tools.TokenizeQueries eval/queries_sample.tsv eval/queries_sample.tok.tsv`.
   3. Upload the tokenised file to `/bda/msmarco/eval/`.
   4. Log how many queries end up with zero terms (expected: about 0).
3. **`spark/eval_msmarco.py`** (`spark.sql.shuffle.partitions=64`):
   ```python
   # self-test: same toy example as ScorerTest → assert equal (1e-9)
   qt = (spark.read.csv(qtok, sep="\t").toDF("qid", "terms")
         .withColumn("term", F.explode(F.split("terms", " "))).select("qid", "term").distinct())
   nq = qt.groupBy("qid").agg(F.count("*").alias("nq"))
   vocab = qt.select("term").distinct()
   post = (spark.read.csv(postings, sep="\t").toDF("term", "df", "cf", "plist")
           .join(F.broadcast(vocab), "term")
           .withColumn("p", F.explode(F.split("plist", ",")))
           .select("term", F.col("df").cast("long"),
                   F.split("p", ":")[0].cast("long").alias("docid"),
                   F.split("p", ":")[1].cast("int").alias("tf")))
   w = (post.join(docstats, "docid").join(norms, "docid")            # docstats: docid,len ; norms: docid,norm
        .withColumn("idf",   F.log(1 + (N - F.col("df") + 0.5) / (F.col("df") + 0.5)))
        .withColumn("bm25a", F.col("idf") * F.col("tf") * 1.9 / (F.col("tf") + 0.9 * (1 - 0.4 + 0.4 * F.col("len") / avgdl)))
        .withColumn("bm25b", F.col("idf") * F.col("tf") * 2.2 / (F.col("tf") + 1.2 * (1 - 0.75 + 0.75 * F.col("len") / avgdl)))
        .withColumn("tfidf", F.pow(F.log(N / F.col("df")), 2) * (1 + F.log("tf")) / F.col("norm")))
   scores = (qt.join(w, "term").groupBy("qid", "docid")
             .agg(F.sum("bm25a").alias("bm25a"), F.sum("bm25b").alias("bm25b"),
                  F.sum("tfidf").alias("tfidf"), F.count("*").alias("matched"))
             .join(nq, "qid").persist(StorageLevel.MEMORY_AND_DISK))
   # for each model: rank with Window.partitionBy("qid").orderBy(desc(score), "docid"); keep rank <= 100
   # boolean: filter(matched == nq), order by docid asc, rank <= 100
   # write TREC runs: qid Q0 docid rank score tag  → HDFS → hdfs dfs -getmerge → eval/runs/<model>.trec
   ```
   - `F.log(x)` with one argument is the natural log.
   - Expected scale: around 0.3 billion `(qid, doc)` pairs for 1,000 queries; budget 30–60 minutes on Profile A, possibly double on B. Run it unattended with `bda-run.sh eval-msmarco spark-submit --conf spark.sql.shuffle.partitions=64 spark/eval_msmarco.py`, with HBase and JobHistory stopped.
4. **`eval/metrics.py`:**
   1. Load `qrels_sample.tsv`.
   2. For each run:
      - Truncate to the top 10, then use `pytrec_eval` with `recip_rank` → MRR@10, and `P_10`.
      - Use the top 100 for `recall_100`.
   3. **Average over all 1,000 sample qids.** `pytrec_eval` only scores qids that appear in the run, so a query with no results must be added as 0.
   4. Paired t-tests (`scipy.stats.ttest_rel` on per-query RR):
      - BM25(0.9/0.4) vs TF-IDF
      - BM25(0.9/0.4) vs BM25(1.2/0.75)
   5. Write `eval/results.md`: model | params | MRR@10 | P@10 | R@100 | % of queries with zero results | runtime. Also write per-query CSVs for the charts.
5. **Reference point:** Anserini, the standard Lucene-based research toolkit, reports BM25 MRR@10 = 0.187 on the full dev/small set (with tuned k1 = 0.82, b = 0.68). Our sample and our parameters will land somewhat differently, so the 0.15–0.20 range in S5 allows for that. **If BM25 MRR@10 < 0.15, debug in this order:**
   1. Meta tokeniser version.
   2. N and avgdl taken from the **msmarco** meta, not wiki.
   3. df taken from the msmarco lexicon.
   4. qid/pid types (plain strings, no padding).
   5. No duplicate docIDs per query in a run.
   6. Trace one query by hand: show its top 10, and the relevant passage's score and rank.
6. **Stretch:** all 6,980 queries overnight, with the same script and every qid.

**Acceptance:** S5; `results.md` complete; runtimes logged.

**Fallback:** too slow or out of memory →
- use the first 500 of the sample (documented); or
- prune query terms with df > 0.2·N (a documented approximation).
Report which one was applied.

### WP12 — Charts, report, demo (3.5 h)

**Depends on:** everything above.

1. **`charts/make_charts.py`:** produce every PNG in §10.2 from `charts/data/`, `eval/`, and the build-log numbers.
2. **Report:** follow §10.1. Teammates can draft sections from `build-log.md` in parallel as soon as each work package finishes.
3. **`bin/demo-start.sh`:** check the mount → `bda-start.sh all` (waits for the HBase master) → start `QueryServer` with `bda-run.sh` → poll `http://localhost:8080/` until it answers → print the URL. Rehearse §9 once from a cold start and time it (S7).

**Acceptance:** S7 and S8; the PDF exported; the demo rehearsed.

---

## 9. Demo script (6–7 minutes)

**Before the demo:**
- Laptop plugged in; `bin/demo-start.sh` run 5 minutes earlier.
- Browser tabs open: search page (`:8080`), HDFS UI (`:9870`), YARN (`:8088`), JobHistory (`:19888`), HBase Master (`:16010`).
- A terminal open in `~/bda/repo`.

| Time | Show | Say |
|---|---|---|
| 0:00 | Architecture diagram (§4.1) | Each stage, and the tool that does it |
| 0:40 | HDFS UI → `/bda/raw/simplewiki` | The dump stays bzip2. Hadoop splits it, so N mappers read it in parallel (show the split test numbers). |
| 1:30 | JobHistory → Job 1 counters | Secondary sort; the combiner cut shuffle volume by X% |
| 2:30 | Search page: `photosynthesis` with BM25, then TF-IDF, then Boolean | How the three models differ; the timing line |
| 3:30 | A multi-term query, then the PageRank toggle (an example from `pagerank_examples.md`) | The ranking moves as link authority is added |
| 4:15 | Terminal: `scan 'postings', {ROWPREFIXFILTER => '<term>#', LIMIT => 5}` | Rowkey design: one term = one contiguous prefix scan |
| 4:45 | Saved Hive outputs + the Zipf chart | Corpus statistics; Zipf's law holds |
| 5:15 | `eval/results.md` + the MRR chart | BM25 > TF-IDF > Boolean on MS MARCO; close to the published baseline |
| 6:00 | PageRank top 10 + one K-means cluster | Link analysis and topic structure |

**Fallbacks:**
- Web page fails → use `QueryCli`.
- HBase fails → show the screenshots and the evaluation (it doesn't depend on HBase).
- Live Hive is too slow → show the saved output.

---

## 10. Report

### 10.1 Outline (target 12–18 pages plus appendices; adjust to the brief)

1. **Abstract** (≈150 words)
2. **Introduction and objectives**
3. **Syllabus mapping** (the table from §3.2)
4. **Architecture:** diagram, data flow, and the design-choices table (§4.2)
5. **Environment:**
   - Hardware; WSL2 setup; versions.
   - Memory and disk layout, and why.
   - **Ecosystem drift and fixes:** Hive 4.2 requires JDK 21 while HBase is only officially tested up to JDK 17; the `--add-opens` flags needed; VHDX for HDFS because of WSL's Windows-drive bridge.
6. **Data:** both corpora, sizes, licences; Pig cleaning results (category counts, stub threshold)
7. **Ingest and HDFS:** bz2 splittability; split-test results (N mappers vs 1); HDFS layout; replication = 1 and why
8. **Index construction (MapReduce):**
   - `XmlInputFormat`.
   - Jobs 1–3, with a secondary-sort diagram.
   - The combiner experiment (table + chart); counters.
   - Integrity checks (Σcf = tokens).
9. **Corpus statistics (Hive):** vocabulary, hapax share, Zipf (a fitted slope via `numpy.polyfit` on log–log ranks ≤ 10,000), document lengths
10. **Serving (HBase + query server):** schema, rowkey reasoning, pre-splitting from Hive quantiles, load performance, query flow, latency results
11. **Retrieval models:** the formulas (§WP10), with intuition for each
12. **Link analysis and clustering:** PageRank method (dangling nodes, damping), convergence, top pages, the boost; K-means setup and clusters
13. **Evaluation:**
    - Protocol: dev/small, a fixed 1,000-query sample with seed 42, MRR@10 as the main metric, and why.
    - The results table and significance tests.
    - Discussion: term-frequency saturation and length normalisation; Boolean's zero-result problem; comparison with the published ~0.18 baseline.
14. **Limitations and future work:**
    - Limitations: a single node; simplewiki rather than full enwiki; no positional index or phrase queries; the query sample; no fit for Unit 7.
    - Future work: full enwiki, multi-node, WAND/MaxScore early termination, learned re-ranking.
15. **Conclusion**

**References. Appendix A:** configs. **Appendix B:** `bin/run-*.sh` commands. **Appendix C:** screenshots.

### 10.2 Charts (`charts/make_charts.py` → `charts/*.png`)

| # | Chart | Data source |
|---|---|---|
| C1 | Zipf rank–frequency, log–log, with fitted slope | Hive `zipf.tsv` |
| C2 | Document-length histograms (simplewiki, MS MARCO) | Hive exports |
| C3 | Combiner experiment: records and shuffle bytes, on vs off | build-log counters |
| C4 | Job 0: mappers and wall time, splittable vs single split | build-log |
| C5 | MRR@10 / P@10 / R@100 by model (grouped bars) | `results.md` |
| C6 | Per-query reciprocal rank, BM25 vs TF-IDF (scatter) | per-query CSVs |
| C7 | PageRank: L1 change (log y-axis) and time per iteration | `pagerank_log.csv` |
| C8 | PageRank top 15 (horizontal bars with titles) | `pagerank_top50.tsv` |
| C9 | K-means cluster sizes, labelled with top terms | `kmeans_k20.md` data |
| C10 | Query latency p50/p95 by model and query type | `latency.tsv` |
| C11 | df distribution by order of magnitude | Hive export |

**Screenshots:** YARN with a running job; JobHistory counters; the HDFS browser; a Hive query on Tez; an HBase shell scan; HBase regions; the search page in each of the three models.

---

## 11. Viva preparation (likely questions, short answers)

1. **Why Hadoop for a 300 MB corpus?** The pipeline is built for scale. The same code indexes MS MARCO (8.8M passages) and would run unchanged on full English Wikipedia (26 GB); we used simplewiki because of time, not because of a design limit.
2. **Why is bzip2 splittable and gzip not?** bzip2 compresses independent blocks, each starting with a recognisable marker, so a reader can begin at any block boundary. gzip is a single DEFLATE stream with no sync points.
3. **What is secondary sort here?** The composite key is `(term, docID)`. The partitioner routes by term, so a term lands on one reducer. The grouping comparator groups by term, so there's one `reduce()` call per term. The shuffle sorts by the full key, so docIDs arrive in order and the reducer never sorts a list itself.
4. **Why is the combiner safe?** Summing is associative and commutative, and Hadoop may run the combiner zero or more times. The reducer re-sums repeated docIDs, so the result is correct either way.
5. **Why a custom partitioner?** The default partitioner hashes the whole key, which would scatter one term's postings across several reducers.
6. **Why rowkey `term#docID` and not one row per term?** A term's postings become one contiguous prefix scan. Rows stay small, instead of rows with hundreds of thousands of columns for common terms, and the docID padding keeps them in numeric order.
7. **Why pre-split?** Term frequencies are Zipf-distributed. Split points come from Hive quantiles of cumulative df, so regions hold similar amounts of data and loading doesn't hammer a single region.
8. **Why HBase for serving instead of reading HDFS files?** HBase gives low-latency random reads by key, while HDFS is built for large sequential scans.
9. **Why does BM25 beat TF-IDF?** Term-frequency saturation (k1) stops repeated terms dominating; length normalisation (b) corrects for long documents; and its idf is grounded in the probabilistic relevance model.
10. **Why MRR@10 and not nDCG?** dev/small has about one judged-relevant passage per query, so the rank of the first hit is the meaningful measure. nDCG adds little with binary, single judgments.
11. **Why 1,000 sampled queries?** Compute limits on a laptop. With a fixed seed, the standard error is about ±0.01 MRR, which is enough to separate the models; paired t-tests confirm the differences.
12. **Why Spark for PageRank?** It's iterative: Spark caches the graph in memory, while chained MapReduce jobs re-read it from HDFS on every iteration.
13. **What if query and index tokenisation differ?** Terms stop matching and recall collapses silently. We have one tokeniser class, and a version stamp in the index metadata that the server checks at startup.
14. **Why replication = 1?** There's a single DataNode, so there's nowhere to put extra replicas; on a real cluster we'd use 3.
15. **Why Tez for Hive?** It runs queries as a DAG, avoiding the intermediate HDFS writes that chained MapReduce stages need. It's Hive 4's default engine.
16. **How do you handle dangling nodes in PageRank?** Their rank mass is spread evenly across all nodes each iteration, so total rank stays at 1 (we check Σpr ≈ 1).
17. **Why not use Lucene or Elasticsearch?** The syllabus asks us to implement and evaluate a search engine. We borrow only the Porter stemmer class; indexing, storage, scoring and evaluation are ours.
18. **How would this scale to many nodes?** Raise replication, add DataNodes and RegionServers, use more reducers, bulk-load HBase from HFiles, and add early-termination query processing (WAND).
19. **Pig vs Hive roles?** Pig handles procedural dataflow cleaning (filter, project, branch into several outputs). Hive handles declarative analytics and joins over tables.
20. **What was the hardest bug?** Answer from `build-log.md`'s "Issues & fixes". Have one concrete story ready.

---

## 12. Schedule (about 24 working hours over Sat–Tue; deadline Fri 23 Oct)

Mit's free time is about 24 hours over four days, so the plan still finishes the must-haves by **Tue 20 Oct**. Wed–Thu absorb slippage first; any time left after that goes to stretch items and polish. **Aim to submit by Thursday night**; Friday is a safety margin, not a work day.

**Overnight runs** (they cost no working hours):
- The MS MARCO index runs overnight after Day 2.
- The MS MARCO evaluation (1,000 queries) runs overnight after Day 3.
- Optional: the full 6,980-query evaluation runs overnight after Day 4.
- For all of them: start with `bda-run.sh`, laptop plugged in, sleep off, lid open, `/data` mounted, HBase stopped, a `bda-status.sh --watch` tab open (§5.10).

| Day | Work packages (hours) | End-of-day checkpoint |
|---|---|---|
| **Day 1 — Sat 17 Oct** (evening, ~4.5 h) | WP1 (3.5); WP2 starts as soon as HDFS is up; WP3 (1, or a teammate in parallel) | Stack verified; both corpora in HDFS; sample qids committed; tokeniser tests green |
| **Day 2 — Sun 18 Oct** (~6 h) | WP4 (2), WP5 (0.75), WP6 on simplewiki + combiner experiment (2.5), WP7a (0.75) | S1–S3 met; Hive stats and split keys exported. **Start the MS MARCO index (WP11 step 1) before sleeping.** |
| **Day 3 — Mon 19 Oct** (~6 h) | Check the overnight index; WP8 (2), WP7b (0.25), WP9 (1.5), WP10 (2), WP11 step 2 (0.25) | Search page works (S4, S6). **Start `eval_msmarco.py` before sleeping.** |
| **Day 4 — Tue 20 Oct** (~6 h) | WP11 steps 4–5 (0.75), WP12: charts (1), report (3), demo rehearsal (0.5), buffer (0.75) | S5, S7, S8 met; complete report draft. Optionally start the full 6,980-query evaluation before sleeping. |
| **Wed 21 – Thu 22 Oct** (buffer; only Mit's spare time) | 1. Finish anything that slipped. 2. Stretch items in §3.3 order (the full evaluation first). 3. Report polish and a second demo rehearsal. | **Submit by Thursday night** |
| **Fri 23 Oct** | Deadline. Safety margin only. | — |

**If behind by more than 1.5 hours at a checkpoint, cut in this order:**
1. Every stretch item (never start them).
2. The K-means k = 10 comparison and silhouette.
3. The latency test, reduced to 20 queries.
4. The Job 0 split-mode comparison (keep the grep check for S1).
5. Hive-based pre-splitting (use fixed alphabetical split keys instead).
6. K-means entirely (keep PageRank).

**Never cut:** Jobs 0–3; HBase + the query server with BM25; the MS MARCO MRR@10 result; the report.

Items cut during Sat–Tue can come back on Wed–Thu, in reverse cut order, if there's time.

---

## 13. Risk register

| ID | Risk | Likelihood / Impact | Early signal | Mitigation → fallback |
|---|---|---|---|---|
| R1 | JDK 21 incompatibility (Pig and Hive/Tez still untested; Hadoop, Spark and HBase passed) | L / H | `InaccessibleObjectException` in logs | `--add-opens` flags → JDK 17 + Hive 4.1.0; Pig client on JDK 8 |
| R2 | Hive 4 / Tez / metastore setup fails | M / M | `schematool` or Tez AM errors | 30-minute box → MR engine if accepted → Spark SQL for the statistics |
| R3 | bz2 split-boundary bug | M / M | Page count ≠ grep count | Single split (`bda.xml.splittable=false`) |
| R4 | `wikiclean` fails or is slow | L / M | High `CLEAN_FALLBACK` count | Regex cleaner for all pages |
| R5 | Memory pressure / killed containers (Windows uses 11.6 GB normally) | **H** / H | Exit code 137, heavy swapping, Windows becoming sluggish | Lean-Windows checklist (§5.1); Profile B; stop HBase and JobHistory during big jobs; fewer or smaller executors |
| R6 | SSD fills up (only 78 GB free on C:) | M / H | Free space on `/` < 15 GB | Clear `/bda/tmp`, spark-tmp and the `~/smoke` tarballs; move YARN local dirs to `/data` |
| R7 | `/data` not attached after a reboot | M / M | `bda-start.sh` refuses to start | Run the `bda-mount-vhdx` task; manual mount command |
| R8 | MS MARCO evaluation too slow | M / H | A Spark stage over 60 minutes | 500 queries, or df pruning (documented) |
| R9 | BM25 well below the baseline | M / H | MRR@10 < 0.15 | WP11 step 5 checklist |
| R10 | HBase load too slow | L / M | Projected > 30 minutes | Bulk load from HFiles |
| R11 | Laptop sleeps or loses power mid-job | M / M | Job missing from JobHistory | Plugged in, sleep off; every job can be re-run from its script |
| R12 | Time overrun | H / H | Checkpoint behind | Cut order (§12) |
| R13 | Download blocked on campus network | L / M | `wget` fails | Mirror, home network, or hotspot |
| R14 | Numbers missing at report time | M / H | Empty build-log entries | Log at the end of every work package (§0) |
| R15 | Daemons or jobs die when the `wsl.exe` call returns (SIGHUP), or WSL stops an idle distro overnight | **H** if unmitigated / H | `RECEIVED SIGNAL 1: SIGHUP` in logs; `jps` empty in the morning | `setsid` for everything long-lived, via `bda-start.sh` / `bda-run.sh`; a `bda-status.sh --watch` tab open; fallback: systemd units |
| R16 | HBase master aborts on a ZooKeeper session expiry under load | M / M | `received expired from ZooKeeper` in the master log | 90 s session timeout + tickTime 6000 (§5.6); never run Spark while HBase loads; restart HBase and re-run the loader (it's idempotent) |
| R17 | A GitHub CDN address doesn't answer from WSL (seen in the preflight for `185.199.109.133`) | M / L | Download stuck in `SYN-SENT` | `curl --connect-timeout 15 --resolve release-assets.githubusercontent.com:443:185.199.108.133,185.199.110.133,185.199.111.133`; WSL has no IPv6, so don't try `-6` |
| R18 | PowerShell → `wsl.exe` quoting silently changes a command | M / M | A script behaves oddly (the preflight lost the "r" in `resourcemanager`) | Linux steps only as `.sh` files (§0 rule 8) |
| R19 | MS MARCO hosting changes again | L / M | HTTP 4xx from the download loop | The source order in §6.2 |
| R20 | Windows MySQL80 / PostgreSQL 18 services hold ports 3306 / 5432 on Windows | L / L | A Windows tool reaches the wrong database | Stop them while working. Hive connects from *inside* WSL, so it always reaches WSL's own PostgreSQL. |

---

## 14. Team and roles

**Default:** Mit runs everything on his laptop with Claude Code. Teammates contribute through the repo, without needing a cluster.

| Role | Owner | Work | Develops against |
|---|---|---|---|
| A — Platform and integration | Mit | WP1, WP2, WP4, all runs on the laptop, WP9, demo | The real stack |
| B — Indexing | `<teammate>` | WP3, Job 1–3 code, unit tests | Maven + MapReduce local mode (`-D mapreduce.framework.name=local -D fs.defaultFS=file:///`) on a sample |
| C — Spark and evaluation | `<teammate>` | WP8 scripts, `eval_msmarco.py`, `metrics.py` | PySpark `local[*]` on sample files |
| D — Analytics and report | `<teammate>` | WP5/WP7 scripts, charts, report skeleton, viva sheet | Hive/Pig syntax on samples; charts from exported TSVs |

**Interfaces between roles:** §7.1 (tokeniser) and §7.3 (record formats). Those two are the contract.

**Samples:** after WP5, Mit exports 2,000 docs, a 1% postings sample and the matching lexicon and meta files to a shared folder.

**Repo:** one private GitHub repository with small commits. Mit pulls and runs on the laptop.

---

## 15. Definition of done

- [ ] §2 filled in; any official brief reconciled with this plan
- [ ] WP1: all services verified; `setup-log.md` complete
- [ ] WP2: data verified; `sample_qids.txt` committed
- [ ] WP3: tokeniser tests green; `TOKENIZER_VERSION` in every meta file
- [ ] WP4: S1 met; counters logged
- [ ] WP5: category counts add up; stub threshold justified
- [ ] WP6: S2 and S3 met on simplewiki; MS MARCO index built
- [ ] WP7: statistics exported; split keys saved; `docs_full` built
- [ ] WP8: norms (both corpora), PageRank (Σ ≈ 1), K-means outputs
- [ ] WP9: three HBase tables loaded; row counts verified
- [ ] WP10: search page works with all three models + PageRank; S4 met; edge cases handled
- [ ] WP11: `results.md` with all models; S5 met; significance tests run
- [ ] WP12: C1–C11 produced; screenshots taken; report PDF exported; demo rehearsed from a cold start (S7)
- [ ] Repo committed and tagged `v1.0`; `build-log.md` has every number the report quotes (S8)
