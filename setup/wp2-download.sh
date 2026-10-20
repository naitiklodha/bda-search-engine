#!/bin/bash
# WP2 (plan §6.2): both corpora + MS MARCO dev/small queries/qrels onto the data disk and into HDFS.
set -uo pipefail
source ~/bda/env.sh
bash ~/bda/bin/bda-mount.sh || exit 1
echo "start: $(date)"
DATE=20261001   # newest complete simplewiki dump (checked 17 Oct: articlesdump=done, 342 MB)
UA="BDA-coursework/1.0 (student project; contact: cool.mit005@gmail.com)"
mkdir -p /data/raw/simplewiki /data/raw/msmarco

echo "=== Simple English Wikipedia $DATE ==="
cd /data/raw/simplewiki
F=simplewiki-$DATE-pages-articles.xml.bz2
t0=$(date +%s)
wget -nv -c --user-agent="$UA" "https://dumps.wikimedia.org/simplewiki/$DATE/$F"
wget -nv -c --user-agent="$UA" "https://dumps.wikimedia.org/simplewiki/$DATE/simplewiki-$DATE-sha1sums.txt"
echo "download: $(( $(date +%s) - t0 )) s, $(stat -c %s "$F") bytes"
grep " $F\$" "simplewiki-$DATE-sha1sums.txt" | sha1sum -c - || { echo "SHA1 FAILED"; exit 1; }
t0=$(date +%s)
echo "S1 ground truth: <page> count = $(bzcat "$F" | grep -c '<page>')   ($(( $(date +%s) - t0 )) s)"
hdfs dfs -mkdir -p /bda/raw/simplewiki && hdfs dfs -put -f "$F" /bda/raw/simplewiki/

echo "=== MS MARCO passage collectionandqueries ==="
cd /data/raw/msmarco
SRC=""
for U in https://msmarco.z22.web.core.windows.net/msmarcoranking/collectionandqueries.tar.gz \
         https://rgw.cs.uwaterloo.ca/JIMMYLIN-bucket0/data/collectionandqueries.tar.gz; do
  code=$(curl -s -L --connect-timeout 15 -r 0-0 -o /dev/null -w '%{http_code}' "$U")
  echo "$code $U"
  if [ "$code" = 206 ] || [ "$code" = 200 ]; then SRC=$U; break; fi
done
[ -n "$SRC" ] || { echo "NO MS MARCO SOURCE ANSWERED"; exit 1; }
t0=$(date +%s)
wget -nv -c "$SRC"
echo "MS MARCO source used: $SRC   download: $(( $(date +%s) - t0 )) s, $(stat -c %s collectionandqueries.tar.gz) bytes"
tar -xzf collectionandqueries.tar.gz
ls -la
wc -l collection.tsv qrels.dev.small.tsv queries.dev.tsv
awk -F'\t' 'NR==FNR{q[$1]=1; next} ($1 in q)' qrels.dev.small.tsv queries.dev.tsv > queries.dev.small.from-awk.tsv
echo "dev/small queries via awk: $(wc -l < queries.dev.small.from-awk.tsv)  (expect 6980)"
if [ -f queries.dev.small.tsv ]; then
  cmp -s <(sort queries.dev.small.tsv) <(sort queries.dev.small.from-awk.tsv) && echo "archive's queries.dev.small.tsv matches awk output" || echo "WARNING: archive's queries.dev.small.tsv differs from awk output"
else
  mv queries.dev.small.from-awk.tsv queries.dev.small.tsv
fi
hdfs dfs -mkdir -p /bda/raw/msmarco && hdfs dfs -put -f collection.tsv /bda/raw/msmarco/
echo "=== HDFS ==="
hdfs dfs -ls -h /bda/raw/simplewiki /bda/raw/msmarco
echo "end: $(date)"
echo WP2_DOWNLOAD_OK
