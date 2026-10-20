-- WP7a: 15 split points for 16 HBase regions with roughly equal posting counts (plan WP7a / §4.2).
-- Terms are Zipf-distributed, so splits come from the cumulative document frequency, not from term counts.
USE bda;
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/split_keys' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT MIN(term) FROM (
  SELECT term, SUM(df) OVER (ORDER BY term ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum,
         SUM(df) OVER () AS tot
  FROM wiki_lexicon) c
WHERE FLOOR(cum * 16 / tot) BETWEEN 1 AND 15
GROUP BY FLOOR(cum * 16 / tot) ORDER BY 1;
