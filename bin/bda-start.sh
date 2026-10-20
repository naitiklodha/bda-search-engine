#!/bin/bash
# Start the BDA stack. Usage: bda-start.sh [core|hbase|all]   (default: core)
#   core      = HDFS + YARN + JobHistory
#   hbase/all = core + HBase (ZooKeeper, Master, RegionServer)
# Every daemon starts under setsid, so it survives the wsl.exe call that started it (plan §5.10).
source ~/bda/env.sh
MODE=${1:-core}
case "$MODE" in core|hbase|all) ;; *) echo "usage: bda-start.sh [core|hbase|all]"; exit 2 ;; esac

bash "$BDA/bin/bda-mount.sh" || { echo "REFUSING TO START: data disk not mounted"; exit 1; }
mkdir -p "$BDA/logs/hadoop" "$BDA/run" /data/hdfs/dn /data/yarn/local /data/yarn/userlogs

running() { jps 2>/dev/null | awk '{print $2}' | grep -qx "$1"; }
start() {  # start <jps name> <command...>
  local name=$1; shift
  if running "$name"; then echo "  $name already running"; else setsid "$@" >/dev/null 2>&1; echo "  started $name"; fi
}

echo "HDFS"
start NameNode hdfs --daemon start namenode
start DataNode hdfs --daemon start datanode
hdfs dfsadmin -safemode wait 2>/dev/null | sed 's/^/  /'
echo "YARN + JobHistory"
start ResourceManager yarn --daemon start resourcemanager
start NodeManager     yarn --daemon start nodemanager
start JobHistoryServer mapred --daemon start historyserver
for i in $(seq 30); do yarn node -list 2>/dev/null | grep -q RUNNING && break; sleep 2; done
yarn node -list 2>/dev/null | grep -q RUNNING && echo "  NodeManager registered (RUNNING)" || echo "  WARNING: NodeManager not RUNNING yet"

if [ "$MODE" != core ]; then
  echo "HBase"
  start HQuorumPeer   hbase-daemon.sh start zookeeper
  start HMaster       hbase-daemon.sh start master
  start HRegionServer hbase-daemon.sh start regionserver
  echo "  waiting for the HBase master (up to 120 s)..."
  ok=0
  for i in $(seq 24); do
    if echo "status" | hbase shell -n >/dev/null 2>&1; then ok=1; break; fi
    sleep 5
  done
  [ $ok = 1 ] && echo "  HBase master ready" || echo "  WARNING: HBase master not ready after 120 s (see $HBASE_HOME/logs)"
fi
echo "--- jps ---"; jps | grep -v Jps | sort -k2
