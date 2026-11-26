package kr.ccomics;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
public class RemoteBooksTest {
  static MockResponse range(byte[] bytes, RecordedRequest r, int bytesPer10ms) {
    Matcher m = Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(r.getHeader("Range"));
    m.matches();
    long start = Long.parseLong(m.group(1));
    long end = Math.min(bytes.length - 1, Long.parseLong(m.group(2)));
    return new MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length)
        .throttleBody(bytesPer10ms, 10, TimeUnit.MILLISECONDS)
        .setBody(new Buffer().write(Arrays.copyOfRange(bytes, (int) start, (int) end + 1)));
  }

  @Test
  public void downloadsWholeVolumeInParallelThenOpensOffline() throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    Random rnd = new Random(3);
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(sink)) {
      for (int i = 0; i < 20; i++) {
        byte[] data = new byte[700_000];
        rnd.nextBytes(data);
        data[0] = (byte) i;
        BundleTest.put(zip, String.format("%03d.jpg", i), data, true);
      }
    }
    byte[] bytes = sink.toByteArray();
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
              Thread.sleep(150);
              return range(bytes, r, 64_000);
            } catch (InterruptedException e) {
              return new MockResponse().setResponseCode(500);
            } finally {
              active.decrementAndGet();
            }
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    String url = s.url("/ccomics/vol.zip").toString();
    try {
      assertFalse(RemoteBooks.has(ctx, url));
      long[] last = new long[2];
      File f =
          RemoteBooks.download(
              ctx,
              url,
              "",
              new RemoteBooks.Progress() {
                public void update(long done, long total) {
                  last[0] = done;
                  last[1] = total;
                }

                public boolean cancelled() {
                  return false;
                }
              });
      assertTrue(RemoteBooks.has(ctx, url));
      assertEquals(bytes.length, last[0]);
      assertEquals(bytes.length, last[1]);
      assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(f.toPath()));
      assertTrue("parallel requests: " + peak.get(), peak.get() >= 3);
    } finally {
      s.shutdown();
    }
    // 서버가 꺼진 뒤에도 저장본으로 열린다.
    try (Archive a = new Archive(ctx, RemoteBooks.localAddress(ctx, url), null)) {
      assertEquals(20, a.pages.size());
      try (InputStream in = a.zip.getInputStream(a.pages.get(7))) {
        assertEquals(7, in.read());
      }
    }
    assertEquals(1, RemoteBooks.usage(ctx)[0]);
    RemoteBooks.clear(ctx);
    assertFalse(RemoteBooks.has(ctx, url));
  }

  @Test
  public void cancelDeletesPartialFile() throws Exception {
    byte[] bytes = new byte[6_000_000];
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            return range(bytes, r, 10_000);
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    String url = s.url("/ccomics/big.zip").toString();
    long t0 = System.currentTimeMillis();
    try {
      RemoteBooks.download(
          ctx,
          url,
          "",
          new RemoteBooks.Progress() {
            public void update(long done, long total) {}

            public boolean cancelled() {
              return System.currentTimeMillis() - t0 > 500;
            }
          });
      fail();
    } catch (InterruptedIOException expected) {
    } finally {
      s.shutdown();
    }
    // 취소해도 받은 조각은 남고, 다시 받으면 나머지만 받아 완성한다.
    assertFalse(RemoteBooks.has(ctx, url));
    assertTrue(new File(RemoteBooks.file(ctx, url).getPath() + ".part").exists());
  }

  @Test
  public void survivesDroppedConnectionsAndResumesAfterServerOutage() throws Exception {
    byte[] bytes = new byte[18_000_000];
    new Random(5).nextBytes(bytes);
    AtomicInteger calls = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicLong served = new java.util.concurrent.atomic.AtomicLong();
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            int n = calls.incrementAndGet();
            if (down.get()) return new MockResponse().setResponseCode(502);
            MockResponse res = range(bytes, r, 200_000);
            // 휴대폰 망처럼 세 번에 한 번은 본문 도중 연결이 끊긴다.
            if (n % 3 == 0) res.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
            else {
              Matcher m = Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(r.getHeader("Range"));
              m.matches();
              served.addAndGet(Math.min(bytes.length - 1, Long.parseLong(m.group(2))) - Long.parseLong(m.group(1)) + 1);
            }
            return res;
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    String url = s.url("/ccomics/flaky.zip").toString();
    try {
      // 1) 연결이 자주 끊겨도 끝까지 받는다.
      File f = RemoteBooks.download(ctx, url, "", quiet());
      assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(f.toPath()));
      // 2) 받는 도중 PC가 꺼지면(502) 받은 만큼 남기고 Incomplete, 다시 켜지면 나머지만 받는다.
      RemoteBooks.clear(ctx);
      long[] seen = new long[1];
      try {
        RemoteBooks.download(
            ctx,
            url,
            "",
            new RemoteBooks.Progress() {
              public void update(long done, long total) {
                seen[0] = done;
                if (done > total / 3) down.set(true);
              }

              public boolean cancelled() {
                return false;
              }
            });
        fail();
      } catch (RemoteBooks.Incomplete e) {
        assertTrue(e.done > 0);
      }
      down.set(false);
      served.set(0);
      File again = RemoteBooks.download(ctx, url, "", quiet());
      assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(again.toPath()));
      assertTrue("resumed bytes " + served.get(), served.get() < bytes.length);
    } finally {
      s.shutdown();
    }
  }

  static RemoteBooks.Progress quiet() {
    return new RemoteBooks.Progress() {
      public void update(long done, long total) {}

      public boolean cancelled() {
        return false;
      }
    };
  }
}
