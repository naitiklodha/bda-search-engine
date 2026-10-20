-- WP5: filter Job 0 pages into clean article docs + side files for PageRank/HBase, plus summaries.
-- Run: bin/run-pig.sh   (pig -x mapreduce -param IN=/bda/wiki/job0/pages -param OUT=/bda/wiki/clean)
%default MINLEN 200
pages    = LOAD '$IN' USING PigStorage('\t')
           AS (docid:long, title:chararray, ns:int, redir:int, rtarget:chararray, len:int, text:chararray);
articles = FILTER pages BY ns == 0 AND redir == 0 AND len >= $MINLEN;
docs     = FOREACH articles GENERATE docid, text;
titles   = FOREACH articles GENERATE docid, title, (len > 300 ? SUBSTRING(text, 0, 300) : text) AS snippet;
redirs0  = FILTER pages BY ns == 0 AND redir == 1;
redirs   = FOREACH redirs0 GENERATE title, rtarget;
STORE docs   INTO '$OUT/docs'      USING PigStorage('\t');
STORE titles INTO '$OUT/titles'    USING PigStorage('\t');
STORE redirs INTO '$OUT/redirects' USING PigStorage('\t');

tagged   = FOREACH pages GENERATE (ns != 0 ? 'other_ns' : (redir == 1 ? 'redirect' : (len < $MINLEN ? 'stub' : 'article'))) AS c;
grp      = GROUP tagged BY c;
bycat    = FOREACH grp GENERATE group AS category, COUNT(tagged) AS n;
STORE bycat INTO '$OUT/summary/by_category' USING PigStorage('\t');
bucketed = FOREACH articles GENERATE (len / 500) * 500 AS bucket;
hgrp     = GROUP bucketed BY bucket;
hist     = FOREACH hgrp GENERATE group AS bucket, COUNT(bucketed) AS n;
STORE hist INTO '$OUT/summary/length_hist' USING PigStorage('\t');
