#!/bin/bash
# WP5: Pig filter + summaries. Usage: run-pig.sh [MINLEN=200]
source ~/bda/env.sh
MINLEN=${1:-200}
IN=/bda/wiki/job0/pages; OUT=/bda/wiki/clean
hdfs dfs -rm -r -f -skipTrash "$OUT" >/dev/null 2>&1
start=$(date +%s)
pig -x mapreduce -l "$BDA/logs" -param IN=$IN -param OUT=$OUT -param MINLEN="$MINLEN" \
    "$BDA/repo/pig/filter_pages.pig" > "$BDA/logs/pig-filter.log" 2>&1; rc=$?
echo "pig exit=$rc  MINLEN=$MINLEN  wall=$(( $(date +%s) - start )) s"
grep -E 'Success!|Failed!|ERROR [0-9]' "$BDA/logs/pig-filter.log" | head -3
echo "--- by_category ---"
hdfs dfs -cat "$OUT/summary/by_category/part-*" | sort
hdfs dfs -cat "$OUT/summary/by_category/part-*" | awk -F'\t' '{s+=$2} END {print "sum =", s}'
echo "--- row counts ---"
for d in docs titles redirects; do echo "$d: $(hdfs dfs -cat "$OUT/$d/part-*" | wc -l)"; done
echo "--- article length histogram (chars, bucket = 500) ---"
hdfs dfs -cat "$OUT/summary/length_hist/part-*" | sort -n | head -25
