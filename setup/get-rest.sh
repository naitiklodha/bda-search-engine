#!/bin/bash
# WP1 step 5.2: download Hive 4.2.1, Tez 0.10.5, Pig 0.18.0 and Maven 3.9.16 onto the data disk,
# verify sha512 against downloads.apache.org, extract into ~/bda/opt.
set -euo pipefail
bash ~/bda/bin/bda-mount.sh || exit 1
T=/data/raw/tarballs; O=~/bda/opt; cd "$T"
C="curl -fL -sS --retry 3 --connect-timeout 15 -w %{filename_effective}\thttp=%{http_code}\t%{size_download}B\t%{speed_download}B/s\t%{time_total}s\n"
get() {  # $1 = path under the Apache dist root, $2 = file name. Projects publish .sha512, .sha256 or (Pig) .md5.
  [ -s "$2" ] || $C -O "https://dlcdn.apache.org/$1/$2" || $C -O "https://archive.apache.org/dist/$1/$2"
  for ext in sha512 sha256 md5; do
    if curl -fsSL --connect-timeout 15 -o "$2.$ext" "https://downloads.apache.org/$1/$2.$ext" 2>/dev/null \
       || curl -fsSL --connect-timeout 15 -o "$2.$ext" "https://archive.apache.org/dist/$1/$2.$ext" 2>/dev/null; then
      case $ext in sha512) n=128 ;; sha256) n=64 ;; md5) n=32 ;; esac
      want=$(tr 'A-F' 'a-f' < "$2.$ext" | grep -oE "[0-9a-f]{$n}" | head -1)
      have=$(${ext}sum "$2" | cut -d' ' -f1)
      [ "$want" = "$have" ] && { echo "$ext OK  $2"; return 0; }
      echo "${ext^^} MISMATCH $2"; exit 1
    fi
    rm -f "$2.$ext"
  done
  echo "NO CHECKSUM FILE FOUND for $2"; exit 1
}
echo "start: $(date)"
get hive/hive-4.2.1          apache-hive-4.2.1-bin.tar.gz
get tez/0.10.5               apache-tez-0.10.5-bin.tar.gz
get pig/pig-0.18.0           pig-0.18.0.tar.gz
get maven/maven-3/3.9.16/binaries apache-maven-3.9.16-bin.tar.gz
for f in apache-hive-4.2.1-bin.tar.gz apache-tez-0.10.5-bin.tar.gz pig-0.18.0.tar.gz apache-maven-3.9.16-bin.tar.gz; do
  tar -xzf "$f" -C "$O"
done
ls "$O"
du -sh "$O"
echo "end: $(date)"
echo GET_REST_OK
