-- WP7a: corpus statistics for the report (plan WP7a). Run with bin/run-hive.sh hive/stats.sql <corpus>.
-- Each result goes to ${OUTDIR}/<name>/ (one session, so the embedded Hive starts once); the runner
-- turns every directory into charts/data/<name>.tsv (no header; columns as in the SELECT).
-- ${C} = wiki | msmarco
USE bda;

-- vocab, hapax, hapax_pct, total_tokens, postings (= sum df)
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_headline' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT COUNT(*), SUM(IF(cf = 1, 1, 0)), ROUND(100.0 * SUM(IF(cf = 1, 1, 0)) / COUNT(*), 2), SUM(cf), SUM(df)
FROM ${C}_lexicon;

-- docs, distinct_docs, tokens, avg_len, min_len, max_len   (integrity: docs == distinct_docs)
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_docstats_summary' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT COUNT(*), COUNT(DISTINCT docid), SUM(len), ROUND(AVG(len), 2), MIN(len), MAX(len) FROM ${C}_docstats;

-- term, df, cf   (top 50 by collection frequency)
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_top50_cf' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT term, df, cf FROM ${C}_lexicon ORDER BY cf DESC, term LIMIT 50;

-- term, df, cf   (top 50 by document frequency)
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_top50_df' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT term, df, cf FROM ${C}_lexicon ORDER BY df DESC, term LIMIT 50;

-- rank, cf   (Zipf: all ranks <= 1000, then every 100th rank)
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_zipf' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT rnk, cf FROM (SELECT ROW_NUMBER() OVER (ORDER BY cf DESC, term) AS rnk, cf FROM ${C}_lexicon) z
WHERE rnk <= 1000 OR rnk % 100 = 0 ORDER BY rnk;

-- bucket (tokens, width 50), docs
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_doclen_hist' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT CAST(FLOOR(len / 50) * 50 AS INT) AS bucket, COUNT(*) FROM ${C}_docstats GROUP BY CAST(FLOOR(len / 50) * 50 AS INT) ORDER BY bucket;

-- magnitude (floor(log10 df)), terms
INSERT OVERWRITE LOCAL DIRECTORY '${OUTDIR}/${C}_df_magnitude' ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
SELECT CAST(FLOOR(LOG10(df)) AS INT) AS mag, COUNT(*) FROM ${C}_lexicon GROUP BY CAST(FLOOR(LOG10(df)) AS INT) ORDER BY mag;
