#!/bin/bash
# WP9: create the three serving tables (plan WP9). `postings` is pre-split into 16 regions at the 15 Hive
# split keys (hive/split_keys.txt: equal shares of cumulative df), so the load and queries spread evenly.
# Tables that already exist are left alone. Usage: hbase/create_tables.sh
source ~/bda/env.sh
R=$BDA/repo
KEYS=$(sed "s/.*/'&'/" "$R/hive/split_keys.txt" | paste -sd, -)
exists() { echo "exists '$1'" | hbase shell -n 2>/dev/null | grep -q 'does exist'; }
cmds=""
exists postings || cmds+="create 'postings', {NAME => 'p', VERSIONS => 1, BLOOMFILTER => 'ROW', DATA_BLOCK_ENCODING => 'FAST_DIFF'}, SPLITS => [$KEYS]"$'\n'
exists docs     || cmds+="create 'docs', {NAME => 'd', VERSIONS => 1}"$'\n'
exists terms    || cmds+="create 'terms', {NAME => 't', VERSIONS => 1, BLOOMFILTER => 'ROW'}"$'\n'
if [ -n "$cmds" ]; then
  printf '%s' "$cmds"
  printf '%s' "$cmds" | hbase shell -n 2>&1 | grep -E 'Created table|ERROR' || true
else
  echo "all three tables already exist"
fi
echo "list" | hbase shell -n 2>/dev/null | grep -E '^(docs|postings|terms)$'
