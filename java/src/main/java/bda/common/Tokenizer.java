package bda.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.tartarus.snowball.ext.PorterStemmer;

/**
 * The project's one and only tokeniser (plan §7.1). The same code runs at index time (Jobs 1–2),
 * at query time (QueryServer) and on the evaluation queries (TokenizeQueries).
 *
 * <ol>
 *   <li>Unicode NFKD, then drop combining marks (café → cafe)</li>
 *   <li>Lowercase with {@link Locale#ROOT}</li>
 *   <li>Every character outside [a-z0-9] becomes a space; split on whitespace</li>
 *   <li>Drop tokens shorter than 2 or longer than 40 characters</li>
 *   <li>Drop Lucene's 33 English stopwords</li>
 *   <li>Porter-stem (Snowball's {@code PorterStemmer} from lucene-analysis-common 9.x)</li>
 * </ol>
 *
 * Instances are thread-safe: the stemmer (which keeps state) is per thread.
 */
public final class Tokenizer {

  /** Written into every index's meta.tsv; the QueryServer refuses to start on a mismatch. */
  public static final String TOKENIZER_VERSION = "v1-lucene33-porter";
  public static final int MIN_LEN = 2;
  public static final int MAX_LEN = 40;

  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
  private static final Pattern SPACES = Pattern.compile(" +");
  private static final Set<String> STOPWORDS = loadStopwords();
  private static final ThreadLocal<PorterStemmer> STEMMER = ThreadLocal.withInitial(PorterStemmer::new);

  /** Tokens in document order, duplicates kept. */
  public List<String> tokenize(String text) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    String s = Normalizer.normalize(text, Normalizer.Form.NFKD);
    s = COMBINING_MARKS.matcher(s).replaceAll("");
    s = s.toLowerCase(Locale.ROOT);
    s = NON_ALNUM.matcher(s).replaceAll(" ").trim();
    if (s.isEmpty()) {
      return out;
    }
    PorterStemmer stemmer = STEMMER.get();
    for (String tok : SPACES.split(s)) {
      if (tok.length() < MIN_LEN || tok.length() > MAX_LEN || STOPWORDS.contains(tok)) {
        continue;
      }
      stemmer.setCurrent(tok);
      stemmer.stem();
      out.add(stemmer.getCurrent());
    }
    return out;
  }

  /** Distinct terms in first-occurrence order. */
  public Set<String> uniqueTerms(String text) {
    return new LinkedHashSet<>(tokenize(text));
  }

  public static Set<String> stopwords() {
    return STOPWORDS;
  }

  private static Set<String> loadStopwords() {
    Set<String> words = new LinkedHashSet<>();
    try (InputStream in = Tokenizer.class.getResourceAsStream("/stopwords.txt")) {
      if (in == null) {
        throw new IllegalStateException("stopwords.txt not on the classpath");
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          words.add(line);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (words.size() != 33) {
      throw new IllegalStateException("expected Lucene's 33 English stopwords, found " + words.size());
    }
    return Collections.unmodifiableSet(words);
  }
}
