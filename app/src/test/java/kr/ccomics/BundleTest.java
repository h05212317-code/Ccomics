package kr.ccomics;

import static org.junit.Assert.*;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BundleTest {
  static byte[] volume(int pages) throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(sink)) {
      for (int i = 1; i <= pages; i++) {
        zip.putArchiveEntry(new ZipArchiveEntry(String.format("%03d.jpg", i)));
        zip.write(new byte[] {(byte) i, 2, 3});
        zip.closeArchiveEntry();
      }
    }
    return sink.toByteArray();
  }

  static void put(ZipArchiveOutputStream zip, String name, byte[] data, boolean stored)
      throws IOException {
    ZipArchiveEntry e = new ZipArchiveEntry(name);
    if (stored) {
      CRC32 crc = new CRC32();
      crc.update(data);
      e.setMethod(ZipArchiveEntry.STORED);
      e.setSize(data.length);
      e.setCrc(crc.getValue());
    }
    zip.putArchiveEntry(e);
    zip.write(data);
    zip.closeArchiveEntry();
  }

  @Test
  public void volumesInsideBundleZipOpenAndGroupAsOneSeries() throws Exception {
    Context ctx = RuntimeEnvironment.getApplication();
    File file = new File(ctx.getCacheDir(), "블리치.zip");
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(file)) {
      put(zip, "블리치/블리치 1권.zip", volume(2), true);
      put(zip, "블리치/블리치 2권.zip", volume(3), false);
      put(zip, "cover.jpg", new byte[] {1}, false);
    }
    String outer = android.net.Uri.fromFile(file).toString();
    try {
      new Archive(ctx, outer, null).close();
      fail("묶음 ZIP은 권 목록을 알려야 합니다.");
    } catch (Archive.Bundle bundle) {
      assertEquals(2, bundle.volumes.size());
      assertEquals("블리치/블리치 1권.zip", bundle.volumes.get(0).getName());
    }
    try (Archive a = new Archive(ctx, Archive.nested(outer, "블리치/블리치 1권.zip"), null)) {
      assertEquals(2, a.pages.size());
      try (InputStream in = a.zip.getInputStream(a.pages.get(1))) {
        assertEquals(2, in.read());
      }
    }
    try (Archive a = new Archive(ctx, Archive.nested(outer, "블리치/블리치 2권.zip"), null)) {
      assertEquals(3, a.pages.size());
    }

    Library lib = new Library(ctx);
    List<Long> pending = new ArrayList<>();
    String parent = Library.bundleParent("블리치");
    lib.record(1, Archive.nested(outer, "a/3권.zip"), "3권.zip", parent, 10, 1, 7, pending);
    lib.record(1, Archive.nested(outer, "블리치 1권.zip"), "블리치 1권.zip", parent, 10, 1, 7, pending);
    lib.record(1, "content://x/[완결] 블리치 4권.zip", "[완결] 블리치_4권.zip", "p", 10, 1, 7, pending);
    Set<String> groups = new HashSet<>();
    for (Library.Book b : lib.books()) groups.add(b.grp);
    assertEquals(Collections.singleton("series:블리치"), groups);
    lib.close();
  }
}
