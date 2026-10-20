package bda.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ToolRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Job 1 end to end in Hadoop's local runner (MRUnit is retired, so this exercises the real mapper,
 * combiner, partitioner, grouping comparator and reducer), plus raw-comparator consistency.
 */
class PostingsReducerTest {

  @TempDir
  Path tmp;

  private TreeMap<String, String> runJob(boolean combiner, int reducers) throws Exception {
    File docs = tmp.resolve("docs-" + combiner + reducers + ".tsv").toFile();
    // "dogs"/"dog" both stem to "dog"; "the" is a stopword.
    Files.writeString(docs.toPath(), String.join("\n",
        "1\tdog dogs cat",
        "2\tdog bird the",
        "3\tcat cat cats dog",
        "10\tbird"), StandardCharsets.UTF_8);
    File out = tmp.resolve("out-" + combiner + reducers).toFile();
    Configuration conf = new Configuration();
    conf.set("mapreduce.framework.name", "local");
    conf.set("fs.defaultFS", "file:///");
    int rc = ToolRunner.run(conf, new PostingsJob(), new String[] {
        "-D", PostingsJob.COMBINER + "=" + combiner, "-D", "mapreduce.job.reduces=" + reducers,
        docs.toURI().toString(), out.toURI().toString()});
    assertEquals(0, rc);
    TreeMap<String, String> lines = new TreeMap<>();
    File[] parts = out.listFiles((d, n) -> n.startsWith("part-r-"));
    assertTrue(parts != null && parts.length == reducers);
    for (File p : parts) {
      for (String l : Files.readAllLines(p.toPath(), StandardCharsets.UTF_8)) {
        String term = l.substring(0, l.indexOf('\t'));
        assertTrue(lines.put(term, l.substring(l.indexOf('\t') + 1)) == null, "term in two partitions: " + term);
      }
    }
    return lines;
  }

  @Test
  void postingsWithAndWithoutCombinerAndAcrossReducers() throws Exception {
    TreeMap<String, String> expected = new TreeMap<>();
    expected.put("bird", "2\t2\t2:1,10:1");     // docIDs in numeric order, not string order
    expected.put("cat", "2\t4\t1:1,3:3");
    expected.put("dog", "3\t4\t1:2,2:1,3:1");
    assertEquals(expected, runJob(true, 1));
    assertEquals(expected, runJob(false, 1));
    assertEquals(expected, runJob(true, 3));
  }

  private static byte[] ser(TermDocKey k) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    k.write(new DataOutputStream(bos));
    return bos.toByteArray();
  }

  @Test
  void rawComparatorsAgreeWithObjectComparison() throws Exception {
    Random r = new Random(7);
    String[] terms = {"a", "ab", "abc", "b", "zz", "caf", "x".repeat(40), "1984", "olymp"};
    List<TermDocKey> keys = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      TermDocKey k = new TermDocKey();
      k.set(terms[r.nextInt(terms.length)], r.nextInt(5) == 0 ? r.nextLong() & Long.MAX_VALUE : r.nextInt(1000));
      keys.add(k);
    }
    TermDocKey.Comparator raw = new TermDocKey.Comparator();
    TermGroupingComparator group = new TermGroupingComparator();
    for (TermDocKey a : keys) {
      for (TermDocKey b : keys) {
        byte[] x = ser(a);
        byte[] y = ser(b);
        assertEquals(Integer.signum(a.compareTo(b)), Integer.signum(raw.compare(x, 0, x.length, y, 0, y.length)));
        assertEquals(Integer.signum(a.getTerm().compareTo(b.getTerm())),
            Integer.signum(group.compare(x, 0, x.length, y, 0, y.length)));
      }
    }
  }
}
