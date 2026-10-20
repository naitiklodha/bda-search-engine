package bda.index;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskCounter;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import bda.common.Tokenizer;

/**
 * Job 1 (plan WP6): docs (docID \t text) → postings lines {@code term \t df \t cf \t d1:tf1,d2:tf2,…}
 * with docIDs strictly ascending.
 *
 * <ul>
 *   <li>The mapper emits one ((term, docID), 1) per token occurrence, on purpose, so the combiner has
 *       measurable work (the combiner experiment).</li>
 *   <li>The combiner sums per full (term, docID) key; switch it off with {@code -D bda.combiner=false}.</li>
 *   <li>{@link TermPartitioner} + {@link TermGroupingComparator}: one reduce() per term, values already
 *       sorted by docID (secondary sort), so the reducer never sorts a list itself.</li>
 * </ul>
 * Usage: hadoop jar bda-search-1.0.jar bda.index.PostingsJob [-D mapreduce.job.reduces=4] [-D bda.combiner=false] <docs> <out>
 */
public class PostingsJob extends Configured implements Tool {

  public static final String COMBINER = "bda.combiner";

  public static class TokenMapper extends Mapper<Text, Text, TermDocKey, IntWritable> {
    private static final IntWritable ONE = new IntWritable(1);
    private final Tokenizer tokenizer = new Tokenizer();
    private final TermDocKey outKey = new TermDocKey();

    @Override
    protected void map(Text docId, Text text, Context ctx) throws IOException, InterruptedException {
      long id;
      try {
        id = Long.parseLong(docId.toString().trim());
      } catch (NumberFormatException e) {
        ctx.getCounter("bda", "BAD_DOCID").increment(1);
        return;
      }
      for (String t : tokenizer.tokenize(text.toString())) {
        outKey.set(t, id);
        ctx.write(outKey, ONE);
      }
    }
  }

  /** Sums tf for one (term, docID). Safe as a combiner: summing is associative and commutative. */
  public static class SumCombiner extends Reducer<TermDocKey, IntWritable, TermDocKey, IntWritable> {
    private final IntWritable sum = new IntWritable();

    @Override
    protected void reduce(TermDocKey key, Iterable<IntWritable> vals, Context ctx) throws IOException, InterruptedException {
      int s = 0;
      for (IntWritable v : vals) {
        s += v.get();
      }
      sum.set(s);
      ctx.write(key, sum);
    }
  }

  /**
   * One call per term. Hadoop updates the key object as the values advance, so docID is read inside the
   * loop. The combiner may not have merged everything, so the same docID can appear several times in a
   * row: those are summed.
   */
  public static class PostingsReducer extends Reducer<TermDocKey, IntWritable, Text, Text> {
    private final Text outTerm = new Text();
    private final Text outVal = new Text();

    @Override
    protected void reduce(TermDocKey key, Iterable<IntWritable> vals, Context ctx) throws IOException, InterruptedException {
      StringBuilder sb = new StringBuilder();
      long prev = -1;
      long cf = 0;
      int tf = 0;
      int df = 0;
      for (IntWritable v : vals) {
        long d = key.getDocId();
        if (d != prev && prev != -1) {
          sb.append(sb.length() > 0 ? "," : "").append(prev).append(':').append(tf);
          df++;
          tf = 0;
        }
        prev = d;
        tf += v.get();
        cf += v.get();
      }
      sb.append(sb.length() > 0 ? "," : "").append(prev).append(':').append(tf);
      df++;
      outTerm.set(key.getTerm());
      outVal.set(df + "\t" + cf + "\t" + sb);
      ctx.write(outTerm, outVal);
    }
  }

  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: PostingsJob <docs> <out>");
      return 2;
    }
    Configuration conf = getConf();
    boolean combiner = conf.getBoolean(COMBINER, true);
    if (conf.get("mapreduce.job.reduces") == null) {
      conf.setInt("mapreduce.job.reduces", 4);
    }
    Job job = Job.getInstance(conf, "bda-job1-postings" + (combiner ? "" : "-nocombiner"));
    job.setJarByClass(PostingsJob.class);
    job.setInputFormatClass(KeyValueTextInputFormat.class);
    job.setMapperClass(TokenMapper.class);
    if (combiner) {
      job.setCombinerClass(SumCombiner.class);
    }
    job.setPartitionerClass(TermPartitioner.class);
    job.setGroupingComparatorClass(TermGroupingComparator.class);
    job.setReducerClass(PostingsReducer.class);
    job.setMapOutputKeyClass(TermDocKey.class);
    job.setMapOutputValueClass(IntWritable.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(Text.class);
    job.setOutputFormatClass(TextOutputFormat.class);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));

    long t0 = System.currentTimeMillis();
    boolean ok = job.waitForCompletion(true);
    long secs = (System.currentTimeMillis() - t0) / 1000;
    var c = job.getCounters();
    System.out.println("=== Job 1 summary ===");
    System.out.println("job=" + job.getJobID() + " success=" + ok + " combiner=" + combiner + " wall_seconds=" + secs
        + " maps=" + c.findCounter(JobCounter.TOTAL_LAUNCHED_MAPS).getValue()
        + " reduces=" + c.findCounter(JobCounter.TOTAL_LAUNCHED_REDUCES).getValue());
    for (TaskCounter tc : new TaskCounter[] {TaskCounter.MAP_INPUT_RECORDS, TaskCounter.MAP_OUTPUT_RECORDS,
        TaskCounter.MAP_OUTPUT_BYTES, TaskCounter.MAP_OUTPUT_MATERIALIZED_BYTES, TaskCounter.COMBINE_INPUT_RECORDS,
        TaskCounter.COMBINE_OUTPUT_RECORDS, TaskCounter.REDUCE_SHUFFLE_BYTES, TaskCounter.REDUCE_INPUT_RECORDS,
        TaskCounter.REDUCE_INPUT_GROUPS, TaskCounter.REDUCE_OUTPUT_RECORDS, TaskCounter.SPILLED_RECORDS,
        TaskCounter.CPU_MILLISECONDS, TaskCounter.GC_TIME_MILLIS}) {
      System.out.println(tc.name() + "=" + c.findCounter(tc).getValue());
    }
    return ok ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    System.exit(ToolRunner.run(new PostingsJob(), args));
  }
}
