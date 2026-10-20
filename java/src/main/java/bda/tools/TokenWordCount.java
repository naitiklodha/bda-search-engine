package bda.tools;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import bda.common.Tokenizer;

/**
 * WP1 step 12.3: a WordCount that uses the project tokeniser. Running it on YARN proves the jar's
 * provided/shaded split is right (Hadoop from the cluster, Lucene's stemmer from inside the jar).
 * Usage: hadoop jar bda-search-1.0.jar bda.tools.TokenWordCount <in> <out>
 */
public final class TokenWordCount extends Configured implements Tool {

  public static final class TokenMapper extends Mapper<LongWritable, Text, Text, IntWritable> {
    private static final IntWritable ONE = new IntWritable(1);
    private final Tokenizer tokenizer = new Tokenizer();
    private final Text term = new Text();

    @Override
    protected void map(LongWritable key, Text value, Context ctx) throws IOException, InterruptedException {
      for (String t : tokenizer.tokenize(value.toString())) {
        term.set(t);
        ctx.write(term, ONE);
      }
    }
  }

  public static final class SumReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
    private final IntWritable sum = new IntWritable();

    @Override
    protected void reduce(Text key, Iterable<IntWritable> values, Context ctx) throws IOException, InterruptedException {
      int s = 0;
      for (IntWritable v : values) {
        s += v.get();
      }
      sum.set(s);
      ctx.write(key, sum);
    }
  }

  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: TokenWordCount <in> <out>");
      return 2;
    }
    Configuration conf = getConf();
    Job job = Job.getInstance(conf, "bda-token-wordcount");
    job.setJarByClass(TokenWordCount.class);
    job.setMapperClass(TokenMapper.class);
    job.setCombinerClass(SumReducer.class);
    job.setReducerClass(SumReducer.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(IntWritable.class);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));
    return job.waitForCompletion(true) ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    System.exit(ToolRunner.run(new TokenWordCount(), args));
  }
}
