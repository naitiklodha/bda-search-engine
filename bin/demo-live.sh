#!/bin/bash
# Short live actions for showing the system working in real time (plan §9). Each finishes in under ~30 s.
#   demo-live.sh mr               a real MapReduce job (tokenise + count 2,000 passages); watch it on :8088
#   demo-live.sh hive [word]      a live Hive-on-Tez query: how many articles use the word (default: photosynthesis)
#   demo-live.sh term [word]      how the tokeniser turns a word into its index term
#   demo-live.sh pagerank         top 10 pages by PageRank
#   demo-live.sh clusters         the 20 K-means topic clusters
#   demo-live.sh hbase [word]     HBase prefix scan of the word's postings          (after WP9)
#   demo-live.sh search [query]   ask the search server, all three models           (after WP10)
#   demo-live.sh status           what's running, disk, memory
source ~/bda/env.sh
R=$BDA/repo
J=$R/java/target/bda-search-1.0.jar
stem() { java -cp "$J" bda.tools.TokenizeText "$*"; }

case "${1:-}" in
  mr)
    IN=/bda/tmp/wc-in.tsv
    hdfs dfs -test -e "$IN" || head -2000 /data/raw/msmarco/collection.tsv | hdfs dfs -put -f - "$IN"
    OUT=/bda/tmp/demo-wc-$(date +%H%M%S)
    echo ">> Live MapReduce job: tokenise 2,000 MS MARCO passages and count every word."
    echo ">> Watch it appear at http://localhost:8088/cluster, then its counters in http://localhost:19888"
    hadoop jar "$J" bda.tools.TokenWordCount "$IN" "$OUT" 2>&1 \
      | grep -E 'Running job|map [0-9]+% reduce [0-9]+%|completed successfully|Map output records|Combine (input|output) records|Reduce shuffle bytes' \
      | sed 's/^.*INFO [a-zA-Z.]*: //; s/^\t*//'
    echo ">> Top 10 words in those passages:"
    hdfs dfs -cat "$OUT/part-*" | sort -t$'\t' -k2,2nr | head -10 | sed 's/^/   /'
    hdfs dfs -rm -r -f -skipTrash "$OUT" >/dev/null 2>&1 ;;
  term)
    W=${2:-photosynthesis}
    echo ">> '$W'  ->  index term(s): $(stem "$W")" ;;
  hive)
    W=${2:-photosynthesis}; T=$(stem "$W" | awk '{print $1}')
    echo ">> '$W' is indexed as '$T'. Live Hive query on Tez (~20 s, mostly Hive starting up):"
    bash "$BDA/bin/bda-beeline.sh" --outputformat=table \
      -e "USE bda; SELECT term, df AS articles_containing, cf AS total_occurrences FROM wiki_lexicon WHERE term = '$T' OR term LIKE '${T}%' ORDER BY df DESC LIMIT 8;" \
      | grep -E '^\+|^\||DAG finished|rows selected' ;;
  pagerank)
    echo ">> Most important pages by PageRank (Spark, 10 iterations over 3.27M links):"
    head -11 "$R/eval/pagerank_top50.tsv" | column -t -s$'\t' ;;
  clusters)
    echo ">> K-means: 20 topic clusters found from vocabulary alone (Spark ML):"
    grep '^| [0-9]' "$R/eval/kmeans_k20.md" | awk -F'|' '{printf "  %-3s %8s  %s\n", $2, $3, $4}' ;;
  hbase)
    W=${2:-photosynthesis}; T=$(stem "$W" | awk '{print $1}')
    if echo "exists 'postings'" | hbase shell -n 2>/dev/null | grep -q 'does exist'; then
      echo ">> HBase prefix scan: the first 5 postings of '$T' (rowkey = term#docID)"
      echo "scan 'postings', {ROWPREFIXFILTER => '$T#', LIMIT => 5}" | hbase shell -n 2>/dev/null | grep -E 'column=|row\(s\)'
    else
      echo "(HBase tables arrive with WP9)"
    fi ;;
  search)
    Q=${2:-photosynthesis}
    if curl -s -o /dev/null http://localhost:8090/; then
      "$BDA/venv/bin/python" - "$Q" <<'EOF'
import json, sys, urllib.parse, urllib.request
q = sys.argv[1]
for m in ["boolean", "tfidf", "bm25"]:
    r = json.load(urllib.request.urlopen(f"http://localhost:8090/search?q={urllib.parse.quote(q)}&model={m}&k=5"))
    t = r["timings"]
    print(f">> {m:8s} {r['total']:>7,} results in {t['total']:.0f} ms (scan {t['scan']:.0f} · score {t['score']:.0f} · fetch {t['fetch']:.0f})"
          + (f"  [{r['message']}]" if r["message"] else ""))
    for h in r["results"]:
        print(f"     {h['rank']}. {h['title']}  ({h['score']})")
EOF
    else
      echo "(search server arrives with WP10)"
    fi ;;
  status) bash "$BDA/bin/bda-status.sh" ;;
  *) sed -n '2,10p' "$0" ;;
esac
