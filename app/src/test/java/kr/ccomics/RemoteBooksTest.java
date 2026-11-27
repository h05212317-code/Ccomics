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

  @Test
  public void cancelAbortsStalledServerImmediately() throws Exception {
    java.util.concurrent.CountDownLatch releaseServer = new java.util.concurrent.CountDownLatch(1);
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            try {
              releaseServer.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/10485760")
                .setBody("a");
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    String url = s.url("/ccomics/stalled.zip").toString();
    RemoteBooks.Cancel cancel = new RemoteBooks.Cancel();
    java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
        new java.util.concurrent.atomic.AtomicReference<>();
    long t0 = System.currentTimeMillis();
    Thread worker =
        new Thread(
            () -> {
              try {
                RemoteBooks.download(ctx, url, "", quiet(), cancel);
              } catch (Throwable t) {
                thrown.set(t);
              } finally {
                finished.countDown();
              }
            });
    worker.start();
    try {
      Thread.sleep(250);
      cancel.cancel();
      assertTrue("download did not abort quickly", finished.await(3, TimeUnit.SECONDS));
      long elapsed = System.currentTimeMillis() - t0;
      assertTrue("expected fast cancel, took " + elapsed + "ms", elapsed < 3000);
      assertTrue(
          "expected InterruptedIOException, got " + thrown.get(),
          thrown.get() instanceof InterruptedIOException);
    } finally {
      releaseServer.countDown();
      s.shutdown();
    }
  }

  @Test
  public void cloudIconsShowCachedBlueAndMainShelfAllExternalOnly() throws Exception {
    Main host = org.robolectric.Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("cloud-test", 0);
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    RemoteBooks.clear(host);
    try (Library lib = new Library(host)) {
      host.db = lib;
      String u1 = "https://pc.example/ccomics/all-ext-1.zip";
      String u2 = "https://pc.example/ccomics/all-ext-2.zip";
      String u3 = "https://pc.example/ccomics/mixed-2.zip";
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,'https://pc.example/ccomics/','','')");
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(2,'content://local/','','')");
      lib.exec(
          "INSERT INTO books(source,url,title,series,grp,size,changed,seen,pages) VALUES"
              + "(1,?,'외부작 1권','외부작','series:ext',100,1,1,10),"
              + "(1,?,'외부작 2권','외부작','series:ext',100,1,1,10),"
              + "(2,'content://local/1','혼합작 1권','혼합작','series:mix',100,1,1,10),"
              + "(1,?,'혼합작 2권','혼합작','series:mix',100,1,1,10),"
              + "(2,'content://local/2','내부작 1권','내부작','series:loc',100,1,1,10)",
          u1,
          u2,
          u3);

      java.util.List<Library.Book> all = lib.books();
      host.books = all;
      host.groups.clear();
      for (Library.Book b : all)
        host.groups.computeIfAbsent(b.grp, k -> new java.util.ArrayList<>()).add(b);
      host.entries.clear();
      host.ensureEntries();

      // 전체 외부(미다운로드) -> 빈 구름, 혼합/내부 -> 구름 없음
      assertEquals(Main.REMOTE_CLOUD, host.entries.get("series:ext").remoteState);
      assertEquals(Main.REMOTE_NONE, host.entries.get("series:mix").remoteState);
      assertEquals(Main.REMOTE_NONE, host.entries.get("series:loc").remoteState);

      // 1권만 다운로드 완료 -> 메인 책장은 아직 빈 구름, 상세 책장에서 1권은 파란 구름 / 2권은 빈 구름
      assertTrue(RemoteBooks.file(host, u1).createNewFile());
      host.refreshRemoteEntries();
      assertEquals(Main.REMOTE_CLOUD, host.entries.get("series:ext").remoteState);
      java.util.Set<String> downloaded = RemoteBooks.downloadedNames(host);
      assertTrue(RemoteBooks.has(downloaded, u1));
      assertFalse(RemoteBooks.has(downloaded, u2));

      // 2권도 다운로드 완료 -> 전체권이 보관함에 있으므로 메인 책장도 파란 구름
      assertTrue(RemoteBooks.file(host, u2).createNewFile());
      host.refreshRemoteEntries();
      assertEquals(Main.REMOTE_CACHED, host.entries.get("series:ext").remoteState);
      assertEquals(Main.REMOTE_NONE, host.entries.get("series:mix").remoteState);

      // remoteMark drawable 부착/해제 검증
      android.widget.TextView sub = new android.widget.TextView(host);
      host.remoteMark(sub, Main.REMOTE_NONE);
      assertNull(sub.getCompoundDrawablesRelative()[2]);
      host.remoteMark(sub, Main.REMOTE_CLOUD);
      assertNotNull(sub.getCompoundDrawablesRelative()[2]);
      host.remoteMark(sub, Main.REMOTE_CACHED);
      assertNotNull(sub.getCompoundDrawablesRelative()[2]);

      // 보관함 비우기 후 다시 빈 구름으로 복귀
      RemoteBooks.clear(host);
      host.refreshRemoteEntries();
      assertEquals(Main.REMOTE_CLOUD, host.entries.get("series:ext").remoteState);
    }
  }

  @Test
  public void cancellingRemoteOpenDoesNotBlockLocalOpen() throws Exception {
    // 1x1 PNG가 든 로컬 ZIP 생성
    File localZip = new File(RuntimeEnvironment.getApplication().getCacheDir(), "local-open-test.zip");
    android.graphics.Bitmap bmp =
        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
    bmp.recycle();
    try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(new FileOutputStream(localZip))) {
      BundleTest.put(zos, "001.png", png.toByteArray(), true);
    }

    java.util.concurrent.CountDownLatch releaseServer = new java.util.concurrent.CountDownLatch(1);
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            try {
              releaseServer.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/10485760")
                .setBody("a");
          }
        });
    s.start();

    Main host = org.robolectric.Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("open-cancel-test", 0);
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    RemoteBooks.clear(host);
    try (Library lib = new Library(host)) {
      host.db = lib;
      String remoteUrl = s.url("/ccomics/very-slow.zip").toString();
      String localUrl = android.net.Uri.fromFile(localZip).toString();
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,'https://pc.example/','','')");
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(2,'file:///local/','','')");
      lib.exec(
          "INSERT INTO books(source,url,title,series,grp,size,changed,seen,pages) VALUES"
              + "(1,?,'느린 원격 1권','느린 원격','series:slow',100,1,1,10),"
              + "(2,?,'내부 책 1권','내부 책','series:local',100,1,1,1)",
          remoteUrl,
          localUrl);
      Library.Book remoteBook = null, localBook = null;
      for (Library.Book b : lib.books()) {
        if (b.url.equals(remoteUrl)) remoteBook = b;
        if (b.url.equals(localUrl)) localBook = b;
      }
      assertNotNull(remoteBook);
      assertNotNull(localBook);

      // 원격 책 열기 시작 → 뒤로가기 취소 → 즉시 내부 책 열기
      host.open(remoteBook);
      assertTrue(host.loadingBook);
      Thread.sleep(200);
      host.onBackPressed();
      assertFalse(host.loadingBook);

      long t0 = System.currentTimeMillis();
      host.open(localBook);
      long deadline = t0 + 3000;
      while (host.reader == null && System.currentTimeMillis() < deadline) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Thread.sleep(30);
      }
      assertNotNull("local book should open immediately after cancelling remote open", host.reader);
      assertEquals(localBook.id, host.reader.book.id);
      assertFalse(host.loadingBook);
      host.reader.close();
      host.reader = null;
      host.readerIO.shutdown();
      host.readerIO.awaitTermination(2, TimeUnit.SECONDS);
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
    } finally {
      releaseServer.countDown();
      s.shutdown();
      localZip.delete();
    }
  }
}
