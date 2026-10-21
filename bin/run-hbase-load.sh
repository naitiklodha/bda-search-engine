#!/bin/bash
# WP9: start HBase, create the tables, load terms -> docs -> postings, compact, verify (plan WP9 acceptance).
# Usage: run-hbase-load.sh        log: ~/bda/logs/hbase-load.log
source ~/bda/env.sh
R=$BDA/repo; J=$R/java/target/bda-search-1.0.jar
export HADOOP_CLASSPATH="$(hbase mapredcp 2>/dev/null):$HBASE_HOME/conf"
{
  echo "######## start $(date)"
  bash "$BDA/bin/bda-start.sh" hbase | sed -n '/^HBase/,/master/p'
  echo "######## create tables"; bash "$R/hbase/create_tables.sh"
  for spec in "terms /bda/wiki/index/lexicon" "docs /bda/wiki/hive/docs_full" "postings /bda/wiki/index/postings"; do
    set -- $spec
    echo "######## load $1 <- $2"
    hadoop jar "$J" bda.hbase.HBaseLoadJob "$1" "$2" > "$BDA/logs/hbase-load-$1.log" 2>&1
    grep -A1 '=== HBase load summary ===' "$BDA/logs/hbase-load-$1.log" | tail -1
    grep -E 'Exception|ERROR' "$BDA/logs/hbase-load-$1.log" | grep -v WARN | head -3
  done
  echo "######## flush + major compact"
  start=$(date +%s)
  printf "flush 'terms'\nflush 'docs'\nflush 'postings'\nmajor_compact 'postings'\nmajor_compact 'docs'\nmajor_compact 'terms'\n" | hbase shell -n >/dev/null 2>&1
  echo "flush + compaction requested ($(( $(date +%s) - start )) s; compaction finishes in the background)"
  echo "######## verify"; bash "$BDA/bin/verify-hbase.sh"
  echo "######## end $(date)"
} 2>&1 | tee "$BDA/logs/hbase-load.log"
