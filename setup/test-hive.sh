#!/bin/bash
# WP1 step 7 check: the INSERT launches a Tez DAG on YARN, which proves Hive -> Tez -> YARN works.
source ~/bda/env.sh
OUT="$BDA/logs/hive-test.out"
start=$(date +%s)
bash "$BDA/bin/bda-beeline.sh" -e "drop table if exists t; create table t(x int); insert into t values (1); select * from t; drop table t;" > "$OUT" 2>&1; rc=$?
echo "exit=$rc ($(( $(date +%s) - start )) s)"
grep -E 'Executing on YARN cluster|VERTICES|Map 1 |Reducer 2 |Status: |FAILED|Error:|Caused by|^\| +[0-9]|t\.x' "$OUT" | grep -v WARN | head -20
