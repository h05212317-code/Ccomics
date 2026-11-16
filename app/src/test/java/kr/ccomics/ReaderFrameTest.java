package kr.ccomics;

import static org.junit.Assert.*;

import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderFrameTest {
  Reader reader;
  Main host;
  CountDownLatch release;
  final int[] colors = {
    Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN, Color.WHITE
  };

  @Before
  public void prepare() throws Exception {
    host = Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("frames", 0);
    host.db = new Library(host);
    host.db.exec("INSERT INTO books(id,title,grp) VALUES(1,'Test','Test')");
    File file = new File(host.getCacheDir(), "frame-test.zip");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      for (int i = 0; i < 30; i++) {
        out.putNextEntry(new ZipEntry(i + ".png"));
        out.write(0);
        out.closeEntry();
      }
    }
    reader =
        new Reader(host, new Archive(host, Uri.fromFile(file).toString(), null), host.db.get(1));
    release = new CountDownLatch(1);
    reader.io.execute(
        () -> {
          try {
            release.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });
    reader.checked = reader.count();
    reader.solo = false;
    reader.layout(0, 0, 100, 100);
  }

  @After
  public void cleanup() throws Exception {
    if (reader != null) {
      reader.close();
      release.countDown();
      reader.io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    host.db.close();
  }

  void touch(long down, long time, int action, float x, float y) {
    android.view.MotionEvent event = android.view.MotionEvent.obtain(down, time, action, x, y, 0);
    reader.onTouchEvent(event);
    event.recycle();
  }

  void tap(long time, float x) {
    touch(time, time, 0, x, 50);
    touch(time, time + 15, 1, x, 50);
  }

  @Test
  public void rapidEdgeTapsAdvanceOnReleaseInBothDirections() {
    reader.mode = 1;
    assertEquals(0, reader.effect);
    assertEquals(2, reader.tapEffect);
    draw();
    decode(0);
    decode(1);
    assertFalse(reader.doubleZoom);
    long t = android.os.SystemClock.uptimeMillis();
    tap(t, 5);
    assertEquals(1, reader.page);
    draw();
    decode(2);
    tap(t + 65, 5);
    assertEquals(2, reader.page);
    tap(t + 120, 96);
    assertEquals(1, reader.page);
    assertEquals(1f, reader.zoom, 0f);
  }

  @Test
  public void centerDoubleZoomDoesNotDelayEdgeTaps() {
    host.prefs.edit().putBoolean("doubleZoom", true).apply();
    reader.readGestures();
    reader.mode = 1;
    reader.effect = 2;
    draw();
    decode(0);
    decode(1);
    long t = android.os.SystemClock.uptimeMillis();
    tap(t, 5);
    assertEquals(1, reader.page);
    tap(t + 100, 50);
    tap(t + 205, 50);
    assertTrue(reader.zoom > 1f);
  }

  @Test
  public void doubleZoomOffAndSwipeOff() {
    host.prefs.edit().putBoolean("swipeTurn", false).apply();
    reader.readGestures();
    reader.mode = 1;
    reader.effect = 2;
    draw();
    decode(0);
    decode(1);
    long t = android.os.SystemClock.uptimeMillis();
    tap(t, 50);
    tap(t + 100, 50);
    assertEquals(1f, reader.zoom, 0f);
    touch(t + 200, t + 200, 0, 5, 50);
    touch(t + 200, t + 240, 2, 85, 50);
    touch(t + 200, t + 255, 1, 90, 50);
    assertEquals(0, reader.page);
  }

  @Test
  public void bookshelfSelectorFitsLargeTextOnNarrowScreen() {
    android.widget.LinearLayout row = host.shelfSummary(42);
    android.widget.TextView button = (android.widget.TextView) row.getChildAt(1);
    button.setTextSize(28);
    row.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(
            host.dp(280), android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.UNSPECIFIED);
    row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    assertTrue(
        button.getLayout().getHeight()
            <= button.getHeight()
                - button.getCompoundPaddingTop()
                - button.getCompoundPaddingBottom());
    assertTrue(button.getBottom() <= row.getHeight() - row.getPaddingBottom());
  }

  @Test
  public void seriesDirectionAndFirstPageOverridesFollowAllVolumes() {
    assertFalse(reader.solo);
    reader.saveDirection(false, false);
    reader.saveDirection(true, true);
    String firstGroup = reader.book.grp;
    host.prefs.edit().putBoolean(reader.firstPageKey(), true).apply();
    reader.book.id++;
    reader.readSettings();
    assertTrue(reader.rtl);
    assertTrue(reader.solo);
    reader.book.grp = "Other";
    reader.readSettings();
    assertFalse(reader.rtl);
    assertFalse(reader.solo);
    reader.book.grp = firstGroup;
    reader.saveDirection(false, false);
    assertFalse(host.prefs.contains(reader.directionKey()));
  }

  @Test
  public void seekBarTouchFollowsDirection() {
    host.reader = reader;
    host.frame = new android.widget.FrameLayout(host);
    host.toggleReaderMenu();
    android.view.ViewGroup bar = (android.view.ViewGroup) host.seek.getParent();
    int width = 1000, height = 220;
    bar.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, 1073741824),
        android.view.View.MeasureSpec.makeMeasureSpec(height, 1073741824));
    bar.layout(0, 0, width, height);
    for (boolean rtl : new boolean[] {true, false}) {
      reader.rtl = rtl;
      host.readerStatus();
      float x = bar.getWidth() * .2f, y = host.seek.getTop() + host.seek.getHeight() / 2f;
      long t = android.os.SystemClock.uptimeMillis();
      for (int action : new int[] {0, 1}) {
        android.view.MotionEvent e =
            android.view.MotionEvent.obtain(t, t + action * 10, action, x, y, 0);
        bar.dispatchTouchEvent(e);
        e.recycle();
      }
      assertEquals(rtl, reader.page > reader.count() / 2);
    }
  }

  @Test
  public void releaseMetadataIdentifiesSignedAsset() throws Exception {
    org.json.JSONObject data = new org.json.JSONObject().put("tag_name", "v1.1.0");
    String url = "https://github.com/" + AppUpdates.REPO + "/releases/download/v1.1.0/Ccomics.apk";
    org.json.JSONObject asset =
        new org.json.JSONObject()
            .put("name", "Ccomics.apk")
            .put("size", 100)
            .put("digest", "sha256:" + new String(new char[64]).replace((char) 0, 'a'))
            .put("browser_download_url", url);
    data.put("assets", new org.json.JSONArray().put(asset));
    assertEquals(url, new AppUpdates.Release(data).url);
    assertTrue(AppUpdates.compare("1.10.0", "1.9.9") > 0);
    asset.put("browser_download_url", "https://example.org/Ccomics.apk");
    assertThrows(IOException.class, () -> new AppUpdates.Release(data));
  }

  @Test
  @Config(qualifiers = "w800dp-h900dp")
  public void wideSeriesScreenUsesTheSameCoverGridAsLibrary() {
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    host.db.exec(
        "INSERT INTO books(id,title,grp,series,pages) VALUES(2,'Test 02','Test','Test',10)");
    host.db.exec("UPDATE books SET series='Test' WHERE id=1");
    host.books = host.db.books();
    host.groups.put("Test", host.books);
    for (int size = 0; size < 3; size++) {
      host.prefs.edit().putInt("size", size).apply();
      host.selected = "";
      host.renderLibrary();
      int width = host.grid.getRequestedColumnWidth();
      assertEquals(1, host.grid.getAdapter().getCount());
      host.selected = "Test";
      host.renderLibrary();
      assertEquals(2, host.grid.getAdapter().getCount());
      assertEquals(width, host.grid.getRequestedColumnWidth());
      android.view.ViewGroup body =
          (android.view.ViewGroup) host.root.getChildAt(host.root.getChildCount() - 1);
      assertEquals(1, body.getChildCount());
    }
  }

  Bitmap draw() {
    Bitmap result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    reader.onDraw(new Canvas(result));
    return result;
  }

  void decode(int i) {
    Bitmap image = Bitmap.createBitmap(50, 100, Bitmap.Config.ARGB_8888);
    image.eraseColor(colors[i % colors.length]);
    reader.pageDecoded(i, image);
  }

  void assertVisible(int page) {
    Bitmap rendered = draw();
    int[] spread = reader.spread(page);
    boolean pair = spread.length == 2;
    assertEquals(
        colors[spread[0] % colors.length],
        rendered.getPixel(pair ? (reader.rtl ? 75 : 25) : 50, 50));
    if (pair)
      assertEquals(colors[spread[1] % colors.length], rendered.getPixel(reader.rtl ? 25 : 75, 50));
  }

  void exercise(int mode, boolean rtl, int effect) {
    reader.mode = mode;
    reader.rtl = rtl;
    reader.effect = effect;
    reader.tapEffect = effect;
    draw();
    for (int i : reader.spread(0)) decode(i);
    assertVisible(0);
    for (int k = 0; k < 10; k++) {
      int from = reader.page, dir = k < 6 ? 1 : -1, to = reader.next(dir);
      int[] target = reader.spread(to);
      if (k % 2 == 0) reader.animateTurn(dir);
      else {
        reader.startTurn(dir);
        reader.progress = .4f;
        reader.settle(true);
      }
      if (reader.waitingToCommit) {
        assertVisible(from);
        for (int i : target) {
          if (!reader.window.has(i)) {
            assertVisible(from);
            decode(i);
          }
        }
      }
      if (effect != 2) {
        assertNotNull(reader.animator);
        for (int frame = 1; frame < 10; frame++) {
          reader.progress = frame / 10f;
          Bitmap b = draw();
          int content = 0;
          for (int y = 5; y < 100; y += 10)
            for (int x = 5; x < 100; x += 10) if (b.getPixel(x, y) != reader.paper) content++;
          assertTrue("blank transition frame " + frame, content >= 25);
        }
        reader.animator.end();
      }
      reader.images.evictAll(); // Scroll cache eviction must never replace the displayed sheet.
      assertVisible(target[0]);
      assertEquals(target[0], reader.page);
    }
  }

  @Test
  public void rtlSinglePageCurl() {
    exercise(1, true, 0);
  }

  @Test
  public void rtlTwoPageCurl() {
    exercise(2, true, 0);
  }

  @Test
  public void ltrTwoPageCurl() {
    exercise(2, false, 0);
  }

  @Test
  public void ltrSinglePageSlide() {
    exercise(1, false, 1);
  }

  @Test
  public void instantTwoPageTurn() {
    exercise(2, true, 2);
  }

  @Test
  public void seekRetainsEntireOldSpreadUntilBothNewPagesArrive() {
    reader.mode = 2;
    draw();
    decode(0);
    decode(1);
    assertVisible(0);
    reader.seek(12);
    assertVisible(0);
    decode(13);
    assertVisible(0);
    decode(12);
    assertVisible(12);
    reader.seek(22);
    draw();
    reader.seek(4);
    draw();
    decode(22);
    decode(23);
    assertVisible(12);
    decode(4);
    assertVisible(12);
    decode(5);
    assertVisible(4);
  }
}
