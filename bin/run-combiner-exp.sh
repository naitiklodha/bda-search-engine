#!/bin/bash
# WP6 combiner experiment (plan S3), simplewiki: Job 1 with the combiner OFF into /bda/tmp, compared with
# the combiner-ON run that run-index.sh wiki already logged. Output is deleted afterwards; counters are kept.
source ~/bda/env.sh
J=$BDA/repo/java/target/bda-search-1.0.jar
OUT=/bda/tmp/postings-nocombiner
hdfs dfs -rm -r -f -skipTrash "$OUT" >/dev/null 2>&1
hadoop jar "$J" bda.index.PostingsJob -D bda.combiner=false -D mapreduce.job.reduces=4 \
  /bda/wiki/clean/docs "$OUT" > "$BDA/logs/combiner-off.log" 2>&1 || { tail -20 "$BDA/logs/combiner-off.log"; exit 1; }
ON=$BDA/logs/index-wiki-job1.log
OFF=$BDA/logs/combiner-off.log
echo "counter                         combiner ON        combiner OFF"
for k in wall_seconds MAP_OUTPUT_RECORDS MAP_OUTPUT_BYTES MAP_OUTPUT_MATERIALIZED_BYTES COMBINE_INPUT_RECORDS \
         COMBINE_OUTPUT_RECORDS REDUCE_SHUFFLE_BYTES REDUCE_INPUT_RECORDS SPILLED_RECORDS CPU_MILLISECONDS GC_TIME_MILLIS; do
  on=$(grep -oP "(^|\s)$k=\K[0-9]+" "$ON" | tail -1); off=$(grep -oP "(^|\s)$k=\K[0-9]+" "$OFF" | tail -1)
  printf "%-30s %15s %18s\n" "$k" "$on" "$off"
done
# identical output either way?
same=$(cmp -s <(hdfs dfs -cat /bda/wiki/index/postings/part-* | sort | md5sum) <(hdfs dfs -cat "$OUT/part-*" | sort | md5sum) && echo yes || echo NO)
echo "postings identical with and without combiner: $same"
hdfs dfs -rm -r -f -skipTrash "$OUT" >/dev/null 2>&1
