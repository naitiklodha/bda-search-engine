#!/bin/bash
# WP3 acceptance (throughput), WP1 step 12.3 (our jar on YARN), WP2 steps 2-4 (eval files + fixed sample),
# and the version list for docs/setup-log.md.
set -uo pipefail
source ~/bda/env.sh
R=$BDA/repo; J=$R/java/target/bda-search-1.0.jar

echo "=== WP3 throughput (first 10,000 MS MARCO passages) ==="
java -cp "$J" bda.tools.TokenizerBench /data/raw/msmarco/collection.tsv 10000

echo "=== our jar on YARN: TokenWordCount over 2,000 MS MARCO passages ==="
head -2000 /data/raw/msmarco/collection.tsv | hdfs dfs -put -f - /bda/tmp/wc-in.tsv
hdfs dfs -rm -r -f -skipTrash /bda/tmp/wc-out >/dev/null 2>&1
start=$(date +%s)
hadoop jar "$J" bda.tools.TokenWordCount /bda/tmp/wc-in.tsv /bda/tmp/wc-out 2>&1 \
  | grep -E 'Running job|completed successfully|Exception|ERROR' | sed 's/^.*INFO [a-z.]*: /  /'
echo "  wall time: $(( $(date +%s) - start )) s; top terms:"
hdfs dfs -cat '/bda/tmp/wc-out/part-*' | sort -t$'\t' -k2,2nr | head -6 | sed 's/^/    /'

echo "=== WP2: eval files + fixed 1,000-query sample (seed 42) ==="
cp /data/raw/msmarco/queries.dev.small.tsv /data/raw/msmarco/qrels.dev.small.tsv "$R/eval/"
cd "$R"
"$BDA/venv/bin/python" - <<'EOF'
import random
qids = sorted({line.split("\t")[0] for line in open("eval/queries.dev.small.tsv", encoding="utf-8")})
assert len(qids) == 6980, len(qids)
sample = sorted(random.Random(42).sample(qids, 1000))
open("eval/sample_qids.txt", "w").write("\n".join(sample) + "\n")
print("  sample_qids.txt:", len(sample), "qids; first three:", sample[:3])
EOF
echo "=== TokenizeQueries on all 6,980 dev/small queries (sanity) ==="
java -cp "$J" bda.tools.TokenizeQueries eval/queries.dev.small.tsv /tmp/q.tok.tsv 2>&1 | sed 's/^/  /'
head -3 /tmp/q.tok.tsv | sed 's/^/  /'

echo "=== versions (setup-log) ==="
{
  echo '$ java -version';         java -version 2>&1
  echo '$ hadoop version';        hadoop version 2>/dev/null | head -1
  echo '$ hive --version';        hive --version 2>/dev/null | grep -m1 '^Hive'
  echo '$ hbase version';         hbase version 2>/dev/null | grep -m1 '^HBase'
  echo '$ spark-submit --version'; spark-submit --version 2>&1 | grep -m1 -o 'version [0-9.]*'
  echo '$ pig -version';          pig -version 2>/dev/null | grep -m1 'Apache Pig'
  echo '$ mvn -version';          mvn -version 2>/dev/null | head -1
  echo '$ psql --version';        psql --version
  echo '$ python --version';      "$BDA/venv/bin/python" --version
} | tee "$BDA/logs/versions.txt"
echo "=== disk ==="
df -h / /data | sed 's/^/  /'
du -sh "$BDA/opt" "$BDA/venv" ~/.m2 ~/smoke 2>/dev/null | sed 's/^/  /'
