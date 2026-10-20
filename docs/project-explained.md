# The BDA Search Engine, in plain English

*Status: Tuesday 20 October 2026. Work packages WP1–WP8 are done for Simple English Wikipedia. Every number here comes from `repo-mirror/docs/build-log.md`.*

---

## 1. The big idea

We're building a **small Google** for the ~210,000 articles of Simple English Wikipedia:
- You type a question.
- The system finds the articles that match.
- It ranks them, best first.

Two things make it a *Big Data* project rather than an ordinary program:

1. **Every heavy step runs on Hadoop-style tools.** These are built to split work across many machines; here they run on one laptop. The same code would run unchanged on a real cluster with far more data.
2. **We measure how good the ranking is.** Microsoft's MS MARCO dataset has 8.8 million text passages and real search questions where humans marked the correct answer. We'll score our engine against it the way research papers do.

---

## 2. The tools, in everyday terms

| Tool | Everyday comparison | What it does for us |
|---|---|---|
| **WSL / Ubuntu** | A Linux computer living inside Windows | Big-data tools are made for Linux, so they all run in here |
| **HDFS** | A giant shared filing cabinet that cuts every file into 128 MB blocks | Stores the raw data and every intermediate result |
| **YARN** | A foreman handing out jobs to workers and sharing out the memory | Decides which task runs where, with how much memory |
| **MapReduce** | Many workers each handle a pile of pages (*map*), then the results are sorted and combined (*reduce*) | Splits Wikipedia into articles and builds the search index |
| **Pig** | A quick data-cleaning recipe language | Throws away redirects, stubs and non-article pages |
| **Hive (+ Tez)** | SQL, the database query language, but on files in HDFS. Tez is a faster engine underneath. | Corpus statistics (word counts, Zipf's law) and joining tables |
| **HBase** | A huge, super-fast lookup table | Will serve the index to the search page in milliseconds |
| **Spark** | MapReduce's faster cousin that keeps data in memory | TF-IDF weights, PageRank, topic clustering, and the big evaluation |
| **PostgreSQL** | A normal database | Holds Hive's *catalog*: which tables exist and where their files live |

---

## 3. Getting the laptop ready (WP1, plus the preflight)

**What we did**
- Checked the laptop could cope: 20 GB RAM, 4-core i5, SSD (C:) plus hard disk (D:).
- Switched on Windows' *Virtual Machine Platform* so Linux (WSL2) can run.
- Gave Linux **10 GB RAM, 6 CPU cores and 8 GB swap** ("Profile B"). That leaves enough for Windows and the Claude app.
- Created a **300 GB virtual disk on D:** (`D:\BDA\hdfs.vhdx`). All the bulky data lives there, keeping your C: drive under 10 GB.
- Installed and configured Java 21, Hadoop, Hive + Tez, HBase, Spark, Pig, PostgreSQL and Python.
- Wrote start/stop/status scripts and `startup-commands.md`, so a cold start takes **78 seconds**.

**What it means**
- One laptop now behaves like a one-machine cluster.
- Each daily start is a single command, and the data disk re-attaches itself after restarts.

**Problems solved along the way** (these make good report material on "real-world friction"):
- An NVIDIA helper service started crashing in a loop after the Windows change. It piled up hundreds of stuck processes until we disabled it.
- Background services died when the command that started them finished. We fixed that by starting them in their own session (`setsid`).
- HBase's start script expects a remote-login (SSH) server, so we start its parts directly instead.
- Hive's command-line tool doesn't work smoothly with Java 21. We wrapped it in a script that fixes this.

---

## 4. What we built, step by step

### WP2: Getting the data
- **Did:** Downloaded the Simple English Wikipedia dump (342 MB, compressed) and MS MARCO (1 GB). Checked the checksums (a file's "fingerprint") and put both into HDFS.
- **Means:** We know the files are complete and uncorrupted. Counting the `<page>` tags gives the ground truth: **562,214 pages**.
- **Also:** Picked a fixed random set of **1,000 test questions** (seed 42), so the evaluation is repeatable.

### WP3: The tokeniser (how text becomes words)
- **Did:** Wrote one Java class that turns any text into search terms. It:
  1. removes accents (café → cafe);
  2. lowercases everything;
  3. splits on anything that isn't a letter or digit;
  4. drops 33 tiny "stopwords" (the, of, and…);
  5. **stems** words to their root (running → run, dogs → dog).
- **Means:** "Running dogs" and "the dog runs" now match each other.
- **The key rule:** the *same* class is used when indexing, when searching, and when evaluating. If they differed, searches would silently miss things.
- **Proof:** 11 automatic tests pass. It handles **22,000 documents per second**.

### WP4: Job 0, splitting the dump into articles
- **Did:** Wrote a custom Hadoop "reader" that understands Wikipedia's XML format. Each article spans many lines, which a normal line reader would cut apart.
- **Did:** Read the *compressed* file in 12 pieces at once. bzip2 compresses in independent blocks, so each worker can unzip its own chunk. It's like a book where each chapter can be unzipped on its own.
- **Means:** Every page comes out exactly once: **562,214, matching the ground truth** (success criterion **S1**).
- **Speed:** **12 workers took 234 s; 1 worker took 427 s**, so the parallel version is **1.8× faster**.
- **Cleaning:** Wikipedia markup (`{{templates}}`, `[[links]]`, tables) is stripped into plain prose. We also pull out every link between articles (5.95 million), which PageRank needs later.

### WP5: Pig, keeping only real articles
- **Did:** Sorted the 562,214 pages into four groups:
  - **210,106 articles**
  - 75,372 stubs (under 200 characters)
  - 115,214 redirects
  - 161,522 other pages (talk pages, templates…)
- **Means:** Only real articles get indexed. The four counts add up to exactly 562,214, so nothing was lost.

### WP6: Building the index (the heart of the search engine)
- **Did:** Built an **inverted index**, just like the index at the back of a textbook. For every word, it lists which articles contain it and how many times. Example: `photosynthesis → article 1234 (5 times), article 9876 (2 times), …`
- **Three jobs:**
  - **Job 2** measures each article's length → N = 210,106 articles, 30.5 million words, 145 words on average.
  - **Job 1** builds the word → article lists (postings).
  - **Job 3** makes the dictionary of all **570,619 distinct words**.
- **Secondary sort:** Hadoop's shuffle delivers each word's articles *already in order*, like letters sorted before they reach the postman. The final step never has to sort anything itself.
- **Combiner:** each worker pre-totals its own tallies before sending them on.
  - It **cut the data moved between workers by 39.7%** (371 MB instead of 615 MB) with *identical* results (criterion **S3**).
- **Integrity checks (criterion S2):**
  - The total of all word counts equals the 30,510,204 words counted separately.
  - Every word in the dictionary has exactly one posting list.
  - A random sample of 4,963 lists is correctly sorted.

### WP7a: Hive, corpus statistics
- **Did:** Ran SQL over the index files.
  - **52% of all distinct words appear only once.** This is **Zipf's law**: a few words are extremely common, while most are rare.
  - The most common words are he, from, his, also, were…
- **Did:** Computed **15 "split keys"** that divide the dictionary into 16 shelves holding equal amounts of postings. HBase uses them so no single shelf gets overloaded.

### WP8: Spark analytics
- **TF-IDF norms:** A word matters to an article if it appears often *in that article* but rarely elsewhere. Each article gets a "size" number so long and short articles are compared fairly. 210,106 norms computed.
- **PageRank** (Google's original idea): a page is important if important pages link to it, like citations in research.
  - Run over **3.27 million links**, 10 rounds, each round **~10 s**.
  - Top pages: **United States, United Kingdom, France, Germany, Europe, England, India, Japan…**
  - The scores add up to exactly 1, as they should.
- **K-means clustering:** Sorted 209,083 articles into **20 topic piles** by vocabulary. Spark found chemistry, TV series, actors, football, basketball, music albums, films, frogs, French villages, Swiss municipalities, video games, medicine, wars and empires, politicians, and more, without being told any topics.

### WP7b: Joining it all for serving
- **Did:** Joined title + snippet + length + TF-IDF norm + PageRank into one table per article: **210,106 rows**, one for every article.

---

## 5. Problems found in the data, and how we fixed them

| Problem | Plain explanation | Fix |
|---|---|---|
| The cleaner found no text | Wikipedia changed its file format slightly; the cleaning library expected the old one, so 285,478 pages fell back to a cruder cleaner | Feed the library the old format. Only **143 pages** (0.03%) now need the fallback |
| Junk words at the top of the statistics | ~190 giant "List of minor planets" tables leaked code like `bgcolor` and `align` into the text | Strip tables and templates before cleaning. The vocabulary shrank from 743k to 571k real words |
| PageRank getting slower each round | Spark kept recomputing earlier rounds | Keep the data partitioned the same way and save a checkpoint each round: **10× faster** |
| Two "topics" with one article each | Two near-empty articles each grabbed a whole cluster | Only cluster articles with at least 10 vocabulary words |

---

## 6. What's still to do

| When | Step | Plain meaning |
|---|---|---|
| **Tonight (unattended)** | Index MS MARCO's 8.8 M passages | The same index-building as WP6, on a corpus 40× bigger |
| Wed | **WP9:** load HBase | Put the index into the fast lookup table |
| Wed | **WP10:** search page | A web page (http://localhost:8090) with three ranking methods (Boolean, TF-IDF, BM25) and a PageRank boost |
| Wed night (unattended) | **WP11:** evaluation | Score 1,000 real questions and measure how often the right answer is near the top (MRR@10) |
| Thu | **WP12:** charts, report, demo rehearsal | Turn the logged numbers into charts and the report |

---

## 7. Mini glossary

- **Corpus:** the collection of documents being searched.
- **Token / term:** a word after cleaning and stemming ("running" → `run`).
- **Posting list:** the list of articles containing a term, with counts.
- **df / cf:** *document frequency* (how many articles contain a word) and *collection frequency* (how many times it appears in total).
- **Shuffle:** the step where Hadoop moves and sorts data between the map and reduce stages.
- **Combiner:** a mini-reducer on each worker that shrinks the shuffle.
- **TF-IDF / BM25:** formulas that score how well an article matches a query. BM25 is the modern standard.
- **MRR@10:** on average, how high up the first correct answer appears in the top 10. Higher is better.
