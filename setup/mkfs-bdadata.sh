#!/bin/bash
# Format the BDA data disk. Device letters change when the WSL VM restarts, so the disk is
# found by what it is: exactly 300 GiB, no filesystem signature, not mounted. Exactly one must match.
set -uo pipefail
WANT=322122547200   # 307200 MiB = D:\BDA\hdfs.vhdx
lsblk -o NAME,SIZE,FSTYPE,LABEL,MOUNTPOINTS
MATCHES=()
for d in $(lsblk -dnbo NAME,SIZE | awk -v w="$WANT" '$2 == w {print $1}'); do
  dev=/dev/$d
  fst=$(lsblk -dno FSTYPE "$dev" | tr -d '[:space:]')
  mnt=$(lsblk -no MOUNTPOINTS "$dev" | tr -d '[:space:]')
  if [ -z "$fst" ] && [ -z "$mnt" ] && ! blkid -p "$dev" >/dev/null 2>&1; then MATCHES+=("$dev"); fi
done
echo "candidates: ${MATCHES[*]:-none}"
[ "${#MATCHES[@]}" -eq 1 ] || { echo "ABORT: need exactly one empty, unmounted 300 GiB disk"; exit 1; }
DEV=${MATCHES[0]}
echo "guards passed, formatting $DEV"
mkfs.ext4 -L bdadata "$DEV"
echo "mkfs exit=$?"
lsblk -o NAME,SIZE,FSTYPE,LABEL,MOUNTPOINTS "$DEV"
