package bda.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Required cases from plan §7.1. */
class TokenizerTest {

  private final Tokenizer tok = new Tokenizer();

  @Test
  void stopwordsAndStemming() {
    assertEquals(List.of("run", "dog"), tok.tokenize("The Running Dogs"));
  }

  @Test
  void hyphenatedPhrase() {
    assertEquals(List.of("state", "art"), tok.tokenize("state-of-the-art"));
  }

  @Test
  void accentsRemoved() {
    assertEquals(List.of("cafe"), tok.tokenize("Café"));
  }

  @Test
  void numbersKeptAndPorterOutputPinned() {
    assertEquals(List.of("1984", "olymp"), tok.tokenize("1984 Olympics"));
  }

  @Test
  void possessive() {
    assertEquals(List.of("dog"), tok.tokenize("dog's"));
  }

  @Test
  void emptyAndBlank() {
    assertTrue(tok.tokenize("").isEmpty());
    assertTrue(tok.tokenize("   ").isEmpty());
    assertTrue(tok.tokenize(null).isEmpty());
  }

  @Test
  void lengthLimits() {
    assertTrue(tok.tokenize("x".repeat(41)).isEmpty(), "41-character token must be dropped");
    assertEquals(1, tok.tokenize("x".repeat(40)).size(), "40-character token is kept");
  }

  @Test
  void punctuationAndSingleLetters() {
    // Single letters are dropped: a documented limitation (U.S.A. disappears).
    assertEquals(List.of("2020"), tok.tokenize("U.S.A. (2020)!"));
  }

  @Test
  void deterministic() {
    String text = "Photosynthesis converts light energy into chemical energy; the Calvin cycle follows.";
    assertEquals(tok.tokenize(text), tok.tokenize(text));
    assertEquals(tok.tokenize(text), new Tokenizer().tokenize(text));
  }

  @Test
  void uniqueTermsKeepFirstOccurrenceOrder() {
    assertEquals(List.of("dog", "run"), List.copyOf(tok.uniqueTerms("dogs dog running dogs")));
  }

  @Test
  void exactly33Stopwords() {
    assertEquals(33, Tokenizer.stopwords().size());
  }
}
