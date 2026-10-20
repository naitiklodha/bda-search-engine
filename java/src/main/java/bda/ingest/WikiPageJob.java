package bda.ingest;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.LazyOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * Job 0 (plan WP4): Wikipedia bz2 dump → {@code <out>/pages} and {@code <out>/links}. Map-only.
 * Usage: hadoop jar bda-search-1.0.jar bda.ingest.WikiPageJob [-D ...] <dump dir/file> <out>
 *   -D mapreduce.input.fileinputformat.split.maxsize=33554432   (≈10 splits on simplewiki)
 *   -D bda.xml.splittable=false                                  (one mapper, for the comparison)
 */
public class WikiPageJob extends Configured implements Tool {

  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: WikiPageJob <in> <out>");
      return 2;
    }
    Configuration conf = getConf();
    conf.setIfUnset(XmlInputFormat.START_TAG, "<page>");
    conf.setIfUnset(XmlInputFormat.END_TAG, "</page>");

    Job job = Job.getInstance(conf, "bda-job0-wikipages"
        + (conf.getBoolean(XmlInputFormat.SPLITTABLE, true) ? "" : "-singlesplit"));
    job.setJarByClass(WikiPageJob.class);
    job.setInputFormatClass(XmlInputFormat.class);
    job.setMapperClass(WikiPageMapper.class);
    job.setNumReduceTasks(0);
    job.setOutputKeyClass(NullWritable.class);
    job.setOutputValueClass(Text.class);
    LazyOutputFormat.setOutputFormatClass(job, TextOutputFormat.class);
    MultipleOutputs.addNamedOutput(job, "pages", TextOutputFormat.class, NullWritable.class, Text.class);
    MultipleOutputs.addNamedOutput(job, "links", TextOutputFormat.class, NullWritable.class, Text.class);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));

    long t0 = System.currentTimeMillis();
    boolean ok = job.waitForCompletion(true);
    long secs = (System.currentTimeMillis() - t0) / 1000;

    System.out.println("=== Job 0 summary ===");
    System.out.println("job=" + job.getJobID() + " success=" + ok + " wall_seconds=" + secs
        + " maps=" + job.getCounters().findCounter(JobCounter.TOTAL_LAUNCHED_MAPS).getValue());
    for (WikiPageMapper.C c : WikiPageMapper.C.values()) {
      Counter counter = job.getCounters().findCounter(c);
      System.out.println(c.name() + "=" + counter.getValue());
    }
    return ok ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    System.exit(ToolRunner.run(new WikiPageJob(), args));
  }
}
