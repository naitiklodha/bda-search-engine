package bda.index;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Partitioner;

/**
 * Routes by term only (plan WP6). The default HashPartitioner would hash the whole (term, docID) key
 * and scatter one term's postings across several reducers.
 */
public class TermPartitioner extends Partitioner<TermDocKey, IntWritable> {
  @Override
  public int getPartition(TermDocKey key, IntWritable value, int numPartitions) {
    return (key.getTerm().hashCode() & Integer.MAX_VALUE) % numPartitions;
  }
}
