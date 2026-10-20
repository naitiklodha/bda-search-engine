#!/bin/bash
# WP1 steps 8 (HBase shell test), 9 (Pig on MapReduce), 10.2 (SparkPi on YARN). No root needed.
set -uo pipefail
source ~/bda/env.sh

echo "=== HBase: start + shell test ==="
bash "$BDA/bin/bda-start.sh" hbase | sed -n '/^HBase/,$p'
printf "create 'smoke','cf'\nput 'smoke','r1','cf:a','v1'\nscan 'smoke'\ndisable 'smoke'\ndrop 'smoke'\n" \
  | hbase shell -n 2>&1 | grep -E 'Created table|row\(s\)|column=|ERROR|Exception' | sed 's/^/  /'
grep -lE 'InaccessibleObjectException|IllegalAccessError|RECEIVED SIGNAL 1' "$BDA"/logs/hbase/*.log 2>/dev/null \
  && echo "  module/SIGHUP errors FOUND" || echo "  no module errors / SIGHUP in HBase logs"
echo "  stopping HBase (it only runs for WP9/WP10, plan §5.6)"
bash "$BDA/bin/bda-stop.sh" hbase | grep stopped | sed 's/^/  /'

echo "=== Pig 0.18 on MapReduce (JDK 21) ==="
hdfs dfs -mkdir -p /bda/tmp/pigtest
printf 'a\t1\nb\t2\na\t3\n' | hdfs dfs -put -f - /bda/tmp/pigtest/in.tsv
hdfs dfs -rm -r -f -skipTrash /bda/tmp/pigtest/out >/dev/null 2>&1
cat > /tmp/bda-pigtest.pig <<'EOF'
d = LOAD '/bda/tmp/pigtest/in.tsv' USING PigStorage('\t') AS (k:chararray, v:int);
g = GROUP d BY k;
s = FOREACH g GENERATE group, SUM(d.v);
STORE s INTO '/bda/tmp/pigtest/out' USING PigStorage('\t');
EOF
start=$(date +%s)
pig -x mapreduce -l "$BDA/logs" /tmp/bda-pigtest.pig > "$BDA/logs/pigtest.out" 2>&1; rc=$?
echo "  pig exit=$rc  ($(( $(date +%s) - start )) s)"
grep -E 'Success!|Failed!|ERROR|Exception' "$BDA/logs/pigtest.out" | head -5 | sed 's/^/  /'
hdfs dfs -cat '/bda/tmp/pigtest/out/part-*' 2>/dev/null | sed 's/^/  result: /'

echo "=== SparkPi on YARN ==="
start=$(date +%s)
cd "$SPARK_HOME"
EXJAR=$(ls examples/jars/spark-examples_*.jar | head -1)
spark-submit --class org.apache.spark.examples.SparkPi "$EXJAR" 50 2>&1 | grep -E 'Pi is roughly|Exception|ERROR' | head -5 | sed 's/^/  /'
echo "  wall time: $(( $(date +%s) - start )) s"
