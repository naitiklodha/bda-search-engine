#!/bin/bash
# Status of the BDA stack. Usage: bda-status.sh [--watch]   (--watch repeats every 60 s; Ctrl+C to quit)
source ~/bda/env.sh
show() {
  echo "=== BDA status  $(date '+%a %d %b %H:%M:%S') ==="
  echo "--- daemons (jps) ---"
  jps 2>/dev/null | grep -v Jps | sort -k2 | sed 's/^/  /' | grep . || echo "  (none running)"
  if jps 2>/dev/null | grep -q ' NameNode$'; then
    echo "--- HDFS ---"
    hdfs dfsadmin -report 2>/dev/null | grep -E '^(Configured Capacity|DFS Used:|DFS Remaining:|Live datanodes)' | head -4 | sed 's/^/  /'
  fi
  if jps 2>/dev/null | grep -q ' ResourceManager$'; then
    echo "--- YARN ---"
    yarn node -list 2>/dev/null | grep -E 'RUNNING|UNHEALTHY|LOST' | awk '{print "  node "$1" "$2"  containers="$NF}'
    yarn application -list 2>/dev/null | awk 'NR>2 {print "  app "$1"  "$2"  "$6"  "$8}'
  fi
  echo "--- disks ---"
  df -h / /data 2>/dev/null | awk 'NR==1 || /\/$|\/data$/ {printf "  %-10s %6s free of %6s  (%s)\n", $6, $4, $2, $5}'
  findmnt -n -t ext4 /data >/dev/null 2>&1 || echo "  WARNING: /data is NOT mounted (run bda-mount.sh)"
  avail=$(df -BG --output=avail / | tail -1 | tr -dc 0-9)
  [ "${avail:-99}" -lt 15 ] && echo "  WARNING: under 15 GB free on / (WSL disk on C:) — clear ~/bda/tmp or old logs"
  echo "--- memory ---"
  free -h | awk 'NR<=3 {print "  "$0}'
}
if [ "${1:-}" = "--watch" ]; then
  while true; do clear; show; sleep 60; done
else
  show
fi
