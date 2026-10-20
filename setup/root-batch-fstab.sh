#!/bin/bash
# Root batch 2 (needs approval): mount the data disk by label from inside Linux, via systemd automount.
set -euo pipefail
cp -n /etc/fstab /etc/fstab.bak-bda
# 1) /data becomes a real mount point instead of a symlink to /mnt/wsl/bdadata (removes the link only)
if [ -L /data ]; then rm /data; fi
mkdir -p /data
# 2) fstab: mount LABEL=bdadata at /data on first access; never block boot if the disk isn't attached
grep -q 'LABEL=bdadata' /etc/fstab || \
  echo 'LABEL=bdadata  /data  ext4  defaults,noatime,nofail,x-systemd.automount,x-systemd.device-timeout=10s  0  2' >> /etc/fstab
# 3) drop the boot hook (Windows programs can't run that early in boot: Exec format error)
sed -i '/bda-mount-vhdx/d' /etc/wsl.conf
systemctl daemon-reload
systemctl start data.automount
ls /data >/dev/null
findmnt /data
echo "--- /etc/fstab ---"; grep -v '^#' /etc/fstab
echo "--- /etc/wsl.conf ---"; cat /etc/wsl.conf
