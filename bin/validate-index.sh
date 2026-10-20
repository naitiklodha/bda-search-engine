#!/bin/bash
# WP6 integrity checks (plan S2). Usage: validate-index.sh wiki|msmarco
#  1) a ~5,000-line random sample of postings: docIDs strictly increasing; df = #entries; cf = sum of tf
#  2) sum(cf) over the lexicon == totalTokens from Job 2 (proves Jobs 1 and 2 tokenised identically)
#  3) lexicon rows == postings lines
source ~/bda/env.sh
C=${1:?usage: validate-index.sh wiki|msmarco}
O=/bda/$C/index
echo "--- meta.tsv ---"; hdfs dfs -cat "$O/meta/meta.tsv"
TOKENS=$(hdfs dfs -cat "$O/meta/meta.tsv" | awk -F'\t' '$1=="totalTokens"{print $2}')

read -r LEXROWS SUMDF SUMCF < <(hdfs dfs -cat "$O/lexicon/part-*" | awk -F'\t' '{n++; df+=$2; cf+=$3} END {printf "%d %d %d\n", n, df, cf}')
echo "lexicon rows=$LEXROWS  sum(df)=total (term,doc) pairs=$SUMDF  sum(cf)=$SUMCF"

hdfs dfs -cat "$O/postings/part-*" | awk -F'\t' -v p="$(awk -v n="$LEXROWS" 'BEGIN{printf "%.8f", (n>0 ? 5000/n : 1)}')" '
  BEGIN { srand(42) }
  { lines++ }
  rand() < p {
    sampled++
    n = split($4, a, ","); prev = -1; sumtf = 0; ok = 1
    for (i = 1; i <= n; i++) { split(a[i], b, ":"); d = b[1] + 0; if (d <= prev) ok = 0; prev = d; sumtf += b[2] }
    if (n != $2 || sumtf != $3) ok = 0
    if (!ok) { bad++; if (bad <= 3) print "  BAD sample line for term: " $1 }
  }
  END { printf "postings lines=%d  sampled=%d  bad=%d\n", lines, sampled, bad + 0 > "/dev/stderr"; print lines > "/tmp/bda-postings-lines" }'
POSTLINES=$(cat /tmp/bda-postings-lines)

pass() { [ "$1" = "$2" ] && echo "PASS  $3 ($1)" || echo "FAIL  $3 ($1 vs $2)"; }
echo "--- S2 integrity ($C) ---"
pass "$SUMCF" "$TOKENS" "sum(cf) == totalTokens"
pass "$LEXROWS" "$POSTLINES" "lexicon rows == postings lines"
