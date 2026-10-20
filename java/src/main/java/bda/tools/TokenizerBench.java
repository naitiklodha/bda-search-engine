package bda.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import bda.common.Tokenizer;

/**
 * WP3 throughput check: tokenise the text column of the first N lines of a "id \t text" file.
 * Usage: java -cp bda-search-1.0.jar bda.tools.TokenizerBench <file> [N=10000]
 */
public final class TokenizerBench {

  public static void main(String[] args) throws IOException {
    int n = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
    List<String> docs = new ArrayList<>(n);
    try (BufferedReader in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
      for (String line = in.readLine(); line != null && docs.size() < n; line = in.readLine()) {
        int tab = line.indexOf('\t');
        docs.add(tab < 0 ? line : line.substring(tab + 1));
      }
    }
    Tokenizer tokenizer = new Tokenizer();
    for (int i = 0; i < Math.min(1000, docs.size()); i++) {
      tokenizer.tokenize(docs.get(i)); // warm-up
    }
    long tokens = 0;
    long t0 = System.nanoTime();
    for (String d : docs) {
      tokens += tokenizer.tokenize(d).size();
    }
    double secs = (System.nanoTime() - t0) / 1e9;
    System.out.printf("docs=%d tokens=%d seconds=%.3f docs_per_sec=%.0f tokens_per_sec=%.0f%n",
        docs.size(), tokens, secs, docs.size() / secs, tokens / secs);
  }
}
