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

  @Test
  public void remoteCacheListingMatchesBooksBundlesPartialsAndOrphans() throws Exception {
    Main host = org.robolectric.Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("cache-manage-test", 0);
    host.prefs.edit().putBoolean("remoteOn", false).apply(); // 원격 폴더가 꺼져 있어도 DB로 책 이름을 찾는다.
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    host.theme();
    RemoteBooks.clear(host);
    try (Library lib = new Library(host)) {
      host.db = lib;
      String uA1 = "https://pc.example/ccomics/alpha-1.zip";
      String uA2 = "https://pc.example/ccomics/alpha-2.zip";
      String uB = "https://pc.example/ccomics/beta-bundle.zip";
      String uB1 = Archive.nested(uB, "beta-01.zip");
      String uB2 = Archive.nested(uB, "beta-02.zip");
      String uMiss = "https://pc.example/ccomics/gone-1.zip";
      String uPart = "https://pc.example/ccomics/partial-1.zip";
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,'https://pc.example/ccomics/','','')");
      lib.exec(
          "INSERT INTO books(source,url,title,series,grp,size,changed,seen,pages,page,opened,done,missing,bundle) VALUES"
              + "(1,?,'알파 1권','알파','series:alpha',100,1,1,100,99,10,1,0,0),"
              + "(1,?,'알파 2권','알파','series:alpha',100,1,1,100,40,20,0,0,0),"
              + "(1,?,'베타 합본','베타','series:beta',100,1,1,0,0,0,0,0,1),"
              + "(1,?,'베타 1권','베타','series:beta',100,1,1,50,0,0,1,0,0),"
              + "(1,?,'베타 2권','베타','series:beta',100,1,1,50,0,0,0,0,0),"
              + "(1,?,'사라진 책 1권','사라진 책','series:gone',100,1,1,50,0,0,0,1,0),"
              + "(1,?,'미완료작 1권','미완료작','series:partial',100,1,1,50,0,0,0,0,0)",
          uA1,
          uA2,
          uB,
          uB1,
          uB2,
          uMiss,
          uPart);

      // 캐시 파일 생성 (크기와 수정 시각을 다르게 설정해 정렬 검증)
      writeSized(RemoteBooks.file(host, uA1), 3 << 20, 1000L);
      writeSized(RemoteBooks.file(host, uA2), 4 << 20, 4000L);
      writeSized(RemoteBooks.file(host, uB), 10 << 20, 2000L);
      writeSized(RemoteBooks.file(host, uMiss), 2 << 20, 1500L);
      // 미완료 파일 (.part + .pieces)
      File part = new File(RemoteBooks.file(host, uPart).getPath() + ".part");
      writeSized(part, 4 << 20, 5000L);
      File pieces = new File(RemoteBooks.file(host, uPart).getPath() + ".pieces");
      try (DataOutputStream out = new DataOutputStream(new FileOutputStream(pieces))) {
        out.writeLong(0x43435032L);
        out.writeInt(RemoteBooks.PIECE);
        out.writeLong(4L << 20);
        BitSet bs = new BitSet();
        bs.set(0); // 2MB / 4MB = 50%
        byte[] bits = bs.toByteArray();
        out.writeInt(bits.length);
        out.write(bits);
      }
      // DB에 없는 고아 파일
      File orphan = new File(RemoteBooks.dir(host), "deadbeef0011223344556677.zip");
      writeSized(orphan, 1 << 20, 500L);

      // 용량 큰 순: 베타(10MB) > 알파(7MB) > 미완료작(4MB+) > 사라진 책(2MB) > 고아(1MB)
      List<Main.RemoteCacheGroup> bySize = host.buildRemoteCacheGroups(Main.CACHE_SORT_SIZE);
      assertEquals(5, bySize.size());
      assertEquals("베타", bySize.get(0).title);
      assertTrue(bySize.get(0).items.get(0).isBundle);
      assertEquals(2, bySize.get(0).items.get(0).matchedBooks.size());
      assertTrue(bySize.get(0).items.get(0).subtitle.contains("1/2권 완독"));

      assertEquals("알파", bySize.get(1).title);
      assertEquals(2, bySize.get(1).items.size());
      assertTrue(bySize.get(1).items.get(0).allDone);
      assertTrue(bySize.get(1).items.get(0).subtitle.contains("완독"));
      assertFalse(bySize.get(1).items.get(1).allDone);
      assertTrue(bySize.get(1).items.get(1).subtitle.contains("41/100p"));

      assertEquals("미완료작", bySize.get(2).title);
      assertFalse(bySize.get(2).items.get(0).item.complete);
      assertEquals(50, bySize.get(2).items.get(0).item.percent);
      assertTrue(bySize.get(2).items.get(0).subtitle.contains("미완료 50%"));

      assertEquals("사라진 책", bySize.get(3).title);
      assertTrue(bySize.get(3).items.get(0).missing);
      assertTrue(bySize.get(3).items.get(0).subtitle.contains("PC에서 사라짐"));

      assertTrue(bySize.get(4).orphan);
      assertEquals("연결되지 않은 캐시", bySize.get(4).title);

      // 최근 저장순: 미완료작(5000) > 알파(4000) > 베타(2000)
      List<Main.RemoteCacheGroup> byRecent = host.buildRemoteCacheGroups(Main.CACHE_SORT_RECENT);
      assertEquals("미완료작", byRecent.get(0).title);
      assertEquals("알파", byRecent.get(1).title);

      // 이름순: 고아 그룹이 맨 뒤로 간다.
      List<Main.RemoteCacheGroup> byName = host.buildRemoteCacheGroups(Main.CACHE_SORT_NAME);
      assertTrue(byName.get(byName.size() - 1).orphan);

      // 개별 삭제 검증: 알파 1권 + 미완료작(.part/.pieces) 삭제
      assertTrue(RemoteBooks.hasAny(host, uPart));
      assertEquals(2, host.deleteRemoteCacheFiles(Arrays.asList(RemoteBooks.fileName(uA1), RemoteBooks.fileName(uPart))));
      assertFalse(RemoteBooks.has(host, uA1));
      assertTrue(RemoteBooks.has(host, uA2));
      assertFalse(RemoteBooks.hasAny(host, uPart));
      assertFalse(part.exists());
      assertFalse(pieces.exists());

      // 원격 보관함 다이얼로그가 정상적으로 열리고 렌더링되는지 검증
      host.remoteStorage();
      android.app.AlertDialog shown = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(shown);
      assertTrue(shown.isShowing());
      shown.dismiss();
    } finally {
      RemoteBooks.clear(host);
    }
  }

  private static void writeSized(File f, int size, long modified) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
      raf.setLength(size);
    }
    assertTrue(f.setLastModified(modified));
  }

  @Test
  public void pikpakConcurrencySettingsAnd429RedirectDownload() throws Exception {
    assertEquals(6, RemoteBooks.parallelFor("https://dav.pikpak.ai/a.zip"));
    assertEquals(RemoteBooks.PIKPAK_PIECE, RemoteBooks.pieceFor("https://dav.pikpak.ai/a.zip"));
    assertEquals(RemoteBooks.PARALLEL, RemoteBooks.parallelFor("https://pc.ts.net/ccomics/a.zip"));
    assertEquals(RemoteBooks.PIECE, RemoteBooks.pieceFor("https://pc.ts.net/ccomics/a.zip"));

    byte[] bytes = new byte[5_000_000];
    new Random(11).nextBytes(bytes);
    AtomicInteger gatewayHits = new AtomicInteger();
    AtomicInteger cdnHits = new AtomicInteger();
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            if (r.getPath().startsWith("/cdn/")) {
              if (cdnHits.incrementAndGet() == 2) {
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "1");
              }
              return range(bytes, r, 512_000);
            }
            gatewayHits.incrementAndGet();
            return new MockResponse().setResponseCode(302).setHeader("Location", "/cdn/pik.zip");
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    String url = s.url("/pik.zip").toString();
    try {
      File out = RemoteBooks.download(ctx, url, WebDav.auth("u", "p"), quiet());
      assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(out.toPath()));
      // probe에서 1회 302로 얻은 CDN 주소를 모든 조각이 재사용하므로 게이트웨이는 1번만 호출된다.
      assertEquals("gateway should only be hit once", 1, gatewayHits.get());
      assertTrue("cdn should serve pieces", cdnHits.get() >= 3);
    } finally {
      RemoteBooks.clear(ctx);
      s.shutdown();
    }
  }

  @Test
  public void perSourceModesCachedOnlyHiddenAndRemoveSource() throws Exception {
    Main host = org.robolectric.Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("per-source-test", 0);
    host.prefs.edit().clear().apply();
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    host.theme();
    RemoteBooks.clear(host);
    try (Library lib = new Library(host)) {
      host.db = lib;
      String pcUrl = "https://pc.example/ccomics/pc-1.zip";
      String pikCached = "https://dav.pikpak.ai/%EB%A7%8C%ED%99%94/pik-1.zip";
      String pikUncached = "https://dav.pikpak.ai/%EB%A7%8C%ED%99%94/pik-2.zip";
      String localUrl = "content://local/1";
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,'https://pc.example/ccomics/','u1','')");
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(2,'https://dav.pikpak.ai/%EB%A7%8C%ED%99%94/','u2','')");
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(3,'content://local/','','')");
      lib.exec(
          "INSERT INTO books(source,url,title,series,grp,size,changed,seen,pages) VALUES"
              + "(1,?,'PC작 1권','PC작','series:pc',100,1,1,10),"
              + "(2,?,'픽팍작 1권','픽팍작','series:pik',100,1,1,10),"
              + "(2,?,'픽팍작 2권','픽팍작','series:pik',100,1,1,10),"
              + "(3,?,'로컬작 1권','로컬작','series:loc',100,1,1,10)",
          pcUrl,
          pikCached,
          pikUncached,
          localUrl);
      assertTrue(RemoteBooks.file(host, pikCached).createNewFile());

      // 레거시 remoteOn=false 마이그레이션: 개별 설정이 없으면 숨김으로 취급
      host.prefs.edit().putBoolean("remoteOn", false).apply();
      assertEquals(Main.SOURCE_HIDDEN, host.sourceMode(1, "https://pc.example/ccomics/"));
      assertEquals(Main.SOURCE_HIDDEN, host.sourceMode(2, "https://dav.pikpak.ai/%EB%A7%8C%ED%99%94/"));

      // PC=숨김, PikPak=보관본만 -> 로컬 + 받아 둔 픽팍 1권만 표시
      host.setSourceMode(1, Main.SOURCE_HIDDEN);
      host.setSourceMode(2, Main.SOURCE_CACHED_ONLY);
      host.library(false);
      long deadline = System.currentTimeMillis() + 3000;
      while (host.books.size() != 2 && System.currentTimeMillis() < deadline) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Thread.sleep(20);
      }
      assertEquals(2, host.books.size());
      Set<String> shownUrls = new HashSet<>();
      for (Library.Book b : host.books) shownUrls.add(b.url);
      assertTrue(shownUrls.contains(localUrl));
      assertTrue(shownUrls.contains(pikCached));
      assertFalse(shownUrls.contains(pikUncached));
      assertFalse(shownUrls.contains(pcUrl));

      // PikPak 연결 해제 시 책·소스·캐시가 함께 정리된다.
      lib.removeSource(2);
      assertEquals(1, lib.remoteSources().size());
      assertEquals(1L, lib.remoteSources().get(0).id);
      assertFalse(RemoteBooks.has(host, pikCached));
      assertEquals(0, lib.sourceBookCount(2));

      // 같은 URL의 원격 ZIP 크기가 바뀌면(페이지 추가 교체 등) 예전 보관함 캐시를 자동 무효화한다.
      assertTrue(RemoteBooks.file(host, pcUrl).createNewFile());
      assertTrue(RemoteBooks.has(host, pcUrl));
      List<Long> pending = new ArrayList<>();
      lib.record(1, pcUrl, "pc-1.zip", "https://pc.example/ccomics/", 250, 10000L, 99L, pending);
      assertFalse(RemoteBooks.has(host, pcUrl));
      assertFalse(pending.isEmpty());
    } finally {
      RemoteBooks.clear(host);
    }
  }
}
