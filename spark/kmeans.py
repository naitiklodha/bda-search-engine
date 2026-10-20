"""WP8: K-means topic clustering of Simple English Wikipedia articles (plan WP8).
Usage: spark-submit spark/kmeans.py [k=20] [vocab=20000] [maxIter=20]   (run from ~/bda/repo)

Vocabulary: terms with 5 <= df <= 0.1*N, top `vocab` by df. Vectors: (1 + ln tf) * ln(N/df), L2-normalised.
KMeans(k, maxIter, seed=42). Writes eval/kmeans_k<k>.md (sizes, top-10 centroid terms, 5 random titles each)
and charts/data/kmeans_k<k>_sizes.tsv; silhouette on a 20k-document sample.
"""
import math
import os
import random
import sys
import time

from pyspark.ml.clustering import KMeans
from pyspark.ml.evaluation import ClusteringEvaluator
from pyspark.ml.feature import Normalizer
from pyspark.ml.linalg import SparseVector, VectorUDT
from pyspark.sql import SparkSession
from pyspark.sql.types import LongType, StructField, StructType

K = int(sys.argv[1]) if len(sys.argv) > 1 else 20
VOCAB = int(sys.argv[2]) if len(sys.argv) > 2 else 20000
MAXITER = int(sys.argv[3]) if len(sys.argv) > 3 else 20
# Articles with fewer vocabulary terms than this are not clustered. In the first run (no minimum), two
# near-empty outlier articles each took a whole cluster ("NCAA Award of Valor", "Mount Price").
MIN_TERMS = int(sys.argv[4]) if len(sys.argv) > 4 else 10
HDFS = "hdfs://localhost:9000/bda/wiki"
spark = SparkSession.builder.appName(f"bda-kmeans-k{K}").getOrCreate()
sc = spark.sparkContext
t0 = time.time()

meta = dict(l.split("\t", 1) for l in sc.textFile(f"{HDFS}/index/meta/meta.tsv").collect())
N = int(meta["N"])
lex = (sc.textFile(f"{HDFS}/index/lexicon").map(lambda l: l.split("\t"))
       .map(lambda f: (f[0], int(f[1]))).filter(lambda t: 5 <= t[1] <= 0.1 * N))
vocab = lex.top(VOCAB, key=lambda t: (t[1], t[0]))
term_index = {t: i for i, (t, _) in enumerate(sorted(vocab))}
idf = {t: math.log(N / df) for t, df in vocab}
index_to_term = {i: t for t, i in term_index.items()}
b_index, b_idf = sc.broadcast(term_index), sc.broadcast(idf)
V = len(term_index)


def explode(line):
    term, _df, _cf, plist = line.split("\t", 3)
    i = b_index.value.get(term)
    if i is None:
        return []
    w_idf = b_idf.value[term]
    out = []
    for p in plist.split(","):
        d, tf = p.split(":")
        out.append((int(d), (i, (1.0 + math.log(int(tf))) * w_idf)))
    return out


grouped = sc.textFile(f"{HDFS}/index/postings").flatMap(explode).groupByKey().mapValues(list).cache()
n_all = grouped.count()
docvecs = (grouped.filter(lambda kv: len(kv[1]) >= MIN_TERMS)
           .map(lambda kv: (kv[0], SparseVector(V, sorted(kv[1])))))
schema = StructType([StructField("docid", LongType()), StructField("raw", VectorUDT())])
df = Normalizer(inputCol="raw", outputCol="features", p=2.0).transform(spark.createDataFrame(docvecs, schema)).cache()
n_docs = df.count()
grouped.unpersist()
print(f"KMEANS vocab={V} docs_with_any_vocab_term={n_all} clustered(min_terms={MIN_TERMS})={n_docs} "
      f"excluded={n_all - n_docs} prep_seconds={time.time() - t0:.0f}")

t1 = time.time()
model = KMeans(k=K, maxIter=MAXITER, seed=42, featuresCol="features").fit(df)
pred = model.transform(df).select("docid", "prediction").cache()
fit_secs = time.time() - t1
sizes = dict(pred.groupBy("prediction").count().collect())

titles = dict(sc.textFile(f"{HDFS}/clean/titles").map(lambda l: l.split("\t")).filter(lambda f: len(f) >= 2)
              .map(lambda f: (int(f[0]), f[1])).collect())
rnd = random.Random(42)
members = pred.rdd.map(lambda r: (r.prediction, r.docid)).groupByKey().mapValues(list).collectAsMap()

sil = None
try:
    sample = model.transform(df.sample(fraction=min(1.0, 20000 / max(n_docs, 1)), seed=42))
    sil = ClusteringEvaluator(featuresCol="features", predictionCol="prediction", distanceMeasure="squaredEuclidean").evaluate(sample)
except Exception as e:  # optional metric
    print(f"KMEANS silhouette skipped: {e}")

os.makedirs("eval", exist_ok=True)
os.makedirs("charts/data", exist_ok=True)
with open(f"eval/kmeans_k{K}.md", "w", encoding="utf-8") as f:
    f.write(f"# K-means, k = {K}\n\nSimple English Wikipedia, {n_docs:,} articles clustered (those with at least "
            f"{MIN_TERMS} vocabulary terms; {n_all - n_docs:,} shorter ones excluded); vocabulary {V:,} terms "
            f"(5 <= df <= 0.1·N, top by df); weights (1 + ln tf)·idf, L2-normalised; maxIter {MAXITER}, seed 42.\n"
            f"Fit time {fit_secs:.0f} s; within-set sum of squared errors {model.summary.trainingCost:.1f}"
            + (f"; silhouette (20k sample) {sil:.4f}" if sil is not None else "") + ".\n\n")
    f.write("| Cluster | Size | Top 10 terms (centroid weight) | 5 random titles |\n|---|---|---|---|\n")
    for c, centre in enumerate(model.clusterCenters()):
        top = sorted(range(V), key=lambda i: -centre[i])[:10]
        ids = members.get(c, [])
        pick = rnd.sample(ids, min(5, len(ids)))
        f.write(f"| {c} | {sizes.get(c, 0):,} | {', '.join(index_to_term[i] for i in top)} | "
                f"{'; '.join(titles.get(d, str(d)).replace('|', '/') for d in pick)} |\n")
with open(f"charts/data/kmeans_k{K}_sizes.tsv", "w", encoding="utf-8") as f:
    for c, centre in enumerate(model.clusterCenters()):
        top = sorted(range(V), key=lambda i: -centre[i])[:3]
        f.write(f"{c}\t{sizes.get(c, 0)}\t{' '.join(index_to_term[i] for i in top)}\n")
print(f"KMEANS k={K} fit_seconds={fit_secs:.0f} sse={model.summary.trainingCost:.1f} silhouette={sil} "
      f"total_seconds={time.time() - t0:.0f} sizes={sorted(sizes.values(), reverse=True)}")
spark.stop()
