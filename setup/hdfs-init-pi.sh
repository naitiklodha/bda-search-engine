#!/bin/bash
# WP1 steps 6.5-6.7: SIGHUP check, HDFS layout, Pi example on YARN.
source ~/bda/env.sh
echo "--- SIGHUP check in daemon logs ---"
grep -l 'RECEIVED SIGNAL 1: SIGHUP' "$BDA"/logs/hadoop/*.log 2>/dev/null && echo "SIGHUP FOUND" || echo "  no SIGHUP in any daemon log"
echo "--- HDFS layout ---"
hdfs dfs -mkdir -p /bda/raw /bda/tmp /user/mit /tmp /spark-logs /apps/tez /user/hive/warehouse
hdfs dfs -chmod 1777 /tmp
hdfs dfs -chmod 775 /user/hive/warehouse
hdfs dfs -ls / | sed 's/^/  /'
echo "--- Pi on YARN ---"
start=$(date +%s)
hadoop jar "$HADOOP_HOME/share/hadoop/mapreduce/hadoop-mapreduce-examples-3.4.3.jar" pi 4 1000 2>&1 \
  | grep -E 'Estimated value|Running job|completed successfully|Exception|ERROR' | sed 's/^.*INFO [a-z.]*: //'
echo "  wall time: $(( $(date +%s) - start )) s"
