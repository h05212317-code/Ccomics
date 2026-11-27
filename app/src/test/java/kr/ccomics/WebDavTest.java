package kr.ccomics;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WebDavTest {
  /** rclone serve webdav 처럼 PROPFIND와 Range GET에 답하는 가짜 서버. */
  static MockWebServer server(byte[] zip, String auth) throws IOException {
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            if (!auth.equals(r.getHeader("Authorization"))) return new MockResponse().setResponseCode(401);
            String path = r.getPath();
            if ("PROPFIND".equals(r.getMethod())) {
              String xml;
              if (path.equals("/ccomics/"))
                xml = "<D:response><D:href>/ccomics/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>"
                    + "<D:response><D:href>/ccomics/%ED%85%8C%EC%8A%A4%ED%8A%B8/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>";
              else
                xml = "<D:response><D:href>/ccomics/%ED%85%8C%EC%8A%A4%ED%8A%B8/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>"
                    + "<D:response><D:href>/ccomics/%ED%85%8C%EC%8A%A4%ED%8A%B8/%EB%A7%88%EC%99%95%EC%84%B1%20%EC%9A%94%EB%A6%AC%EC%82%AC%201%EA%B6%8C.zip</D:href><D:propstat><D:prop><D:resourcetype></D:resourcetype><D:getcontentlength>"
                    + zip.length + "</D:getcontentlength><D:getlastmodified>Mon, 23 Nov 2026 12:06:05 GMT</D:getlastmodified></D:prop></D:propstat></D:response>";
              return new MockResponse()
                  .setResponseCode(207)
                  .setBody("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">" + xml + "</D:multistatus>");
            }
            Matcher m = Pattern.compile("bytes=([0-9]*)-([0-9]*)").matcher(r.getHeader("Range"));
            if (!m.matches()) return new MockResponse().setResponseCode(400);
            long start, end;
            if (m.group(1).isEmpty()) {
              start = Math.max(0, zip.length - Long.parseLong(m.group(2)));
              end = zip.length - 1;
            } else {
              start = Long.parseLong(m.group(1));
              end = Math.min(zip.length - 1, Long.parseLong(m.group(2)));
            }
            Buffer body = new Buffer().write(Arrays.copyOfRange(zip, (int) start, (int) end + 1));
            return new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + zip.length)
                .setBody(body);
          }
        });
    s.start();
    return s;
  }

  @Test
  public void listsFoldersAndReadsZipPagesByRange() throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    Random rnd = new Random(1);
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(sink)) {
      for (int i = 1; i <= 5; i++) {
        byte[] data = new byte[700_000];
        rnd.nextBytes(data);
        data[0] = (byte) i;
        BundleTest.put(zip, String.format("%03d.jpg", i), data, true);
      }
    }
    byte[] bytes = sink.toByteArray();
    String auth = WebDav.auth("ccomics", "pw");
    MockWebServer s = server(bytes, auth);
    try {
      String root = s.url("/ccomics/").toString();
      List<WebDav.Entry> top = WebDav.list(root, auth);
      assertEquals(1, top.size());
      assertTrue(top.get(0).dir);
      assertEquals("테스트", top.get(0).name);
      List<WebDav.Entry> files = WebDav.list(top.get(0).url, auth);
      assertEquals(1, files.size());
      WebDav.Entry f = files.get(0);
      assertEquals("마왕성 요리사 1권.zip", f.name);
      assertEquals(bytes.length, f.size);

      Context ctx = RuntimeEnvironment.getApplication();
      try (Archive a = new Archive(ctx, f.url, null, auth)) {
        assertEquals(5, a.pages.size());
        for (int i = 0; i < 5; i++)
          try (InputStream in = a.zip.getInputStream(a.pages.get(i))) {
            assertEquals(i + 1, in.read());
          }
      }
      // 읽기 화면처럼 미리 받기를 켜면 파일 전체가 보관본에 차고, 다시 열면 같은 내용을 읽는다.
      try (Archive a = new Archive(ctx, f.url, null, auth)) {
        a.remote.streamAhead();
        long until = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < until) {
          synchronized (a.remote) {
            if (a.remote.have.cardinality() == a.remote.chunks) break;
          }
          Thread.sleep(50);
        }
        assertEquals(a.remote.chunks, a.remote.have.cardinality());
      }
      for (int k = 0; k < 100; k++) {
        synchronized (WebDav.Cache.OPEN) {
          if (WebDav.Cache.OPEN.isEmpty()) break;
        }
        Thread.sleep(50);
      }
      String key = Integer.toHexString(f.url.hashCode()) + "-" + Integer.toHexString(f.url.length());
      byte[] cached =
          java.nio.file.Files.readAllBytes(new File(WebDav.Cache.dir(ctx), key + ".part").toPath());
      assertArrayEquals(bytes, cached);
      try (Archive a = new Archive(ctx, f.url, null, auth)) {
        assertEquals(a.remote.chunks, a.remote.have.cardinality());
        try (InputStream in = a.zip.getInputStream(a.pages.get(4))) {
          assertEquals(5, in.read());
        }
      }
      try {
        WebDav.list(root, WebDav.auth("ccomics", "wrong"));
        fail();
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("비밀번호"));
      }
    } finally {
      s.shutdown();
    }
  }

  @Test
  public void remoteFolderNameBecomesSeries() {
    assertEquals(
        "마왕성 요리사",
        Order.resolveFolderSeries("https://pc.ts.net/ccomics/%EB%A7%88%EC%99%95%EC%84%B1%20%EC%9A%94%EB%A6%AC%EC%82%AC/"));
    assertEquals("1권", Order.volumeLabel("마왕성 요리사 1권 [원본] [1512px]", ""));
  }

  @Test
  public void pikpakEndpointDisplayAndErrors() {
    assertTrue(WebDav.isPikPak("https://dav.pikpak.ai/"));
    assertTrue(WebDav.isPikPak("https://dav.mypikpak.com/Comics/"));
    assertFalse(WebDav.isPikPak("https://pc.ts.net/ccomics/"));

    okhttp3.HttpUrl parsed = WebDav.parseEndpoint("dav.pikpak.ai");
    assertNotNull(parsed);
    assertEquals("https://dav.pikpak.ai/", parsed.toString());
    assertNull(WebDav.parseEndpoint("http://dav.pikpak.ai/"));

    assertEquals("PikPak · 최상위", WebDav.displayName("https://dav.pikpak.ai/"));
    assertEquals(
        "PikPak · /만화/신작",
        WebDav.displayName("https://dav.pikpak.ai/%EB%A7%8C%ED%99%94/%EC%8B%A0%EC%9E%91/"));

    IOException authErr = WebDav.fail(403, "https://dav.pikpak.ai/");
    assertTrue(authErr.getMessage().contains("PikPak"));
    assertTrue(authErr.getMessage().contains("프리미엄"));
    assertTrue(authErr.getMessage().contains("비밀번호"));
    assertTrue(WebDav.fail(429, "https://dav.pikpak.ai/").getMessage().contains("429"));
  }

  @Test
  public void handlesLowercaseHrefSuffixRangeFallbackRedirectAnd429() throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    Random rnd = new Random(9);
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(sink)) {
      for (int i = 1; i <= 3; i++) {
        byte[] data = new byte[400_000];
        rnd.nextBytes(data);
        data[0] = (byte) (i + 10);
        BundleTest.put(zip, String.format("%03d.jpg", i), data, true);
      }
    }
    byte[] bytes = sink.toByteArray();
    String auth = WebDav.auth("pikuser", "pikpass");
    java.util.concurrent.atomic.AtomicInteger propfindCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            String path = r.getPath();
            if (path.startsWith("/cdn/")) {
              // 리디렉션된 CDN은 명시적 Range만 허용하고 suffix Range(bytes=-)는 400 거부.
              if ("HEAD".equals(r.getMethod())) {
                return new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", bytes.length);
              }
              String range = r.getHeader("Range");
              Matcher m = Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(range == null ? "" : range);
              if (!m.matches()) return new MockResponse().setResponseCode(400);
              long start = Long.parseLong(m.group(1));
              long end = Math.min(bytes.length - 1, Long.parseLong(m.group(2)));
              return new MockResponse()
                  .setResponseCode(206)
                  .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length)
                  .setBody(new Buffer().write(Arrays.copyOfRange(bytes, (int) start, (int) end + 1)));
            }
            if (!auth.equals(r.getHeader("Authorization")))
              return new MockResponse().setResponseCode(401);
            if ("PROPFIND".equals(r.getMethod())) {
              if (propfindCalls.incrementAndGet() == 1) {
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "1");
              }
              // 소문자 퍼센트 인코딩으로 자기 자신 경로를 돌려줘도 중복 포함하지 않아야 한다.
              String xml =
                  "<d:response><d:href>/%eb%a7%8c%ed%99%94</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"
                      + "<d:response><d:href>/%eb%a7%8c%ed%99%94/book1.zip</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>"
                      + bytes.length
                      + "</d:getcontentlength><d:getlastmodified>2026-11-27T08:00:00Z</d:getlastmodified></d:prop></d:propstat></d:response>";
              return new MockResponse()
                  .setResponseCode(207)
                  .setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" + xml + "</d:multistatus>");
            }
            if ("HEAD".equals(r.getMethod())) {
              return new MockResponse().setResponseCode(302).setHeader("Location", "/cdn/book1.zip");
            }
            // GET도 CDN으로 302 리디렉션
            return new MockResponse().setResponseCode(302).setHeader("Location", "/cdn/book1.zip");
          }
        });
    s.start();
    try {
      String dir = s.url("/%EB%A7%8C%ED%99%94/").toString();
      List<WebDav.Entry> entries = WebDav.list(dir, auth);
      assertEquals(2, propfindCalls.get());
      assertEquals(1, entries.size());
      assertEquals("book1.zip", entries.get(0).name);
      assertTrue(entries.get(0).modified > 0);

      Context ctx = RuntimeEnvironment.getApplication();
      try (Archive a = new Archive(ctx, entries.get(0).url, null, auth)) {
        assertEquals(3, a.pages.size());
        for (int i = 0; i < 3; i++) {
          try (InputStream in = a.zip.getInputStream(a.pages.get(i))) {
            assertEquals(i + 11, in.read());
          }
        }
      }
    } finally {
      s.shutdown();
    }
  }

  @Test
  public void parallelWebDavScanUsesKnownSizeAndDoesNotPrefetchExtraPages() throws Exception {
    // 1장 표지(작은 PNG) + 10장의 큰 더미 페이지가 든 ZIP: 표지 스캔 시 뒤쪽 페이지를 받지 않아야 한다.
    android.graphics.Bitmap bmp =
        android.graphics.Bitmap.createBitmap(16, 24, android.graphics.Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
    bmp.recycle();
    byte[] coverBytes = png.toByteArray();
    byte[] filler = new byte[400_000];
    new Random(17).nextBytes(filler);

    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(sink)) {
      BundleTest.put(zos, "001.png", coverBytes, true);
      for (int p = 2; p <= 10; p++) {
        BundleTest.put(zos, String.format("%03d.jpg", p), filler, true);
      }
    }
    byte[] zipBytes = sink.toByteArray();
    String auth = WebDav.auth("pik", "pw");
    java.util.concurrent.atomic.AtomicInteger gatewayGetCount =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicLong bytesServed =
        new java.util.concurrent.atomic.AtomicLong();
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            String path = r.getPath();
            if (path.startsWith("/cdn/")) {
              String range = r.getHeader("Range");
              Matcher m =
                  Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(range == null ? "" : range);
              if (!m.matches()) return new MockResponse().setResponseCode(400);
              long start = Long.parseLong(m.group(1));
              long end = Math.min(zipBytes.length - 1, Long.parseLong(m.group(2)));
              bytesServed.addAndGet(end - start + 1);
              return new MockResponse()
                  .setResponseCode(206)
                  .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + zipBytes.length)
                  .setBody(
                      new Buffer().write(Arrays.copyOfRange(zipBytes, (int) start, (int) end + 1)));
            }
            if (!auth.equals(r.getHeader("Authorization")))
              return new MockResponse().setResponseCode(401);
            if ("PROPFIND".equals(r.getMethod())) {
              String xml;
              if (path.equals("/root/")) {
                xml =
                    "<D:response><D:href>/root/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>"
                        + "<D:response><D:href>/root/seriesA/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>"
                        + "<D:response><D:href>/root/seriesB/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>";
              } else {
                String prefix = path;
                xml =
                    "<D:response><D:href>" + prefix + "</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>"
                        + "<D:response><D:href>" + prefix + "vol1.zip</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>"
                        + zipBytes.length + "</D:getcontentlength></D:prop></D:propstat></D:response>"
                        + "<D:response><D:href>" + prefix + "vol2.zip</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>"
                        + zipBytes.length + "</D:getcontentlength></D:prop></D:propstat></D:response>";
              }
              return new MockResponse()
                  .setResponseCode(207)
                  .setBody("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">" + xml + "</D:multistatus>");
            }
            gatewayGetCount.incrementAndGet();
            return new MockResponse().setResponseCode(302).setHeader("Location", "/cdn" + path);
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    WebDav.Cache.clear(ctx);
    try (Library lib = new Library(ctx)) {
      String root = s.url("/root/").toString();
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,?,'pik','')", root);
      lib.httpAuths.put(1L, auth);
      lib.scan(1L, () -> {});
      List<Library.Book> books = lib.books();
      assertEquals(4, books.size());
      for (Library.Book b : books) {
        assertEquals(10, b.pages);
        assertTrue(lib.coverFile(b.id).isFile());
      }
      // 4권 각각 게이트웨이는 딱 1회(tail 302)만 거치고, 뒤쪽 9장(3.6MB*4=14.4MB)은 받지 않는다.
      assertEquals(4, gatewayGetCount.get());
      assertTrue(
          "served too many bytes during cover scan: " + bytesServed.get(),
          bytesServed.get() < (long) zipBytes.length * 2);
    } finally {
      s.shutdown();
    }
  }
}
