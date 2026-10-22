#!/bin/bash
# Stop the BDA stack. Usage: bda-stop.sh [hbase|all]   (default: all)
#   hbase = QueryServer + HBase;  all = QueryServer + HBase (if running) + JobHistory + YARN + HDFS, in reverse start order.
source ~/bda/env.sh
MODE=${1:-all}
case "$MODE" in hbase|all) ;; *) echo "usage: bda-stop.sh [hbase|all]"; exit 2 ;; esac
export HADOOP_STOP_TIMEOUT=30
running() { jps 2>/dev/null | awk '{print $2}' | grep -qx "$1"; }
stop() {  # stop <jps name> <command...>
  local name=$1; shift
  if running "$name"; then "$@" >/dev/null 2>&1; echo "  stopped $name"; fi
}
if pgrep -f bda.serve.QueryServer >/dev/null; then pkill -f bda.serve.QueryServer; echo "  stopped QueryServer (search page)"; fi
echo "HBase"
stop HRegionServer hbase-daemon.sh stop regionserver
stop HMaster       hbase-daemon.sh stop master
stop HQuorumPeer   hbase-daemon.sh stop zookeeper
if [ "$MODE" = all ]; then
  echo "YARN + JobHistory"
  stop JobHistoryServer mapred --daemon stop historyserver
  stop NodeManager      yarn --daemon stop nodemanager
  stop ResourceManager  yarn --daemon stop resourcemanager
  echo "HDFS"
  stop DataNode hdfs --daemon stop datanode
  stop NameNode hdfs --daemon stop namenode
fi
echo "--- still running ---"; jps | grep -v Jps || echo "  (nothing)"
