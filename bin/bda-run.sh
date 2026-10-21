#!/bin/bash
# Run a long job detached, so it survives the wsl.exe call that started it (plan §5.10).
# Usage: bda-run.sh <name> <command...>      log: ~/bda/logs/<name>.log
#
# The script must not exit until the job is in its own session. WSL ends the calling session as soon as
# wsl.exe returns. The first version (setsid ... & then exit at once) lost that race: on 21 Oct the
# overnight job died before even creating its log. So we wait until the child's session ID differs from ours.
source ~/bda/env.sh
[ $# -ge 2 ] || { echo "usage: bda-run.sh <name> <command...>"; exit 2; }
name=$1; shift
mkdir -p "$BDA/logs"
log="$BDA/logs/$name.log"
setsid nohup "$@" > "$log" 2>&1 < /dev/null &
pid=$!
mysid=$(ps -o sid= -p $$ | tr -d ' ')
for i in $(seq 50); do
  sid=$(ps -o sid= -p "$pid" 2>/dev/null | tr -d ' ')
  [ -n "$sid" ] && [ "$sid" != "$mysid" ] && break
  sleep 0.1
done
sleep 2   # let it get going before this session ends
if ps -p "$pid" >/dev/null 2>&1; then
  echo "started '$name'  pid=$pid (own session $(ps -o sid= -p "$pid" | tr -d ' '))  log=$log"
else
  echo "FAILED to start '$name' (it exited straight away); see $log"; tail -5 "$log" 2>/dev/null; exit 1
fi
