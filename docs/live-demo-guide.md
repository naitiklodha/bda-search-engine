# Live demo guide: showing the search engine working in real time

*Use this to show your professor the system live. Every part below works and was rehearsed on 22 Oct: cold start to first search took 103 s.*

## What "real time" means in this demo

Some jobs take an hour or more: indexing 8.8 million MS MARCO passages, and the 1,000-question evaluation. Those are **run beforehand**. In the demo you show their saved results and their counters in JobHistory. Say this upfront: it's how real clusters are demonstrated too.

What runs **live, in front of the professor**:
1. **Cold start:** the whole cluster comes up from nothing with one command (rehearsal: ready in 94 s).
2. **A real MapReduce job:** it tokenises and counts 2,000 passages. You can watch it appear and finish in the YARN web page (about 20 s).
3. **A live Hive SQL query** running as a Tez job (about 20 s).
4. **A live HBase lookup** of a word's posting list.
5. **The search page:** every search is answered live from HBase (typically 40–70 ms), with three ranking models and a PageRank switch.

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
2. **Start everything with one command** (normal PowerShell). This checks the data disk, starts HDFS, YARN, JobHistory, HBase and the search server, then opens the browser tabs:
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
   demo-live.sh search "solar energy"
   ```
   Also do one search on the search page.

**Browser tabs that `demo-start.sh` opens:**

| Tab | Address | Shows |
|---|---|---|
| Search page | http://localhost:8090 | The search engine itself |
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
| 2:40 | **Search page** | Search `photosynthesis` with BM25, then TF-IDF, then Boolean | "Three ranking models over the same index. The status line shows the time spent on each step." |
| 3:30 | Search page | Search `river` with BM25, then tick **PageRank** | "Plain BM25 returns lists of rivers; adding link authority brings up *River*, *Water*, *Mississippi River*." (more in `eval/pagerank_examples.md`) |
| 4:15 | Terminal | `demo-live.sh hbase photosynthesis` | "Rowkey = term#docID, so one word's postings are one fast prefix scan." |
| 4:45 | Terminal | `demo-live.sh hive photosynthesis` | "A live SQL query on Tez: how many articles use this word." Then show the Zipf chart: "52% of words occur only once." |
| 5:20 | `eval/results.md` + `charts/C5_eval_models.png` | — | "On 1,000 real MS MARCO questions: BM25 MRR@10 0.198 vs TF-IDF 0.127 vs Boolean 0.058, all differences significant. The published Lucene baseline is 0.187, so we match it within sampling error." |
| 6:00 | Terminal | `demo-live.sh pagerank`, then `demo-live.sh clusters` | "PageRank over 3.27 million links: United States, United Kingdom, France on top. K-means found 20 topics from vocabulary alone: chemistry, football, video games…" |
| 6:45 | — | — | Questions |

**Other quick live commands:**
- `demo-live.sh term running` → shows `run` (how the tokeniser stems words).
- `demo-live.sh search "solar energy"` → all three models' top 5 in the terminal, with timings.
- `demo-live.sh status` → everything that's running, plus disk and memory.

## Part C2: Explaining as you go

**The habit:** most steps have a short wait. *Talk during the wait* about what the system is doing. When the output appears, *point at one number*. The numbers below come from the full rehearsal on 22 Oct, so you'll see the same ones.

**`demo-start.sh` (about 90 s)**
- *While it runs*, walk through the architecture (report §4):
  - "HDFS stores the files across the cluster."
  - "YARN hands out CPU and memory to jobs."
  - "HBase holds the index for fast lookups."
  - "A small Java server answers the searches."
- *Then point at* `demo ready in 91 s`: "The target was under 5 minutes."

**`demo-live.sh mr` (about 30 s)**
- *While it runs*, switch to the YARN tab to show the job:
  - "Map: each worker splits its passages into words and writes (word, 1) for every word."
  - "Combiner: adds those up on the same machine, before anything is sent."
  - "Shuffle: brings each word's counts together in one place."
  - "Reduce: adds up the final totals."
- *Then point at* `Combine input records=74488` → `Combine output records=10410`: "The combiner cut what goes over the network by 86%."
- The top words are *you, your, from*: "MS MARCO is question-and-answer text."

**Search `photosynthesis` (instant)**
- *Point at* the status line, e.g. `scan 9 · score 1 · fetch 12 ms`: "Almost all the time is reading word lists from HBase. Scoring takes about 1 ms."
- *Compare the three models:*
  - BM25 puts *Photosynthesis* first.
  - TF-IDF puts *Plant community* first: "it over-rewards short pages."
  - Boolean gives every match a score of 1.0, in ID order: "Boolean can only say yes or no. It can't rank."

**Search `river`, then tick PageRank (instant)**
- *Without PageRank:* "List of rivers of New Zealand / Brazil / Alaska". "That's a pure text match, and lists repeat the word."
- *With PageRank:* River, Water, China, Tributary, Mississippi River. "Pages that many others link to rise to the top."

**`demo-live.sh hbase photosynthesis` (about 15 s; mostly the HBase shell starting)**
- *While it runs:* "The word is stored as `photosynthesi`, because the stemmer cuts word endings. So 'photosynthetic' finds the same pages."
- *Then point at* the rowkeys `photosynthesi#0000000101`, `…108`, `…218`, which are in order:
  - "The rowkey is word#docID, kept sorted, so one word's whole list is one contiguous read."
  - "The value is how many times the word appears: 7 times in document 101, *Biology*."

**`demo-live.sh hive photosynthesis` (about 30 s)**
- *While it runs:* "Hive turns this SQL into a Tez job on YARN. Most of the 20 s is start-up."
- *Then point at* `photosynthesi | 157 | 258`: "157 articles contain it, 258 times in all. 157 is the same result count the search page showed."

**`demo-live.sh pagerank` (instant)**
- "Spark ran 10 iterations over 3.27 million links. Countries and big topics come out on top because everyone links to them."

**`demo-live.sh clusters` (instant)**
- "K-means sorted 210,000 articles into 20 groups using only their words: football, music, film, video games, politics, war, disease…"
- Be upfront about cluster 8: "One cluster is just 2 near-empty pages. The other 19 are real topics."

**`eval/results.md` + C5 chart**
- "BM25 scored 0.198 on 1,000 real questions; the published Lucene baseline is 0.187. Our own MapReduce index and scorer reproduce the standard result."

**For "why" questions:**
- `project-explained.md` has the plain-English version of every step.
- Report §4, *Key design choices*, gives a one-line reason for each design decision.
- Plan §11 has 20 likely viva questions with answers.

## Part D: If something goes wrong

| Problem | Do this |
|---|---|
| Search page won't load | Use the terminal instead: `demo-live.sh search "query"`. To restart only the server: `bda-stop.sh hbase`, then re-run `demo-start.sh` |
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
