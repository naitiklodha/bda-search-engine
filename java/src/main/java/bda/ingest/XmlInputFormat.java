package bda.ingest;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

/**
 * Reads multi-line XML records such as Wikipedia's {@code <page>…</page>} (plan WP4). Line-based input
 * would cut articles apart. Key = position of the record's start tag, value = the record's bytes from the
 * start tag through the end tag, inclusive.
 *
 * <p>Splittable when the file is uncompressed or its codec is a {@link SplittableCompressionCodec}
 * (bzip2 is, gzip isn't), so many mappers read one .bz2 dump without a separate decompression step.
 * {@code -D bda.xml.splittable=false} forces a single split, for the comparison run.
 */
public class XmlInputFormat extends FileInputFormat<LongWritable, Text> {

  public static final String START_TAG = "bda.xml.start";
  public static final String END_TAG = "bda.xml.end";
  public static final String SPLITTABLE = "bda.xml.splittable";

  @Override
  protected boolean isSplitable(JobContext context, Path file) {
    Configuration conf = context.getConfiguration();
    if (!conf.getBoolean(SPLITTABLE, true)) {
      return false;
    }
    CompressionCodec codec = new CompressionCodecFactory(conf).getCodec(file);
    return codec == null || codec instanceof SplittableCompressionCodec;
  }

  @Override
  public RecordReader<LongWritable, Text> createRecordReader(InputSplit split, TaskAttemptContext context) {
    return new XmlRecordReader();
  }
}
