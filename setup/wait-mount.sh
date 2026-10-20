#!/bin/bash
# After a WSL restart: wait up to 60 s for the boot hook to re-attach the data disk, then test writing as mit.
for i in $(seq 60); do mountpoint -q /mnt/wsl/bdadata && break; sleep 1; done
if mountpoint -q /mnt/wsl/bdadata; then
  echo "MOUNTED after ~${i}s"
  df -h /data/ | tail -1
  touch /data/.bda-write-test && rm /data/.bda-write-test && echo "write test as $(whoami): OK"
else
  echo "NOT MOUNTED after 60 s"
fi
