package bda.index;

import java.io.IOException;
import java.time.Instant;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import bda.common.Meta;
import bda.common.Tokenizer;

/**
 * Job 2 (plan WP6), map-only: docs → {@code docID \t len} (tokens after tokenising). Counters DOCS,
 * TOKENS, EMPTY_DOCS. On success the driver writes meta.tsv: N = DOCS, totalTokens, avgdl,
 * tokenizerVersion, builtAt.
 * Usage: hadoop jar bda-search-1.0.jar bda.index.DocStatsJob <docs> <docstatsOut> <metaFile>
 */
public class DocStatsJob extends Configured implements Tool {

  public enum C { DOCS, TOKENS, EMPTY_DOCS, BAD_DOCID }

  public static class LenMapper extends Mapper<Text, Text, NullWritable, Text> {
    private final Tokenizer tokenizer = new Tokenizer();
    private final Text out = new Text();

    @Override
    protected void map(Text docId, Text text, Context ctx) throws IOException, InterruptedException {
      String id = docId.toString().trim();
      try {
        Long.parseLong(id);
      } catch (NumberFormatException e) {
        ctx.getCounter(C.BAD_DOCID).increment(1);
        return;
      }
      int len = tokenizer.tokenize(text.toString()).size();
      ctx.getCounter(C.DOCS).increment(1);
      ctx.getCounter(C.TOKENS).increment(len);
      if (len == 0) {
        ctx.getCounter(C.EMPTY_DOCS).increment(1);
      }
      out.set(id + "\t" + len);
      ctx.write(NullWritable.get(), out);
    }
  }

  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("usage: DocStatsJob <docs> <docstatsOut> <metaFile>");
      return 2;
    }
    Configuration conf = getConf();
    Job job = Job.getInstance(conf, "bda-job2-docstats");
    job.setJarByClass(DocStatsJob.class);
    job.setInputFormatClass(KeyValueTextInputFormat.class);
    job.setMapperClass(LenMapper.class);
    job.setNumReduceTasks(0);
    job.setOutputKeyClass(NullWritable.class);
    job.setOutputValueClass(Text.class);
    job.setOutputFormatClass(TextOutputFormat.class);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));

    long t0 = System.currentTimeMillis();
    boolean ok = job.waitForCompletion(true);
    long secs = (System.currentTimeMillis() - t0) / 1000;
    long docs = job.getCounters().findCounter(C.DOCS).getValue();
    long tokens = job.getCounters().findCounter(C.TOKENS).getValue();
    System.out.println("=== Job 2 summary ===");
    System.out.println("job=" + job.getJobID() + " success=" + ok + " wall_seconds=" + secs
        + " maps=" + job.getCounters().findCounter(JobCounter.TOTAL_LAUNCHED_MAPS).getValue());
    for (C c : C.values()) {
      System.out.println(c.name() + "=" + job.getCounters().findCounter(c).getValue());
    }
    if (!ok || docs == 0) {
      return 1;
    }
    Meta meta = new Meta()
        .put("N", docs)
        .put("totalTokens", tokens)
        .put("avgdl", (double) tokens / docs)
        .put("tokenizerVersion", Tokenizer.TOKENIZER_VERSION)
        .put("builtAt", Instant.now().toString());
    meta.write(conf, new Path(args[2]));
    System.out.println("meta written to " + args[2] + ": " + meta.asMap());
    return 0;
  }

  public static void main(String[] args) throws Exception {
    System.exit(ToolRunner.run(new DocStatsJob(), args));
  }
}
