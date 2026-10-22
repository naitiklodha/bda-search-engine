#!/bin/bash
# WP11 step 2: the fixed 1,000-query sample (eval/sample_qids.txt) -> queries + qrels, tokenised with the
# project tokeniser, uploaded to HDFS. Also estimates the size of the scoring join (sum over query terms of df).
source ~/bda/env.sh
R=$BDA/repo; J=$R/java/target/bda-search-1.0.jar
cd "$R"
awk -F'\t' 'NR==FNR {s[$1]; next} ($1 in s)' eval/sample_qids.txt eval/queries.dev.small.tsv > eval/queries_sample.tsv
awk -F'\t' 'NR==FNR {s[$1]; next} ($1 in s) {print $1" 0 "$3" "$4}' eval/sample_qids.txt eval/qrels.dev.small.tsv > eval/qrels_sample.tsv
echo "queries_sample.tsv: $(wc -l < eval/queries_sample.tsv) lines (expect 1000)"
echo "qrels_sample.tsv:   $(wc -l < eval/qrels_sample.tsv) judgments, $(cut -d' ' -f1 eval/qrels_sample.tsv | sort -u | wc -l) queries"
java -cp "$J" bda.tools.TokenizeQueries eval/queries_sample.tsv eval/queries_sample.tok.tsv
hdfs dfs -mkdir -p /bda/msmarco/eval
hdfs dfs -put -f eval/queries_sample.tok.tsv /bda/msmarco/eval/
echo "--- scoring-join size estimate (MS MARCO lexicon) ---"
awk -F'\t' 'FNR==NR { n = split($2, t, " "); for (i = 1; i <= n; i++) cnt[t[i]]++; qterms += n; next }
            ($1 in cnt) { pairs += cnt[$1] * $2; post += $2; vocab++; if ($2 > maxdf) { maxdf = $2; mt = $1 } }
            END { printf "query-term occurrences=%d  distinct query terms in index=%d\n", qterms, vocab;
                  printf "postings rows for query vocabulary=%d  (term,doc) x query pairs=%d (%.2f billion)\n", post, pairs, pairs/1e9;
                  printf "largest df: %s = %d\n", mt, maxdf }' \
  eval/queries_sample.tok.tsv <(hdfs dfs -cat /bda/msmarco/index/lexicon/part-*)
echo "--- toy example (independent Python implementation, pins the cross-language test) ---"
"$BDA/venv/bin/python" - <<'EOF'
import math
N, avgdl, ln, norm, pr = 10, 5.0, 4, 2.5, 0.05
terms = [(2, 3), (5, 1)]                       # (df, tf) for the two query terms
def bm25(k1, b):
    return sum(math.log(1 + (N - df + 0.5) / (df + 0.5)) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * ln / avgdl))
               for df, tf in terms)
tfidf = sum(math.log(N / df) ** 2 * (1 + math.log(tf)) / norm for df, tf in terms)
print(f"TOY bm25(0.9,0.4)={bm25(0.9, 0.4)!r}")
print(f"TOY bm25(1.2,0.75)={bm25(1.2, 0.75)!r}")
print(f"TOY tfidf={tfidf!r}")
print(f"TOY prboost(w=0.5,pr=0.05,N=10)={0.5 * math.log(pr * N)!r}")
EOF
