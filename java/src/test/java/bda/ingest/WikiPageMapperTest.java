package bda.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.wikiclean.WikiClean;

class WikiPageMapperTest {

  @Test
  void unescapeEntities() {
    assertEquals("A & B <c> \"d\" 'e' é", WikiPageMapper.unescape("A &amp; B &lt;c&gt; &quot;d&quot; &#39;e&#39; &#xE9;"));
    assertEquals("AT&T; no entity", WikiPageMapper.unescape("AT&T; no entity"));
  }

  @Test
  void linksNormalisedDedupedAndNamespacesSkipped() {
    String wt = "See [[photosynthesis]], [[Light_energy|light]], [[light energy#History]], "
        + "[[File:Leaf.jpg|thumb]], [[Category:Biology]], [[ chlorophyll ]].";
    assertEquals(List.of("Photosynthesis", "Light energy", "Chlorophyll"), List.copyOf(WikiPageMapper.links(wt)));
  }

  @Test
  void fieldExtraction() {
    String xml = "<page><title>AT&amp;T</title><ns>0</ns><id>42</id><redirect title=\"Foo &amp; Bar\" />"
        + "<revision><id>99</id><text xml:space=\"preserve\">#REDIRECT [[Foo &amp; Bar]]</text></revision></page>";
    assertEquals("42", WikiPageMapper.between(xml, "<id>", "</id>"), "first <id> is the page id");
    assertEquals("Foo &amp; Bar", WikiPageMapper.between(xml, "<redirect title=\"", "\""));
    assertEquals("#REDIRECT [[Foo &amp; Bar]]", WikiPageMapper.rawText(xml));
    assertEquals("", WikiPageMapper.rawText("<page><text bytes=\"0\" /></page>"));
  }

  @Test
  void wikicleanWorksWithoutItsCliDependencies() {
    // stanford-corenlp, ant and args4j are excluded from the jar; clean() must not need them.
    String xml = "<page><title>Leaf</title><ns>0</ns><id>7</id><revision><id>8</id><text xml:space=\"preserve\">"
        + "{{Infobox plant}}A '''leaf''' is an [[organ (biology)|organ]] of a [[plant]].&lt;ref&gt;Source&lt;/ref&gt;"
        + "\n== Function ==\nLeaves make food.</text></revision></page>";
    String clean = new WikiClean.Builder().withTitle(false).withFooter(false).build().clean(xml);
    assertTrue(clean.contains("A leaf is an organ of a plant."), clean);
    assertFalse(clean.contains("Infobox"), clean);
    assertFalse(clean.contains("Source"), clean);
  }

  @Test
  void wikicleanOnCurrentDumpFormat() {
    // Same shape as the 20261001 dump: bytes/sha1 attributes before xml:space, full <revision> block.
    String xml = "<page>\n    <title>Leaf</title>\n    <ns>0</ns>\n    <id>7</id>\n    <revision>\n"
        + "      <id>10861257</id>\n      <parentid>10730460</parentid>\n      <timestamp>2026-05-18T12:42:53Z</timestamp>\n"
        + "      <contributor>\n        <username>Someone</username>\n        <id>1806169</id>\n      </contributor>\n"
        + "      <comment>/* Trivia */</comment>\n      <model>wikitext</model>\n      <format>text/x-wiki</format>\n"
        + "      <text bytes=\"120\" sha1=\"6ujzqa7xrm2rn4s2oofjpedjl08gtt2\" xml:space=\"preserve\">"
        + "{{Infobox plant}}A '''leaf''' is an [[organ (biology)|organ]] of a [[plant]].&lt;ref&gt;Source&lt;/ref&gt;"
        + "</text>\n      <sha1>6ujzqa7xrm2rn4s2oofjpedjl08gtt2</sha1>\n    </revision>\n  </page>";
    WikiClean wc = new WikiClean.Builder().withTitle(false).withFooter(false).build();
    String raw = wc.clean(xml);
    assertTrue(raw == null || raw.isBlank(), "root cause: wikiclean finds no text in the current format, got: " + raw);
    String clean = WikiPageMapper.cleanWithWikiclean(wc, xml);
    assertTrue(clean.contains("A leaf is an organ of a plant."), clean);
    assertFalse(clean.contains("Infobox") || clean.contains("Source"), clean);
  }

  @Test
  void simpleWikiFooterDropped() {
    String wt = "Body text.\n== History ==\nMore.\n== Other websites ==\n* [http://x.org X]\n== Related pages ==\n* [[Y]]";
    assertEquals("Body text.\n== History ==\nMore.\n", WikiPageMapper.dropFooter(wt));
    assertEquals("No footer here.", WikiPageMapper.dropFooter("No footer here."));
    assertEquals("A\n", WikiPageMapper.dropFooter("A\n==References==\n&lt;references /&gt;"));
  }

  @Test
  void orphanTableRowsAndTemplatesRemoved() {
    // Shape of "List of minor planets": the table opens inside a template, rows follow bare.
    String wt = "{{List of minor planets/header2|top-link=no}}\n|-bgcolor=#E9E9E9\n"
        + "| '''[[1 Ceres]]''' || — || January 1, 1801 || align=right | 939 km || {{M+J|1}}\n"
        + "|-id=002 bgcolor=#E9E9E9\n! Header cell\nCeres is the largest object in the belt.\n"
        + "{| class=\"wikitable\"\n|-\n| a || b\n|}\nAfter the table.";
    String out = WikiPageMapper.normalize(WikiPageMapper.preprocess(wt));
    assertEquals("Ceres is the largest object in the belt. After the table.", out);
  }

  @Test
  void fallbackStripper() {
    String wt = "{{Infobox|a={{nested}}}}'''Bold''' [[a|b]] [[c]] [http://x.org text] <ref>r</ref>\n== H ==\nEnd";
    assertEquals("Bold b c text H End", WikiPageMapper.normalize(WikiText.strip(wt)));
  }
}
