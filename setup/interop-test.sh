#!/bin/bash
echo "--- Windows interop from a normal session ---"
/mnt/c/Windows/System32/schtasks.exe /query /tn bda-mount-vhdx /fo LIST 2>&1 | tr -d '\r' | grep -E 'TaskName|Status'
echo "interop exit=$?"
echo "--- block device by label (as user) ---"
lsblk -rno NAME,LABEL | awk '$2=="bdadata"{print "/dev/"$1}'
