package kr.ccomics;

import static org.junit.Assert.*;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowService;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DownloadServiceTest {
  @Test
  public void continuesDownloadInBackgroundAndPromotesForegroundService() throws Exception {
    byte[] bytes = new byte[3_000_000];
    new Random(21).nextBytes(bytes);
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            return RemoteBooksTest.range(bytes, r, 512_000);
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    DownloadService.setListener(null);
    String url = s.url("/pikpak/bg-vol.zip").toString();
    try {
      // 앱이 백그라운드(listener == null) 상태여도 다운로드가 끝까지 완료되고 pendingOpenBookId가 기록된다.
      int sid = DownloadService.startSingle(ctx, new DownloadService.Target(42L, url, "테스트 1권", ""));
      assertTrue(sid > 0);

      ServiceController<DownloadService> ctrl =
          Robolectric.buildService(
              DownloadService.class,
              new Intent(ctx, DownloadService.class).setAction(DownloadService.ACTION_START));
      ctrl.create().startCommand(0, 1);
      ShadowService shadowSvc = Shadows.shadowOf(ctrl.get());
      Notification notif = shadowSvc.getLastForegroundNotification();
      assertNotNull("foreground notification should be started", notif);

      long deadline = System.currentTimeMillis() + 10_000;
      while (DownloadService.isRunning() && System.currentTimeMillis() < deadline) {
        Thread.sleep(30);
      }
      assertFalse(DownloadService.isRunning());
      assertTrue(RemoteBooks.has(ctx, url));
      assertEquals(42L, DownloadService.consumePendingOpenBookId());
      assertTrue(DownloadService.consumePendingRefresh());
      ctrl.destroy();
    } finally {
      DownloadService.cancelActive(ctx);
      RemoteBooks.clear(ctx);
      s.shutdown();
    }
  }

  @Test
  public void cancelActionStopsActiveBackgroundDownloadImmediately() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            try {
              release.await(15, TimeUnit.SECONDS);
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
    DownloadService.setListener(null);
    String url = s.url("/pikpak/stalled-bg.zip").toString();
    try {
      DownloadService.startSingle(ctx, new DownloadService.Target(99L, url, "느린 책", ""));
      assertTrue(DownloadService.isRunning());

      ServiceController<DownloadService> ctrl =
          Robolectric.buildService(
              DownloadService.class,
              new Intent(ctx, DownloadService.class).setAction(DownloadService.ACTION_CANCEL));
      ctrl.create().startCommand(0, 1);
      assertFalse(DownloadService.isRunning());
      assertFalse(RemoteBooks.has(ctx, url));
      ctrl.destroy();
    } finally {
      release.countDown();
      DownloadService.cancelActive(ctx);
      RemoteBooks.clear(ctx);
      s.shutdown();
    }
  }

  @Test
  public void enqueueBatchAppendsInsteadOfCancellingActiveDownload() throws Exception {
    byte[] bytes5 = new byte[2_500_000];
    byte[] bytes6 = new byte[2_500_000];
    new Random(51).nextBytes(bytes5);
    new Random(61).nextBytes(bytes6);
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            byte[] src = r.getPath().contains("vol5") ? bytes5 : bytes6;
            return RemoteBooksTest.range(src, r, 128_000);
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    DownloadService.setListener(null);
    String u5 = s.url("/pikpak/vol5.zip").toString();
    String u6 = s.url("/pikpak/vol6.zip").toString();
    try {
      DownloadService.EnqueueResult r5 =
          DownloadService.enqueueBatch(
              ctx, Collections.singletonList(new DownloadService.Target(5L, u5, "5권", "")));
      assertEquals(1, r5.addedCount);
      assertFalse(r5.appended);

      // 5권이 내려받아지는 도중에 6권을 추가하면 5권을 취소하지 않고 대기열에 이어 붙는다.
      Thread.sleep(100);
      DownloadService.EnqueueResult r6 =
          DownloadService.enqueueBatch(
              ctx, Collections.singletonList(new DownloadService.Target(6L, u6, "6권", "")));
      assertTrue(r6.appended);
      assertEquals(1, r6.addedCount);
      assertEquals(r5.sessionId, r6.sessionId);

      long deadline = System.currentTimeMillis() + 15_000;
      while (DownloadService.isRunning() && System.currentTimeMillis() < deadline) {
        Thread.sleep(40);
      }
      assertFalse(DownloadService.isRunning());
      assertTrue("vol5 should complete without being cancelled", RemoteBooks.has(ctx, u5));
      assertTrue("vol6 should also complete from queue", RemoteBooks.has(ctx, u6));
    } finally {
      DownloadService.cancelActive(ctx);
      RemoteBooks.clear(ctx);
      s.shutdown();
    }
  }

  @Test
  public void detachAutoOpenAndSecondSingleKeepFirstDownloadRunningToCompletion() throws Exception {
    byte[] bytes1 = new byte[2_200_000];
    byte[] bytes2 = new byte[2_200_000];
    new Random(71).nextBytes(bytes1);
    new Random(72).nextBytes(bytes2);
    MockWebServer s = new MockWebServer();
    s.setDispatcher(
        new Dispatcher() {
          public MockResponse dispatch(RecordedRequest r) {
            byte[] src = r.getPath().contains("first") ? bytes1 : bytes2;
            return RemoteBooksTest.range(src, r, 128_000);
          }
        });
    s.start();
    Context ctx = RuntimeEnvironment.getApplication();
    RemoteBooks.clear(ctx);
    DownloadService.setListener(null);
    String u1 = s.url("/pikpak/first.zip").toString();
    String u2 = s.url("/pikpak/second.zip").toString();
    try {
      // 1) 단일 다운로드 시작 후 detachAutoOpen 호출(뒤로가기·다른 책 열기 상황) -> 자동 열기만 해제되고 파일은 끝까지 보관됨
      int sid = DownloadService.startSingle(ctx, new DownloadService.Target(11L, u1, "1권", ""));
      assertTrue(sid > 0);
      DownloadService.detachAutoOpen();
      assertFalse(DownloadService.isWaitingToOpen());
      assertTrue(DownloadService.isRunning());

      // 2) 그 와중에 다른 원격 책을 직접 눌러도(startSingle) 1권을 버리지 않고 2권 우선 후 1권도 끝까지 완료됨
      int sid2 = DownloadService.startSingle(ctx, new DownloadService.Target(12L, u2, "2권", ""));
      assertEquals(sid, sid2);

      long deadline = System.currentTimeMillis() + 15_000;
      while (DownloadService.isRunning() && System.currentTimeMillis() < deadline) {
        Thread.sleep(40);
      }
      assertFalse(DownloadService.isRunning());
      assertTrue("first volume should still finish caching", RemoteBooks.has(ctx, u1));
      assertTrue("second volume should also finish caching", RemoteBooks.has(ctx, u2));
      assertEquals(12L, DownloadService.consumePendingOpenBookId());
    } finally {
      DownloadService.cancelActive(ctx);
      RemoteBooks.clear(ctx);
      s.shutdown();
    }
  }

  @Test
  public void backgroundScanCompletesInForegroundServiceWithoutActivityListener() throws Exception {
    android.graphics.Bitmap bmp =
        android.graphics.Bitmap.createBitmap(16, 24, android.graphics.Bitmap.Config.ARGB_8888);
    java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
    bmp.recycle();
    java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
    try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos =
        new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(sink)) {
      BundleTest.put(zos, "001.png", png.toByteArray(), true);
    }
    byte[] zipBytes = sink.toByteArray();
    String auth = WebDav.auth("u", "p");
    MockWebServer s = WebDavTest.server(zipBytes, auth);
    Context ctx = RuntimeEnvironment.getApplication();
    WebDav.Cache.clear(ctx);
    DownloadService.setScanListener(null);
    DownloadService.consumePendingScanRefresh();
    try (Library lib = new Library(ctx)) {
      String root = s.url("/ccomics/").toString();
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,?,'u','')", root);
      lib.httpAuths.put(1L, auth);

      List<Long> started = DownloadService.enqueueScan(ctx, lib, Collections.singletonList(1L));
      assertEquals(1, started.size());

      ServiceController<DownloadService> ctrl =
          Robolectric.buildService(
              DownloadService.class,
              new Intent(ctx, DownloadService.class).setAction(DownloadService.ACTION_START));
      ctrl.create().startCommand(0, 1);
      assertNotNull(Shadows.shadowOf(ctrl.get()).getLastForegroundNotification());

      long deadline = System.currentTimeMillis() + 10_000;
      while (DownloadService.isScanning() && System.currentTimeMillis() < deadline) {
        Thread.sleep(30);
      }
      assertFalse(DownloadService.isScanning());
      assertTrue(DownloadService.consumePendingScanRefresh());
      assertEquals(1, lib.books().size());
      assertTrue(lib.coverFile(lib.books().get(0).id).isFile());
      ctrl.destroy();
    } finally {
      s.shutdown();
    }
  }
}
