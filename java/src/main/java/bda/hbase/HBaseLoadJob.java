package bda.hbase;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * WP9: loads one serving table from HDFS into HBase, map-only, through TableOutputFormat.
 * <pre>
 *   postings  rowkey term#%010d(docID)   p:tf  (int)                        &lt;- /bda/wiki/index/postings
 *   terms     rowkey term                t:df, t:cf (long)                  &lt;- /bda/wiki/index/lexicon
 *   docs      rowkey %010d(docID)        d:title, d:snippet (UTF-8), d:len (int), d:norm, d:pr (double)
 *                                                                           &lt;- /bda/wiki/hive/docs_full
 * </pre>
 * The docID padding makes byte order equal numeric order, so a term's postings come back in docID order
 * from one prefix scan. Every Put skips the write-ahead log: the data can always be reloaded from HDFS.
 * Usage: HADOOP_CLASSPATH=$(hbase mapredcp):$HBASE_HOME/conf hadoop jar bda-search-1.0.jar bda.hbase.HBaseLoadJob postings|terms|docs &lt;input&gt;
 */
public class HBaseLoadJob extends Configured implements Tool {

  public static final String TYPE = "bda.hbase.type";
  public enum C { LINES, PUTS, BAD_LINES }

  static final byte[] FAM_P = Bytes.toBytes("p"), TF = Bytes.toBytes("tf");
  static final byte[] FAM_T = Bytes.toBytes("t"), DF = Bytes.toBytes("df"), CF = Bytes.toBytes("cf");
  static final byte[] FAM_D = Bytes.toBytes("d"), TITLE = Bytes.toBytes("title"), SNIPPET = Bytes.toBytes("snippet"),
      LEN = Bytes.toBytes("len"), NORM = Bytes.toBytes("norm"), PR = Bytes.toBytes("pr");

  public static String pad(long docId) {
    return String.format("%010d", docId);
  }

  public static byte[] postingRow(String term, long docId) {
    return Bytes.toBytes(term + "#" + pad(docId));
  }

  public static class LoadMapper extends Mapper<LongWritable, Text, ImmutableBytesWritable, Put> {
    private String type;
    private final ImmutableBytesWritable row = new ImmutableBytesWritable();

    @Override
    protected void setup(Context ctx) {
      type = ctx.getConfiguration().get(TYPE);
    }

    private void emit(byte[] key, Put put, Context ctx) throws IOException, InterruptedException {
      put.setDurability(Durability.SKIP_WAL);
      row.set(key);
      ctx.write(row, put);
      ctx.getCounter(C.PUTS).increment(1);
    }

    @Override
    protected void map(LongWritable offset, Text value, Context ctx) throws IOException, InterruptedException {
      String line = value.toString();
      String[] f = line.split("\t", -1);
      ctx.getCounter(C.LINES).increment(1);
      try {
        switch (type) {
          case "postings": {          // term \t df \t cf \t d1:tf1,d2:tf2,...
            String term = f[0];
            for (String p : f[3].split(",")) {
              int colon = p.indexOf(':');
              long doc = Long.parseLong(p.substring(0, colon));
              int tf = Integer.parseInt(p.substring(colon + 1));
              byte[] key = postingRow(term, doc);
              emit(key, new Put(key).addColumn(FAM_P, TF, Bytes.toBytes(tf)), ctx);
            }
            break;
          }
          case "terms": {             // term \t df \t cf
            byte[] key = Bytes.toBytes(f[0]);
            emit(key, new Put(key).addColumn(FAM_T, DF, Bytes.toBytes(Long.parseLong(f[1])))
                .addColumn(FAM_T, CF, Bytes.toBytes(Long.parseLong(f[2]))), ctx);
            break;
          }
          case "docs": {              // docid \t title \t snippet \t len \t norm \t pr   (Hive docs_full)
            byte[] key = Bytes.toBytes(pad(Long.parseLong(f[0])));
            Put put = new Put(key)
                .addColumn(FAM_D, TITLE, Bytes.toBytes(f[1]))
                .addColumn(FAM_D, SNIPPET, Bytes.toBytes(f[2]))
                .addColumn(FAM_D, LEN, Bytes.toBytes(Integer.parseInt(f[3])))
                .addColumn(FAM_D, NORM, Bytes.toBytes(parseDouble(f[4])))
                .addColumn(FAM_D, PR, Bytes.toBytes(parseDouble(f[5])));
            emit(key, put, ctx);
            break;
          }
          default:
            throw new IllegalArgumentException("unknown table type " + type);
        }
      } catch (RuntimeException e) {
        ctx.getCounter(C.BAD_LINES).increment(1);
      }
    }

    private static double parseDouble(String s) {
      return s.isEmpty() || "\\N".equals(s) ? 0.0 : Double.parseDouble(s);
    }
  }

  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: HBaseLoadJob postings|terms|docs <input>");
      return 2;
    }
    String table = args[0];
    Configuration conf = HBaseConfiguration.create(getConf());
    conf.set(TYPE, table);
    conf.setBoolean("mapreduce.map.speculative", false);   // a second attempt would just rewrite the same cells
    Job job = Job.getInstance(conf, "bda-hbase-load-" + table);
    job.setJarByClass(HBaseLoadJob.class);
    job.setInputFormatClass(TextInputFormat.class);
    FileInputFormat.addInputPath(job, new Path(args[1]));
    job.setMapperClass(LoadMapper.class);
    job.setMapOutputKeyClass(ImmutableBytesWritable.class);
    job.setMapOutputValueClass(Put.class);
    TableMapReduceUtil.initTableReducerJob(table, null, job);   // TableOutputFormat + dependency jars
    job.setNumReduceTasks(0);

    long t0 = System.currentTimeMillis();
    boolean ok = job.waitForCompletion(true);
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    long puts = job.getCounters().findCounter(C.PUTS).getValue();
    System.out.println("=== HBase load summary ===");
    System.out.printf("table=%s job=%s success=%s wall_seconds=%.0f maps=%d LINES=%d PUTS=%d BAD_LINES=%d rows_per_sec=%.0f%n",
        table, job.getJobID(), ok, secs, job.getCounters().findCounter(JobCounter.TOTAL_LAUNCHED_MAPS).getValue(),
        job.getCounters().findCounter(C.LINES).getValue(), puts,
        job.getCounters().findCounter(C.BAD_LINES).getValue(), puts / Math.max(secs, 1));
    return ok ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    System.exit(ToolRunner.run(new HBaseLoadJob(), args));
  }
}
