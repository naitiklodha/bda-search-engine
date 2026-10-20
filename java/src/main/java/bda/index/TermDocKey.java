package bda.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.io.WritableUtils;

/**
 * Composite key (term, docID) for Job 1's secondary sort (plan WP6). The shuffle sorts by the full key,
 * so each term's postings reach the reducer already in docID order; {@link TermPartitioner} and
 * {@link TermGroupingComparator} look at the term only.
 *
 * <p>Serialised as Text (vint length + UTF-8 bytes) followed by an 8-byte long. The registered raw
 * {@link Comparator} compares those bytes directly, so the sort never deserialises keys (this matters
 * for MS MARCO's few hundred million map outputs).
 */
public class TermDocKey implements WritableComparable<TermDocKey> {

  private final Text term = new Text();
  private long docId;

  public void set(String t, long d) {
    term.set(t);
    docId = d;
  }

  public Text getTerm() {
    return term;
  }

  public long getDocId() {
    return docId;
  }

  @Override
  public void write(DataOutput out) throws IOException {
    term.write(out);
    out.writeLong(docId);
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    term.readFields(in);
    docId = in.readLong();
  }

  @Override
  public int compareTo(TermDocKey o) {
    int c = term.compareTo(o.term);
    return c != 0 ? c : Long.compare(docId, o.docId);
  }

  @Override
  public int hashCode() {
    return term.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TermDocKey k && term.equals(k.term) && docId == k.docId;
  }

  @Override
  public String toString() {
    return term + "#" + docId;
  }

  /** Byte-level comparison of serialised keys: term bytes, then docID. Same order as compareTo. */
  public static class Comparator extends WritableComparator {
    public Comparator() {
      super(TermDocKey.class);
    }

    @Override
    public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
      try {
        int n1 = WritableUtils.decodeVIntSize(b1[s1]);
        int n2 = WritableUtils.decodeVIntSize(b2[s2]);
        int len1 = readVInt(b1, s1);
        int len2 = readVInt(b2, s2);
        int c = compareBytes(b1, s1 + n1, len1, b2, s2 + n2, len2);
        if (c != 0) {
          return c;
        }
        return Long.compare(readLong(b1, s1 + n1 + len1), readLong(b2, s2 + n2 + len2));
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    }
  }

  static {
    WritableComparator.define(TermDocKey.class, new Comparator());
  }
}
