package kr.ccomics;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 실제 사용 조건 재현: 페이지가 __ridi__0,1,10,100… 사전순으로 저장된 ZIP, Funnel 실측 속도(약 1.2MB/s),
 * 요청마다 왕복 지연, 한 페이지를 약 2초 보고 넘김. 넘길 때 기다리는 시간이 거의 없어야 한다.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RemoteSpeedTest {
  @Test
  public void pageTurnsDoNotWaitOnSlowLinkWithShuffledZipOrder() throws Exception {
    int count = 40;
    List<String> names = new ArrayList<>();
    for (int i = 0; i < count; i++) names.add("__ridi__" + i + ".jpg");
    Collections.sort(names); // ZIP 안 저장 순서: 사전순(0, 1, 10, 11 … 2, 20 …)
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    Random rnd = new Random(7);
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(sink)) {
      for (String n : names) {
        byte[] data = new byte[600_000];
        rnd.nextBytes(data);
        data[0] = (byte) Integer.parseInt(n.replaceAll("[^0-9]", ""));
        BundleTest.put(zip, n, data, true);
      }
    }
    byte[] bytes = sink.toByteArray();
    String auth = WebDav.auth("u", "p");
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            Matcher m = Pattern.compile("bytes=([0-9]*)-([0-9]*)").matcher(r.getHeader("Range"));
            m.matches();
            long start, end;
            if (m.group(1).isEmpty()) {
              start = Math.max(0, bytes.length - Long.parseLong(m.group(2)));
              end = bytes.length - 1;
            } else {
              start = Long.parseLong(m.group(1));
              end = Math.min(bytes.length - 1, Long.parseLong(m.group(2)));
            }
            return new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length)
                .setHeadersDelay(150, TimeUnit.MILLISECONDS)
                .throttleBody(12_000, 10, TimeUnit.MILLISECONDS) // 약 1.2MB/s
                .setBody(new Buffer().write(Arrays.copyOfRange(bytes, (int) start, (int) end + 1)));
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    WebDav.Cache.clear(ctx);
    try (Archive a = new Archive(ctx, s.url("/ccomics/vol.zip").toString(), null, auth)) {
      a.remote.streamAhead();
      List<Long> waits = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        long t = System.currentTimeMillis();
        a.prefetch(i, 8);
        try (InputStream in = a.limited(a.pages.get(i))) {
          assertEquals(i, in.read()); // 읽는 순서 = 페이지 번호 순서
          byte[] b = new byte[65536];
          while (in.read(b) > 0) {}
        }
        waits.add(System.currentTimeMillis() - t);
        Thread.sleep(2000); // 한 페이지 보는 시간
      }
      System.out.println("SPEED waits(ms)=" + waits);
      for (int i = 1; i < waits.size(); i++)
        assertTrue("page " + i + " waited " + waits.get(i) + "ms " + waits, waits.get(i) < 300);
    } finally {
      s.shutdown();
    }
  }
}
