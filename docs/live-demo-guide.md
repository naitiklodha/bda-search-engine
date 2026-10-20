# Live demo guide: showing the search engine working in real time

*Use this to show your professor the system live. Parts marked ⏳ arrive with WP9–WP11 (Wednesday/Thursday). Everything else works today.*

## What "real time" means in this demo

Some jobs take an hour or more: indexing 8.8 million MS MARCO passages, and the 1,000-question evaluation. Those are **run beforehand**. In the demo you show their saved results and their counters in JobHistory. Say this upfront: it's how real clusters are demonstrated too.

What runs **live, in front of the professor**:
1. **Cold start:** the whole cluster comes up from nothing with one command (about 1.5–2 minutes).
2. **A real MapReduce job:** it tokenises and counts 2,000 passages. You can watch it appear and finish in the YARN web page (about 20 s).
3. **A live Hive SQL query** running as a Tez job (about 20 s).
4. **A live HBase lookup** of a word's posting list ⏳ (WP9).
5. **The search page:** every search is answered live from HBase, with three ranking models and a PageRank switch ⏳ (WP10).

---

## Part A: Settings, the day before

1. **Power:** Settings → System → Power → *When plugged in, put my device to sleep after* → **Never**. Set the lid-close action to *Do nothing* when plugged in.
2. **No interruptions:** turn on **Do not disturb** (notifications off), and **pause Windows Update** for a week (Settings → Windows Update → Pause).
3. **Readability:** browser zoom 125%, and a larger font in the Windows Terminal profile.
4. **Rehearse once,** following Parts B and C, and time it. The plan's target (S7) is cold start to first search in under 5 minutes.

## Part B: Fifteen minutes before

1. **Lean Windows** (step 1 of `startup-commands.md`):
   - Quit ChatGPT, Copilot, WhatsApp, qBittorrent and Docker from their tray icons.
   - In **Admin PowerShell**, stop the Windows databases:
     ```powershell
     Stop-Service MySQL80, postgresql-x64-18 -ErrorAction SilentlyContinue
     ```
2. **Start everything with one command** (normal PowerShell). This checks the data disk, starts HDFS, YARN, JobHistory and HBase (plus the search server once it exists), then opens the browser tabs:
   ```powershell
   wsl -d Ubuntu -- bash -l /home/mit/bda/bin/demo-start.sh
   ```
3. **Open a Linux terminal in the project folder:** in Windows Terminal, open a new PowerShell tab and run:
   ```powershell
   wsl -d Ubuntu --cd /home/mit/bda/repo
   ```
   You're now in Linux. The demo commands below are typed **here**.
4. **Warm up** by running each once, so nothing is slow the first time in front of your professor:
   ```bash
   demo-live.sh status
   demo-live.sh hive photosynthesis
   ```
   Once WP10 exists, also do one search on the search page.

**Browser tabs that `demo-start.sh` opens:**

| Tab | Address | Shows |
|---|---|---|
| Search page ⏳ | http://localhost:8090 | The search engine itself |
| HDFS | http://localhost:9870 | Files in the distributed filesystem (*Utilities → Browse*) |
| YARN | http://localhost:8088 | Jobs running *right now* |
| JobHistory | http://localhost:19888 | Finished jobs and their counters (combiner numbers) |
| HBase | http://localhost:16010 | HBase tables and regions |

## Part C: The walkthrough (about 7 minutes)

| Time | Show | You do | You say |
|---|---|---|---|
| 0:00 | Architecture diagram (report / slide) | — | "Wikipedia dump → Hadoop splits it → Pig cleans → MapReduce builds an inverted index → Hive statistics, Spark PageRank and topics → HBase serves the search page. MS MARCO measures ranking quality." |
| 0:40 | **HDFS** tab → `/bda/raw/simplewiki` | Click into the folder | "The 342 MB dump stays compressed in HDFS. bzip2 can be split, so 12 mappers read it in parallel: 234 s instead of 427 s, with identical output." |
| 1:20 | **Terminal** | `demo-live.sh mr` → switch to the **YARN** tab while it runs | "This is a live MapReduce job on the cluster: it tokenises 2,000 passages and counts the words. You can see it running." |
| 2:00 | Terminal output, then **JobHistory** | Open the full index job (`bda-job1-postings`) and its *Counters* | "The combiner pre-totals on each worker. On the full Wikipedia index it cut the shuffle by 39.7% (371 MB vs 615 MB) with identical results." |
| 2:40 | **Search page** ⏳ | Search `photosynthesis` with BM25, then TF-IDF, then Boolean | "Three ranking models over the same index. The status line shows the time spent on each step." |
| 3:30 | Search page ⏳ | A multi-word query, then tick **PageRank** | "Adding link authority changes the order." (examples in `eval/pagerank_examples.md`) |
| 4:15 | Terminal | `demo-live.sh hbase photosynthesis` ⏳ | "Rowkey = term#docID, so one word's postings are one fast prefix scan." |
| 4:45 | Terminal | `demo-live.sh hive photosynthesis` | "A live SQL query on Tez: how many articles use this word." Then show the Zipf chart: "52% of words occur only once." |
| 5:20 | `eval/results.md` + MRR chart ⏳ | — | "On 1,000 real MS MARCO questions: BM25 beats TF-IDF, which beats Boolean, close to the published baseline." |
| 6:00 | Terminal | `demo-live.sh pagerank`, then `demo-live.sh clusters` | "PageRank over 3.27 million links: United States, United Kingdom, France on top. K-means found 20 topics from vocabulary alone: chemistry, football, video games…" |
| 6:45 | — | — | Questions |

**Other quick live commands:**
- `demo-live.sh term running` → shows `run` (how the tokeniser stems words).
- `demo-live.sh search "solar energy"` ⏳ → search results in the terminal.
- `demo-live.sh status` → everything that's running, plus disk and memory.

## Part D: If something goes wrong

| Problem | Do this |
|---|---|
| Search page won't load ⏳ | Use the terminal instead: `demo-live.sh search "query"` |
| HBase is down | Show the HBase screenshots in the report appendix, then go straight to the evaluation (it doesn't use HBase) |
| A Hive query is slow | Show the saved outputs in `charts/data/` and the charts |
| Everything is stuck | `bda-stop.sh all`, then re-run `demo-start.sh` (about 2 minutes). Keep talking through the architecture meanwhile. |

## Part E: Likely questions

Plan §11 has 20 likely viva questions with short answers. The most likely ones are:
- Why use Hadoop for a 300 MB corpus?
- Why is bzip2 splittable but gzip isn't?
- What is secondary sort?
- Why is the combiner safe?
- Why rowkey `term#docID`?
- Why does BM25 beat TF-IDF?
- Why MRR@10?

`project-explained.md` has the plain-English version of every step.

## Part F: Afterwards

Shut down as in `startup-commands.md`:
```powershell
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-stop.sh all
wsl --shutdown
```
