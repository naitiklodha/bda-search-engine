#!/bin/bash
# WP6: index one corpus. Order (plan WP6): Job 2 (doc stats + meta) -> Job 1 (postings) -> Job 3 (lexicon),
# then the integrity checks (validate-index.sh). Usage: run-index.sh wiki|msmarco
source ~/bda/env.sh
C=${1:?usage: run-index.sh wiki|msmarco}
J=$BDA/repo/java/target/bda-search-1.0.jar
case "$C" in
  wiki)    IN=/bda/wiki/clean/docs;             REDUCERS=4 ;;
  msmarco) IN=/bda/raw/msmarco/collection.tsv;  REDUCERS=6 ;;
  *) echo "unknown corpus $C"; exit 2 ;;
esac
O=/bda/$C/index
L=$BDA/logs/index-$C
hdfs dfs -rm -r -f -skipTrash "$O/docstats" "$O/postings" "$O/lexicon" "$O/meta" >/dev/null 2>&1
hdfs dfs -mkdir -p "$O/meta"
echo "=== index $C: $IN -> $O  ($(date)) ==="

hadoop jar "$J" bda.index.DocStatsJob "$IN" "$O/docstats" "$O/meta/meta.tsv" > "$L-job2.log" 2>&1 || { tail -20 "$L-job2.log"; exit 1; }
sed -n '/=== Job 2 summary ===/,$p' "$L-job2.log"

hadoop jar "$J" bda.index.PostingsJob -D mapreduce.job.reduces=$REDUCERS "$IN" "$O/postings" > "$L-job1.log" 2>&1 || { tail -20 "$L-job1.log"; exit 1; }
sed -n '/=== Job 1 summary ===/,$p' "$L-job1.log"

hadoop jar "$J" bda.index.LexiconJob "$O/postings" "$O/lexicon" > "$L-job3.log" 2>&1 || { tail -20 "$L-job3.log"; exit 1; }
sed -n '/=== Job 3 summary ===/,$p' "$L-job3.log"

bash "$BDA/bin/validate-index.sh" "$C"
echo "=== done $C ($(date)) ==="
