#!/bin/bash
# Run a project Spark script from the repo root (so eval/ and charts/ paths resolve), log it, print its tagged lines.
# Usage: run-spark.sh <name> spark/<script>.py [args...] [-- extra spark-submit options before the script]
source ~/bda/env.sh
name=$1; script=$2; shift 2
cd "$BDA/repo"
LOG=$BDA/logs/spark-$name.log
start=$(date +%s)
spark-submit ${SPARK_EXTRA:-} "$script" "$@" > "$LOG" 2>&1; rc=$?
echo "spark $name: exit=$rc wall=$(( $(date +%s) - start )) s  log=$LOG"
grep -E '^(NORMS|PAGERANK|KMEANS|EVAL|HELLO)|Traceback|Error:|Exception' "$LOG" | grep -v 'WARN' | head -30
