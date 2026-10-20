package bda.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.SplitCompressionInputStream;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

/**
 * Record reader for {@link XmlInputFormat}, modelled on Hadoop's LineRecordReader.
 *
 * <p><b>Split rule:</b> a record belongs to the split in which its start tag begins. The reader emits a
 * record only if its start tag begins inside this split, and keeps reading past the split end until that
 * record's end tag. The next split skips the partial record at its start, because it only begins
 * emitting at the first complete start tag it finds.
 *
 * <p><b>bzip2:</b> opened with {@code READ_MODE.BYBLOCK}; start/end are adjusted to block boundaries and
 * {@code getPos()} is block-granular. Hadoop's bzip2 stream never returns bytes from two blocks in one
 * {@code read()}, and advances {@code getPos()} on the read that returns a new block's first byte. So
 * the position sampled right after each buffer refill is the block every byte in that buffer came from.
 * For bzip2 a record is ours if that block position is {@code <= end} (as in LineRecordReader); for
 * uncompressed input it's ours if the tag's byte offset is {@code < end}, because the next split starts
 * reading exactly at {@code end}.
 */
public class XmlRecordReader extends RecordReader<LongWritable, Text> {

  private static final int BUF_SIZE = 64 * 1024;

  private byte[] startTag;
  private byte[] endTag;
  private long start;
  private long end;
  private boolean compressed;
  private InputStream in;
  private Seekable compressedPos;
  private Decompressor decompressor;

  private final byte[] buf = new byte[BUF_SIZE];
  private int bufLen;
  private int bufIdx;
  private long bufFileOffset;   // uncompressed: file offset of buf[0]
  private long bufBlockPos;     // bzip2: block position of every byte in buf

  private final DataOutputBuffer record = new DataOutputBuffer();
  private final LongWritable key = new LongWritable();
  private final Text value = new Text();
  private boolean done;

  @Override
  public void initialize(InputSplit genericSplit, TaskAttemptContext context) throws IOException {
    FileSplit split = (FileSplit) genericSplit;
    Configuration conf = context.getConfiguration();
    startTag = conf.get(XmlInputFormat.START_TAG, "<page>").getBytes(StandardCharsets.UTF_8);
    endTag = conf.get(XmlInputFormat.END_TAG, "</page>").getBytes(StandardCharsets.UTF_8);
    start = split.getStart();
    end = start + split.getLength();

    Path file = split.getPath();
    FSDataInputStream fileIn = file.getFileSystem(conf).open(file);
    CompressionCodec codec = new CompressionCodecFactory(conf).getCodec(file);
    if (codec == null) {
      fileIn.seek(start);
      in = fileIn;
      bufFileOffset = start;
      return;
    }
    compressed = true;
    decompressor = CodecPool.getDecompressor(codec);
    if (codec instanceof SplittableCompressionCodec) {
      SplitCompressionInputStream cIn = ((SplittableCompressionCodec) codec).createInputStream(
          fileIn, decompressor, start, end, SplittableCompressionCodec.READ_MODE.BYBLOCK);
      in = cIn;
      compressedPos = cIn;
      start = cIn.getAdjustedStart();
      end = cIn.getAdjustedEnd();
    } else {
      // Non-splittable codec (isSplitable() already returned false): read the whole file.
      in = codec.createInputStream(fileIn, decompressor);
      compressedPos = fileIn;
      end = Long.MAX_VALUE;
    }
    bufBlockPos = compressedPos.getPos();
  }

  /** Next byte, or -1 at end of stream. */
  private int readByte() throws IOException {
    if (bufIdx >= bufLen) {
      bufFileOffset += bufLen;
      int n;
      do {
        n = in.read(buf, 0, BUF_SIZE);
      } while (n == 0);
      if (n < 0) {
        bufLen = 0;
        bufIdx = 0;
        return -1;
      }
      bufLen = n;
      bufIdx = 0;
      if (compressed) {
        bufBlockPos = compressedPos.getPos();
      }
    }
    return buf[bufIdx++] & 0xff;
  }

  /** Position of the byte just returned by {@link #readByte()}. */
  private long posOfLastByte() {
    return compressed ? bufBlockPos : bufFileOffset + bufIdx - 1;
  }

  private boolean ownsRecordAt(long pos) {
    return compressed ? pos <= end : pos < end;
  }

  /** Scans for the start tag; returns the position of its first byte, or -1 at end of stream. */
  private long findStartTag() throws IOException {
    int matched = 0;
    long candidate = -1;
    while (true) {
      int b = readByte();
      if (b < 0) {
        return -1;
      }
      if (b == startTag[matched]) {
        if (matched == 0) {
          candidate = posOfLastByte();
        }
        if (++matched == startTag.length) {
          return candidate;
        }
      } else if (matched > 0) {
        // Tags like "<page>" have no repeated prefix, so restarting the match at this byte is enough.
        matched = (b == startTag[0]) ? 1 : 0;
        if (matched == 1) {
          candidate = posOfLastByte();
        }
      }
    }
  }

  /** Copies bytes into the record until the end tag has been written; false if the stream ended first. */
  private boolean readUntilEndTag() throws IOException {
    int matched = 0;
    while (true) {
      int b = readByte();
      if (b < 0) {
        return false;
      }
      record.write(b);
      if (b == endTag[matched]) {
        if (++matched == endTag.length) {
          return true;
        }
      } else {
        matched = (b == endTag[0]) ? 1 : 0;
      }
    }
  }

  @Override
  public boolean nextKeyValue() throws IOException {
    if (done) {
      return false;
    }
    long tagPos = findStartTag();
    if (tagPos < 0 || !ownsRecordAt(tagPos)) {
      done = true;
      return false;
    }
    record.reset();
    record.write(startTag);
    if (!readUntilEndTag()) {
      done = true;   // truncated record at end of file: drop it
      return false;
    }
    key.set(tagPos);
    value.set(record.getData(), 0, record.getLength());
    return true;
  }

  @Override
  public LongWritable getCurrentKey() {
    return key;
  }

  @Override
  public Text getCurrentValue() {
    return value;
  }

  @Override
  public float getProgress() throws IOException {
    if (done || end <= start || end == Long.MAX_VALUE) {
      return done ? 1f : 0f;
    }
    long pos = compressed ? compressedPos.getPos() : bufFileOffset + bufIdx;
    return Math.min(1f, Math.max(0f, (pos - start) / (float) (end - start)));
  }

  @Override
  public void close() throws IOException {
    try {
      if (in != null) {
        in.close();
      }
    } finally {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
    }
  }
}
