"""WP8: TF-IDF document norms (plan WP8). Usage: spark-submit spark/tfidf_norms.py wiki|msmarco

w(t, d) = (1 + ln tf) * ln(N / df);  norm(d) = sqrt(sum_t w(t, d)^2)
Output: docID \t norm  ->  /bda/<c>/spark/norms   (N from the corpus's own meta.tsv)
"""
import sys
import time

from pyspark.sql import SparkSession, functions as F

corpus = sys.argv[1]
base = f"hdfs://localhost:9000/bda/{corpus}"
spark = SparkSession.builder.appName(f"bda-tfidf-norms-{corpus}").getOrCreate()
t0 = time.time()

meta = dict(line.split("\t", 1) for line in spark.sparkContext.textFile(f"{base}/index/meta/meta.tsv").collect())
N = float(meta["N"])

post = spark.read.csv(f"{base}/index/postings", sep="\t").toDF("term", "df", "cf", "plist")
w = (post.select(F.col("df").cast("double").alias("df"), F.explode(F.split("plist", ",")).alias("p"))
     .select("df",
             F.split("p", ":")[0].cast("long").alias("docid"),
             F.split("p", ":")[1].cast("double").alias("tf"))
     .withColumn("w", (1.0 + F.log("tf")) * F.log(F.lit(N) / F.col("df"))))
norms = w.groupBy("docid").agg(F.sqrt(F.sum(F.col("w") * F.col("w"))).alias("norm"))
norms.write.mode("overwrite").csv(f"{base}/spark/norms", sep="\t")

check = spark.read.csv(f"{base}/spark/norms", sep="\t").toDF("docid", "norm")
n_docs = check.count()
zero = check.filter(F.col("norm").cast("double") == 0.0).count()
print(f"NORMS corpus={corpus} N_meta={int(N)} docs_with_norm={n_docs} zero_norm={zero} seconds={time.time() - t0:.0f}")
spark.stop()
