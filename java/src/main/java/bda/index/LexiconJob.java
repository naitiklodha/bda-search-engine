package bda.index;

import java.io.IOException;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * Job 3 (plan WP6), map-only: postings → lexicon {@code term \t df \t cf}. Works on the raw bytes up to
 * the third tab, so very long posting lists (MS MARCO's common terms) are never turned into Strings.
 * Usage: hadoop jar bda-search-1.0.jar bda.index.LexiconJob <postings> <out>
 */
public class LexiconJob extends Configured implements Tool {

  public static class LexMapper extends Mapper<LongWritable, Text, NullWritable, Text> {
    private final Text out = new Text();

    @Override
    protected void map(LongWritable key, Text line, Context ctx) throws IOException, InterruptedException {
      byte[] b = line.getBytes();
      int len = line.getLength();
      int tabs = 0;
      int i = 0;
      while (i < len && tabs < 3) {
        if (b[i] == '\t') {
          tabs++;
        }
        i++;
      }
      if (tabs < 3) {
        ctx.getCounter("bda", "BAD_POSTINGS_LINE").increment(1);
        return;
      }
      out.set(b, 0, i - 1);   // term \t df \t cf
      ctx.write(NullWritable.get(), out);
      ctx.getCounter("bda", "TERMS").increment(1);
    }
  }

  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: LexiconJob <postings> <out>");
      return 2;
    }
    Job job = Job.getInstance(getConf(), "bda-job3-lexicon");
    job.setJarByClass(LexiconJob.class);
    job.setInputFormatClass(TextInputFormat.class);
    job.setMapperClass(LexMapper.class);
    job.setNumReduceTasks(0);
    job.setOutputKeyClass(NullWritable.class);
    job.setOutputValueClass(Text.class);
    job.setOutputFormatClass(TextOutputFormat.class);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));
    long t0 = System.currentTimeMillis();
    boolean ok = job.waitForCompletion(true);
    System.out.println("=== Job 3 summary ===");
    System.out.println("job=" + job.getJobID() + " success=" + ok + " wall_seconds=" + (System.currentTimeMillis() - t0) / 1000
        + " TERMS=" + job.getCounters().findCounter("bda", "TERMS").getValue()
        + " BAD_POSTINGS_LINE=" + job.getCounters().findCounter("bda", "BAD_POSTINGS_LINE").getValue());
    return ok ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    System.exit(ToolRunner.run(new LexiconJob(), args));
  }
}
