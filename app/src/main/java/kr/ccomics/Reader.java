package kr.ccomics;

import android.animation.*;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;
import java.util.*;
import java.util.concurrent.*;

final class Reader extends View {
  final Main host;
  final Archive archive;
  final Library.Book book;
  final ExecutorService io = Executors.newSingleThreadExecutor();
  final LruCache<Integer, Bitmap> images =
      new LruCache<Integer, Bitmap>(
          (int) Math.min(72L << 20, Runtime.getRuntime().maxMemory() / 4)) {
        protected int sizeOf(Integer key, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  final Set<Integer> loading = new HashSet<>();
  final Map<Integer, String> errors = new HashMap<>();
  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  final GestureDetector immediateTaps, centerDoubleTaps;
  GestureDetector activeTaps;
  boolean doubleZoom, pinchZoom, tapTurn, swipeTurn, brightnessGesture;
  static final float TAP_MIN_EDGE = 0.05f;
  static final float TAP_MIN_CENTER = 0.08f;
  boolean brightnessDrag, skipTouch, tapStartedTurn;
  float tapLeft = 0.33f, tapRight = 0.67f, initialBrightness;
  final float previousWindowBrightness;
  final ScaleGestureDetector pinch;
  final OverScroller fling;
  int page, target = -1, turn, side, mode, effect, tapEffect = 2, fit;
  boolean rtl, solo, closed, drag, pinched;
  float downX, downY, lastY, progress, tilt, zoom = 1, panX, panY, scrollY;
  ValueAnimator animator;
  Bitmap front, back;
  boolean leavesReady, waitingToCommit;
  final PageWindow<Bitmap> window = new PageWindow<>();
  final BitSet deferredLandscape = new BitSet();
  BitSet landscape = new BitSet();
  int checked;
  boolean checking;
  final String geometryKey;
  long touchStart;
  float peakTravel;

  void inspectGeometry() {
    if (closed || checking || target >= 0 || !dual() || checked >= count() || checked > page + 7)
      return;
    checking = true;
    final int start = checked, end = Math.min(count(), start + 8);
    io.execute(
        () -> {
          BitSet found = new BitSet();
          for (int i = start; i < end; i++)
            try {
              if (archive.landscape(i)) found.set(i);
            } catch (Exception ignored) {
            }
          post(
              () -> {
                if (closed) return;
                checking = false;
                checked = end;
                if (target >= 0) deferredLandscape.or(found);
                else landscape.or(found);
                BitSet known = (BitSet) landscape.clone();
                known.or(deferredLandscape);
                host.prefs
                    .edit()
                    .putInt(geometryKey + "-n", checked)
                    .putString(
                        geometryKey, android.util.Base64.encodeToString(known.toByteArray(), 2))
                    .apply();
                if (!found.isEmpty() && target < 0) {
                  host.readerStatus();
                }
                invalidate();
              });
        });
  }

  int paper = 0xff161817;

  Reader(Main host, Archive a, Library.Book b) {
    super(host);
    this.host = host;
    archive = a;
    book = b;
    page = Math.min(b.page, count() - 1);
    readSettings();
    geometryKey = "geometry-" + b.id + "-" + b.changed + "-" + b.size;
    checked = host.prefs.getInt(geometryKey + "-n", 0);
    landscape =
        BitSet.valueOf(android.util.Base64.decode(host.prefs.getString(geometryKey, ""), 2));
    setLayerType(View.LAYER_TYPE_HARDWARE, null);
    previousWindowBrightness = host.getWindow().getAttributes().screenBrightness;
    fling = new OverScroller(host);
    GestureDetector.SimpleOnGestureListener taps =
        new GestureDetector.SimpleOnGestureListener() {
          public boolean onDown(MotionEvent e) {
            return true;
          }

          public boolean onSingleTapUp(MotionEvent e) {
            return activeTaps == immediateTaps && handleTap(e);
          }

          public boolean onSingleTapConfirmed(MotionEvent e) {
            return !closed && doubleZoom && handleTap(e);
          }

          public boolean onDoubleTap(MotionEvent e) {
            if (closed || !doubleZoom || pinched) return false;
            scaleAt(zoom > 1 ? 1 : 2.25f, e.getX(), e.getY());
            return true;
          }

          public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
            if (mode == 3 && zoom == 1 && !pinched) {
              fling.fling(0, 0, 0, (int) -vy, 0, 0, -100000, 100000);
              lastFling = 0;
              postInvalidateOnAnimation();
            }
            return true;
          }
        };
    immediateTaps = new GestureDetector(host, taps);
    immediateTaps.setOnDoubleTapListener(null);
    centerDoubleTaps = new GestureDetector(host, taps);
    activeTaps = immediateTaps;
    pinch =
        new ScaleGestureDetector(
            host,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScaleBegin(ScaleGestureDetector d) {
                pinched = true;
                cancelTurn();
                return true;
              }

              public boolean onScale(ScaleGestureDetector d) {
                scaleAt(
                    Math.max(1, Math.min(5, zoom * d.getScaleFactor())),
                    d.getFocusX(),
                    d.getFocusY());
                return true;
              }
            });
    pinch.setQuickScaleEnabled(false);
    pinch.setStylusScaleEnabled(false);
  }

  static float[] clampTapZones(float left, float right) {
    float l = Math.max(TAP_MIN_EDGE, Math.min(1f - TAP_MIN_EDGE - TAP_MIN_CENTER, left));
    float r = Math.max(TAP_MIN_EDGE + TAP_MIN_CENTER, Math.min(1f - TAP_MIN_EDGE, right));
    if (r - l < TAP_MIN_CENTER) {
      float mid = (l + r) * 0.5f;
      l = Math.max(TAP_MIN_EDGE, Math.min(1f - TAP_MIN_EDGE - TAP_MIN_CENTER, mid - TAP_MIN_CENTER * 0.5f));
      r = l + TAP_MIN_CENTER;
    }
    return new float[] {l, r};
  }

  boolean inLeftZone(float x) {
    return x < getWidth() * tapLeft;
  }

  boolean inRightZone(float x) {
    return x > getWidth() * tapRight;
  }

  boolean center(float x) {
    return !inLeftZone(x) && !inRightZone(x);
  }

  boolean handleTap(MotionEvent e) {
    if (closed || pinched || drag || brightnessDrag) return false;
    if (mode == 3 || center(e.getX()) || !tapTurn) host.toggleReaderMenu();
    else animateTurn(inLeftZone(e.getX()) == rtl ? 1 : -1);
    return true;
  }

  void readGestures() {
    doubleZoom = host.prefs.getBoolean("doubleZoom", false);
    pinchZoom = host.prefs.getBoolean("pinchZoom", true);
    tapTurn = host.prefs.getBoolean("tapTurn", true);
    swipeTurn = host.prefs.getBoolean("swipeTurn", true);
    tapEffect = host.prefs.getInt("tapEffect", 2);
    brightnessGesture = host.prefs.getBoolean("brightnessGesture", false);
    int legacyIdx = Math.max(0, Math.min(2, host.prefs.getInt("tapWidth", 1)));
    float fallbackEdge = new float[] {.25f, .33f, .4f}[legacyIdx];
    float rawLeft = host.prefs.contains("tapLeft") ? host.prefs.getFloat("tapLeft", fallbackEdge) : fallbackEdge;
    float rawRight =
        host.prefs.contains("tapRight") ? host.prefs.getFloat("tapRight", 1f - fallbackEdge) : (1f - fallbackEdge);
    float[] clamped = clampTapZones(rawLeft, rawRight);
    tapLeft = clamped[0];
    tapRight = clamped[1];
    if (!pinchZoom && !doubleZoom && zoom > 1) {
      resetZoom();
      invalidate();
    }
  }

  String firstPageKey() {
    return "solo-"
        + android.util.Base64.encodeToString(
            book.grp.getBytes(java.nio.charset.StandardCharsets.UTF_8), 2);
  }

  String directionKey() {
    return "rtl-" + firstPageKey().substring(5);
  }

  void saveDirection(boolean rightToLeft, boolean seriesOnly) {
    android.content.SharedPreferences.Editor edit = host.prefs.edit();
    if (seriesOnly) edit.putBoolean(directionKey(), rightToLeft);
    else edit.remove(directionKey()).putBoolean("rtl", rightToLeft);
    edit.apply();
    optionsChanged();
  }

  void storeScroll() {
    if (mode == 3 && getWidth() > 0)
      host.prefs
          .edit()
          .putFloat("scroll-" + book.id, scrollY / getWidth())
          .putInt("scrollPage-" + book.id, page)
          .apply();
  }

  void readSettings() {
    readGestures();
    mode = host.prefs.getInt("mode", 0);
    rtl = host.prefs.getBoolean(directionKey(), host.prefs.getBoolean("rtl", true));
    solo = host.prefs.getBoolean(firstPageKey(), false);
    effect = host.prefs.getInt("effect", 0);
    fit = host.prefs.getInt("fit", 0);
    paper = new int[] {0xff161817, 0xfff5f3eb, 0xffffffff}[host.prefs.getInt("paper", 0)];
  }

  int count() {
    return archive.pages.size();
  }

  boolean dual() {
    return mode == 2 || mode == 0 && getWidth() / getResources().getDisplayMetrics().density >= 600;
  }

  int[] spread(int at) {
    return Order.spreadWithWidePages(at, count(), dual(), solo, landscape);
  }

  int next(int dir) {
    int[] s = spread(page);
    return dir > 0 ? s[s.length - 1] + 1 : s[0] - 1;
  }

  void optionsChanged() {
    cancelTurn();
    readSettings();
    if (mode == 3) window.clear();
    else images.evictAll();
    resetZoom();
    scrollY = 0;
    invalidate();
    host.readerStatus();
  }

  float fitPanX, fitPanY;

  void resetZoom() {
    zoom = 1;
    panX = panY = 0;
    fitPanX = fitPanY = 0;
  }

  void seek(int p) {
    cancelTurn();
    page = Math.max(0, Math.min(count() - 1, p));
    scrollY = 0;
    resetZoom();
    save();
    invalidate();
  }

  void save() {
    book.page = page;
    book.opened = System.currentTimeMillis();
    int[] s = spread(page);
    book.done |= s[s.length - 1] == count() - 1 && count() > 0;
    host.db.exec(
        "UPDATE books SET page=?,opened=?,done=? WHERE id=?",
        page,
        book.opened,
        book.done ? 1 : 0,
        book.id);
    host.readerStatus();
  }

  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    cancelTurn();
    resetZoom();
    if (mode == 3) {
      if (oldW > 0) scrollY = scrollY * w / oldW;
      else if (host.prefs.getInt("scrollPage-" + book.id, -1) == page)
        scrollY = host.prefs.getFloat("scroll-" + book.id, 0) * w;
    }
    post(
        () -> {
          if (!closed) host.readerStatus();
        });
  }

  void request(int i) {
    if (i < 0
        || i >= count()
        || closed
        || (mode == 3 ? images.get(i) != null : window.has(i))
        || errors.containsKey(i)
        || !loading.add(i)) return;
    final int w = Math.max(1000, getWidth()), h = Math.max(1400, getHeight());
    io.execute(
        () -> {
          try {
            Bitmap b = archive.decode(i, w, h);
            post(() -> pageDecoded(i, b));
          } catch (Exception | OutOfMemoryError ex) {
            post(
                () -> {
                  loading.remove(i);
                  if (closed) return;
                  if (mode != 3) window.loaded(i, null);
                  errors.put(i, ex.getMessage() == null ? "이미지 읽기 실패" : ex.getMessage());
                  resumeTurn();
                  invalidate();
                });
          }
        });
  }

  void pageDecoded(int i, Bitmap b) {
    loading.remove(i);
    if (closed) return;
    if (mode == 3) images.put(i, b);
    else window.loaded(i, b);
    resumeTurn();
    invalidate();
  }

  void drawPage(Canvas c, int i, float l, float r, int align) {
    float w = r - l, h = getHeight();
    Bitmap b = window.image(i);
    if (b == null) {
      paint.setColor(0xffc4c7c4);
      paint.setTextSize(host.dp(14));
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText("읽기 오류 · " + errors.get(i), (l + r) / 2, h / 2, paint);
      return;
    }
    float scale =
        fit == 1
            ? w / b.getWidth()
            : fit == 2 ? h / b.getHeight() : Math.min(w / b.getWidth(), h / b.getHeight());
    float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
    float left = align < 0 ? r - bw : align > 0 ? l : l + (w - bw) / 2, top = (h - bh) / 2;
    if (bw > w) left = l + (w - bw) / 2 + fitPanX * (bw - w) / 2;
    if (bh > h) top += fitPanY * (bh - h) / 2;
    c.save();
    c.clipRect(l, 0, r, h);
    paint.setColor(-1);
    c.drawBitmap(b, null, new RectF(left, top, left + bw, top + bh), paint);
    c.restore();
  }

  int[] renderSpread(int at) {
    return at == page ? window.visible() : spread(at);
  }

  void sheet(Canvas c, int at) {
    paint.setColor(paper);
    c.drawRect(0, 0, getWidth(), getHeight(), paint);
    int[] s = renderSpread(at);
    if (s.length == 0) {
      paint.setColor(0xffc4c7c4);
      paint.setTextSize(host.dp(14));
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText("책을 여는 중…", getWidth() / 2f, getHeight() / 2f, paint);
      return;
    }
    int w = getWidth();
    if (s.length == 1) {
      if (dual() && !(solo && s[0] == 0) && !landscape.get(s[0])) {
        drawPage(c, s[0], rtl ? w / 2f : 0, rtl ? w : w / 2f, rtl ? 1 : -1);
      } else drawPage(c, s[0], 0, w, 0);
      return;
    }
    drawPage(c, s[rtl ? 1 : 0], 0, w / 2f, -1);
    drawPage(c, s[rtl ? 0 : 1], w / 2f, w, 1);
  }

  protected void onDraw(Canvas c) {
    if (closed) return;
    if (mode != 3 && target < 0) preparePages();
    c.save();
    c.translate(panX, panY);
    c.scale(zoom, zoom, getWidth() / 2f, getHeight() / 2f);
    if (mode == 3) continuous(c);
    else if (target < 0 || progress <= 0 || activeEffect() == 2 || !turnReady()) sheet(c, page);
    else if (activeEffect() == 1) {
      float dx = -side * progress * getWidth();
      c.save();
      c.translate(dx, 0);
      sheet(c, page);
      c.restore();
      c.save();
      c.translate(dx + side * getWidth(), 0);
      sheet(c, target);
      c.restore();
    } else if (turn < 0) curl(c, target, page, -side, 1 - progress);
    else curl(c, page, target, side, progress);
    c.restore();
    if (mode != 3 && target < 0) inspectGeometry();
  }

  void preparePages() {
    int[] current = spread(page);
    int after = current[current.length - 1] + 1, before = current[0] - 1;
    int[] next = after < count() ? spread(after) : PageWindow.NONE;
    int[] previous = before >= 0 ? spread(before) : PageWindow.NONE;
    window.plan(current, next, previous);
    for (int[] group : new int[][] {current, next, previous})
      for (int i : group) {
        if (errors.containsKey(i)) window.loaded(i, null);
        else request(i);
      }
    window.present(current);
  }

  void clearLeaves() {
    leavesReady = false;
  }

  void leaves(boolean half, int from, int to, int side) {
    if (leavesReady) return;
    int w = getWidth(), h = getHeight(), leaf = half ? (w + 1) / 2 : w;
    float ratio = Math.min(1f, Math.min(1600f / h, 1400f / leaf));
    int bw = Math.max(1, (int) (leaf * ratio)), bh = Math.max(1, (int) (h * ratio));
    if (front == null || front.getWidth() != bw || front.getHeight() != bh) {
      front = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
      back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
    }
    Canvas f = new Canvas(front);
    f.scale(bw / (float) leaf, bh / (float) h);
    if (half && side > 0) f.translate(-(w - leaf), 0);
    sheet(f, from);
    Canvas b = new Canvas(back);
    b.scale(bw / (float) leaf, bh / (float) h);
    b.translate(leaf, 0);
    b.scale(-1, 1);
    if (half) {
      if (side < 0) b.translate(-(w - leaf), 0);
      sheet(b, to);
    } else {
      b.drawColor(paper);
      paint.setColor(-1);
      paint.setAlpha(180);
      b.drawBitmap(front, null, new RectF(0, 0, leaf, h), paint);
      paint.setAlpha(255);
    }
    leavesReady = true;
  }

  // Each strip lies flat, follows the circular bend, then returns along the reverse face.
  void curl(Canvas c, int from, int to, int side, float progress) {
    final int w = getWidth(), h = getHeight();
    boolean half = dual() && spread(from).length == 2 && spread(to).length == 2;
    float leaf = half ? w / 2f : w, spine = half ? w / 2f : (side > 0 ? 0 : w);
    sheet(c, to);
    if (half) {
      c.save();
      if (side > 0) c.clipRect(0, 0, spine, h);
      else c.clipRect(spine, 0, w, h);
      sheet(c, from);
      c.restore();
    }
    leaves(half, from, to, side);
    float amount = (float) Math.sin(progress * Math.PI);
    float radius = Math.max(.1f, leaf * .105f * amount);
    float distance = leaf * 2 * progress;
    float low = 0, high = leaf + radius * 2;
    for (int k = 0; k < 15; k++) {
      float d = (low + high) / 2, arc = (float) Math.PI * radius;
      float displacement =
          d - radius * (float) Math.sin(Math.min(Math.PI, d / radius)) + Math.max(0, d - arc);
      if (displacement < distance) low = d;
      else high = d;
    }
    float pivot = leaf - (low + high) / 2;
    float slant = tilt * amount * .22f * leaf;
    final int cols = 64, rows = 8;
    int vertices = (cols + 1) * (rows + 1);
    if (frontMesh == null) {
      frontMesh = new float[vertices * 2];
      backMesh = new float[vertices * 2];
      frontLight = new int[vertices];
      backLight = new int[vertices];
    }
    float halfArc = (float) Math.PI * radius / 2;
    for (int row = 0; row <= rows; row++) {
      float v = row / (float) rows, hinge = pivot + slant * (v - touchAnchor);
      for (int col = 0; col <= cols; col++) {
        float u = col / (float) cols, material = (side > 0 ? u : 1 - u) * leaf;
        float bend = material - hinge;
        int i = row * (cols + 1) + col;
        project(
            frontMesh,
            frontLight,
            i,
            Math.min(bend, halfArc),
            hinge,
            radius,
            spine,
            side,
            v * h,
            slant);
        project(
            backMesh,
            backLight,
            i,
            Math.max(bend, halfArc),
            hinge,
            radius,
            spine,
            side,
            v * h,
            slant);
      }
    }
    float ridge = spine + side * (pivot + radius), extent = Math.max(1, radius * .75f);
    paint.setColor(-1);
    paint.setShader(
        new LinearGradient(
            ridge, 0, ridge + side * extent, 0, 0x58000000, 0, Shader.TileMode.CLAMP));
    c.save();
    Matrix shadow = new Matrix();
    shadow.setSkew(side * slant / h, 0, ridge, touchAnchor * h);
    c.concat(shadow);
    c.drawRect(
        Math.min(ridge, ridge + side * extent),
        0,
        Math.max(ridge, ridge + side * extent),
        h,
        paint);
    c.restore();
    paint.setShader(null);
    c.drawBitmapMesh(front, cols, rows, frontMesh, 0, frontLight, 0, paint);
    c.drawBitmapMesh(back, cols, rows, backMesh, 0, backLight, 0, paint);
  }

  float[] frontMesh, backMesh;
  int[] frontLight, backLight;
  float touchAnchor = .5f;

  void project(
      float[] mesh,
      int[] light,
      int i,
      float d,
      float hinge,
      float radius,
      float spine,
      int sign,
      float y,
      float slant) {
    float theta = Math.max(0, Math.min((float) Math.PI, d / radius));
    float x =
        d < 0
            ? hinge + d
            : hinge + radius * (float) Math.sin(theta) - Math.max(0, d - (float) Math.PI * radius);
    mesh[2 * i] = spine + sign * x;
    mesh[2 * i + 1] = y + slant * .08f * (1 - (float) Math.cos(theta));
    int shade = 255 - (int) (70 * Math.sin(theta));
    light[i] = Color.rgb(shade, shade, shade);
  }

  int lastFling;

  float itemHeight(int i) {
    Bitmap b = images.get(i);
    return b == null
        ? getWidth() * 1.45f
        : Math.max(1, getWidth() * b.getHeight() / (float) b.getWidth());
  }

  void advanceScroll(float dy) {
    scrollY += dy;
    while (scrollY >= itemHeight(page) && page < count() - 1) {
      scrollY -= itemHeight(page);
      page++;
      save();
    }
    while (scrollY < 0 && page > 0) {
      page--;
      scrollY += itemHeight(page);
      save();
    }
    scrollY =
        Math.max(
            0,
            Math.min(
                scrollY,
                page == count() - 1
                    ? Math.max(0, itemHeight(page) - getHeight())
                    : itemHeight(page)));
    invalidate();
  }

  void continuous(Canvas c) {
    c.drawColor(paper);
    float top = -scrollY;
    for (int i = page; i < count() && top < getHeight(); i++) {
      request(i);
      Bitmap b = images.get(i);
      float h = itemHeight(i);
      if (b != null) {
        paint.setColor(-1);
        c.drawBitmap(b, null, new RectF(0, top, getWidth(), top + h), paint);
      } else {
        paint.setColor(-1);
        paint.setTextSize(host.dp(14));
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(
            errors.containsKey(i) ? "이미지를 읽을 수 없습니다" : "페이지 여는 중…",
            getWidth() / 2f,
            top + Math.min(h / 2, getHeight() / 2f),
            paint);
      }
      top += h;
    }
    request(page - 1);
    request(page + 1);
  }

  public void computeScroll() {
    if (fling.computeScrollOffset()) {
      int y = fling.getCurrY();
      advanceScroll(y - lastFling);
      lastFling = y;
      postInvalidateOnAnimation();
    }
  }

  void scaleAt(float newZoom, float x, float y) {
    float ratio = newZoom / zoom;
    panX = (x - getWidth() / 2f) * (1 - ratio) + panX * ratio;
    panY = (y - getHeight() / 2f) * (1 - ratio) + panY * ratio;
    zoom = newZoom;
    clampPan();
    invalidate();
  }

  void clampPan() {
    float mx = getWidth() * (zoom - 1) / 2, my = getHeight() * (zoom - 1) / 2;
    panX = Math.max(-mx, Math.min(mx, panX));
    panY = Math.max(-my, Math.min(my, panY));
  }

  boolean panFit(float dx, float dy) {
    if (fit == 0 || drag) return false;
    int[] group = spread(page);
    float w = getWidth() / (float) group.length, h = getHeight();
    if (dual() && group.length == 1 && !(solo && page == 0) && !landscape.get(page)) w /= 2;
    float overflow = 0;
    for (int i : group) {
      Bitmap b = window.image(i);
      if (b == null) continue;
      float aspect = b.getWidth() / (float) b.getHeight();
      overflow = Math.max(overflow, fit == 1 ? w / aspect - h : h * aspect - w);
    }
    if (overflow <= 1 || (fit == 1 && Math.abs(dy) < Math.abs(dx))) return false;
    if (fit == 1) fitPanY = Math.max(-1, Math.min(1, fitPanY + dy * 2 / overflow));
    else fitPanX = Math.max(-1, Math.min(1, fitPanX + dx * 2 / overflow));
    invalidate();
    return true;
  }

  float lastX;

  public boolean onTouchEvent(MotionEvent e) {
    if (closed) return false;
    int action = e.getActionMasked();
    float x = e.getX(), y = e.getY();
    if (action == MotionEvent.ACTION_DOWN) {
      if (animator != null) animator.end();
      skipTouch = waitingToCommit;
      brightnessDrag = false;
      activeTaps = doubleZoom && center(x) ? centerDoubleTaps : immediateTaps;
      fling.abortAnimation();
      pinched = false;
      drag = false;
      downX = lastX = x;
      downY = lastY = y;
      touchStart = android.os.SystemClock.uptimeMillis();
      peakTravel = 0;
      touchAnchor = Math.max(0, Math.min(1, y / getHeight()));
    }
    if (skipTouch) return true;
    if (action == MotionEvent.ACTION_POINTER_DOWN) {
      pinched = true;
      brightnessDrag = false;
      cancelTurn();
    }
    if (pinchZoom) pinch.onTouchEvent(e);
    activeTaps.onTouchEvent(e);
    if (action == MotionEvent.ACTION_MOVE && !pinched && !pinch.isInProgress()) {
      float dx = x - downX;
      float dy = y - downY;
      if (!brightnessDrag
          && brightnessGesture
          && !drag
          && mode != 3
          && zoom == 1
          && downX < getWidth() * .18f
          && Math.abs(dy) > host.dp(12)
          && Math.abs(dy) > Math.abs(dx) * 1.8f) {
        brightnessDrag = true;
        initialBrightness = host.getWindow().getAttributes().screenBrightness;
        if (initialBrightness < 0)
          initialBrightness =
              android.provider.Settings.System.getInt(
                      host.getContentResolver(),
                      android.provider.Settings.System.SCREEN_BRIGHTNESS,
                      128)
                  / 255f;
      }
      if (brightnessDrag) {
        WindowManager.LayoutParams attrs = host.getWindow().getAttributes();
        attrs.screenBrightness = Math.max(.03f, Math.min(1, initialBrightness - dy / getHeight()));
        host.getWindow().setAttributes(attrs);
      } else if (zoom > 1) {
        panX += x - lastX;
        panY += y - lastY;
        clampPan();
        invalidate();
      } else if (mode == 3) {
        advanceScroll(lastY - y);
      } else if (!panFit(x - lastX, y - lastY)
          && swipeTurn
          && (drag
              || Math.abs(dx) > ViewConfiguration.get(host).getScaledTouchSlop()
                  && Math.abs(dx) > Math.abs(dy))) {
        int dir = (rtl ? dx : -dx) > 0 ? 1 : -1;
        if (!drag) {
          drag = true;
          startTurn(dir);
        }
        if (target >= 0) {
          boolean half = dual() && spread(page).length == 2 && spread(target).length == 2;
          progress = Math.max(0, Math.min(1, (-side * dx) / (getWidth() * (half ? 1 : 2))));
          peakTravel = Math.max(peakTravel, progress);
          tilt =
              Math.max(
                  -1, Math.min(1, 2 * (downY / getHeight() - .5f) + (y - downY) / getHeight()));
          invalidate();
        }
      }
    }
    if (action == MotionEvent.ACTION_UP
        && drag
        && target < 0
        && !pinched
        && next(1) >= count()
        && (rtl ? x - downX : downX - x) > getWidth() * .15f) host.nextVolume();
    if (action == MotionEvent.ACTION_UP && drag && target >= 0) {
      boolean flick = progress > .045 && android.os.SystemClock.uptimeMillis() - touchStart < 330;
      boolean commit = peakTravel - progress < .055 && (progress > .17 || flick);
      settle(commit);
    }
    if (action == MotionEvent.ACTION_CANCEL) cancelTurn();
    lastX = x;
    lastY = y;
    return true;
  }

  void startTurn(int dir) {
    preparePages();
    int p = next(dir);
    if (p < 0 || p >= count()) return;
    target = p;
    tapStartedTurn = false;
    turn = dir;
    side = (dir > 0) == rtl ? -1 : 1;
    progress = 0;
    clearLeaves();
  }

  void animateTurn(int dir) {
    if (target >= 0) return;
    if (next(dir) >= count()) {
      host.nextVolume();
      return;
    }
    startTurn(dir);
    if (target >= 0) {
      tapStartedTurn = true;
      tilt = .42f;
      settle(true);
    }
  }

  boolean turnReady() {
    return target >= 0 && window.ready(spread(page)) && window.ready(spread(target));
  }

  void resumeTurn() {
    if (target >= 0 && window.ready(spread(page))) window.present(spread(page));
    if (waitingToCommit && turnReady()) {
      waitingToCommit = false;
      settle(true);
    }
  }

  int activeEffect() {
    return tapStartedTurn ? tapEffect : effect;
  }

  void settle(boolean commit) {
    if (!turnReady()) {
      if (!commit) {
        finish(false);
        return;
      }
      waitingToCommit = true;
      progress = 0;
      invalidate();
      return;
    }
    if (activeEffect() == 2) {
      finish(commit);
      return;
    }
    animator = ValueAnimator.ofFloat(progress, commit ? 1 : 0);
    animator.setDuration(
        Math.max(90, (int) ((tapStartedTurn ? 145 : 260) * Math.abs((commit ? 1 : 0) - progress))));
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(
        a -> {
          progress = (float) a.getAnimatedValue();
          invalidate();
        });
    animator.addListener(
        new AnimatorListenerAdapter() {
          public void onAnimationEnd(Animator a) {
            finish(commit);
          }
        });
    animator.start();
  }

  void finish(boolean commit) {
    if (commit && target >= 0) {
      window.present(spread(target));
      page = spread(target)[0];
      save();
    }
    target = -1;
    waitingToCommit = false;
    if (!deferredLandscape.isEmpty()) {
      landscape.or(deferredLandscape);
      deferredLandscape.clear();
      host.readerStatus();
    }
    progress = 0;
    animator = null;
    clearLeaves();
    invalidate();
  }

  void cancelTurn() {
    if (animator != null) {
      animator.removeAllListeners();
      animator.cancel();
      animator = null;
    }
    target = -1;
    waitingToCommit = false;
    if (!deferredLandscape.isEmpty()) {
      landscape.or(deferredLandscape);
      deferredLandscape.clear();
      host.readerStatus();
    }
    progress = 0;
    clearLeaves();
    invalidate();
  }

  void close() {
    WindowManager.LayoutParams attrs = host.getWindow().getAttributes();
    if (attrs.screenBrightness != previousWindowBrightness) {
      attrs.screenBrightness = previousWindowBrightness;
      host.getWindow().setAttributes(attrs);
    }
    storeScroll();
    closed = true;
    cancelTurn();
    fling.abortAnimation();
    io.execute(
        () -> {
          try {
            archive.close();
          } catch (Exception ignored) {
          }
        });
    io.shutdown();
    images.evictAll();
    window.clear();
    front = null;
    back = null;
  }
}
