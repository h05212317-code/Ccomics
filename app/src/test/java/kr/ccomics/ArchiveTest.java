package kr.ccomics;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.Test;

public class ArchiveTest {
  @Test
  public void zipAndZip64WithComments() throws Exception {
    for (Zip64Mode mode : new Zip64Mode[] {Zip64Mode.Never, Zip64Mode.Always}) {
      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(sink)) {
        zip.setUseZip64(mode);
        zip.setComment("comic archive");
        zip.putArchiveEntry(new ZipArchiveEntry("pages/1.jpg"));
        zip.write(new byte[] {1, 2, 3});
        zip.closeArchiveEntry();
      }
      try (SeekableInMemoryByteChannel ch = new SeekableInMemoryByteChannel(sink.toByteArray())) {
        Archive.validateDirectory(ch);
        assertEquals(0, ch.position());
      }
    }
  }

  @Test(expected = IOException.class)
  public void rejectsTruncatedZip() throws Exception {
    Archive.validateDirectory(new SeekableInMemoryByteChannel(new byte[] {80, 75, 3, 4, 0}));
  }

  @Test
  public void widePagesStayAloneAndEveryPageAppearsOnce() {
    BitSet wide = new BitSet();
    wide.set(2);
    wide.set(6);
    wide.set(7);
    for (int n = 1; n < 25; n++)
      for (boolean solo : new boolean[] {true, false}) {
        int start = 0;
        while (start < n) {
          int[] group = Order.spreadWithWidePages(start, n, true, solo, wide);
          for (int p : group) {
            assertEquals(start++, p);
            if (wide.get(p)) assertEquals(1, group.length);
            assertArrayEquals(group, Order.spreadWithWidePages(p, n, true, solo, wide));
          }
        }
        assertEquals(n, start);
      }
  }
}
