#!/bin/bash
echo "distro up since: $(uptime -s)   now: $(date '+%F %T')"
lsblk -o NAME,SIZE,FSTYPE,LABEL,MOUNTPOINTS
ls -la /mnt/wsl/ 2>&1 | head
echo "--- boot hook in effect? ---"
grep -n command /etc/wsl.conf
echo "--- journal: wsl boot command / interop ---"
journalctl -b --no-pager 2>/dev/null | grep -iE 'schtasks|boot command|bda-mount' | tail -5
