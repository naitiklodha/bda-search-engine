"""WP11 step 3: score the fixed 1,000-query MS MARCO dev/small sample with four models, exhaustively (plan WP11).

Usage: spark-submit --conf spark.sql.shuffle.partitions=128 spark/eval_msmarco.py [msmarco|msmarco-sample] [runs_dir]
Writes <runs_dir>/{bm25,bm25_default,tfidf,boolean}.trec  (qid Q0 docid rank score tag; top 100 per query)
and <runs_dir>/boolean_counts.tsv (qid, number of documents containing every query term).

  bm25          k1 = 0.9, b = 0.4   (Lucene/Anserini-style defaults used by the QueryServer)
  bm25_default  k1 = 1.2, b = 0.75  (textbook defaults)
  tfidf         sum ln(N/df)^2 (1 + ln tf) / norm
  boolean       documents containing every query term, in docID order

Layout: all (query, document) pairs are shuffled by qid ONCE; the per-(qid, doc) aggregation and the four
rankings then run inside those partitions (no further shuffles), and the four row_numbers are computed in a
single pass so the ~1 billion pairs are never re-derived.
"""
import math
import os
import sys
import time

from pyspark import StorageLevel
from pyspark.sql import SparkSession, Window, functions as F

corpus = sys.argv[1] if len(sys.argv) > 1 else "msmarco"
runs_dir = sys.argv[2] if len(sys.argv) > 2 else "eval/runs"
K = 100
HDFS = f"hdfs://localhost:9000/bda/{corpus}"
QTOK = "hdfs://localhost:9000/bda/msmarco/eval/queries_sample.tok.tsv"
spark = SparkSession.builder.appName(f"bda-eval-{corpus}").getOrCreate()
P = int(spark.conf.get("spark.sql.shuffle.partitions"))
t0 = time.time()


def weights(df_col, tf_col, len_col, norm_col, N, avgdl):
    """Per (term, doc) contributions: identical to bda.common.Scorer."""
    idf = F.log(1 + (F.lit(N) - df_col + 0.5) / (df_col + 0.5))
    def bm25(k1, b):
        return idf * tf_col * (k1 + 1) / (tf_col + k1 * (1 - b + b * len_col / F.lit(avgdl)))
    tfidf = F.when(norm_col > 0, F.pow(F.log(F.lit(float(N)) / df_col), 2) * (1 + F.log(tf_col)) / norm_col).otherwise(0.0)
    return bm25(0.9, 0.4), bm25(1.2, 0.75), tfidf


# ---- self-test: the toy example pinned in ScorerTest (independent Python reference values) ----
toy = spark.createDataFrame([(2.0, 3.0, 4.0, 2.5), (5.0, 1.0, 4.0, 2.5)], ["df", "tf", "len", "norm"])
a, b_, t = weights(F.col("df"), F.col("tf"), F.col("len"), F.col("norm"), 10, 5.0)
got = toy.select(F.sum(a), F.sum(b_), F.sum(t)).first()
want = (2.9265993446113043, 3.1873978380958823, 2.366587306377633)
assert all(abs(g - w) < 1e-9 for g, w in zip(got, want)), f"scorer self-test failed: {got} vs {want}"
print(f"EVAL self-test OK: {tuple(round(g, 12) for g in got)}")

meta = dict(l.split("\t", 1) for l in spark.sparkContext.textFile(f"{HDFS}/index/meta/meta.tsv").collect())
N, avgdl = int(meta["N"]), float(meta["avgdl"])
assert meta["tokenizerVersion"] == "v1-lucene33-porter", meta
print(f"EVAL corpus={corpus} N={N} avgdl={avgdl:.4f} partitions={P}")

qt = (spark.read.csv(QTOK, sep="\t").toDF("qid", "terms")
      .withColumn("term", F.explode(F.split("terms", " "))).filter(F.col("term") != "")
      .select(F.col("qid").cast("long").alias("qid"), "term").distinct().cache())
nq = qt.groupBy("qid").agg(F.count("*").alias("nq")).cache()
n_queries = nq.count()
vocab = qt.select("term").distinct()

post = (spark.read.csv(f"{HDFS}/index/postings", sep="\t").toDF("term", "df", "cf", "plist")
        .join(F.broadcast(vocab), "term")
        .select("term", F.col("df").cast("double").alias("df"), F.explode(F.split("plist", ",")).alias("p"))
        .select("term", "df", F.split("p", ":")[0].cast("long").alias("docid"),
                F.split("p", ":")[1].cast("double").alias("tf")))
ds = spark.read.csv(f"{HDFS}/index/docstats", sep="\t").toDF("docid", "len") \
    .select(F.col("docid").cast("long"), F.col("len").cast("double"))
nm = spark.read.csv(f"{HDFS}/spark/norms", sep="\t").toDF("docid", "norm") \
    .select(F.col("docid").cast("long"), F.col("norm").cast("double"))
w = post.join(ds, "docid").join(nm, "docid")
wa, wb, wt = weights(F.col("df"), F.col("tf"), F.col("len"), F.col("norm"), N, avgdl)
w = w.select("term", "docid", wa.alias("wa"), wb.alias("wb"), wt.alias("wt"))

pairs = w.join(F.broadcast(qt), "term").select("qid", "docid", "wa", "wb", "wt").repartition(P, "qid")
agg = (pairs.groupBy("qid", "docid")
       .agg(F.sum("wa").alias("bm25"), F.sum("wb").alias("bm25_default"), F.sum("wt").alias("tfidf"),
            F.count("*").alias("matched"))
       .join(F.broadcast(nq), "qid")
       .withColumn("allmatch", (F.col("matched") == F.col("nq")).cast("int")))

by_q = Window.partitionBy("qid")
ranked = agg.select(
    "qid", "docid", "bm25", "bm25_default", "tfidf", "allmatch",
    F.row_number().over(by_q.orderBy(F.desc("bm25"), "docid")).alias("r_bm25"),
    F.row_number().over(by_q.orderBy(F.desc("bm25_default"), "docid")).alias("r_bm25_default"),
    F.row_number().over(by_q.orderBy(F.desc("tfidf"), "docid")).alias("r_tfidf"),
    F.row_number().over(by_q.orderBy(F.desc("allmatch"), "docid")).alias("r_boolean"),
    F.sum("allmatch").over(by_q).alias("boolean_total"))
top = ranked.filter((F.col("r_bm25") <= K) | (F.col("r_bm25_default") <= K) | (F.col("r_tfidf") <= K)
                    | ((F.col("allmatch") == 1) & (F.col("r_boolean") <= K))).persist(StorageLevel.MEMORY_AND_DISK)
n_top = top.count()   # the one big job
t_score = time.time() - t0
print(f"EVAL scoring done: queries={n_queries} kept_rows={n_top} seconds={t_score:.0f}")

os.makedirs(runs_dir, exist_ok=True)
for model, rank_col, score_col, cond in [
        ("bm25", "r_bm25", "bm25", None), ("bm25_default", "r_bm25_default", "bm25_default", None),
        ("tfidf", "r_tfidf", "tfidf", None), ("boolean", "r_boolean", None, "allmatch")]:
    sel = top.filter(F.col(rank_col) <= K)
    if cond:
        sel = sel.filter(F.col(cond) == 1)
    rows = sel.select("qid", "docid", rank_col, (F.col(score_col) if score_col else F.lit(1.0) / F.col(rank_col)).alias("s")) \
              .orderBy("qid", rank_col).collect()
    with open(f"{runs_dir}/{model}.trec", "w") as f:
        for r in rows:
            f.write(f"{r['qid']} Q0 {r['docid']} {r[rank_col]} {r['s']:.6f} bda-{model}\n")
    print(f"EVAL run {model}: {len(rows)} lines, {len({r['qid'] for r in rows})} queries with results")
bt = top.select("qid", "boolean_total").distinct().collect()
have = {r["qid"]: r["boolean_total"] for r in bt}
with open(f"{runs_dir}/boolean_counts.tsv", "w") as f:
    for q in sorted(r["qid"] for r in nq.collect()):
        f.write(f"{q}\t{have.get(q, 0)}\n")
print(f"EVAL total_seconds={time.time() - t0:.0f}")
spark.stop()
