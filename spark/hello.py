# WP1 step 10.3: proves PySpark runs on YARN with the project venv's Python on the executors.
import sys
from pyspark.sql import SparkSession
spark = SparkSession.builder.appName("bda-hello").getOrCreate()
n = spark.range(10**7).count()
execpy = spark.sparkContext.parallelize([0], 1).map(lambda _: __import__("sys").executable).collect()[0]
print(f"HELLO count={n} driver_python={sys.executable} executor_python={execpy}")
spark.stop()
