#!/bin/bash
# WP1 step 5.1: move the smoke-tested installs into ~/bda/opt (no copies, to keep C: small),
# move the kept tarballs onto the data disk, and reset configs to pristine copies from the tarballs.
set -euo pipefail
bash ~/bda/bin/bda-mount.sh || exit 1
S=~/smoke; B=~/bda; T=/data/raw/tarballs
mkdir -p "$B/opt" "$T" "$S/old-logs"

for d in jdk-21 hadoop-3.4.3 hbase-2.6.6-hadoop3 spark-4.1.2-bin-hadoop3; do
  if [ -d "$S/$d" ] && [ ! -e "$B/opt/$d" ]; then mv "$S/$d" "$B/opt/$d"; echo "moved $d"; fi
done
for f in "$S"/*.tar.gz "$S"/*.tgz "$S"/*.sha512; do
  [ -e "$f" ] && mv "$f" "$T/" && echo "moved $(basename "$f") -> $T"
done

# Smoke-test logs and HBase test data are redundant: park them in ~/smoke (deleted at project end).
[ -d "$B/opt/hadoop-3.4.3/logs" ] && mv "$B/opt/hadoop-3.4.3/logs" "$S/old-logs/hadoop-logs"
[ -d "$B/opt/hbase-2.6.6-hadoop3/logs" ] && mv "$B/opt/hbase-2.6.6-hadoop3/logs" "$S/old-logs/hbase-logs"
[ -d "$B/opt/hbase-2.6.6-hadoop3/logs.attempt1" ] && mv "$B/opt/hbase-2.6.6-hadoop3/logs.attempt1" "$S/old-logs/hbase-logs.attempt1"

# Pristine configs (the smoke test appended JAVA_HOME=~/smoke/... and standalone HBase settings).
TMP=$(mktemp -d)
tar -xzf "$T/hadoop-3.4.3.tar.gz" -C "$TMP" hadoop-3.4.3/etc/hadoop
rm -rf "$B/opt/hadoop-3.4.3/etc/hadoop" && mv "$TMP/hadoop-3.4.3/etc/hadoop" "$B/opt/hadoop-3.4.3/etc/hadoop"
tar -xzf "$T/hbase-2.6.6-hadoop3-bin.tar.gz" -C "$TMP" hbase-2.6.6-hadoop3/conf
rm -rf "$B/opt/hbase-2.6.6-hadoop3/conf" && mv "$TMP/hbase-2.6.6-hadoop3/conf" "$B/opt/hbase-2.6.6-hadoop3/conf"
rm -rf "$TMP"
echo "configs reset: smoke edits remaining = $(grep -l smoke "$B"/opt/hadoop-3.4.3/etc/hadoop/* "$B"/opt/hbase-2.6.6-hadoop3/conf/* 2>/dev/null | wc -l)"

ls -la "$B/opt" "$T"
du -sh "$S" "$B/opt"
