package bda.index;

import java.io.IOException;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.io.WritableUtils;

/**
 * Groups reducer input by term only, so there is one reduce() call per term while the values arrive in
 * docID order (secondary sort, plan WP6). Never used as the combiner's grouping comparator: the combiner
 * must group by the full (term, docID) key, which is Hadoop's default.
 */
public class TermGroupingComparator extends WritableComparator {

  protected TermGroupingComparator() {
    super(TermDocKey.class, true);
  }

  @Override
  @SuppressWarnings("rawtypes")
  public int compare(WritableComparable a, WritableComparable b) {
    return ((TermDocKey) a).getTerm().compareTo(((TermDocKey) b).getTerm());
  }

  /** Raw version: compare only the serialised term bytes. */
  @Override
  public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
    try {
      int n1 = WritableUtils.decodeVIntSize(b1[s1]);
      int n2 = WritableUtils.decodeVIntSize(b2[s2]);
      return compareBytes(b1, s1 + n1, readVInt(b1, s1), b2, s2 + n2, readVInt(b2, s2));
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
