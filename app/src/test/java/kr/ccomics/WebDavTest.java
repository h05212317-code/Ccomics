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
}
