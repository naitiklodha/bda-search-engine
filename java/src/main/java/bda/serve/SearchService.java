package bda.serve;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

import bda.common.Meta;
import bda.common.Scorer;
import bda.common.Tokenizer;

/**
 * Query engine over the HBase serving tables (plan WP10). At start-up it reads meta.tsv (refusing to start if
 * the index was built with another tokeniser), then loads every term's df from `terms` and every document's
 * len/norm/pr from `docs` into memory (seconds for simplewiki). Each search runs one prefix scan of
 * `postings` per known query term, then scores with {@link Scorer}.
 */
public class SearchService implements Closeable {

  static final byte[] P = Bytes.toBytes("p"), TF = Bytes.toBytes("tf");
  static final byte[] T = Bytes.toBytes("t"), DF = Bytes.toBytes("df");
  static final byte[] D = Bytes.toBytes("d"), TITLE = Bytes.toBytes("title"), SNIPPET = Bytes.toBytes("snippet"),
      LEN = Bytes.toBytes("len"), NORM = Bytes.toBytes("norm"), PR = Bytes.toBytes("pr");
  static final TableName POSTINGS = TableName.valueOf("postings"), DOCS = TableName.valueOf("docs"),
      TERMS = TableName.valueOf("terms");

  /** In-memory per-document statistics. */
  record DocStat(int len, double norm, double pr) {
  }

  /** One result row. */
  public record Hit(int rank, long docId, String title, String snippet, double score) {
  }

  /** Everything the page / CLI shows for one search. */
  public static final class Response {
    public String query, model, message = "";
    public boolean pr;
    public int k;
    public List<String> terms = new ArrayList<>(), unknown = new ArrayList<>();
    public long total;
    public List<Hit> hits = new ArrayList<>();
    public double msTokenize, msScan, msScore, msFetch, msTotal;
  }

  private final Tokenizer tokenizer = new Tokenizer();
  private final Connection conn;
  private final Map<String, Long> dfs = new HashMap<>(1 << 20);
  private final Map<Long, DocStat> docs = new HashMap<>(1 << 19);
  private final long n;
  private final double avgdl;
  private final long loadMs;

  public SearchService(Configuration base, String metaPath) throws IOException {
    long t0 = System.currentTimeMillis();
    Configuration conf = HBaseConfiguration.create(base);
    Meta meta = Meta.read(conf, new Path(metaPath));
    if (!Tokenizer.TOKENIZER_VERSION.equals(meta.get("tokenizerVersion"))) {
      throw new IllegalStateException("index tokenizerVersion " + meta.get("tokenizerVersion")
          + " != code " + Tokenizer.TOKENIZER_VERSION + ": refusing to start");
    }
    n = meta.getLong("N");
    avgdl = meta.getDouble("avgdl");
    conn = ConnectionFactory.createConnection(conf);
    try (Table terms = conn.getTable(TERMS);
         ResultScanner rs = terms.getScanner(new Scan().addColumn(T, DF).setCaching(10000))) {
      for (Result r : rs) {
        dfs.put(Bytes.toString(r.getRow()), Bytes.toLong(r.getValue(T, DF)));
      }
    }
    try (Table d = conn.getTable(DOCS);
         ResultScanner rs = d.getScanner(new Scan().addColumn(D, LEN).addColumn(D, NORM).addColumn(D, PR).setCaching(10000))) {
      for (Result r : rs) {
        docs.put(Long.parseLong(Bytes.toString(r.getRow())),
            new DocStat(Bytes.toInt(r.getValue(D, LEN)), Bytes.toDouble(r.getValue(D, NORM)), Bytes.toDouble(r.getValue(D, PR))));
      }
    }
    loadMs = System.currentTimeMillis() - t0;
  }

  public long n() {
    return n;
  }

  public int termCount() {
    return dfs.size();
  }

  public int docCount() {
    return docs.size();
  }

  public long loadMs() {
    return loadMs;
  }

  public List<String> tokenize(String text) {
    return tokenizer.tokenize(text);
  }

  public Response search(String q, String model, boolean usePr, int k, double k1, double b, double prWeight) throws IOException {
    Response res = new Response();
    long t0 = System.nanoTime();
    res.query = q == null ? "" : q;
    res.model = model;
    res.pr = usePr && "bm25".equals(model);
    res.k = Math.max(1, Math.min(k, 100));

    Set<String> qterms = tokenizer.uniqueTerms(res.query);
    List<String> known = new ArrayList<>();
    for (String t : qterms) {
      (dfs.containsKey(t) ? known : res.unknown).add(t);
    }
    res.terms = known;
    long t1 = System.nanoTime();
    res.msTokenize = (t1 - t0) / 1e6;
    if (res.query.isBlank()) {
      res.message = "Type something to search.";
    } else if (qterms.isEmpty()) {
      res.message = "Only stopwords or very short words: nothing to search for.";
    } else if (known.isEmpty()) {
      res.message = "None of the words occur in any article: " + String.join(", ", res.unknown) + ".";
    } else if ("boolean".equals(model) && !res.unknown.isEmpty()) {
      res.message = "Boolean AND: '" + String.join(", ", res.unknown) + "' occurs in no article, so nothing can contain every word.";
    }
    if (!res.message.isEmpty()) {
      res.msTotal = (System.nanoTime() - t0) / 1e6;
      return res;
    }

    // ---- scan: one prefix scan of `postings` per known term
    List<long[]> postDocs = new ArrayList<>(known.size());
    List<int[]> postTfs = new ArrayList<>(known.size());
    try (Table postings = conn.getTable(POSTINGS)) {
      for (String term : known) {
        int df = (int) (long) dfs.get(term);
        long[] ids = new long[df];
        int[] tfs = new int[df];
        int i = 0;
        int prefixLen = Bytes.toBytes(term).length + 1;
        Scan scan = new Scan().setStartStopRowForPrefixScan(Bytes.toBytes(term + "#")).addFamily(P).setCaching(5000);
        try (ResultScanner rs = postings.getScanner(scan)) {
          for (Result r : rs) {
            if (i == ids.length) {
              ids = java.util.Arrays.copyOf(ids, i * 2 + 1);
              tfs = java.util.Arrays.copyOf(tfs, i * 2 + 1);
            }
            byte[] row = r.getRow();
            ids[i] = Long.parseLong(Bytes.toString(row, prefixLen, row.length - prefixLen));
            tfs[i] = Bytes.toInt(r.getValue(P, TF));
            i++;
          }
        }
        postDocs.add(java.util.Arrays.copyOf(ids, i));
        postTfs.add(java.util.Arrays.copyOf(tfs, i));
      }
    }
    long t2 = System.nanoTime();
    res.msScan = (t2 - t1) / 1e6;

    // ---- score: accumulate matched-term count and the model's score per candidate document
    Map<Long, double[]> acc = new HashMap<>();   // [matched, score]
    for (int ti = 0; ti < known.size(); ti++) {
      long df = dfs.get(known.get(ti));
      long[] ids = postDocs.get(ti);
      int[] tfs = postTfs.get(ti);
      for (int j = 0; j < ids.length; j++) {
        double[] a = acc.computeIfAbsent(ids[j], x -> new double[2]);
        a[0]++;
        DocStat ds = docs.get(ids[j]);
        if (ds == null) {
          continue;
        }
        if ("bm25".equals(model)) {
          a[1] += Scorer.bm25(n, avgdl, df, tfs[j], ds.len(), k1, b);
        } else if ("tfidf".equals(model)) {
          a[1] += Scorer.tfidf(n, df, tfs[j], ds.norm());
        }
      }
    }
    // Ranking: highest score first, ties by docID ascending. Boolean: documents containing every known term, by docID.
    PriorityQueue<long[]> heap = new PriorityQueue<>(res.k + 1, (x, y) -> {   // [docId, scoreBits]; head = worst
      int c = Double.compare(Double.longBitsToDouble(x[1]), Double.longBitsToDouble(y[1]));
      return c != 0 ? c : Long.compare(y[0], x[0]);
    });
    long total = 0;
    int need = known.size();
    for (Map.Entry<Long, double[]> e : acc.entrySet()) {
      long doc = e.getKey();
      double[] a = e.getValue();
      double score;
      if ("boolean".equals(model)) {
        if (a[0] < need) {
          continue;
        }
        score = -doc;                      // smaller docID ranks higher
      } else {
        score = a[1];
        if (res.pr) {
          DocStat ds = docs.get(doc);
          score += ds == null ? 0 : Scorer.prBoost(ds.pr(), n, prWeight);
        }
      }
      total++;
      heap.add(new long[] {doc, Double.doubleToLongBits(score)});
      if (heap.size() > res.k) {
        heap.poll();
      }
    }
    res.total = total;
    List<long[]> top = new ArrayList<>(heap);
    top.sort((x, y) -> {
      int c = Double.compare(Double.longBitsToDouble(y[1]), Double.longBitsToDouble(x[1]));
      return c != 0 ? c : Long.compare(x[0], y[0]);
    });
    long t3 = System.nanoTime();
    res.msScore = (t3 - t2) / 1e6;

    // ---- fetch titles and snippets for the top k in one batch Get
    if (!top.isEmpty()) {
      List<Get> gets = new ArrayList<>(top.size());
      for (long[] h : top) {
        gets.add(new Get(Bytes.toBytes(String.format("%010d", h[0]))).addColumn(D, TITLE).addColumn(D, SNIPPET));
      }
      try (Table d = conn.getTable(DOCS)) {
        Result[] rs = d.get(gets);
        for (int i = 0; i < top.size(); i++) {
          long doc = top.get(i)[0];
          double s = "boolean".equals(model) ? 1.0 : Double.longBitsToDouble(top.get(i)[1]);
          res.hits.add(new Hit(i + 1, doc, Bytes.toString(rs[i].getValue(D, TITLE)), Bytes.toString(rs[i].getValue(D, SNIPPET)), s));
        }
      }
    }
    if (total == 0) {
      res.message = "boolean".equals(model) ? "No article contains every word (Boolean AND)." : "No matching articles.";
    }
    long t4 = System.nanoTime();
    res.msFetch = (t4 - t3) / 1e6;
    res.msTotal = (t4 - t0) / 1e6;
    return res;
  }

  @Override
  public void close() throws IOException {
    conn.close();
  }
}
