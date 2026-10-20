#!/bin/bash
# WP4 -> WP7a for simplewiki in one go, steps strictly one after another so wall times are comparable:
# Job 0 (split) -> Pig -> index (Jobs 2,1,3 + S2 checks) -> combiner experiment (S3) -> Hive stats + split keys
# -> Job 0 single-split comparison (S1, slowest, last). Usage: run-wiki-pipeline.sh   (log: ~/bda/logs/wiki-pipeline.log)
source ~/bda/env.sh
B=$BDA/bin
{
  echo "######## start $(date)"
  echo "######## WP4 Job 0 (split)";        bash "$B/run-job0.sh" split
  echo "######## WP5 Pig";                  bash "$B/run-pig.sh" 200
  echo "######## WP6 index wiki";           bash "$B/run-index.sh" wiki
  echo "######## WP6 combiner experiment";  bash "$B/run-combiner-exp.sh"
  echo "######## WP7a Hive";               bash "$B/run-hive.sh" hive/ddl.sql wiki
                                            bash "$B/run-hive.sh" hive/stats.sql wiki
                                            bash "$B/run-hive.sh" hive/split_keys.sql wiki
  cp "$BDA/repo/charts/data/split_keys.tsv" "$BDA/repo/hive/split_keys.txt"
  echo "######## WP4 Job 0 (single split)"; bash "$B/run-job0.sh" single
  echo "######## end $(date)"
  echo PIPELINE_DONE
} 2>&1 | tee "$BDA/logs/wiki-pipeline.log"
