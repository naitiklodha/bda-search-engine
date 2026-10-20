#!/bin/bash
# Approved root batch (20 Oct): /data link, ownership, wsl.conf boot hook for the mount task.
set -euo pipefail
mountpoint -q /mnt/wsl/bdadata || { echo "ABORT: /mnt/wsl/bdadata is not mounted"; exit 1; }
[ -L /data ] || ln -s /mnt/wsl/bdadata /data
chown mit:mit /mnt/wsl/bdadata
cp -n /etc/wsl.conf /etc/wsl.conf.bak-bda
if ! grep -q 'bda-mount-vhdx' /etc/wsl.conf; then
  sed -i '/^systemd=true/a command="/mnt/c/Windows/System32/schtasks.exe /run /tn bda-mount-vhdx"' /etc/wsl.conf
fi
echo "--- /etc/wsl.conf ---"; cat /etc/wsl.conf
echo "--- links/ownership ---"; ls -ld /data /mnt/wsl/bdadata
