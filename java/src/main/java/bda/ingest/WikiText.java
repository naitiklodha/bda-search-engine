package bda.ingest;

import java.util.regex.Pattern;

/**
 * Fallback wikitext cleaner (plan WP4), used when wikiclean throws or returns nothing, and for
 * redirect / non-article pages. Input is already XML-unescaped wikitext.
 */
public final class WikiText {

  private static final Pattern REFS = Pattern.compile("(?is)<ref[^>/]*/>|<ref[^>]*>.*?</ref>");
  private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
  private static final Pattern FILE_LINKS =
      Pattern.compile("(?i)\\[\\[(?:file|image|category|media):[^\\[\\]]*(?:\\[\\[[^\\[\\]]*\\]\\][^\\[\\]]*)*\\]\\]");
  private static final Pattern PIPED_LINK = Pattern.compile("\\[\\[[^\\[\\]|]*\\|([^\\[\\]]*)\\]\\]");
  private static final Pattern PLAIN_LINK = Pattern.compile("\\[\\[([^\\[\\]|]*)\\]\\]");
  private static final Pattern EXT_LINK_TEXT = Pattern.compile("\\[(?:https?|ftp)://[^\\s\\]]+\\s+([^\\]]*)\\]");
  private static final Pattern EXT_LINK_BARE = Pattern.compile("\\[(?:https?|ftp)://[^\\]]*\\]");
  private static final Pattern BOLD_ITALIC = Pattern.compile("'{2,}");
  private static final Pattern HEADINGS = Pattern.compile("(?m)^=+\\s*(.*?)\\s*=+\\s*$");
  private static final Pattern HTML_TAGS = Pattern.compile("<[^>]{1,200}>");
  private static final Pattern REDIRECT = Pattern.compile("(?i)^\\s*#redirect\\b.*");

  private WikiText() {
  }

  public static String strip(String wikitext) {
    if (wikitext == null || wikitext.isEmpty()) {
      return "";
    }
    String s = REDIRECT.matcher(wikitext).replaceFirst("");
    s = COMMENTS.matcher(s).replaceAll(" ");
    s = REFS.matcher(s).replaceAll(" ");
    s = removeNested(s, "{{", "}}");
    s = removeNested(s, "{|", "|}");
    s = FILE_LINKS.matcher(s).replaceAll(" ");
    s = PIPED_LINK.matcher(s).replaceAll("$1");
    s = PLAIN_LINK.matcher(s).replaceAll("$1");
    s = EXT_LINK_TEXT.matcher(s).replaceAll("$1");
    s = EXT_LINK_BARE.matcher(s).replaceAll(" ");
    s = BOLD_ITALIC.matcher(s).replaceAll("");
    s = HEADINGS.matcher(s).replaceAll("$1");
    s = HTML_TAGS.matcher(s).replaceAll(" ");
    return s;
  }

  /** Removes open…close spans with a depth counter (templates nest: {{a|{{b}}}}). */
  static String removeNested(String s, String open, String close) {
    if (s.indexOf(open) < 0) {
      return s;
    }
    StringBuilder out = new StringBuilder(s.length());
    int depth = 0;
    int i = 0;
    while (i < s.length()) {
      if (s.startsWith(open, i)) {
        depth++;
        i += open.length();
      } else if (depth > 0 && s.startsWith(close, i)) {
        depth--;
        i += close.length();
        if (depth == 0) {
          out.append(' ');
        }
      } else {
        if (depth == 0) {
          out.append(s.charAt(i));
        }
        i++;
      }
    }
    return out.toString();
  }
}
