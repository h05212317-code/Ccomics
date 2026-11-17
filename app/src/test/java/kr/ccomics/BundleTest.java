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
  public void volumeFoldersInsideOneZipBecomeBooks() throws Exception {
    Context ctx = RuntimeEnvironment.getApplication();
    File file = new File(ctx.getCacheDir(), "원피스 1~74권.zip");
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(file)) {
      put(zip, "원피스/cover.jpg", new byte[] {1}, false);
      for (int v = 1; v <= 74; v++)
        for (int p = 1; p <= 3; p++)
          put(zip, "원피스/원피스 " + v + "권/" + p + ".jpg", new byte[] {(byte) p}, false);
    }
    String outer = android.net.Uri.fromFile(file).toString();
    Library lib = new Library(ctx);
    List<Long> pending = new ArrayList<>();
    lib.record(1, outer, file.getName(), "folder", file.length(), 5, 9, pending);
    Library.Book b = lib.get(pending.get(0));
    try {
      new Archive(ctx, outer, null).close();
      fail("권 폴더 묶음이어야 합니다.");
    } catch (Archive.Bundle bundle) {
      assertEquals(74, bundle.volumes.size());
      assertEquals("원피스/원피스 1권/", bundle.volumes.get(0).getName());
      assertEquals("원피스/원피스 74권/", bundle.volumes.get(73).getName());
      lib.exec("UPDATE books SET bundle=1 WHERE id=?", b.id);
      lib.expandBundle(b, bundle.volumes, 9, pending);
    }
    List<Library.Book> books = lib.books();
    assertEquals(74, books.size());
    for (Library.Book v : books) assertEquals("series:" + Order.seriesKey("원피스"), v.grp);
    try (Archive a = new Archive(ctx, Archive.nested(outer, "원피스/원피스 10권/"), null)) {
      assertEquals(3, a.pages.size());
      assertEquals("원피스/원피스 10권/1.jpg", a.name(0));
    }
    lib.close();
  }

  @Test
  public void chapterFoldersStayOneBook() throws Exception {
    List<ZipArchiveEntry> images = new ArrayList<>();
    for (int c = 1; c <= 8; c++)
      for (int p = 1; p <= 20; p++) images.add(new ZipArchiveEntry("책/" + c + "화/" + p + ".jpg"));
    assertNull(Archive.volumeFolders(images));
    images.clear();
    for (int c = 1; c <= 8; c++)
      for (int p = 1; p <= 20; p++) images.add(new ZipArchiveEntry(String.format("%02d/%d.jpg", c, p)));
    assertNull(Archive.volumeFolders(images));
  }

  @Test
  public void seventyVolumeBundleWithCoversBecomesSeventyBooks() throws Exception {
    Context ctx = RuntimeEnvironment.getApplication();
    File file = new File(ctx.getCacheDir(), "블리치1~74권.zip");
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(file)) {
      for (int i = 1; i <= 70; i++) {
        put(zip, "블리치 " + i + "권.zip", volume(6), true);
        put(zip, "covers/" + i + ".jpg", new byte[] {1}, false);
      }
    }
    String outer = android.net.Uri.fromFile(file).toString();
    Library lib = new Library(ctx);
    List<Long> pending = new ArrayList<>();
    lib.record(1, outer, file.getName(), "folder", file.length(), 5, 9, pending);
    Library.Book b = lib.get(pending.get(0));
    assertEquals("블리치", b.series);
    try {
      new Archive(ctx, outer, null).close();
      fail("표지가 있어도 권 ZIP 묶음이어야 합니다.");
    } catch (Archive.Bundle bundle) {
      assertEquals(70, bundle.volumes.size());
      lib.exec("UPDATE books SET bundle=1 WHERE id=?", b.id);
      lib.expandBundle(b, bundle.volumes, 9, pending);
    }
    List<Library.Book> books = lib.books();
    assertEquals(70, books.size());
    Set<Integer> numbers = new TreeSet<>();
    for (Library.Book v : books) {
      assertEquals("series:블리치", v.grp);
      assertEquals("블리치", v.series);
      numbers.add(Order.volume(v.title).number);
    }
    assertEquals(70, numbers.size());
    assertEquals(Integer.valueOf(1), ((TreeSet<Integer>) numbers).first());
    assertEquals(Integer.valueOf(70), ((TreeSet<Integer>) numbers).last());
    try (Archive a = new Archive(ctx, Archive.nested(outer, "블리치 70권.zip"), null)) {
      assertEquals(6, a.pages.size());
    }
    // 묶음 파일이 그대로여도 표지가 없는 안쪽 권은 다시 표지를 만들 대상이 된다.
    List<Long> again = new ArrayList<>();
    lib.record(1, outer, file.getName(), "folder", file.length(), 5, 10, again);
    assertEquals(70, again.size());
    for (Library.Book v : lib.books()) lib.exec("UPDATE books SET pages=6 WHERE id=?", v.id);
    lib.writeCover(books.get(0).id, android.graphics.Bitmap.createBitmap(4, 6, android.graphics.Bitmap.Config.ARGB_8888));
    again.clear();
    lib.record(1, outer, file.getName(), "folder", file.length(), 5, 11, again);
    assertEquals(69, again.size());
    assertFalse(again.contains(books.get(0).id));
    assertEquals(70, lib.books().size());
    lib.close();
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
    String other = Library.bundleParent("블리치 (문구)");
    lib.record(1, Archive.nested("content://y/b.zip", "5권.zip"), "5권.zip", other, 10, 1, 7, pending);
    lib.record(1, "content://z/블리치 (문구).zip", "블리치 (문구).zip", "q", 10, 1, 7, pending);
    lib.record(1, "content://z/블리치 6권 (문구).zip", "블리치 6권 (문구).zip", "q", 10, 1, 7, pending);
    Set<String> groups = new HashSet<>();
    for (Library.Book b : lib.books()) groups.add(b.grp);
    assertEquals(Collections.singleton("series:블리치"), groups);
    lib.record(1, "content://a/외전.zip", "외전.zip", "folderA", 10, 1, 7, pending);
    lib.record(1, "content://b/외전.zip", "외전.zip", "folderB", 10, 1, 7, pending);
    groups.clear();
    for (Library.Book b : lib.books()) groups.add(b.grp);
    assertEquals(3, groups.size());
    lib.close();
  }
}
