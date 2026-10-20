package bda.ingest;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.wikiclean.WikiClean;

/**
 * Job 0 mapper (map-only, plan WP4): one {@code <page>} XML record in, then
 * <ul>
 *   <li>{@code pages}: docID, title, ns, isRedirect, redirectTarget, len, text</li>
 *   <li>{@code links}: srcDocID, targetTitle (ns-0 non-redirect pages only)</li>
 * </ul>
 * Fields come from plain string operations (no DOM parser). Article text (ns 0, not a redirect) is
 * cleaned with wikiclean, falling back to {@link WikiText#strip} on an exception or empty output
 * (counted in CLEAN_FALLBACK). Redirects and other namespaces always use {@link WikiText#strip}.
 */
public class WikiPageMapper extends Mapper<LongWritable, Text, NullWritable, Text> {

  public enum C { PAGES, NS0, REDIRECTS, EMPTY_TEXT, CLEAN_FALLBACK, LINKS }

  static final Pattern LINK = Pattern.compile("\\[\\[([^\\[\\]|#]+)(?:#[^\\[\\]|]*)?(?:\\|[^\\[\\]]*)?\\]\\]");
  private static final Pattern CONTROL_WS = Pattern.compile("[\\t\\r\\n]+");
  private static final Pattern SPACES = Pattern.compile(" {2,}");

  private MultipleOutputs<NullWritable, Text> mos;
  private WikiClean cleaner;
  private final Text line = new Text();

  @Override
  protected void setup(Context ctx) {
    mos = new MultipleOutputs<>(ctx);
    cleaner = new WikiClean.Builder().withTitle(false).withFooter(false).build();
  }

  @Override
  protected void map(LongWritable key, Text value, Context ctx) throws IOException, InterruptedException {
    String xml = value.toString();
    ctx.getCounter(C.PAGES).increment(1);

    long id = parseLong(between(xml, "<id>", "</id>"));
    String title = oneLine(unescape(between(xml, "<title>", "</title>")));
    int ns = (int) parseLong(between(xml, "<ns>", "</ns>"));
    boolean isRedirect = xml.contains("<redirect");
    String redirectTarget = isRedirect ? oneLine(unescape(between(xml, "<redirect title=\"", "\""))) : "";
    String rawWikitext = rawText(xml);
    String wikitext = unescape(preprocess(rawWikitext));
    if (ns == 0) {
      ctx.getCounter(C.NS0).increment(1);
    }
    if (isRedirect) {
      ctx.getCounter(C.REDIRECTS).increment(1);
    }

    String text;
    boolean article = ns == 0 && !isRedirect;
    if (article) {
      text = cleanArticle(xml);
      if (text == null || text.isBlank()) {
        ctx.getCounter(C.CLEAN_FALLBACK).increment(1);
        text = WikiText.strip(wikitext);
      }
    } else {
      text = WikiText.strip(wikitext);
    }
    text = normalize(text);
    if (text.isEmpty()) {
      ctx.getCounter(C.EMPTY_TEXT).increment(1);
    }

    line.set(id + "\t" + title + "\t" + ns + "\t" + (isRedirect ? 1 : 0) + "\t" + redirectTarget + "\t"
        + text.length() + "\t" + text);
    mos.write("pages", NullWritable.get(), line, "pages/part");

    if (article) {
      // Links come from the raw wikitext (plan WP4), so links in tables, templates and footers count too.
      for (String target : links(unescape(rawWikitext))) {
        line.set(id + "\t" + target);
        mos.write("links", NullWritable.get(), line, "links/part");
        ctx.getCounter(C.LINKS).increment(1);
      }
    }
  }

  @Override
  protected void cleanup(Context ctx) throws IOException, InterruptedException {
    mos.close();
  }

  private String cleanArticle(String pageXml) {
    return cleanWithWikiclean(cleaner, pageXml);
  }

  /** wikiclean on a normalised page; null on an exception (the caller falls back to WikiText.strip). */
  static String cleanWithWikiclean(WikiClean cleaner, String pageXml) {
    try {
      return cleaner.clean(wikicleanInput(pageXml));
    } catch (RuntimeException | StackOverflowError e) {
      return null;
    }
  }

  /**
   * wikiclean 1.2 only finds the wikitext in {@code <text xml:space="preserve">}, but current dumps write
   * {@code <text bytes="…" sha1="…" xml:space="preserve">}. Fed raw, it returns nothing for every page
   * (first Job 0 run: CLEAN_FALLBACK = 285,478). So it gets a minimal page in the old format instead.
   */
  static String wikicleanInput(String pageXml) {
    return "<page>\n<title>" + between(pageXml, "<title>", "</title>") + "</title>\n"
        + "<text xml:space=\"preserve\">" + preprocess(rawText(pageXml)) + "</text>\n</page>";
  }

  private static final Pattern TABLE_ROW_LINE = Pattern.compile("(?m)^[ \\t]*[|!].*(?:\\n|$)");

  /**
   * Pre-cleaning before wikiclean / the fallback stripper (works on escaped or unescaped wikitext):
   * <ol>
   *   <li>cut the Simple Wikipedia footer sections ({@link #dropFooter});</li>
   *   <li>remove templates {@code {{…}}} and whole tables {@code {|…|}} with a depth counter;</li>
   *   <li>drop leftover table-row lines (starting with {@code |} or {@code !}).</li>
   * </ol>
   * Step 3 is for tables opened inside one template and closed in another (e.g. the ~190 "List of minor
   * planets" pages): wikiclean never sees a whole table there and left 1,000 rows per page such as
   * "|-bgcolor=#E9E9E9 | 1 Ceres || … || align=right | 939 km", which put bgcolor/align/class/km at the
   * top of the corpus term frequencies.
   */
  static String preprocess(String wikitext) {
    String s = dropFooter(wikitext);
    s = WikiText.removeNested(s, "{{", "}}");
    s = WikiText.removeNested(s, "{|", "|}");
    return TABLE_ROW_LINE.matcher(s).replaceAll("");
  }

  /**
   * Simple English Wikipedia ends articles with "Other websites" / "Related pages" / "References"
   * sections that wikiclean doesn't recognise, leaving "Other websites *" in the text. These footer
   * sections come last, so the wikitext is cut at the first footer heading.
   */
  static String dropFooter(String wikitext) {
    Matcher m = FOOTER.matcher(wikitext);
    return m.find() ? wikitext.substring(0, m.start()) : wikitext;
  }

  private static final Pattern FOOTER = Pattern.compile(
      "(?im)^[ \\t]*==+[ \\t]*(other websites|related pages|references|sources|notes|further reading|external links|see also)[ \\t]*==+[ \\t]*$");

  /** Distinct, normalised link targets in first-occurrence order; targets containing ':' are skipped. */
  static Set<String> links(String wikitext) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = LINK.matcher(wikitext);
    while (m.find()) {
      String t = m.group(1);
      if (t.indexOf(':') >= 0) {
        continue;
      }
      t = normalizeTitle(t);
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }

  /** Same normalisation PageRank uses: '_' → space, collapse spaces, trim, first character upper-case. */
  static String normalizeTitle(String t) {
    t = SPACES.matcher(oneLine(t.replace('_', ' '))).replaceAll(" ").trim();
    if (t.isEmpty()) {
      return t;
    }
    int first = t.codePointAt(0);
    return new String(Character.toChars(Character.toUpperCase(first))) + t.substring(Character.charCount(first));
  }

  static String normalize(String text) {
    return SPACES.matcher(CONTROL_WS.matcher(text).replaceAll(" ")).replaceAll(" ").trim();
  }

  private static String oneLine(String s) {
    return CONTROL_WS.matcher(s).replaceAll(" ");
  }

  static String between(String s, String open, String close) {
    int a = s.indexOf(open);
    if (a < 0) {
      return "";
    }
    a += open.length();
    int b = s.indexOf(close, a);
    return b < 0 ? "" : s.substring(a, b);
  }

  /** Raw (still escaped) wikitext inside {@code <text …>…</text>}; empty for {@code <text … />}. */
  static String rawText(String xml) {
    int t = xml.indexOf("<text");
    if (t < 0) {
      return "";
    }
    int gt = xml.indexOf('>', t);
    if (gt < 0 || xml.charAt(gt - 1) == '/') {
      return "";
    }
    int close = xml.indexOf("</text>", gt);
    return close < 0 ? "" : xml.substring(gt + 1, close);
  }

  private static long parseLong(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** XML-unescape: &amp; &lt; &gt; &quot; &apos; &#39; and other numeric entities. */
  static String unescape(String s) {
    if (s.indexOf('&') < 0) {
      return s;
    }
    StringBuilder out = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int semi = c == '&' ? s.indexOf(';', i) : -1;
      if (semi > i && semi - i <= 10) {
        String ent = s.substring(i + 1, semi).toLowerCase(Locale.ROOT);
        String rep = switch (ent) {
          case "amp" -> "&";
          case "lt" -> "<";
          case "gt" -> ">";
          case "quot" -> "\"";
          case "apos" -> "'";
          default -> numeric(ent);
        };
        if (rep != null) {
          out.append(rep);
          i = semi + 1;
          continue;
        }
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  private static String numeric(String ent) {
    try {
      if (ent.startsWith("#x")) {
        return new String(Character.toChars(Integer.parseInt(ent.substring(2), 16)));
      }
      if (ent.startsWith("#")) {
        return new String(Character.toChars(Integer.parseInt(ent.substring(1))));
      }
    } catch (IllegalArgumentException e) {
      return null;
    }
    return null;
  }
}
