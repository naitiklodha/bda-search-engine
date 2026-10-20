"""WP8: PageRank over the Simple English Wikipedia link graph (plan WP8).
Usage: spark-submit spark/pagerank.py   (run from ~/bda/repo; writes eval/ files locally)

Nodes   = articles in /bda/wiki/clean/titles (after the Pig filter).
Edges   = /bda/wiki/job0/links, each target resolved title -> docID, following redirects up to 3 hops;
          unresolvable targets, sources that aren't nodes, and self-links dropped; distinct.
Ranks   = 10 iterations, d = 0.85, dangling mass spread evenly; nodes with no in-links get the base value.
Outputs = /bda/wiki/spark/pagerank (docID \t pr), eval/pagerank_top50.tsv, eval/pagerank_log.csv
"""
import csv
import os
import time
from operator import add

from pyspark.sql import SparkSession

D, ITERS = 0.85, 10
HDFS = "hdfs://localhost:9000"
spark = SparkSession.builder.appName("bda-pagerank").getOrCreate()
sc = spark.sparkContext
t_start = time.time()


def norm_title(t):
    """Same normalisation as Job 0's link targets: '_' -> space, collapse spaces, trim, first char upper."""
    t = " ".join(t.replace("_", " ").split())
    return t[:1].upper() + t[1:] if t else t


titles = sc.textFile(f"{HDFS}/bda/wiki/clean/titles").map(lambda l: l.split("\t")).filter(lambda f: len(f) >= 2)
title_to_id = dict(titles.map(lambda f: (norm_title(f[1]), int(f[0]))).collect())
id_to_title = {v: k for k, v in title_to_id.items()}
redirects = dict(sc.textFile(f"{HDFS}/bda/wiki/clean/redirects").map(lambda l: l.split("\t"))
                 .filter(lambda f: len(f) == 2).map(lambda f: (norm_title(f[0]), norm_title(f[1]))).collect())
b_titles, b_redirects = sc.broadcast(title_to_id), sc.broadcast(redirects)
node_ids = sc.broadcast(set(title_to_id.values()))


def resolve(target):
    t = target
    for _ in range(4):  # the title itself, then up to 3 redirect hops
        doc = b_titles.value.get(t)
        if doc is not None:
            return doc
        t = b_redirects.value.get(t)
        if t is None:
            return None
    return None


def edge(line):
    src, _, tgt = line.partition("\t")
    s = int(src)
    if s not in node_ids.value:
        return None
    d = resolve(tgt)
    return (s, d) if d is not None and d != s else None


raw_links = sc.textFile(f"{HDFS}/bda/wiki/job0/links")
edges = raw_links.map(edge).filter(lambda e: e is not None).distinct()
links = edges.groupByKey().mapValues(list).cache()
nodes = sc.parallelize(sorted(title_to_id.values()), 8).map(lambda n: (n, None)).cache()
N = nodes.count()
n_links_raw, n_edges, n_with_out = raw_links.count(), edges.count(), links.count()
print(f"PAGERANK graph: nodes={N} raw_links={n_links_raw} edges={n_edges} nodes_with_outlinks={n_with_out} "
      f"setup_seconds={time.time() - t_start:.0f}")

ranks = nodes.mapValues(lambda _: 1.0 / N).cache()
log_rows = []
for it in range(1, ITERS + 1):
    t0 = time.time()
    contribs = links.join(ranks).flatMap(lambda kv: [(dst, kv[1][1] / len(kv[1][0])) for dst in kv[1][0]])
    dangling = ranks.subtractByKey(links).values().sum()
    sums = contribs.reduceByKey(add)
    base, dshare = (1 - D) / N, D * dangling / N
    new = nodes.leftOuterJoin(sums).mapValues(lambda v: base + D * (v[1] or 0.0) + dshare)
    if it % 3 == 0:
        new = new.localCheckpoint()
    new = new.cache()
    l1 = new.join(ranks).map(lambda kv: abs(kv[1][0] - kv[1][1])).sum()   # action: forces the iteration
    ranks.unpersist()
    ranks = new
    secs = time.time() - t0
    log_rows.append((it, round(secs, 2), l1, dangling))
    print(f"PAGERANK iteration={it} seconds={secs:.1f} l1_change={l1:.3e} dangling_mass={dangling:.4f}")

total = ranks.values().sum()
print(f"PAGERANK sum_pr={total:.9f} (|1 - sum| = {abs(1 - total):.2e})")
ranks.map(lambda kv: f"{kv[0]}\t{kv[1]:.12e}").saveAsTextFile(f"{HDFS}/bda/wiki/spark/pagerank_tmp")
fs = sc._jvm.org.apache.hadoop.fs.FileSystem.get(sc._jsc.hadoopConfiguration())
P = sc._jvm.org.apache.hadoop.fs.Path
fs.delete(P("/bda/wiki/spark/pagerank"), True)
fs.rename(P("/bda/wiki/spark/pagerank_tmp"), P("/bda/wiki/spark/pagerank"))

os.makedirs("eval", exist_ok=True)
top = ranks.top(50, key=lambda kv: kv[1])
with open("eval/pagerank_top50.tsv", "w", encoding="utf-8") as f:
    f.write("rank\tdocid\ttitle\tpr\n")
    for i, (doc, pr) in enumerate(top, 1):
        f.write(f"{i}\t{doc}\t{id_to_title.get(doc, '')}\t{pr:.6e}\n")
with open("eval/pagerank_log.csv", "w", newline="") as f:
    wr = csv.writer(f)
    wr.writerow(["iteration", "seconds", "l1_change", "dangling_mass"])
    wr.writerows(log_rows)
print("PAGERANK top10: " + "; ".join(id_to_title.get(d, str(d)) for d, _ in top[:10]))
print(f"PAGERANK total_seconds={time.time() - t_start:.0f}")
spark.stop()
