package bda.common;

/**
 * Ranking formulas shared by the QueryServer and (re-implemented identically) by spark/eval_msmarco.py
 * (plan WP10). ScorerTest pins a toy example; eval_msmarco.py asserts the same numbers within 1e-9.
 *
 * <ul>
 *   <li>TF-IDF: Σ ln(N/df)² · (1 + ln tf) / norm (cosine with query weight = idf; the constant query norm is dropped)</li>
 *   <li>BM25: Σ idf · tf · (k1 + 1) / (tf + k1 · (1 − b + b · len / avgdl)), idf = ln(1 + (N − df + 0.5)/(df + 0.5))</li>
 *   <li>PageRank boost (BM25 only): + w · ln(pr · N), skipped when pr = 0</li>
 * </ul>
 */
public final class Scorer {

  public static final double DEFAULT_K1 = 0.9;
  public static final double DEFAULT_B = 0.4;
  public static final double DEFAULT_PR_WEIGHT = 0.5;

  private Scorer() {
  }

  public static double bm25Idf(long n, long df) {
    return Math.log(1 + (n - df + 0.5) / (df + 0.5));
  }

  /** One query term's BM25 contribution for one document. */
  public static double bm25(long n, double avgdl, long df, int tf, int len, double k1, double b) {
    return bm25Idf(n, df) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avgdl));
  }

  /** One query term's TF-IDF (cosine) contribution for one document; 0 if the norm is 0. */
  public static double tfidf(long n, long df, int tf, double norm) {
    if (norm <= 0) {
      return 0;
    }
    double idf = Math.log((double) n / df);
    return idf * idf * (1 + Math.log(tf)) / norm;
  }

  public static double prBoost(double pr, long n, double w) {
    return pr > 0 ? w * Math.log(pr * n) : 0;
  }
}
