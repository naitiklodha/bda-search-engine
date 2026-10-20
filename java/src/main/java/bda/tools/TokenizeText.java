package bda.tools;

import bda.common.Tokenizer;

/**
 * Prints the project tokeniser's terms for some text, one line, space-separated (used by the live demo
 * to turn "photosynthesis" into its stemmed index term).
 * Usage: java -cp bda-search-1.0.jar bda.tools.TokenizeText "some text"
 */
public final class TokenizeText {
  public static void main(String[] args) {
    System.out.println(String.join(" ", new Tokenizer().tokenize(String.join(" ", args))));
  }
}
