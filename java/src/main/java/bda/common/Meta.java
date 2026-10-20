package bda.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Index metadata, {@code key \t value} rows in {@code /bda/<c>/index/meta/meta.tsv} (plan §7.3):
 * N, totalTokens, avgdl, tokenizerVersion, builtAt.
 */
public final class Meta {

  private final Map<String, String> values = new LinkedHashMap<>();

  public Meta put(String key, Object value) {
    values.put(key, String.valueOf(value));
    return this;
  }

  public String get(String key) {
    return values.get(key);
  }

  public long getLong(String key) {
    return Long.parseLong(values.get(key));
  }

  public double getDouble(String key) {
    return Double.parseDouble(values.get(key));
  }

  public Map<String, String> asMap() {
    return values;
  }

  public void write(Configuration conf, Path file) throws IOException {
    FileSystem fs = file.getFileSystem(conf);
    try (Writer w = new OutputStreamWriter(fs.create(file, true), StandardCharsets.UTF_8)) {
      for (Map.Entry<String, String> e : values.entrySet()) {
        w.write(e.getKey() + "\t" + e.getValue() + "\n");
      }
    }
  }

  public static Meta read(Configuration conf, Path file) throws IOException {
    Meta m = new Meta();
    FileSystem fs = file.getFileSystem(conf);
    try (FSDataInputStream in = fs.open(file);
         BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        int tab = line.indexOf('\t');
        if (tab > 0) {
          m.values.put(line.substring(0, tab), line.substring(tab + 1));
        }
      }
    }
    return m;
  }
}
