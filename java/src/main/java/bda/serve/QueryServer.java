package bda.serve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import org.apache.hadoop.conf.Configuration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import bda.common.Scorer;

/**
 * Search page + JSON API on the JDK's built-in HTTP server (plan WP10). Port 8090: Windows port 8080 is taken
 * by the PEMHTTPD service.
 * <pre>
 *   GET /                                                        -> index.html
 *   GET /search?q=..&amp;model=bm25|tfidf|boolean&amp;pr=0|1&amp;k=10[&amp;k1=&amp;b=&amp;w=]   -> JSON
 * </pre>
 * Start detached (it must outlive the wsl.exe call):
 *   bda-run.sh queryserver java --add-opens java.base/java.nio=ALL-UNNAMED -cp bda-search-1.0.jar:$(hbase classpath) bda.serve.QueryServer --port 8090
 */
public final class QueryServer {

  public static void main(String[] args) throws Exception {
    int port = 8090;
    // A local copy of /bda/wiki/index/meta/meta.tsv (demo-start.sh fetches it): HBase's client classpath has no
    // HDFS FileSystem ("No FileSystem for scheme hdfs"), and adding Hadoop 3.4.3's jars would mix two Hadoop versions.
    String meta = "file:///home/mit/bda/tmp/wiki-meta.tsv";
    for (int i = 0; i < args.length - 1; i++) {
      if ("--port".equals(args[i])) {
        port = Integer.parseInt(args[i + 1]);
      } else if ("--meta".equals(args[i])) {
        meta = args[i + 1];
      }
    }
    SearchService svc = new SearchService(new Configuration(), meta);
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 64);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.createContext("/", ex -> {
      try {
        String path = ex.getRequestURI().getPath();
        if ("/search".equals(path)) {
          search(ex, svc);
        } else if ("/".equals(path) || "/index.html".equals(path)) {
          try (InputStream in = QueryServer.class.getResourceAsStream("/static/index.html")) {
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
          }
        } else {
          send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        }
      } catch (Exception e) {
        send(ex, 500, "application/json", ("{\"error\":" + Json.str(String.valueOf(e)) + "}").getBytes(StandardCharsets.UTF_8));
      }
    });
    server.start();
    System.out.printf("QueryServer ready on http://localhost:%d  (N=%d, terms=%d, docs=%d, loaded in %d ms)%n",
        port, svc.n(), svc.termCount(), svc.docCount(), svc.loadMs());
  }

  private static void search(HttpExchange ex, SearchService svc) throws IOException {
    Map<String, String> p = params(ex.getRequestURI().getRawQuery());
    String model = p.getOrDefault("model", "bm25");
    if (!model.matches("bm25|tfidf|boolean")) {
      model = "bm25";
    }
    SearchService.Response r = svc.search(p.getOrDefault("q", ""), model, "1".equals(p.get("pr")),
        num(p.get("k"), 10), dbl(p.get("k1"), Scorer.DEFAULT_K1), dbl(p.get("b"), Scorer.DEFAULT_B),
        dbl(p.get("w"), Scorer.DEFAULT_PR_WEIGHT));
    send(ex, 200, "application/json; charset=utf-8", Json.of(r).getBytes(StandardCharsets.UTF_8));
  }

  static Map<String, String> params(String raw) {
    Map<String, String> m = new HashMap<>();
    if (raw == null) {
      return m;
    }
    for (String kv : raw.split("&")) {
      int eq = kv.indexOf('=');
      if (eq > 0) {
        m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return m;
  }

  private static int num(String s, int dflt) {
    try {
      return s == null ? dflt : Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static double dbl(String s, double dflt) {
    try {
      return s == null || s.isEmpty() ? dflt : Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
    ex.getResponseHeaders().set("Content-Type", type);
    ex.sendResponseHeaders(code, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
  }

  /** Small hand-written JSON writer (avoids dependency clashes with HBase's classpath). */
  static final class Json {
    static String str(String s) {
      if (s == null) {
        return "null";
      }
      StringBuilder b = new StringBuilder(s.length() + 2).append('"');
      for (char c : s.toCharArray()) {
        switch (c) {
          case '"' -> b.append("\\\"");
          case '\\' -> b.append("\\\\");
          case '\n' -> b.append("\\n");
          case '\r' -> b.append("\\r");
          case '\t' -> b.append("\\t");
          default -> {
            if (c < 0x20) {
              b.append(String.format("\\u%04x", (int) c));
            } else {
              b.append(c);
            }
          }
        }
      }
      return b.append('"').toString();
    }

    static String of(SearchService.Response r) {
      StringBuilder b = new StringBuilder(2048).append('{');
      b.append("\"query\":").append(str(r.query)).append(",\"model\":").append(str(r.model))
          .append(",\"pr\":").append(r.pr).append(",\"k\":").append(r.k).append(",\"total\":").append(r.total)
          .append(",\"message\":").append(str(r.message)).append(",\"terms\":[");
      for (int i = 0; i < r.terms.size(); i++) {
        b.append(i > 0 ? "," : "").append(str(r.terms.get(i)));
      }
      b.append("],\"unknown\":[");
      for (int i = 0; i < r.unknown.size(); i++) {
        b.append(i > 0 ? "," : "").append(str(r.unknown.get(i)));
      }
      b.append(String.format(java.util.Locale.ROOT,
          "],\"timings\":{\"tokenize\":%.2f,\"scan\":%.2f,\"score\":%.2f,\"fetch\":%.2f,\"total\":%.2f},\"results\":[",
          r.msTokenize, r.msScan, r.msScore, r.msFetch, r.msTotal));
      for (int i = 0; i < r.hits.size(); i++) {
        SearchService.Hit h = r.hits.get(i);
        String url = "https://simple.wikipedia.org/wiki/" + URLEncoderHelper.title(h.title());
        b.append(i > 0 ? "," : "").append("{\"rank\":").append(h.rank()).append(",\"docid\":").append(h.docId())
            .append(",\"title\":").append(str(h.title())).append(",\"snippet\":").append(str(h.snippet()))
            .append(",\"score\":").append(String.format(java.util.Locale.ROOT, "%.4f", h.score()))
            .append(",\"url\":").append(str(url)).append('}');
      }
      return b.append("]}").toString();
    }
  }

  static final class URLEncoderHelper {
    static String title(String t) {
      return java.net.URLEncoder.encode((t == null ? "" : t).replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
    }
  }
}
