#!/bin/bash
# WP9 acceptance: row counts (RowCounter on YARN) vs the HDFS sources, a prefix scan in docID order,
# a full docs row, and the postings region list.
source ~/bda/env.sh
R=$BDA/repo; J=$R/java/target/bda-search-1.0.jar
export HADOOP_CLASSPATH="$(hbase mapredcp 2>/dev/null):$HBASE_HOME/conf"
MR=$(ls "$HBASE_HOME"/lib/hbase-mapreduce-*.jar | grep -v tests | head -1)
SUMDF=$(hdfs dfs -cat /bda/wiki/index/lexicon/part-* | awk -F'\t' '{s+=$2} END {printf "%d", s}')
LEX=$(hdfs dfs -cat /bda/wiki/index/lexicon/part-* | wc -l)
DOCS=$(hdfs dfs -cat /bda/wiki/hive/docs_full/* | wc -l)
count() {
  local t=$1
  hadoop jar "$MR" rowcounter "$t" > "$BDA/logs/rowcounter-$t.log" 2>&1
  grep -oP 'ROWS=\K[0-9]+' "$BDA/logs/rowcounter-$t.log" | tail -1
}
check() { [ "$2" = "$3" ] && echo "PASS  $1: HBase rows $2 = expected $3" || echo "FAIL  $1: HBase rows ${2:-?} vs expected $3"; }
check terms    "$(count terms)"    "$LEX"
check docs     "$(count docs)"     "$DOCS"
check postings "$(count postings)" "$SUMDF"
T=$(java -cp "$J" bda.tools.TokenizeText photosynthesis | awk '{print $1}')
echo "--- scan 'postings' ROWPREFIXFILTER '$T#' LIMIT 5 (docIDs must ascend) ---"
echo "scan 'postings', {ROWPREFIXFILTER => '$T#', LIMIT => 5}" | hbase shell -n 2>/dev/null | grep -E 'column=|row\(s\)'
ID=$(echo "scan 'postings', {ROWPREFIXFILTER => '$T#', LIMIT => 1}" | hbase shell -n 2>/dev/null | grep -oP "#\K[0-9]{10}" | head -1)
echo "--- get 'docs', '$ID' ---"
printf "get 'docs', '%s'\n" "$ID" | hbase shell -n 2>/dev/null | grep -E 'd:|row\(s\)|COLUMN' | cut -c1-160
echo "--- list_regions 'postings' ---"
echo "list_regions 'postings'" | hbase shell -n 2>/dev/null | grep -E 'SERVER_NAME|postings,|rows' | cut -c1-170
