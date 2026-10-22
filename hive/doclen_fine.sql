-- WP7a addendum: a finer document-length histogram (bins of 5 tokens, up to 300) for short-document corpora
-- (MS MARCO passages average 38.6 tokens, so 50-token bins collapse into two bars). Run: bin/run-hive.sh hive/doclen_fine.sql msmarco
USE bda;
-- bucket (tokens, width 5), docs
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_doclen_fine' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT CAST(FLOOR(len / 5) * 5 AS INT) AS bucket, COUNT(*) FROM ${C}_docstats WHERE len < 300
GROUP BY CAST(FLOOR(len / 5) * 5 AS INT) ORDER BY bucket;
