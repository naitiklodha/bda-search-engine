#!/bin/bash
# WP11 step 1, unattended (plan §12 overnight run): index MS MARCO (Job 2 -> Job 1 -> Job 3 + S2 checks),
# then its TF-IDF norms. Starts the core services first (idempotent) and makes sure HBase is off.
# Start it detached, so it survives closing the terminal:
#   wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-run.sh msmarco-overnight bash /home/mit/bda/bin/run-msmarco-overnight.sh
# Usage: run-msmarco-overnight.sh [msmarco|msmarco-sample]      log: ~/bda/logs/msmarco-overnight.log
source ~/bda/env.sh
C=${1:-msmarco}
LOG=$BDA/logs/$C-overnight.log
{
  echo "######## start $(date)"
  bash "$BDA/bin/bda-start.sh" core | tail -8
  bash "$BDA/bin/bda-stop.sh" hbase | grep stopped || true
  echo "######## index $C";  bash "$BDA/bin/run-index.sh" "$C"
  echo "######## TF-IDF norms $C"
  SPARK_EXTRA="--conf spark.sql.shuffle.partitions=32" bash "$BDA/bin/run-spark.sh" "norms-$C" spark/tfidf_norms.py "$C"
  echo "######## disk + status"; bash "$BDA/bin/bda-status.sh" | sed -n '/--- disks ---/,$p'
  echo "######## end $(date)"
  echo OVERNIGHT_DONE
} 2>&1 | tee "$LOG"
