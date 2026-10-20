#!/bin/bash
# Run a long job detached, so it survives the wsl.exe call that started it (plan §5.10).
# Usage: bda-run.sh <name> <command...>      log: ~/bda/logs/<name>.log
source ~/bda/env.sh
[ $# -ge 2 ] || { echo "usage: bda-run.sh <name> <command...>"; exit 2; }
name=$1; shift
mkdir -p "$BDA/logs"
log="$BDA/logs/$name.log"
setsid nohup "$@" > "$log" 2>&1 < /dev/null &
echo "started '$name'  pid=$!  log=$log"
