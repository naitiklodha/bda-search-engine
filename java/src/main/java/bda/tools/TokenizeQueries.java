package bda.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import bda.common.Tokenizer;

/**
 * Tokenises evaluation queries with the project tokeniser (plan WP3 / WP11 step 2).
 * Input:  qid \t text        Output: qid \t term1 term2 ...  (unique terms, first-occurrence order)
 * Usage:  java -cp bda-search-1.0.jar bda.tools.TokenizeQueries in.tsv out.tsv
 */
public final class TokenizeQueries {

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: TokenizeQueries <queries.tsv> <out.tsv>");
      System.exit(2);
    }
    Tokenizer tokenizer = new Tokenizer();
    int queries = 0, empty = 0;
    try (BufferedReader in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
         BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
      for (String line = in.readLine(); line != null; line = in.readLine()) {
        int tab = line.indexOf('\t');
        if (tab < 0) {
          continue;
        }
        String terms = String.join(" ", tokenizer.uniqueTerms(line.substring(tab + 1)));
        queries++;
        if (terms.isEmpty()) {
          empty++;
        }
        out.write(line.substring(0, tab));
        out.write('\t');
        out.write(terms);
        out.newLine();
      }
    }
    System.err.printf("queries=%d zero_term_queries=%d tokenizer=%s%n", queries, empty, Tokenizer.TOKENIZER_VERSION);
  }
}
