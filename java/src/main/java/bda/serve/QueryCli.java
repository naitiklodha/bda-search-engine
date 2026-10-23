package bda.serve;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;

import bda.common.Scorer;
import bda.common.Tokenizer;

/**
 * Terminal version of the search page (plan WP10; the demo fallback).
 * Usage: java --add-opens java.base/java.nio=ALL-UNNAMED -cp bda-search-1.0.jar:$(hbase mapredcp):$HBASE_HOME/conf:$(hadoop classpath) bda.serve.QueryCli [--model bm25|tfidf|boolean] [--pr] [--k 10] "query"
 *        java -cp bda-search-1.0.jar bda.serve.QueryCli --tokenize "text"
 */
public final class QueryCli {

  public static void main(String[] args) throws Exception {
    String model = "bm25";
    boolean pr = false;
    int k = 10;
    List<String> words = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--tokenize" -> {
          System.out.println(String.join(" ", new Tokenizer().tokenize(String.join(" ", List.of(args).subList(i + 1, args.length)))));
          return;
        }
        case "--model" -> model = args[++i];
        case "--pr" -> pr = true;
        case "--k" -> k = Integer.parseInt(args[++i]);
        default -> words.add(args[i]);
      }
    }
    // Local copy of the index meta (see QueryServer): fetched by demo-start.sh / demo-live.sh.
    try (SearchService svc = new SearchService(new Configuration(), "file:///home/mit/bda/tmp/wiki-meta.tsv")) {
      SearchService.Response r = svc.search(String.join(" ", words), model, pr, k,
          Scorer.DEFAULT_K1, Scorer.DEFAULT_B, Scorer.DEFAULT_PR_WEIGHT);
      System.out.printf("query='%s' model=%s%s terms=%s unknown=%s%n", r.query, r.model, r.pr ? "+pagerank" : "", r.terms, r.unknown);
      if (!r.message.isEmpty()) {
        System.out.println(r.message);
      }
      for (SearchService.Hit h : r.hits) {
        String snip = h.snippet() == null ? "" : h.snippet();
        System.out.printf("%2d. %-40s  %8.4f  %s%n", h.rank(), h.title(), h.score(), snip.substring(0, Math.min(90, snip.length())));
      }
      System.out.printf("%d results · %.1f ms (scan %.1f · score %.1f · fetch %.1f)%n", r.total, r.msTotal, r.msScan, r.msScore, r.msFetch);
    }
  }
}
