-- WP7b: one row per article for HBase's `docs` table: title, snippet, length, TF-IDF norm, PageRank.
-- Needs WP8 outputs (/bda/wiki/spark/norms, /bda/wiki/spark/pagerank). Run: bin/run-hive.sh hive/docs_full.sql wiki
USE bda;
INSERT OVERWRITE TABLE bda.wiki_docs_full
SELECT t.docid, t.title, t.snippet, d.len, n.norm, COALESCE(p.pr, 0.0)
FROM bda.wiki_titles t JOIN bda.wiki_docstats d ON t.docid = d.docid
LEFT JOIN bda.wiki_norms n ON t.docid = n.docid LEFT JOIN bda.wiki_pagerank p ON t.docid = p.docid;

-- titles, docs_full, with_norm, with_pr, sum_pr   (acceptance: docs_full rows = titles rows)
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/wiki_docs_full_check' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT (SELECT COUNT(*) FROM bda.wiki_titles), COUNT(*), COUNT(norm), SUM(IF(pr > 0, 1, 0)), ROUND(SUM(pr), 9)
FROM bda.wiki_docs_full;
