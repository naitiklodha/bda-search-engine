package bda.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Toy example pinned for the cross-language consistency check (plan WP10): N = 10, avgdl = 5, one document
 * with len = 4, norm = 2.5, pr = 0.05, matching two query terms (df = 2, tf = 3) and (df = 5, tf = 1).
 * Expected values come from an independent Python implementation (bin/prep-eval.sh); spark/eval_msmarco.py
 * asserts the same constants.
 */
class ScorerTest {

  static final long N = 10;
  static final double AVGDL = 5.0, NORM = 2.5, PR = 0.05;
  static final int LEN = 4;
  static final long[] DF = {2, 5};
  static final int[] TF = {3, 1};

  private static double sumBm25(double k1, double b) {
    double s = 0;
    for (int i = 0; i < DF.length; i++) {
      s += Scorer.bm25(N, AVGDL, DF[i], TF[i], LEN, k1, b);
    }
    return s;
  }

  @Test
  void bm25MatchesPythonReference() {
    assertEquals(2.9265993446113043, sumBm25(0.9, 0.4), 1e-12);
    assertEquals(3.1873978380958823, sumBm25(1.2, 0.75), 1e-12);
  }

  @Test
  void tfidfMatchesPythonReference() {
    double s = 0;
    for (int i = 0; i < DF.length; i++) {
      s += Scorer.tfidf(N, DF[i], TF[i], NORM);
    }
    assertEquals(2.366587306377633, s, 1e-12);
  }

  @Test
  void pageRankBoost() {
    assertEquals(-0.34657359027997264, Scorer.prBoost(PR, N, 0.5), 1e-12);
    assertEquals(0.0, Scorer.prBoost(0.0, N, 0.5));
  }
}
