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
  @Config(qualifiers = "w320dp-h640dp")
  public void redesignedLibraryTopMenuStarHeaderAndFloatingResume() {
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    host.prefs.edit().clear().apply();
    host.db.exec(
        "UPDATE books SET title='원피스 1권',series='원피스',grp='series:원피스',rating=5,opened=100,page=4,pages=20,done=0 WHERE id=1");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,pages) VALUES(2,'나루토 1권','나루토','series:나루토',5,10)");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,pages) VALUES(3,'블리치 1권','블리치','series:블리치',4,10)");
    host.books = host.db.books();
    host.groups.clear();
    for (Library.Book b : host.books)
      host.groups.computeIfAbsent(b.grp, k -> new java.util.ArrayList<>()).add(b);

    // 1. 상단 통합 메뉴([전체] ⌄)와 하단 플로팅 이어읽기 기본 ON 확인
    assertTrue(host.showFloatingResume());
    host.selected = "";
    host.shelf = "";
    host.renderLibrary();

    android.view.ViewGroup topBar = (android.view.ViewGroup) host.root.getChildAt(0);
    android.view.ViewGroup menuBtn = (android.view.ViewGroup) topBar.getChildAt(0);
    android.widget.TextView pill = (android.widget.TextView) menuBtn.getChildAt(0);
    assertEquals("전체", pill.getText().toString());
    assertEquals(host.dp(30), pill.getLayoutParams().height);
    assertEquals("메뉴 열기", menuBtn.getChildAt(1).getContentDescription().toString());

    // 별점 첫 헤더: 정렬방식 ↑/↓ + 숫자만(작품 문구 없음, ★별점 없음), 빨간색 강조
    android.view.View firstHdr = host.sectionAdapter.getView(0, null, host.shelfList);
    android.view.ViewGroup firstLine = (android.view.ViewGroup) ((android.view.ViewGroup) firstHdr).getChildAt(1);
    android.widget.TextView sortTv = (android.widget.TextView) firstLine.getChildAt(1);
    android.widget.TextView countTv = (android.widget.TextView) firstLine.getChildAt(2);
    assertEquals(android.view.View.VISIBLE, sortTv.getVisibility());
    assertEquals("제목순 ↑", sortTv.getText().toString());
    assertFalse(sortTv.getText().toString().contains("★별점"));
    assertEquals("2", countTv.getText().toString());
    assertEquals(host.countAccentColor(), countTv.getCurrentTextColor());

    // 두 번째 별점 헤더: 정렬 텍스트 숨김, 숫자만("1") 표시
    android.view.View secondHdr = host.sectionAdapter.getView(2, null, host.shelfList);
    android.view.ViewGroup secondLine = (android.view.ViewGroup) ((android.view.ViewGroup) secondHdr).getChildAt(1);
    assertEquals(android.view.View.GONE, secondLine.getChildAt(1).getVisibility());
    assertEquals("1", ((android.widget.TextView) secondLine.getChildAt(2)).getText().toString());

    // 플로팅 이어 읽기 오버레이 존재 (body = SwipeRefreshLayout + floatingBar)
    android.view.ViewGroup body =
        (android.view.ViewGroup) host.root.getChildAt(host.root.getChildCount() - 1);
    assertTrue(host.floatingResumeVisible);
    assertEquals(2, body.getChildCount());

    // 플로팅 좌/우 스와이프로 닫기 동작 확인
    android.view.View floating = body.getChildAt(1);
    long t = android.os.SystemClock.uptimeMillis();
    android.view.MotionEvent d = android.view.MotionEvent.obtain(t, t, 0, 200f, 500f, 0);
    android.view.MotionEvent m = android.view.MotionEvent.obtain(t, t + 20, 2, 200f + host.dp(100), 500f, 0);
    android.view.MotionEvent u = android.view.MotionEvent.obtain(t, t + 40, 1, 200f + host.dp(110), 500f, 0);
    floating.dispatchTouchEvent(d);
    floating.dispatchTouchEvent(m);
    floating.dispatchTouchEvent(u);
    d.recycle();
    m.recycle();
    u.recycle();
    assertTrue(host.resumeDismissed);
    assertFalse(host.floatingResumeVisible);

    // 설정 OFF일 때는 resumeDismissed=false여도 표시하지 않음
    host.resumeDismissed = false;
    host.prefs.edit().putBoolean("showFloatingResume", false).apply();
    host.renderLibrary();
    body = (android.view.ViewGroup) host.root.getChildAt(host.root.getChildCount() - 1);
    assertFalse(host.floatingResumeVisible);
    assertEquals(1, body.getChildCount());
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
  public void longPressEdgeMovesNextAndPreviousVolumeWhenEnabled() {
    host.reader = reader;
    host.prefs.edit().putBoolean("autoNext", false).putBoolean("longPressVolume", true).apply();
    reader.readGestures();
    reader.rtl = true; // RTL: 왼쪽=다음, 오른쪽=이전
    reader.layout(0, 0, 1000, 1600);
    // 3권 시리즈(1, 2, 3)에서 현재 2권(reader.book.id == 1을 2번으로 구성)
    Library.Book prev = host.db.get(1);
    prev.id = 100;
    prev.title = "테스트 1권";
    Library.Book cur = reader.book;
    cur.grp = "series:lp";
    Library.Book next = host.db.get(1);
    next.id = 200;
    next.title = "테스트 3권";
    host.groups.put("series:lp", java.util.Arrays.asList(prev, cur, next));

    // 왼쪽(다음 영역) 꾹 누르기 -> 다음 권 확인 다이얼로그
    android.view.MotionEvent leftPress = android.view.MotionEvent.obtain(0, 0, 0, 100f, 800f, 0);
    assertTrue(reader.handleLongPress(leftPress));
    leftPress.recycle();
    android.app.AlertDialog nextDlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(nextDlg);
    assertEquals("테스트 3권", org.robolectric.Shadows.shadowOf(nextDlg).getMessage().toString());
    nextDlg.dismiss();

    // 오른쪽(이전 영역) 꾹 누르기 -> 이전 권 확인 다이얼로그
    android.view.MotionEvent rightPress = android.view.MotionEvent.obtain(0, 0, 0, 900f, 800f, 0);
    assertTrue(reader.handleLongPress(rightPress));
    rightPress.recycle();
    android.app.AlertDialog prevDlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(prevDlg);
    assertEquals("테스트 1권", org.robolectric.Shadows.shadowOf(prevDlg).getMessage().toString());
    prevDlg.dismiss();

    // 가운데 영역 꾹 누르기 -> 동작 안 함
    android.view.MotionEvent midPress = android.view.MotionEvent.obtain(0, 0, 0, 500f, 800f, 0);
    assertFalse(reader.handleLongPress(midPress));
    midPress.recycle();

    // 설정 OFF 시 동작 안 함
    host.prefs.edit().putBoolean("longPressVolume", false).apply();
    reader.readGestures();
    android.view.MotionEvent offPress = android.view.MotionEvent.obtain(0, 0, 0, 100f, 800f, 0);
    assertFalse(reader.handleLongPress(offPress));
    offPress.recycle();

    // 실제 터치에서 미세 흔들림(12dp)이 있어도 400ms 뒤 정상 동작하고, 큰 스와이프(36dp)는 취소된다.
    host.prefs.edit().putBoolean("longPressVolume", true).apply();
    reader.readGestures();
    long t = android.os.SystemClock.uptimeMillis();
    android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, 0, 100f, 800f, 0);
    reader.onTouchEvent(down);
    down.recycle();
    assertTrue(reader.edgeLongPressArmed);
    android.view.MotionEvent wiggle =
        android.view.MotionEvent.obtain(t, t + 100, 2, 100f + host.dp(12), 800f + host.dp(4), 0);
    reader.onTouchEvent(wiggle);
    wiggle.recycle();
    assertTrue("slight thumb wiggle should not cancel edge long press", reader.edgeLongPressArmed);
    android.view.MotionEvent bigMove =
        android.view.MotionEvent.obtain(t, t + 150, 2, 100f + host.dp(36), 800f, 0);
    reader.onTouchEvent(bigMove);
    bigMove.recycle();
    assertFalse("intentional swipe should cancel edge long press", reader.edgeLongPressArmed);
  }

  @Test
  public void longPressWorksAfterSeekBarMoveBothWithOverlayOpenAndClosed() {
    host.reader = reader;
    host.frame = new android.widget.FrameLayout(host);
    host.frame.addView(reader, -1, -1);
    int width = host.dp(320), screenHeight = host.dp(600);
    host.frame.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, 1073741824),
        android.view.View.MeasureSpec.makeMeasureSpec(screenHeight, 1073741824));
    host.frame.layout(0, 0, width, screenHeight);
    host.prefs.edit().putBoolean("autoNext", false).putBoolean("longPressVolume", true).apply();
    reader.readGestures();
    reader.rtl = true;

    host.db.exec("UPDATE books SET title='탐색 2권',grp='series:seeklp' WHERE id=1");
    host.db.exec("INSERT INTO books(id,title,grp) VALUES(101,'탐색 1권','series:seeklp')");
    host.db.exec("INSERT INTO books(id,title,grp) VALUES(103,'탐색 3권','series:seeklp')");
    reader.book.grp = "series:seeklp";
    // groups에 현재 권이 빠져 있어도 DB 폴백으로 시리즈 전권을 찾아야 한다.
    host.groups.put("series:seeklp", java.util.Collections.singletonList(host.db.get(103)));

    // 1. 탐색바 열기 -> 탐색바로 페이지 이동 -> 메뉴 바가 열린 상태(overlay != null)에서 곧장 왼쪽 꾹 누르기
    host.toggleReaderMenu();
    assertNotNull(host.overlay);
    android.view.ViewGroup bar = (android.view.ViewGroup) host.seek.getParent();
    bar.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, 1073741824),
        android.view.View.MeasureSpec.makeMeasureSpec(host.dp(60), 1073741824));
    bar.layout(0, 0, width, host.dp(60));
    float sx = host.seek.getLeft() + host.seek.getWidth() * .25f;
    float sy = host.seek.getTop() + host.seek.getHeight() / 2f;
    long t = android.os.SystemClock.uptimeMillis();
    for (int action : new int[] {0, 1}) {
      android.view.MotionEvent ev =
          android.view.MotionEvent.obtain(t, t + action * 10, action, sx, sy, 0);
      bar.dispatchTouchEvent(ev);
      ev.recycle();
    }
    assertTrue("seekbar should move page", reader.page > 0);
    assertNotNull("overlay remains open after seekbar", host.overlay);

    // 메뉴 바가 열린 채로 왼쪽 가장자리 꾹 누르기 -> 메뉴 닫히고 다음 권 동작
    long t2 = t + 100;
    touch(t2, t2, 0, width * 0.1f, screenHeight * 0.5f);
    assertTrue("long press should arm even while overlay is open", reader.edgeLongPressArmed);
    org.robolectric.shadows.ShadowLooper.idleMainLooper(
        Reader.LONG_PRESS_VOLUME_MS + 20, java.util.concurrent.TimeUnit.MILLISECONDS);
    touch(t2, t2 + Reader.LONG_PRESS_VOLUME_MS + 30, 1, width * 0.1f, screenHeight * 0.5f);
    assertNull("long press should dismiss overlay", host.overlay);
    android.app.AlertDialog nextDlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(nextDlg);
    assertEquals("탐색 3권", org.robolectric.Shadows.shadowOf(nextDlg).getMessage().toString());
    nextDlg.dismiss();

    // 2. 미세 확대 잔여(1.03f) 상태에서도 오른쪽 가장자리 꾹 누르기 -> 이전 권 동작
    reader.zoom = 1.03f;
    long t3 = t2 + 1000;
    touch(t3, t3, 0, width * 0.9f, screenHeight * 0.5f);
    assertTrue(reader.edgeLongPressArmed);
    org.robolectric.shadows.ShadowLooper.idleMainLooper(
        Reader.LONG_PRESS_VOLUME_MS + 20, java.util.concurrent.TimeUnit.MILLISECONDS);
    touch(t3, t3 + Reader.LONG_PRESS_VOLUME_MS + 30, 1, width * 0.9f, screenHeight * 0.5f);
    android.app.AlertDialog prevDlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(prevDlg);
    assertEquals("탐색 1권", org.robolectric.Shadows.shadowOf(prevDlg).getMessage().toString());
    prevDlg.dismiss();

    // 3. 읽기 화면 로딩 중 상단 진행 배너 표시 및 취소 시 숨김 확인
    host.loadingBook = true;
    host.scanMessage = "탐색 3권 받는 중…";
    host.updateScanBanner();
    assertNotNull(host.readerStatusBar);
    assertEquals(android.view.View.VISIBLE, host.readerStatusBar.getVisibility());
    assertEquals("탐색 3권 받는 중…", host.readerStatusText.getText().toString());
    host.cancelLoadingBook();
    assertNull(host.readerStatusBar);
  }

  @Test
  public void seekBarTouchFollowsDirection() {
    host.reader = reader;
    host.frame = new android.widget.FrameLayout(host);
    host.frame.addView(reader, -1, -1);
    int width = host.dp(320), screenHeight = host.dp(600);
    host.frame.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, 1073741824),
        android.view.View.MeasureSpec.makeMeasureSpec(screenHeight, 1073741824));
    host.frame.layout(0, 0, width, screenHeight);
    int readerHeightBefore = reader.getHeight();
    int readerTopBefore = reader.getTop();
    host.toggleReaderMenu();
    android.view.ViewGroup bar = (android.view.ViewGroup) host.seek.getParent();
    assertSame(host.overlay, bar);
    assertEquals(host.indicator, bar.getChildAt(0));
    assertEquals(host.seek, bar.getChildAt(1));
    assertEquals("책갈피", ((android.widget.TextView) bar.getChildAt(2)).getText());
    assertEquals("보기 설정", ((android.widget.TextView) bar.getChildAt(3)).getText());
    assertFalse(host.readerImmersive);
    int height = host.dp(60);
    bar.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, 1073741824),
        android.view.View.MeasureSpec.makeMeasureSpec(height, 1073741824));
    bar.layout(0, 0, width, height);
    assertTrue(host.seek.getWidth() >= host.dp(48));
    for (boolean rtl : new boolean[] {true, false}) {
      reader.rtl = rtl;
      host.readerStatus();
      float x = host.seek.getLeft() + host.seek.getWidth() * .2f,
          y = host.seek.getTop() + host.seek.getHeight() / 2f;
      long t = android.os.SystemClock.uptimeMillis();
      for (int action : new int[] {0, 1}) {
        android.view.MotionEvent e =
            android.view.MotionEvent.obtain(t, t + action * 10, action, x, y, 0);
        bar.dispatchTouchEvent(e);
        e.recycle();
      }
      assertEquals(rtl, reader.page > reader.count() / 2);
    }
    android.view.WindowInsets insets =
        new android.view.WindowInsets.Builder()
            .setInsets(
                android.view.WindowInsets.Type.statusBars(),
                android.graphics.Insets.of(0, 24, 0, 0))
            .setInsets(
                android.view.WindowInsets.Type.navigationBars(),
                android.graphics.Insets.of(0, 0, 0, 52))
            .setVisible(android.view.WindowInsets.Type.systemBars(), true)
            .build();
    host.frame.dispatchApplyWindowInsets(insets);
    bar.dispatchApplyWindowInsets(insets);
    host.topBar.dispatchApplyWindowInsets(insets);
    assertEquals(host.dp(2) + 52, bar.getPaddingBottom());
    assertEquals(host.dp(2) + 24, host.topBar.getPaddingTop());
    // 상·하단 바 표시 후에도 만화 뷰(reader)의 크기와 위치가 밀리지 않아야 한다.
    assertEquals(readerHeightBefore, reader.getHeight());
    assertEquals(readerTopBefore, reader.getTop());
    host.toggleReaderMenu();
    assertNull(host.overlay);
    assertTrue(host.readerImmersive);
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
    host.prefs
        .edit()
        .putInt("sortDefaultVersion", 2)
        .putBoolean("groupByRating", false)
        .putInt("sort", Main.SORT_TITLE)
        .putBoolean("sortDesc", false)
        .apply();
    host.db.exec(
        "INSERT INTO books(id,title,grp,series,pages) VALUES(2,'Test 02','Test','Test',10)");
    host.db.exec("UPDATE books SET series='Test' WHERE id=1");
    host.books = host.db.books();
    host.groups.put("Test", host.books);
    for (int size = 0; size < 3; size++) {
      host.prefs.edit().remove("thumbWidthDp").putInt("size", size).apply();
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
    // 바(슬라이더) 조절 즉시 UI 반영 테스트 (재실행 없음)
    host.applyThumbWidth(180);
    assertEquals(host.dp(180), host.grid.getRequestedColumnWidth());
    assertEquals(180, host.prefs.getInt("thumbWidthDp", -1));
  }

  @Test
  public void ratingGroupingToggleIsIndependentOfSortAndCustomOrderRespectsRatingBoundary()
      throws Exception {
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    host.prefs.edit().clear().apply();
    // 기본값: 별점순 표시하기 ON, 제목순 ↑
    assertTrue(host.groupByRating());
    assertEquals(Main.SORT_TITLE, host.currentSort());
    assertFalse(host.currentSortDesc());

    host.db.exec(
        "UPDATE books SET title='나루토 001권',series='나루토',grp='series:나루토',rating=5 WHERE id=1");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,pages) VALUES(2,'가정교사 1권','가정교사','series:가정교사',5,10)");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,pages) VALUES(3,'원피스 1권','원피스','series:원피스',3,10)");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,pages) VALUES(4,'블리치 1권','블리치','series:블리치',0,10)");
    host.books = host.db.books();
    host.groups.clear();
    for (Library.Book b : host.books)
      host.groups.computeIfAbsent(b.grp, k -> new java.util.ArrayList<>()).add(b);

    // 1. 별점순 표시하기 ON + 제목순 ↑ → 5→3→0 섹션, 5점 섹션 내부는 가나다순(가정교사 → 나루토)
    host.selected = "";
    host.renderLibrary();
    assertNotNull(host.shelfList);
    assertNotNull(host.sectionAdapter);
    assertTrue(host.sectionAdapter.showHeaders);
    assertEquals(3, host.sectionAdapter.sections.size());
    assertEquals(5, host.sectionAdapter.sections.get(0).rating);
    assertEquals("가정교사", host.sectionAdapter.sections.get(0).items.get(0).title);
    assertEquals("나루토", host.sectionAdapter.sections.get(0).items.get(1).title);
    assertEquals(3, host.sectionAdapter.sections.get(1).rating);
    assertEquals(0, host.sectionAdapter.sections.get(2).rating);

    // 슬라이더 크기 변경 시 별점 섹션 어댑터 즉시 반영
    host.applyThumbWidth(170);
    assertEquals(170, host.sectionAdapter.widthDp);

    // 2. 별점순 표시하기 ON + 제목순 ↓ → 섹션은 5→3→0 유지, 5점 섹션 내부만 역순(나루토 → 가정교사)
    host.prefs.edit().putBoolean("sortDesc", true).apply();
    host.renderLibrary();
    assertEquals(5, host.sectionAdapter.sections.get(0).rating);
    assertEquals("나루토", host.sectionAdapter.sections.get(0).items.get(0).title);
    assertEquals("가정교사", host.sectionAdapter.sections.get(0).items.get(1).title);

    // 3. 사용자 정의 정렬 + 배치 모드 아이콘 표시 확인 + 같은 별점 섹션 내부만 이동 허용
    host.prefs.edit().putInt("sort", Main.SORT_CUSTOM).putBoolean("sortDesc", false).apply();
    host.renderLibrary();
    android.view.ViewGroup topBar = (android.view.ViewGroup) host.root.getChildAt(0);
    boolean foundArrangeIcon = false;
    for (int i = 0; i < topBar.getChildCount(); i++) {
      CharSequence desc = topBar.getChildAt(i).getContentDescription();
      if (desc != null && "배치 모드".equals(desc.toString())) foundArrangeIcon = true;
    }
    assertTrue("사용자 정의 정렬에서 배치 모드 아이콘이 표시되어야 합니다", foundArrangeIcon);

    host.arrangeMode = true;
    host.renderLibrary();
    assertNotNull(host.dragController);

    // 같은 별점 섹션(5점: 가정교사, 나루토) 내 이동은 성공
    assertEquals("가정교사", host.sectionAdapter.sections.get(0).items.get(0).title);
    assertTrue(host.reorderCustomSection(0, 0, 0, 1));
    assertEquals("나루토", host.sectionAdapter.sections.get(0).items.get(0).title);
    assertEquals("가정교사", host.sectionAdapter.sections.get(0).items.get(1).title);

    // 다른 별점 섹션(5점 → 3점) 이동은 차단
    assertFalse(host.reorderCustomSection(0, 0, 1, 0));

    // 다시 그려도 사용자 정의 순서 유지
    host.arrangeMode = false;
    host.renderLibrary();
    assertEquals("나루토", host.sectionAdapter.sections.get(0).items.get(0).title);
    assertEquals("가정교사", host.sectionAdapter.sections.get(0).items.get(1).title);

    // 4. 별점순 표시하기 OFF + 사용자 정의 → 단일 평면 섹션에서 다른 별점 작품끼리도 자유 이동 가능
    host.prefs.edit().putBoolean("groupByRating", false).apply();
    host.arrangeMode = true;
    host.renderLibrary();
    assertFalse(host.sectionAdapter.showHeaders);
    assertEquals(1, host.sectionAdapter.sections.size());
    assertEquals(4, host.sectionAdapter.sections.get(0).items.size());
    String firstBefore = host.sectionAdapter.sections.get(0).items.get(0).title;
    String lastBefore = host.sectionAdapter.sections.get(0).items.get(3).title;
    assertTrue(host.reorderCustomSection(0, 3, 0, 0));
    assertEquals(lastBefore, host.sectionAdapter.sections.get(0).items.get(0).title);
    assertEquals(firstBefore, host.sectionAdapter.sections.get(0).items.get(1).title);

    // 뒤로가기 시 배치 모드 종료
    host.onBackPressed();
    assertFalse(host.arrangeMode);
  }

  @Test
  @Config(qualifiers = "w400dp-h720dp")
  public void dragArrangeCanMoveToVeryEndViaLastTileEmptySlotAndBottomWithoutPingPong() {
    host.frame = new android.widget.FrameLayout(host);
    host.setContentView(host.frame);
    host.prefs
        .edit()
        .clear()
        .putInt("sortDefaultVersion", 2)
        .putBoolean("groupByRating", false)
        .putInt("sort", Main.SORT_CUSTOM)
        .putBoolean("sortDesc", false)
        .putInt("thumbWidthDp", 100)
        .apply();
    host.db.exec(
        "UPDATE books SET title='가 1권',series='가',grp='series:가',rating=5,customOrder=1 WHERE id=1");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,customOrder,pages) VALUES(2,'나 1권','나','series:나',5,2,10)");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,customOrder,pages) VALUES(3,'다 1권','다','series:다',5,3,10)");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,customOrder,pages) VALUES(4,'라 1권','라','series:라',5,4,10)");
    host.db.exec(
        "INSERT INTO books(id,title,series,grp,rating,customOrder,pages) VALUES(5,'마 1권','마','series:마',5,5,10)");
    host.books = host.db.books();
    host.groups.clear();
    for (Library.Book b : host.books)
      host.groups.computeIfAbsent(b.grp, k -> new java.util.ArrayList<>()).add(b);

    host.selected = "";
    host.arrangeMode = true;
    host.renderLibrary();

    int w = host.dp(400), h = host.dp(720);
    Runnable relayout =
        () -> {
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
          host.shelfList.requestLayout();
          host.frame.measure(
              android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
              android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
          host.frame.layout(0, 0, w, h);
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        };
    relayout.run();

    Main.ShelfDragController dc = host.dragController;
    assertNotNull(dc);
    assertEquals(3, host.sectionAdapter.cols);
    assertEquals(2, host.shelfList.getChildCount());

    android.view.View row0 = host.shelfList.getChildAt(0);
    android.view.View row1 = host.shelfList.getChildAt(1);
    Main.TileHolder[] h0 = (Main.TileHolder[]) row0.getTag();
    Main.TileHolder[] h1 = (Main.TileHolder[]) row1.getTag();
    int[] listLoc = new int[2];
    host.shelfList.getLocationOnScreen(listLoc);
    int r0X = listLoc[0] + row0.getLeft(), r0Y = listLoc[1] + row0.getTop();
    int r1X = listLoc[0] + row1.getLeft(), r1Y = listLoc[1] + row1.getTop();

    // 1. 맨 뒤 바로 앞('라', index 3)을 맨 뒤('마', index 4) 위로 드래그 → 맨 뒤로 이동하고 애니메이션 중에도 되튕기지 않아야 한다.
    dc.startDrag(h1[0]);
    float lastTileCenterX = r1X + (h1[1].root.getLeft() + h1[1].root.getRight()) * 0.5f;
    float lastTileCenterY = r1Y + (h1[1].root.getTop() + h1[1].root.getBottom()) * 0.5f;
    dc.checkSwap(lastTileCenterX, lastTileCenterY);
    relayout.run();
    assertEquals("마", host.sectionAdapter.sections.get(0).items.get(3).title);
    assertEquals("라", host.sectionAdapter.sections.get(0).items.get(4).title);
    assertEquals(4, dc.dragIdx);

    // 밀려난 '마'(h1[0])가 슬라이드 애니메이션 중(translationX로 여전히 4번 자리 위에 보임)이어도 핑퐁으로 되돌아가면 안 된다.
    h1 = (Main.TileHolder[]) host.shelfList.getChildAt(1).getTag();
    float tileStep = h1[1].root.getLeft() - h1[0].root.getLeft();
    h1[0].root.setTranslationX(tileStep);
    dc.checkSwap(lastTileCenterX, lastTileCenterY);
    assertEquals(4, dc.dragIdx);
    assertEquals("라", host.sectionAdapter.sections.get(0).items.get(4).title);
    h1[0].root.setTranslationX(0f);
    dc.finishDrag();

    // 2. 맨 앞('가', index 0)을 마지막 줄 빈 칸(3번째 열)으로 드래그 → 맨 뒤(index 4)로 이동
    relayout.run();
    row0 = host.shelfList.getChildAt(0);
    row1 = host.shelfList.getChildAt(1);
    h0 = (Main.TileHolder[]) row0.getTag();
    h1 = (Main.TileHolder[]) row1.getTag();
    host.shelfList.getLocationOnScreen(listLoc);
    r1X = listLoc[0] + row1.getLeft();
    r1Y = listLoc[1] + row1.getTop();
    assertNull(h1[2].item);
    dc.startDrag(h0[0]);
    float emptySlotX = r1X + (h1[2].root.getLeft() + h1[2].root.getRight()) * 0.5f;
    float emptySlotY = r1Y + (h1[2].root.getTop() + h1[2].root.getBottom()) * 0.5f;
    dc.checkSwap(emptySlotX, emptySlotY);
    relayout.run();
    assertEquals(4, dc.dragIdx);
    assertEquals("가", host.sectionAdapter.sections.get(0).items.get(4).title);
    dc.finishDrag();

    // 3. 첫 항목('나', index 0)을 마지막 줄 아래 여백으로 드래그 → 맨 뒤(index 4)로 이동
    relayout.run();
    row0 = host.shelfList.getChildAt(0);
    row1 = host.shelfList.getChildAt(1);
    h0 = (Main.TileHolder[]) row0.getTag();
    host.shelfList.getLocationOnScreen(listLoc);
    r1X = listLoc[0] + row1.getLeft();
    r1Y = listLoc[1] + row1.getTop();
    dc.startDrag(h0[0]);
    dc.checkSwap(r1X + w * 0.5f, r1Y + row1.getHeight() + host.dp(30));
    relayout.run();
    assertEquals(4, dc.dragIdx);
    assertEquals("나", host.sectionAdapter.sections.get(0).items.get(4).title);

    // 4. 같은 드래그 중 첫 줄 위 여백으로 올리면 맨 앞(index 0)으로 이동
    row0 = host.shelfList.getChildAt(0);
    host.shelfList.getLocationOnScreen(listLoc);
    r0X = listLoc[0] + row0.getLeft();
    r0Y = listLoc[1] + row0.getTop();
    dc.checkSwap(r0X + w * 0.5f, r0Y - host.dp(10));
    relayout.run();
    assertEquals(0, dc.dragIdx);
    assertEquals("나", host.sectionAdapter.sections.get(0).items.get(0).title);
    dc.finishDrag();
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

  @Test
  public void touchZoneOverlayDragsBoundariesSavesAndAppliesAsymmetricTapZones() {
    host.reader = reader;
    host.frame = new android.widget.FrameLayout(host);
    host.frame.addView(reader, -1, -1);
    int w = host.dp(360), h = host.dp(720);
    host.frame.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
    host.frame.layout(0, 0, w, h);

    // 메뉴가 열려 있는 상태에서 터치 범위 설정을 열면 메뉴는 닫히고 전체 화면 오버레이가 뜬다.
    host.toggleReaderMenu();
    assertNotNull(host.overlay);
    host.openTouchZoneEditor();
    assertNull(host.overlay);
    assertNotNull(host.touchZoneOverlay);

    TouchZoneOverlay tz = host.touchZoneOverlay;
    tz.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
    tz.layout(0, 0, w, h);

    // 실제 캔버스 그리기가 예외 없이 동작해야 한다.
    Bitmap preview = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    tz.draw(new Canvas(preview));
    preview.recycle();

    long t = android.os.SystemClock.uptimeMillis();
    float midY = h * 0.45f;

    // 1. 왼쪽 경계(0.33)를 오른쪽(0.58)으로 드래그 (가운데 50%를 넘어 비대칭 배치)
    float startL = w * tz.left;
    float targetL = w * 0.58f;
    android.view.MotionEvent d1 = android.view.MotionEvent.obtain(t, t, 0, startL, midY, 0);
    android.view.MotionEvent m1 = android.view.MotionEvent.obtain(t, t + 20, 2, targetL, midY, 0);
    android.view.MotionEvent u1 = android.view.MotionEvent.obtain(t, t + 40, 1, targetL, midY, 0);
    tz.canvas.onTouchEvent(d1);
    tz.canvas.onTouchEvent(m1);
    tz.canvas.onTouchEvent(u1);
    d1.recycle();
    m1.recycle();
    u1.recycle();
    assertEquals(0.58f, tz.left, 0.02f);

    // 2. 오른쪽 경계(0.67)를 0.80으로 드래그
    float startR = w * tz.right;
    float targetR = w * 0.80f;
    android.view.MotionEvent d2 = android.view.MotionEvent.obtain(t + 50, t + 50, 0, startR, midY, 0);
    android.view.MotionEvent m2 = android.view.MotionEvent.obtain(t + 50, t + 70, 2, targetR, midY, 0);
    android.view.MotionEvent u2 = android.view.MotionEvent.obtain(t + 50, t + 90, 1, targetR, midY, 0);
    tz.canvas.onTouchEvent(d2);
    tz.canvas.onTouchEvent(m2);
    tz.canvas.onTouchEvent(u2);
    d2.recycle();
    m2.recycle();
    u2.recycle();
    assertEquals(0.80f, tz.right, 0.02f);

    // 3. 저장 버튼 실행 → prefs와 reader에 반영되고 오버레이가 닫힌다.
    tz.listener.onSave(tz.left, tz.right);
    assertNull(host.touchZoneOverlay);
    assertEquals(0.58f, reader.tapLeft, 0.02f);
    assertEquals(0.80f, reader.tapRight, 0.02f);

    // 4. 비대칭 영역 실제 탭 동작 검증 (rtl=true, width=w):
    //    55% 위치는 화면 중앙 오른쪽이지만 tapLeft(58%) 안쪽이므로 '왼쪽 영역(다음 페이지)'으로 동작해야 한다.
    reader.mode = 1;
    reader.rtl = true;
    reader.effect = 2;
    reader.tapEffect = 2;
    reader.layout(0, 0, w, h);
    draw();
    decode(0);
    decode(1);
    assertEquals(0, reader.page);
    tap(t + 200, w * 0.55f);
    assertEquals(1, reader.page);
    // 68% 위치는 가운데 메뉴 영역 → 메뉴 토글, 페이지 유지
    tap(t + 260, w * 0.68f);
    assertEquals(1, reader.page);
    assertNotNull(host.overlay);
    host.toggleReaderMenu();
    // 90% 위치는 오른쪽 영역 → 이전 페이지(0쪽)
    tap(t + 320, w * 0.90f);
    assertEquals(0, reader.page);

    // 5. 뒤로가기 시 터치 범위 오버레이만 닫히고 리더는 유지된다.
    host.openTouchZoneEditor();
    assertNotNull(host.touchZoneOverlay);
    host.onBackPressed();
    assertNull(host.touchZoneOverlay);
    assertSame(reader, host.reader);
  }
}
