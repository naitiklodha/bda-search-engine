#!/bin/bash
mountpoint /mnt/wsl/bdadata
df -h /mnt/wsl/bdadata | tail -1
ls -ld /mnt/wsl/bdadata
ls -la /data 2>&1 | head -1
grep -v '^#' /etc/wsl.conf
