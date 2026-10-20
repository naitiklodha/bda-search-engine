#!/bin/bash
# WP4 / Job 0: Wikipedia bz2 dump -> /bda/wiki/job0/{pages,links}. Runs both split modes (plan WP4) and checks S1.
#   1) ~32 MB splits (several mappers)   -> /bda/wiki/job0        (the output the pipeline uses)
#   2) one split (bda.xml.splittable=false) -> /bda/tmp/job0-single (comparison only)
# Usage: run-job0.sh [split|single|both]   (default both)
source ~/bda/env.sh
J=$BDA/repo/java/target/bda-search-1.0.jar
IN=/bda/raw/simplewiki
GT=562214   # bzcat simplewiki-20261001-pages-articles.xml.bz2 | grep -c '<page>'  (build-log WP2)
MODE=${1:-both}

run() {  # run <name> <out> <-D options...>
  local name=$1 out=$2; shift 2
  hdfs dfs -rm -r -f -skipTrash "$out" >/dev/null 2>&1
  echo "--- $name: hadoop jar ... bda.ingest.WikiPageJob $* $IN $out"
  hadoop jar "$J" bda.ingest.WikiPageJob "$@" "$IN" "$out" > "$BDA/logs/$name.log" 2>&1
  sed -n '/=== Job 0 summary ===/,$p' "$BDA/logs/$name.log"
  local pages
  pages=$(grep -oP '^PAGES=\K[0-9]+' "$BDA/logs/$name.log")
  [ "$pages" = "$GT" ] && echo "S1 ($name): PASS  PAGES=$pages = ground truth $GT" \
                       || echo "S1 ($name): FAIL  PAGES=${pages:-?} vs ground truth $GT"
}

case "$MODE" in split|both) run job0-split /bda/wiki/job0 -D mapreduce.input.fileinputformat.split.maxsize=33554432 ;; esac
case "$MODE" in single|both) run job0-single /bda/tmp/job0-single -D bda.xml.splittable=false ;; esac
hdfs dfs -du -s -h /bda/wiki/job0/pages /bda/wiki/job0/links 2>/dev/null
