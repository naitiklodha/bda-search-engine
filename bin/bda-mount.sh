#!/bin/bash
# Make sure the BDA data disk (D:\BDA\hdfs.vhdx, label bdadata) is attached and mounted at /data.
# No admin needed: if the disk isn't attached, it runs the Windows task bda-mount-vhdx, which attaches it
# with admin rights. Linux then mounts it by label via the systemd automount in /etc/fstab.
set -uo pipefail
attached() { lsblk -rno LABEL 2>/dev/null | grep -qx bdadata; }
mounted()  { ls /data >/dev/null 2>&1; findmnt -n -t ext4 /data >/dev/null 2>&1; }

if ! attached; then
  echo "data disk not attached -> running Windows task bda-mount-vhdx"
  /mnt/c/Windows/System32/schtasks.exe /run /tn bda-mount-vhdx >/dev/null 2>&1 || true
  for i in $(seq 60); do attached && break; sleep 1; done
fi
if ! attached; then
  echo "FAIL: data disk still not attached after 60 s."
  echo "      In an Administrator PowerShell run:  wsl --mount --vhd D:\\BDA\\hdfs.vhdx --type ext4 --name bdadata"
  exit 1
fi
for i in $(seq 20); do mounted && break; sleep 1; done
if ! mounted; then echo "FAIL: disk attached but /data did not mount"; exit 1; fi
echo "OK: /data mounted ($(df -h /data | awk 'NR==2{print $4" free of "$2}'))"
