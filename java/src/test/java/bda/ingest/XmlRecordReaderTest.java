package bda.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan WP4: 5 pages read with tiny forced splits must give exactly 5 records, no duplicates. */
class XmlRecordReaderTest {

  @TempDir
  Path tmp;

  private static String page(int id, String body) {
    return "  <page>\n    <title>Page " + id + "</title>\n    <ns>0</ns>\n    <id>" + id + "</id>\n"
        + "    <revision>\n      <id>" + (1000 + id) + "</id>\n      <text bytes=\"9\" xml:space=\"preserve\">"
        + body + "</text>\n    </revision>\n  </page>\n";
  }

  private File writeDump() throws Exception {
    StringBuilder sb = new StringBuilder("<mediawiki xml:lang=\"en\">\n  <siteinfo><sitename>Test</sitename></siteinfo>\n");
    sb.append(page(1, "Short."));
    sb.append(page(2, "A longer body that is definitely more than a few dozen bytes long, with &lt;b&gt;markup&lt;/b&gt;."));
    sb.append(page(3, ""));
    sb.append(page(4, "x".repeat(450)));
    sb.append(page(5, "Last page &amp; done."));
    sb.append("</mediawiki>\n");
    File f = tmp.resolve("dump.xml").toFile();
    Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
    return f;
  }

  private List<String> readAll(File f, long splitSize) throws Exception {
    Configuration conf = new Configuration(false);
    conf.set("fs.defaultFS", "file:///");
    conf.set("fs.file.impl", "org.apache.hadoop.fs.LocalFileSystem");
    org.apache.hadoop.fs.Path p = new org.apache.hadoop.fs.Path(f.toURI());
    List<String> out = new ArrayList<>();
    long len = f.length();
    for (long off = 0; off < len; off += splitSize) {
      FileSplit split = new FileSplit(p, off, Math.min(splitSize, len - off), new String[0]);
      try (XmlRecordReader r = new XmlRecordReader()) {
        r.initialize(split, new TaskAttemptContextImpl(conf, new TaskAttemptID()));
        while (r.nextKeyValue()) {
          out.add(r.getCurrentValue().toString());
        }
      }
    }
    return out;
  }

  @Test
  void tinySplitsGiveEveryRecordExactlyOnce() throws Exception {
    File f = writeDump();
    for (long splitSize : new long[] {200, 37, 61, 1000, f.length()}) {
      List<String> recs = readAll(f, splitSize);
      assertEquals(5, recs.size(), "split size " + splitSize);
      assertEquals(5, new HashSet<>(recs).size(), "duplicates with split size " + splitSize);
      for (int i = 0; i < 5; i++) {
        String r = recs.get(i);
        assertTrue(r.startsWith("<page>") && r.endsWith("</page>"), "record boundaries, split " + splitSize);
        assertTrue(r.contains("<id>" + (i + 1) + "</id>"), "record order, split " + splitSize);
      }
    }
  }
}
