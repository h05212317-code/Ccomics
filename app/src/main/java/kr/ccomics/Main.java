package kr.ccomics;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class Main extends Activity {
  static final int THUMB_MIN_DP = 76, THUMB_MAX_DP = 220, THUMB_DEFAULT_DP = 133;
  static final int SORT_TITLE = 0, SORT_RECENT = 1, SORT_ADDED = 2, SORT_CUSTOM = 3;
  static final String[] SORT_NAMES = {"제목순", "최근 읽은순", "최근 추가순", "사용자 정의"};
  AppUpdates updates;
  Library db;
  SharedPreferences prefs;
  final ExecutorService worker = Executors.newSingleThreadExecutor(),
      readerIO = Executors.newSingleThreadExecutor(),
      thumbs = Executors.newFixedThreadPool(4);
  final LruCache<String, Bitmap> coverCache =
      new LruCache<String, Bitmap>(
          Math.min(48 << 20, Math.max(16 << 20, (int) (Runtime.getRuntime().maxMemory() / 8)))) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  final Handler handler = new Handler(Looper.getMainLooper());
  FrameLayout frame;
  LinearLayout root;
  TextView scanBanner;
  GridView grid;
  ListView shelfList;
  StarSectionAdapter sectionAdapter;
  Reader reader;
  View overlay, topBar;
  TextView indicator;
  SeekBar seek;
  List<Library.Book> books = new ArrayList<>();
  Map<String, List<Library.Book>> groups = new LinkedHashMap<>();
  Map<String, SeriesEntry> entries = new LinkedHashMap<>();
  boolean entriesHideTags = true;
  final SpannableString[] starSpans = new SpannableString[6];
  boolean starSpansDark;
  boolean starSpansReady;
  Runnable pendingThumbResize;
  ShelfDragController dragController;
  TouchZoneOverlay touchZoneOverlay;
  String selected = "", shelf = "", query = "", scanMessage = "";
  int filter, requestId, gridIndex, gridOffset, volumeIndex, volumeOffset;
  boolean busy, loadingBook, dark, readerImmersive, arrangeMode, resumeDismissed, floatingResumeVisible;
  int bg, ink, muted, surface, accent;

  static final class SeriesEntry {
    final String grp;
    final List<Library.Book> books;
    final Library.Book rep;
    final String displayTitle;
    final String displayTitleLower;
    final String searchBlob;
    final String subtitleWithStar;
    final String subtitleNoStar;
    final int rating;
    int customOrder;
    final long maxOpened;
    final long maxId;
    final boolean hasFav, hasReading, hasDone;
    final Set<String> shelves;

    SeriesEntry(String grp, List<Library.Book> books, boolean hideTags) {
      this.grp = grp;
      this.books = books;
      Library.Book first = books.get(0);
      Library.Book foundRep = first;
      int maxRating = 0, maxCustom = 0;
      long opened = 0, added = 0;
      boolean fav = false, reading = false, done = false;
      Set<String> sh = null;
      StringBuilder blob = new StringBuilder();
      for (int i = 0; i < books.size(); i++) {
        Library.Book b = books.get(i);
        if (b.rep) foundRep = b;
        if (b.rating > maxRating) maxRating = b.rating;
        if (b.customOrder > maxCustom) maxCustom = b.customOrder;
        if (b.opened > opened) opened = b.opened;
        if (b.id > added) added = b.id;
        if (b.fav) fav = true;
        if (b.opened > 0 && !b.done) reading = true;
        if (b.done) done = true;
        if (!b.shelf.isEmpty()) {
          if (sh == null) sh = new HashSet<>(2);
          sh.add(b.shelf);
        }
        if (i < 4 || books.size() <= 12) {
          if (!b.series.isEmpty()) blob.append(b.series).append('\n');
          if (!b.title.isEmpty()) blob.append(b.title).append('\n');
          if (!b.comicSeries.isEmpty()) blob.append(b.comicSeries).append('\n');
        }
      }
      this.rep = foundRep;
      this.displayTitle = Order.bestSeriesTitle(books, hideTags);
      this.displayTitleLower = this.displayTitle.toLowerCase(Locale.ROOT);
      blob.append(this.displayTitle);
      this.searchBlob = blob.toString().toLowerCase(Locale.ROOT);
      this.rating = Math.max(0, Math.min(5, maxRating));
      this.customOrder = maxCustom;
      this.maxOpened = opened;
      this.maxId = added;
      this.hasFav = fav;
      this.hasReading = reading;
      this.hasDone = done;
      this.shelves = sh == null ? Collections.emptySet() : sh;
      String base = books.size() + "권";
      String favSuffix = fav ? "  ·  즐겨찾기" : "";
      this.subtitleNoStar = base + favSuffix;
      this.subtitleWithStar =
          this.rating > 0 ? base + "  ·  ★" + this.rating + favSuffix : this.subtitleNoStar;
    }

    boolean matches(String shelfFilter, int tabFilter, String qLower) {
      if (!shelfFilter.isEmpty() && !shelves.contains(shelfFilter)) return false;
      if (tabFilter == 1 && !hasReading) return false;
      if (tabFilter == 2 && !hasDone) return false;
      if (tabFilter == 3 && !hasFav) return false;
      if (qLower.isEmpty()) return true;
      if (displayTitleLower.contains(qLower) || searchBlob.contains(qLower)) return true;
      for (int i = 0; i < books.size(); i++) {
        Library.Book b = books.get(i);
        if (b.title.toLowerCase(Locale.ROOT).contains(qLower)
            || b.series.toLowerCase(Locale.ROOT).contains(qLower)
            || b.comicSeries.toLowerCase(Locale.ROOT).contains(qLower)) return true;
      }
      return false;
    }
  }

  void configureEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    if (Build.VERSION.SDK_INT >= 29) {
      getWindow().setStatusBarContrastEnforced(false);
      getWindow().setNavigationBarContrastEnforced(false);
    }
    WindowManager.LayoutParams lp = getWindow().getAttributes();
    lp.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    getWindow().setAttributes(lp);
  }

  void ensureSortDefaults() {
    if (prefs == null) return;
    int ver = prefs.getInt("sortDefaultVersion", 0);
    if (ver < 2) {
      SharedPreferences.Editor ed = prefs.edit();
      if (!prefs.contains("groupByRating")) ed.putBoolean("groupByRating", true);
      int prevSort = prefs.getInt("sort", SORT_TITLE);
      if (ver == 0 || prevSort == 3) {
        // v1.0.15~16에서 3은 별점순이었으므로 별점순 표시하기(ON) + 제목순 ↑로 자연 전환
        ed.putInt("sort", SORT_TITLE).putBoolean("sortDesc", false);
      }
      ed.putInt("sortDefaultVersion", 2).apply();
    }
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    configureEdgeToEdge();
    db = new Library(getApplicationContext());
    prefs = getSharedPreferences("options", 0);
    ensureSortDefaults();
    updates = new AppUpdates(this);
    if (state != null) {
      selected = state.getString("selected", "");
      shelf = state.getString("shelf", "");
      query = state.getString("query", "");
      filter = state.getInt("filter");
      gridIndex = state.getInt("grid");
      gridOffset = state.getInt("offset");
      volumeIndex = state.getInt("volumeIndex");
      volumeOffset = state.getInt("volumeOffset");
      resumeDismissed = state.getBoolean("resumeDismissed");
    }
    frame = new FrameLayout(this);
    setContentView(frame);
    library(false);
    if (prefs.getInt("renormRules", 0) < Library.RENORM_VERSION) {
      worker.execute(
          () -> {
            boolean changed = db.renormalizeBooks();
            prefs.edit().putInt("renormRules", Library.RENORM_VERSION).apply();
            if (changed) runOnUiThread(() -> library(true));
          });
    }
    if (state == null) updates.check(false);
    if (state == null && prefs.getInt("rulesScan", 0) < Library.RULES
        && !db.strings("SELECT id FROM sources").isEmpty()) {
      // 업데이트로 묶음 인식 규칙이 바뀌면 한 번 자동으로 다시 읽는다.
      prefs.edit().putInt("rulesScan", Library.RULES).apply();
      refreshSources(true);
    }
    if (state != null && state.getLong("reading") > 0) {
      Library.Book b = db.get(state.getLong("reading"));
      if (b != null) open(b);
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && reader != null && overlay == null) immersive();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    remember();
    b.putString("selected", selected);
    b.putString("shelf", shelf);
    b.putString("query", query);
    b.putInt("filter", filter);
    b.putInt("grid", gridIndex);
    b.putInt("offset", gridOffset);
    b.putInt("volumeIndex", volumeIndex);
    b.putInt("volumeOffset", volumeOffset);
    b.putBoolean("resumeDismissed", resumeDismissed);
    if (reader != null) b.putLong("reading", reader.book.id);
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    if (reader == null) renderNow(true);
    else
      reader.post(
          () -> {
            if (reader != null) {
              reader.invalidate();
              readerStatus();
            }
          });
  }

  int dp(float n) {
    return Math.round(getResources().getDisplayMetrics().density * n);
  }

  LinearLayout col() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(1);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  void pad(View v, int x, int y) {
    v.setPadding(dp(x), dp(y), dp(x), dp(y));
  }

  void weight(LinearLayout row, View v) {
    row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
  }

  TextView label(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setIncludeFontPadding(false);
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", 0));
    return t;
  }

  void one(TextView v) {
    v.setSingleLine();
    v.setEllipsize(TextUtils.TruncateAt.END);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  TextView action(String s, Runnable click) {
    TextView v = label(s, 14, ink, true);
    v.setGravity(17);
    pad(v, 14, 10);
    v.setMinHeight(dp(44));
    v.setOnClickListener(w -> click.run());
    v.setFocusable(true);
    return v;
  }

  void icon(LinearLayout bar, String symbol, String desc, int color, Runnable click) {
    ToolsIcon v = new ToolsIcon(this, symbol, color, desc);
    bar.addView(v, dp(42), dp(44));
    v.setOnClickListener(w -> click.run());
  }

  void alert(String title, String msg) {
    if (!isDestroyed())
      dialog().setTitle(title).setMessage(msg).setPositiveButton("확인", null).show();
  }

  AlertDialog.Builder dialog() {
    return new AlertDialog.Builder(
        this,
        dark
            ? android.R.style.Theme_Material_Dialog_Alert
            : android.R.style.Theme_Material_Light_Dialog_Alert);
  }

  void choices(String title, String[] items, IntConsumer fn) {
    dialog()
        .setTitle(title)
        .setItems(items, (d, i) -> fn.accept(i))
        .setNegativeButton("닫기", null)
        .show();
  }

  void input(String title, String value, Consumer<String> done) {
    EditText e = new EditText(this);
    e.setSingleLine();
    e.setText(value);
    e.selectAll();
    LinearLayout wrap = col();
    pad(wrap, 24, 8);
    wrap.addView(e);
    dialog()
        .setTitle(title)
        .setView(wrap)
        .setPositiveButton("적용", (d, w) -> done.accept(e.getText().toString().trim()))
        .setNegativeButton("취소", null)
        .show();
  }

  void theme() {
    int choice = prefs.getInt("theme", 0);
    dark = choice == 2 || choice == 0 && (getResources().getConfiguration().uiMode & 48) == 32;
    bg = dark ? 0xff191b1a : 0xfff8f8f5;
    ink = dark ? 0xfff2f1e9 : 0xff222822;
    muted = dark ? 0xffa5aaa4 : 0xff626b63;
    surface = dark ? 0xff292d29 : 0xffe9ece5;
    accent = dark ? 0xffa6c8af : 0xff285c48;
    frame.setBackgroundColor(bg);
    readerImmersive = false;
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    WindowInsetsControllerCompat ctrl =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    ctrl.show(WindowInsetsCompat.Type.systemBars());
    ctrl.setAppearanceLightStatusBars(!dark);
    ctrl.setAppearanceLightNavigationBars(!dark);
  }

  void remember() {
    AbsListView active = grid != null ? grid : shelfList;
    if (active != null && active.getChildCount() > 0) {
      if (Boolean.TRUE.equals(active.getTag())) {
        volumeIndex = active.getFirstVisiblePosition();
        volumeOffset = active.getChildAt(0).getTop() - active.getPaddingTop();
        return;
      }
      gridIndex = active.getFirstVisiblePosition();
      gridOffset = active.getChildAt(0).getTop() - active.getPaddingTop();
    }
  }

  boolean groupByRating() {
    ensureSortDefaults();
    return prefs.getBoolean("groupByRating", true);
  }

  int currentSort() {
    ensureSortDefaults();
    int s = prefs.getInt("sort", SORT_TITLE);
    return s >= 0 && s < SORT_NAMES.length ? s : SORT_TITLE;
  }

  boolean currentSortDesc() {
    ensureSortDefaults();
    return prefs.getBoolean("sortDesc", false);
  }

  String sortLabel(int sort, boolean desc) {
    int idx = sort >= 0 && sort < SORT_NAMES.length ? sort : SORT_TITLE;
    return SORT_NAMES[idx] + " " + (desc ? "↓" : "↑");
  }

  boolean showShelfCount() {
    return prefs.getBoolean("showShelfCount", false);
  }

  boolean showFloatingResume() {
    return prefs.getBoolean("showFloatingResume", true);
  }

  int countAccentColor() {
    return dark ? 0xffff5286 : 0xffff2e6e;
  }

  String filterLabel() {
    String[] labels = {"전체", "읽는 중", "완독", "즐겨찾기"};
    String f = labels[Math.max(0, Math.min(labels.length - 1, filter))];
    if (shelf.isEmpty()) return f;
    return filter == 0 ? shelf : shelf + " · " + f;
  }

  void showLibraryMenu() {
    AlertDialog.Builder builder = dialog();
    ScrollView scroll = new ScrollView(builder.getContext());
    LinearLayout box = col();
    pad(box, 20, 8);
    scroll.addView(box);

    int[] counts = new int[4];
    for (SeriesEntry e : entries.values()) {
      if (!shelf.isEmpty() && !e.shelves.contains(shelf)) continue;
      counts[0]++;
      if (e.hasReading) counts[1]++;
      if (e.hasDone) counts[2]++;
      if (e.hasFav) counts[3]++;
    }

    final AlertDialog[] holder = new AlertDialog[1];
    TextView filterHdr = label("보기", 12, muted, true);
    pad(filterHdr, 0, 6);
    box.addView(filterHdr);

    String[] filterNames = {"전체", "읽는 중", "완독", "즐겨찾기"};
    for (int i = 0; i < filterNames.length; i++) {
      final int idx = i;
      boolean active = filter == i;
      LinearLayout item = row();
      pad(item, 4, 11);
      item.setMinimumHeight(dp(44));
      TextView name = label((active ? "✓  " : "") + filterNames[i], 15, active ? accent : ink, active);
      weight(item, name);
      item.addView(label(String.valueOf(counts[i]), 14, active ? countAccentColor() : muted, true));
      item.setOnClickListener(
          v -> {
            filter = idx;
            selected = "";
            gridIndex = gridOffset = 0;
            if (holder[0] != null) holder[0].dismiss();
            renderNow(false);
          });
      box.addView(item, -1, -2);
    }

    View divider = new View(this);
    divider.setBackgroundColor(surface);
    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
    dlp.setMargins(0, dp(6), 0, dp(6));
    box.addView(divider, dlp);

    TextView shelfHdr = label("책장", 12, muted, true);
    pad(shelfHdr, 0, 6);
    box.addView(shelfHdr);

    List<String> shelfNames = new ArrayList<>();
    shelfNames.add("전체 책장");
    shelfNames.addAll(db.strings("SELECT name FROM shelves ORDER BY name"));
    for (int i = 0; i < shelfNames.size(); i++) {
      final int idx = i;
      final String targetShelf = i == 0 ? "" : shelfNames.get(i);
      boolean active = shelf.equals(targetShelf);
      int scount = 0;
      for (SeriesEntry e : entries.values()) {
        if (targetShelf.isEmpty() || e.shelves.contains(targetShelf)) scount++;
      }
      LinearLayout item = row();
      pad(item, 4, 11);
      item.setMinimumHeight(dp(44));
      TextView name =
          label((active ? "✓  " : "") + shelfNames.get(i), 15, active ? accent : ink, active);
      one(name);
      weight(item, name);
      item.addView(label(String.valueOf(scount), 14, active ? countAccentColor() : muted, true));
      item.setOnClickListener(
          v -> {
            shelf = targetShelf;
            selected = "";
            gridIndex = gridOffset = 0;
            if (holder[0] != null) holder[0].dismiss();
            renderNow(false);
          });
      box.addView(item, -1, -2);
    }

    TextView newShelfBtn = label("+ 새 책장 만들기", 14, accent, true);
    pad(newShelfBtn, 4, 12);
    newShelfBtn.setMinimumHeight(dp(44));
    newShelfBtn.setGravity(Gravity.CENTER_VERTICAL);
    newShelfBtn.setOnClickListener(
        v -> {
          if (holder[0] != null) holder[0].dismiss();
          createOrRenameShelf(true);
        });
    box.addView(newShelfBtn, -1, -2);

    if (!shelf.isEmpty()) {
      TextView renameBtn = label("현재 책장 이름 변경", 14, muted, true);
      pad(renameBtn, 4, 10);
      renameBtn.setMinimumHeight(dp(44));
      renameBtn.setGravity(Gravity.CENTER_VERTICAL);
      renameBtn.setOnClickListener(
          v -> {
            if (holder[0] != null) holder[0].dismiss();
            createOrRenameShelf(false);
          });
      box.addView(renameBtn, -1, -2);
    }

    holder[0] = builder.setTitle("보기 및 책장").setView(scroll).setNegativeButton("닫기", null).show();
  }

  void createOrRenameShelf(boolean create) {
    input(
        create ? "새 책장" : "책장 이름 변경",
        create ? "" : shelf,
        s -> {
          if (s.isEmpty()) return;
          if (!create && db.strings("SELECT name FROM shelves WHERE name=?", s).size() > 0) {
            toast("같은 이름의 책장이 있습니다.");
            return;
          }
          if (create) db.exec("INSERT OR IGNORE INTO shelves VALUES(?)", s);
          else {
            db.exec("UPDATE shelves SET name=? WHERE name=?", s, shelf);
            db.exec("UPDATE books SET shelf=? WHERE shelf=?", s, shelf);
          }
          shelf = s;
          selected = "";
          library(false);
        });
  }

  static String stars(int rating) {
    int r = Math.max(0, Math.min(5, rating));
    StringBuilder sb = new StringBuilder(5);
    for (int i = 0; i < 5; i++) sb.append(i < r ? '★' : '☆');
    return sb.toString();
  }

  SpannableString styledStars(int rating) {
    int r = Math.max(0, Math.min(5, rating));
    if (!starSpansReady || starSpansDark != dark) {
      int gold = dark ? 0xfff2c14e : 0xffc88a12;
      for (int i = 0; i <= 5; i++) {
        SpannableString s = new SpannableString(stars(i));
        if (i > 0) s.setSpan(new ForegroundColorSpan(gold), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (i < 5) s.setSpan(new ForegroundColorSpan(muted), i, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        starSpans[i] = s;
      }
      starSpansDark = dark;
      starSpansReady = true;
    }
    return starSpans[r];
  }

  int coverWidthDp() {
    if (prefs.contains("thumbWidthDp")) {
      return Math.max(
          THUMB_MIN_DP, Math.min(THUMB_MAX_DP, prefs.getInt("thumbWidthDp", THUMB_DEFAULT_DP)));
    }
    int pref = Math.max(0, Math.min(2, prefs.getInt("size", 1)));
    return new int[] {100, 133, 165}[pref];
  }

  void applyThumbWidth(int widthDp) {
    applyThumbWidth(widthDp, true);
  }

  void applyThumbWidth(int widthDp, boolean persist) {
    int clamped = Math.max(THUMB_MIN_DP, Math.min(THUMB_MAX_DP, widthDp));
    if (persist) prefs.edit().putInt("thumbWidthDp", clamped).apply();
    if (grid != null) {
      int px = dp(clamped);
      if (grid.getRequestedColumnWidth() != px) {
        grid.setColumnWidth(px);
        if (grid.getAdapter() instanceof BaseAdapter) {
          ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        }
        grid.requestLayout();
      }
    }
    if (sectionAdapter != null) {
      sectionAdapter.setThumbWidthDp(clamped);
    }
  }

  void showThumbSizeSlider() {
    int initial = coverWidthDp();
    final int[] latest = {initial};
    LinearLayout box = col();
    pad(box, 20, 12);
    LinearLayout header = row();
    TextView title = label("표지 크기", 16, ink, true);
    weight(header, title);
    TextView value = label(initial + "dp", 14, accent, true);
    header.addView(value);
    box.addView(header);

    LinearLayout sliderRow = row();
    pad(sliderRow, 0, 10);
    sliderRow.addView(label("작게", 12, muted, false));
    SeekBar bar = new SeekBar(this);
    bar.setContentDescription("표지 크기 조절 바");
    bar.setMax(THUMB_MAX_DP - THUMB_MIN_DP);
    bar.setProgress(initial - THUMB_MIN_DP);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(44), 1);
    sp.setMargins(dp(10), 0, dp(10), 0);
    sliderRow.addView(bar, sp);
    sliderRow.addView(label("크게", 12, muted, false));
    box.addView(sliderRow);

    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int progress, boolean user) {
            int w = THUMB_MIN_DP + progress;
            latest[0] = w;
            value.setText(w + "dp");
            if (pendingThumbResize != null) handler.removeCallbacks(pendingThumbResize);
            pendingThumbResize =
                () -> {
                  pendingThumbResize = null;
                  if (!isDestroyed()) applyThumbWidth(latest[0], false);
                };
            handler.post(pendingThumbResize);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {
            if (pendingThumbResize != null) {
              handler.removeCallbacks(pendingThumbResize);
              pendingThumbResize = null;
            }
            applyThumbWidth(latest[0], true);
          }
        });

    AlertDialog dlg =
        dialog()
            .setView(box)
            .setNeutralButton(
                "기본 크기",
                (d, w) -> {
                  latest[0] = THUMB_DEFAULT_DP;
                  applyThumbWidth(THUMB_DEFAULT_DP, true);
                })
            .setPositiveButton("완료", null)
            .create();
    dlg.setOnDismissListener(
        d -> {
          if (pendingThumbResize != null) {
            handler.removeCallbacks(pendingThumbResize);
            pendingThumbResize = null;
          }
          prefs.edit().putInt("thumbWidthDp", latest[0]).apply();
        });
    Window win = dlg.getWindow();
    if (win != null) {
      win.setGravity(Gravity.BOTTOM);
      win.setDimAmount(0.12f);
    }
    dlg.show();
  }

  void pickSort() {
    int current = currentSort();
    boolean desc = currentSortDesc();
    boolean byRating = groupByRating();
    AlertDialog.Builder builder = dialog();
    LinearLayout box = col();
    pad(box, 20, 6);

    Switch ratingToggle = new Switch(builder.getContext());
    ratingToggle.setText("별점순 표시하기");
    ratingToggle.setTextSize(15);
    ratingToggle.setTextColor(ink);
    ratingToggle.setMinHeight(dp(48));
    ratingToggle.setSwitchPadding(dp(10));
    ratingToggle.setChecked(byRating);
    box.addView(ratingToggle, -1, -2);

    View divider = new View(this);
    divider.setBackgroundColor(surface);
    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
    dlp.setMargins(0, dp(4), 0, dp(6));
    box.addView(divider, dlp);

    TextView sortHeader = label("정렬 기준  ·  " + sortLabel(current, desc), 13, muted, true);
    pad(sortHeader, 0, 4);
    box.addView(sortHeader);

    RadioGroup group = new RadioGroup(builder.getContext());
    box.addView(group, -1, -2);
    final RadioButton[] buttons = new RadioButton[SORT_NAMES.length];
    final int[] activeSort = {current};
    final boolean[] activeDesc = {desc};
    for (int i = 0; i < SORT_NAMES.length; i++) {
      final int idx = i;
      boolean itemDesc = i == current ? desc : (i == SORT_RECENT || i == SORT_ADDED);
      RadioButton rb = new RadioButton(builder.getContext());
      rb.setId(View.generateViewId());
      rb.setTextColor(ink);
      rb.setTextSize(15);
      rb.setMinHeight(dp(46));
      rb.setText(
          i == current
              ? SORT_NAMES[i] + "  " + (itemDesc ? "↓" : "↑") + "  (현재 · 다시 누르면 전환)"
              : SORT_NAMES[i] + "  " + (itemDesc ? "↓" : "↑"));
      group.addView(rb, -1, -2);
      buttons[i] = rb;
      if (i == current) group.check(rb.getId());
    }

    AlertDialog dlg =
        builder
            .setTitle("정렬 및 표시")
            .setView(box)
            .setNeutralButton(
                desc ? "오름차순 ↑" : "내림차순 ↓",
                (d, w) -> {
                  prefs.edit().putBoolean("sortDesc", !activeDesc[0]).apply();
                  gridIndex = gridOffset = 0;
                  renderNow(false);
                })
            .setNegativeButton("닫기", null)
            .create();

    ratingToggle.setOnCheckedChangeListener(
        (v, on) -> {
          prefs.edit().putBoolean("groupByRating", on).apply();
          gridIndex = gridOffset = 0;
          renderNow(false);
        });

    for (int i = 0; i < buttons.length; i++) {
      final int idx = i;
      buttons[i].setOnClickListener(
          v -> {
            boolean nextDesc =
                idx == activeSort[0] ? !activeDesc[0] : (idx == SORT_RECENT || idx == SORT_ADDED);
            if (idx != SORT_CUSTOM) arrangeMode = false;
            prefs.edit().putInt("sort", idx).putBoolean("sortDesc", nextDesc).apply();
            gridIndex = gridOffset = 0;
            dlg.dismiss();
            renderNow(false);
          });
    }
    dlg.show();
  }

  final ExecutorService snapshots = Executors.newSingleThreadExecutor();
  int snapshotId;

  /** 메모리 캐시된 서재 데이터로 SQLite 조회 없이 즉시 다시 그린다. */
  void renderNow(boolean keep) {
    if (isDestroyed() || reader != null) return;
    if (keep) remember();
    renderLibrary();
  }

  void ensureEntries() {
    boolean hideTags = prefs.getBoolean("hideVolumeTags", true);
    if (entries.size() == groups.size() && entriesHideTags == hideTags) {
      boolean same = true;
      for (Map.Entry<String, List<Library.Book>> e : groups.entrySet()) {
        SeriesEntry se = entries.get(e.getKey());
        if (se == null || se.books != e.getValue()) {
          same = false;
          break;
        }
      }
      if (same) return;
    }
    Map<String, SeriesEntry> built = new LinkedHashMap<>(groups.size() * 2);
    for (Map.Entry<String, List<Library.Book>> e : groups.entrySet()) {
      if (!e.getValue().isEmpty()) {
        built.put(e.getKey(), new SeriesEntry(e.getKey(), e.getValue(), hideTags));
      }
    }
    entries = built;
    entriesHideTags = hideTags;
  }

  void rebuildEntry(String grp) {
    List<Library.Book> group = groups.get(grp);
    if (group == null || group.isEmpty()) entries.remove(grp);
    else entries.put(grp, new SeriesEntry(grp, group, prefs.getBoolean("hideVolumeTags", true)));
  }

  void updateScanBanner() {
    if (scanBanner == null) return;
    if (scanMessage.isEmpty()) {
      scanBanner.setVisibility(View.GONE);
    } else {
      scanBanner.setText(scanMessage);
      scanBanner.setVisibility(View.VISIBLE);
    }
  }

  void library(boolean keep) {
    if (isDestroyed() || reader != null) return;
    if (keep) remember();
    int token = ++snapshotId;
    boolean hideTags = prefs.getBoolean("hideVolumeTags", true);
    snapshots.execute(
        () -> {
          List<Library.Book> all = db.books();
          Map<String, List<Library.Book>> sorted = new LinkedHashMap<>();
          for (int i = 0; i < all.size(); i++) {
            Library.Book b = all.get(i);
            sorted.computeIfAbsent(b.grp, k -> new ArrayList<>()).add(b);
          }
          Map<String, SeriesEntry> built = new LinkedHashMap<>(sorted.size() * 2);
          for (Map.Entry<String, List<Library.Book>> e : sorted.entrySet()) {
            List<Library.Book> group = e.getValue();
            Order.sortBooks(group);
            built.put(e.getKey(), new SeriesEntry(e.getKey(), group, hideTags));
          }
          runOnUiThread(
              () -> {
                if (isDestroyed() || token != snapshotId) return;
                books = all;
                groups = sorted;
                entries = built;
                entriesHideTags = hideTags;
                if (reader == null) renderLibrary();
              });
        });
  }

  void renderLibrary() {
    if (dragController != null) {
      dragController.cancel();
      dragController = null;
    }
    grid = null;
    shelfList = null;
    sectionAdapter = null;
    scanBanner = null;
    theme();
    ensureEntries();
    if (!groups.containsKey(selected)) selected = "";
    if (!selected.isEmpty() || currentSort() != SORT_CUSTOM) arrangeMode = false;
    frame.removeAllViews();
    root = col();
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, windowInsets) -> {
          Insets safe =
              windowInsets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return windowInsets;
        });
    frame.addView(root, -1, -1);
    ViewCompat.requestApplyInsets(frame);
    LinearLayout bar = row();
    pad(bar, 10, 2);
    root.addView(bar, -1, dp(48));
    boolean showFilters = selected.isEmpty();
    View leftLead;
    if (!showFilters) {
      ToolsIcon backBtn = new ToolsIcon(this, "back", ink, "책장으로");
      bar.addView(backBtn, dp(44), dp(44));
      backBtn.setOnClickListener(
          w -> {
            selected = "";
            renderNow(false);
          });
      leftLead = backBtn;
    } else {
      LinearLayout menuBtn = row();
      menuBtn.setMinimumHeight(dp(44));
      menuBtn.setPadding(dp(4), 0, dp(4), 0);
      menuBtn.setFocusable(true);
      menuBtn.setContentDescription("보기 및 책장 선택");
      TextView pill = label(filterLabel(), 14, bg, true);
      one(pill);
      pill.setGravity(Gravity.CENTER);
      pill.setMaxWidth(dp(168));
      pill.setPadding(dp(13), 0, dp(13), 0);
      pill.setBackground(shape(ink, 15));
      menuBtn.addView(pill, new LinearLayout.LayoutParams(-2, dp(30)));
      ToolsIcon chev = new ToolsIcon(this, "chevron_down", ink, "메뉴 열기");
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(22), dp(30));
      clp.leftMargin = dp(2);
      menuBtn.addView(chev, clp);
      menuBtn.setOnClickListener(w -> showLibraryMenu());
      bar.addView(menuBtn, new LinearLayout.LayoutParams(-2, dp(44)));
      leftLead = menuBtn;
    }
    View spacer = new View(this);
    bar.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));
    boolean showArrange = showFilters && currentSort() == SORT_CUSTOM;
    leftLead.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    int iconW = showArrange ? 40 : 42;
    int available =
        dp(getResources().getConfiguration().screenWidthDp - 20 - (showArrange ? iconW : 0))
            - leftLead.getMeasuredWidth();
    boolean showSearch = available >= dp(iconW * 4), showAdd = available >= dp(iconW * 3);
    if (showSearch) icon(bar, "search", "책 검색", ink, this::searchLibrary);
    if (showFilters) {
      int curSort = currentSort();
      boolean curDesc = currentSortDesc();
      icon(
          bar,
          curDesc ? "sort_desc" : "sort_asc",
          "정렬 · " + sortLabel(curSort, curDesc),
          ink,
          this::pickSort);
    }
    if (showArrange) {
      ToolsIcon arrBtn =
          new ToolsIcon(
              this,
              arrangeMode ? "check" : "arrange",
              arrangeMode ? bg : ink,
              arrangeMode ? "배치 완료" : "배치 모드");
      if (arrangeMode) arrBtn.setBackground(shape(accent, 22));
      bar.addView(arrBtn, dp(42), dp(44));
      arrBtn.setOnClickListener(
          w -> {
            arrangeMode = !arrangeMode;
            renderNow(true);
          });
    }
    if (showAdd) icon(bar, "add", "책 추가", ink, this::addMenu);
    icon(
        bar,
        "more",
        "더보기",
        ink,
        () -> {
          List<String> items = new ArrayList<>();
          if (!showSearch) items.add("책 검색");
          if (!showAdd) items.add("책 추가");
          items.add("서재 설정");
          if (items.size() == 1) {
            settings();
            return;
          }
          choices(
              "더보기",
              items.toArray(new String[0]),
              i -> {
                switch (items.get(i)) {
                  case "책 검색":
                    searchLibrary();
                    break;
                  case "책 추가":
                    addMenu();
                    break;
                  default:
                    settings();
                }
              });
        });
    String bannerText =
        !scanMessage.isEmpty()
            ? scanMessage
            : (arrangeMode
                ? (groupByRating()
                    ? "배치 모드 · 작품을 꾹 눌러 같은 별점 내에서 드래그하세요"
                    : "배치 모드 · 작품을 꾹 눌러 원하는 위치로 드래그하세요")
                : "");
    scanBanner = label(bannerText, 13, accent, true);
    pad(scanBanner, 20, 6);
    scanBanner.setVisibility(bannerText.isEmpty() ? View.GONE : View.VISIBLE);
    root.addView(scanBanner);
    FrameLayout body = new FrameLayout(this);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout pane = col();
    SwipeRefreshLayout pull = new SwipeRefreshLayout(this);
    body.addView(pull, -1, -1);
    pull.addView(pane, -1, -1);
    pull.setColorSchemeColors(accent);
    pull.setProgressBackgroundColorSchemeColor(surface);
    pull.setEnabled(!arrangeMode);
    pull.setOnChildScrollUpCallback(
        (parent, child) ->
            (grid != null && grid.canScrollVertically(-1))
                || (shelfList != null && shelfList.canScrollVertically(-1)));
    pull.setOnRefreshListener(
        () -> {
          if (!refreshSources(true)) pull.setRefreshing(false);
        });
    pull.setRefreshing(!scanning.isEmpty());
    floatingResumeVisible = false;
    if (selected.isEmpty()) libraryPane(pane, body);
    else detailPane(pane);
  }

  void searchLibrary() {
    input(
        "책 검색",
        query,
        s -> {
          query = s;
          selected = "";
          gridIndex = gridOffset = 0;
          renderNow(false);
        });
  }

  int groupRating(String grp) {
    SeriesEntry e = entries.get(grp);
    if (e != null) return e.rating;
    List<Library.Book> g = groups.get(grp);
    if (g != null) {
      int r = 0;
      for (Library.Book b : g) r = Math.max(r, b.rating);
      return Math.max(0, Math.min(5, r));
    }
    return db != null ? db.groupRating(grp) : 0;
  }

  String seriesDisplayName(String grp, List<Library.Book> group) {
    SeriesEntry e = entries.get(grp);
    if (e != null) return e.displayTitle;
    return Order.bestSeriesTitle(group, prefs.getBoolean("hideVolumeTags", true));
  }

  String volumeTitle(Library.Book b) {
    SeriesEntry e = entries.get(b.grp);
    String seriesName =
        e != null
            ? e.displayTitle
            : (groups.containsKey(b.grp)
                ? Order.bestSeriesTitle(groups.get(b.grp), prefs.getBoolean("hideVolumeTags", true))
                : shelfTitle(b.series));
    return Order.volumeDisplayTitle(
        seriesName, b.title, b.comicNumber, prefs.getBoolean("hideVolumeTags", true));
  }

  void libraryPane(LinearLayout pane, FrameLayout host) {
    Library.Book recent = null;
    if (!arrangeMode && filter == 0 && query.isEmpty() && showFloatingResume() && !resumeDismissed) {
      for (int i = 0; i < books.size(); i++) {
        Library.Book b = books.get(i);
        if (b.opened > 0
            && !b.done
            && (recent == null || b.opened > recent.opened)
            && (shelf.isEmpty() || shelf.equals(b.shelf))) recent = b;
      }
    }
    List<SeriesEntry> list = new ArrayList<>(entries.size());
    String q = query.toLowerCase(Locale.ROOT);
    for (SeriesEntry entry : entries.values()) {
      if (entry.matches(shelf, filter, q)) list.add(entry);
    }
    int sort = currentSort();
    boolean desc = currentSortDesc();
    boolean byRating = groupByRating();
    list.sort(
        (a, b) -> {
          int n;
          if (sort == SORT_TITLE) {
            n = Order.natural(a.displayTitle, b.displayTitle);
            return desc ? -n : n;
          } else if (sort == SORT_CUSTOM) {
            int oa = a.customOrder > 0 ? a.customOrder : Integer.MAX_VALUE;
            int ob = b.customOrder > 0 ? b.customOrder : Integer.MAX_VALUE;
            n = Integer.compare(oa, ob);
            if (n == 0) n = Order.natural(a.displayTitle, b.displayTitle);
            return desc ? -n : n;
          } else {
            long oa = sort == SORT_RECENT ? a.maxOpened : a.maxId;
            long ob = sort == SORT_RECENT ? b.maxOpened : b.maxId;
            n = Long.compare(oa, ob);
            if (desc) n = -n;
            return n == 0 ? Order.natural(a.displayTitle, b.displayTitle) : n;
          }
        });
    if (!query.isEmpty()) {
      LinearLayout searchBar = row();
      pad(searchBar, 18, 2);
      searchBar.setMinimumHeight(dp(38));
      TextView qText = label("‘" + query + "’ 검색 결과", 13, muted, true);
      one(qText);
      searchBar.addView(qText);
      TextView qCount = label(String.valueOf(list.size()), 14, countAccentColor(), true);
      LinearLayout.LayoutParams qcp = new LinearLayout.LayoutParams(-2, -2);
      qcp.leftMargin = dp(6);
      if (showShelfCount()) searchBar.addView(qCount, qcp);
      View sp = new View(this);
      searchBar.addView(sp, new LinearLayout.LayoutParams(0, 0, 1));
      TextView clearBtn =
          action(
              "검색 해제",
              () -> {
                query = "";
                renderNow(true);
              });
      clearBtn.setMinHeight(dp(36));
      pad(clearBtn, 8, 4);
      searchBar.addView(clearBtn);
      pane.addView(searchBar, -1, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    if (list.isEmpty()) {
      LinearLayout empty = col();
      empty.setGravity(Gravity.CENTER);
      pad(empty, 20, 12);
      pane.addView(empty, -1, -1);
      TextView msg = label(books.isEmpty() ? "첫 만화를 책장에 담으세요" : "표시할 작품이 없습니다", 20, ink, true);
      pad(msg, 0, 18);
      empty.addView(msg);
      if (books.isEmpty()) {
        TextView btn = action("책 추가", this::addMenu);
        btn.setTextColor(bg);
        btn.setBackground(shape(ink, 24));
        empty.addView(btn);
      }
      return;
    }
    floatingResumeVisible = recent != null;
    if (byRating) {
      @SuppressWarnings("unchecked")
      List<CoverItem>[] buckets = new List[6];
      for (int i = 0; i < list.size(); i++) {
        SeriesEntry e = list.get(i);
        if (buckets[e.rating] == null) buckets[e.rating] = new ArrayList<>();
        buckets[e.rating].add(new CoverItem(e.rep, e.displayTitle, e.subtitleNoStar));
      }
      List<StarSection> sections = new ArrayList<>(6);
      for (int r = 5; r >= 0; r--) {
        if (buckets[r] != null && !buckets[r].isEmpty()) {
          sections.add(new StarSection(r, buckets[r]));
        }
      }
      starSectionList(pane, sections, true);
    } else {
      LinearLayout flatHeader = row();
      pad(flatHeader, 18, 4);
      View leftSpace = new View(this);
      flatHeader.addView(leftSpace, new LinearLayout.LayoutParams(0, 0, 1));
      TextView sortText = label(sortLabel(sort, desc), 13, muted, true);
      sortText.setOnClickListener(v -> pickSort());
      flatHeader.addView(sortText);
      TextView countText = label(String.valueOf(list.size()), 14, countAccentColor(), true);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
      clp.leftMargin = dp(7);
      if (showShelfCount()) flatHeader.addView(countText, clp);
      pane.addView(flatHeader, -1, LinearLayout.LayoutParams.WRAP_CONTENT);

      List<CoverItem> covers = new ArrayList<>(list.size());
      for (int i = 0; i < list.size(); i++) {
        SeriesEntry e = list.get(i);
        covers.add(new CoverItem(e.rep, e.displayTitle, e.subtitleWithStar));
      }
      if (sort == SORT_CUSTOM) {
        List<StarSection> flat = new ArrayList<>(1);
        flat.add(new StarSection(-1, covers));
        starSectionList(pane, flat, false);
      } else {
        coverGrid(pane, covers, false);
      }
    }
    if (recent != null) addFloatingResume(host, recent);
  }

  void addFloatingResume(FrameLayout host, final Library.Book recent) {
    final LinearLayout bar = row();
    GradientDrawable bgShape = new GradientDrawable();
    bgShape.setCornerRadius(dp(18));
    if (dark) {
      bgShape.setColor(0xf22d322e);
      bgShape.setStroke(dp(1), 0x2cf2f1e9);
    } else {
      bgShape.setColor(0xee222822);
    }
    bar.setBackground(bgShape);
    bar.setElevation(dp(8));
    bar.setPadding(dp(14), dp(11), dp(14), dp(11));

    ImageView small = new ImageView(this);
    small.setScaleType(ImageView.ScaleType.CENTER_CROP);
    small.setBackgroundColor(0x28ffffff);
    small.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(5));
          }
        });
    small.setClipToOutline(true);
    bar.addView(small, dp(34), dp(46));
    loadCover(small, recent);

    LinearLayout tx = col();
    tx.setPadding(dp(12), 0, dp(10), 0);
    weight(bar, tx);
    LinearLayout meta = row();
    meta.addView(label("이어 읽기", 11, 0xffa6c8af, true));
    meta.addView(label("  ·  " + (recent.page + 1) + " / " + recent.pages, 11, 0xb8f2f1e9, false));
    tx.addView(meta);
    TextView name = label(volumeTitle(recent), 14, 0xfff5f5f0, true);
    one(name);
    name.setPadding(0, dp(3), 0, 0);
    tx.addView(name);

    ToolsIcon play = new ToolsIcon(this, "play", 0xfff5f5f0, "이어 읽기");
    play.setBackground(shape(0x24ffffff, 15));
    bar.addView(play, dp(30), dp(30));

    boolean wide = getResources().getConfiguration().screenWidthDp >= 520;
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(
            wide ? dp(372) : FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    lp.setMargins(dp(16), 0, dp(16), dp(14));
    host.addView(bar, lp);

    final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
    final int dismissDist = dp(76);
    final float flingVelocity = dp(420);
    bar.setOnTouchListener(
        new View.OnTouchListener() {
          float downRawX, downRawY;
          boolean dragging, dismissed;
          VelocityTracker vt;

          void recycleTracker() {
            if (vt != null) {
              vt.recycle();
              vt = null;
            }
          }

          @Override
          public boolean onTouch(View v, MotionEvent e) {
            if (dismissed) return true;
            switch (e.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                dragging = false;
                recycleTracker();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return true;
              case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                  dragging = true;
                  ViewParent p = v.getParent();
                  if (p != null) p.requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                  v.setTranslationX(dx);
                  v.setAlpha(Math.max(0.15f, 1f - Math.abs(dx) / dp(180)));
                }
                return true;
              case MotionEvent.ACTION_UP:
                float upDx = e.getRawX() - downRawX;
                float vx = 0f;
                if (vt != null) {
                  vt.addMovement(e);
                  vt.computeCurrentVelocity(1000);
                  vx = vt.getXVelocity();
                }
                recycleTracker();
                if (!dragging) {
                  open(recent);
                  return true;
                }
                boolean fastFling =
                    Math.abs(vx) >= flingVelocity && Math.abs(upDx) > dp(24) && (vx * upDx > 0);
                if (Math.abs(upDx) >= dismissDist || fastFling) {
                  dismissed = true;
                  resumeDismissed = true;
                  floatingResumeVisible = false;
                  float targetX =
                      (upDx >= 0 ? 1f : -1f) * Math.max(dp(380), host.getWidth() + dp(40));
                  v.animate()
                      .translationX(targetX)
                      .alpha(0f)
                      .setDuration(180)
                      .setInterpolator(new DecelerateInterpolator())
                      .withEndAction(
                          () -> {
                            host.removeView(v);
                            if (shelfList != null) shelfList.setPadding(0, dp(2), 0, dp(22));
                            if (grid != null && !Boolean.TRUE.equals(grid.getTag()))
                              grid.setPadding(dp(18), dp(3), dp(18), dp(22));
                          })
                      .start();
                } else {
                  v.animate().translationX(0f).alpha(1f).setDuration(160).start();
                }
                return true;
              case MotionEvent.ACTION_CANCEL:
                recycleTracker();
                if (dragging) v.animate().translationX(0f).alpha(1f).setDuration(160).start();
                return true;
            }
            return false;
          }
        });
  }

  static final class CoverItem {
    final Library.Book book;
    final String title, subtitle;

    CoverItem(Library.Book book, String title, String subtitle) {
      this.book = book;
      this.title = title;
      this.subtitle = subtitle;
    }
  }

  static final class StarSection {
    final int rating;
    final List<CoverItem> items;

    StarSection(int rating, List<CoverItem> items) {
      this.rating = rating;
      this.items = items;
    }
  }

  static final class SectionRow {
    final boolean header;
    final int rating, count, secIndex, startItemIndex;
    final List<CoverItem> items;

    SectionRow(int secIndex, int rating, int count) {
      this.header = true;
      this.secIndex = secIndex;
      this.startItemIndex = -1;
      this.rating = rating;
      this.count = count;
      this.items = Collections.emptyList();
    }

    SectionRow(int secIndex, int startItemIndex, List<CoverItem> items) {
      this.header = false;
      this.secIndex = secIndex;
      this.startItemIndex = startItemIndex;
      this.rating = -1;
      this.count = items.size();
      this.items = items;
    }
  }

  static final class TileHolder {
    final LinearLayout root;
    final ImageView img;
    final TextView name, sub;
    CoverItem item;
    int secIndex = -1, itemIndex = -1;
    ObjectAnimator wiggle;

    TileHolder(LinearLayout root, ImageView img, TextView name, TextView sub) {
      this.root = root;
      this.img = img;
      this.name = name;
      this.sub = sub;
    }

    void stopWiggle() {
      if (wiggle != null) {
        wiggle.cancel();
        wiggle = null;
      }
      if (root.getRotation() != 0f) root.setRotation(0f);
    }

    void syncWiggle(boolean active, long seed) {
      if (!active) {
        stopWiggle();
        return;
      }
      if (wiggle != null && wiggle.isRunning()) return;
      stopWiggle();
      float deg = (seed % 2 == 0) ? 1.55f : -1.55f;
      wiggle = ObjectAnimator.ofFloat(root, View.ROTATION, -deg, deg);
      wiggle.setDuration(130 + (Math.abs(seed) % 35));
      wiggle.setRepeatCount(ValueAnimator.INFINITE);
      wiggle.setRepeatMode(ValueAnimator.REVERSE);
      wiggle.setInterpolator(new AccelerateDecelerateInterpolator());
      wiggle.setStartDelay(Math.abs(seed * 29) % 110);
      wiggle.start();
    }
  }

  /**
   * 사용자 정의 정렬에서 작품을 드래그해 순서를 바꾼다.
   * 별점순 표시하기가 켜져 있으면 같은 별점 섹션 안에서만 이동할 수 있다.
   */
  boolean reorderCustomSection(int fromSec, int fromIdx, int toSec, int toIdx) {
    if (sectionAdapter == null) return false;
    if (fromSec != toSec) return false;
    if (fromSec < 0 || fromSec >= sectionAdapter.sections.size()) return false;
    StarSection sec = sectionAdapter.sections.get(fromSec);
    if (fromIdx < 0
        || fromIdx >= sec.items.size()
        || toIdx < 0
        || toIdx >= sec.items.size()
        || fromIdx == toIdx) return false;
    CoverItem moved = sec.items.remove(fromIdx);
    sec.items.add(toIdx, moved);
    persistSectionCustomOrder(sec);
    sectionAdapter.rebuildRowsOnly();
    sectionAdapter.notifyDataSetChanged();
    return true;
  }

  void persistSectionCustomOrder(StarSection sec) {
    // 아직 customOrder가 0인 작품이 있으면 전체 시리즈에 1..N 기본 슬롯을 먼저 부여한다.
    List<SeriesEntry> all = new ArrayList<>(entries.values());
    boolean needsInit = false;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).customOrder <= 0) {
        needsInit = true;
        break;
      }
    }
    Map<String, Integer> dbUpdates = new LinkedHashMap<>();
    if (needsInit) {
      all.sort(
          (a, b) -> {
            int oa = a.customOrder > 0 ? a.customOrder : Integer.MAX_VALUE;
            int ob = b.customOrder > 0 ? b.customOrder : Integer.MAX_VALUE;
            int d = Integer.compare(oa, ob);
            return d == 0 ? Order.natural(a.displayTitle, b.displayTitle) : d;
          });
      for (int i = 0; i < all.size(); i++) {
        SeriesEntry e = all.get(i);
        int pos = i + 1;
        if (e.customOrder != pos) {
          e.customOrder = pos;
          for (Library.Book b : e.books) b.customOrder = pos;
          dbUpdates.put(e.grp, pos);
        }
      }
    }
    // 이 섹션이 보유한 슬롯 번호들만 새 시각 순서대로 재배정해 다른 별점 섹션의 순서를 보존한다.
    int count = sec.items.size();
    int[] slots = new int[count];
    for (int i = 0; i < count; i++) {
      SeriesEntry e = entries.get(sec.items.get(i).book.grp);
      slots[i] = e != null && e.customOrder > 0 ? e.customOrder : (i + 1);
    }
    Arrays.sort(slots);
    boolean desc = currentSortDesc();
    for (int i = 0; i < count; i++) {
      int assigned = desc ? slots[count - 1 - i] : slots[i];
      String grp = sec.items.get(i).book.grp;
      SeriesEntry e = entries.get(grp);
      if (e != null) {
        e.customOrder = assigned;
        for (Library.Book b : e.books) b.customOrder = assigned;
      }
      dbUpdates.put(grp, assigned);
    }
    if (!dbUpdates.isEmpty() && db != null) {
      final Map<String, Integer> batch = new LinkedHashMap<>(dbUpdates);
      worker.execute(() -> db.saveCustomOrders(batch));
    }
  }

  final class StarSectionAdapter extends BaseAdapter {
    final List<StarSection> sections;
    final boolean showHeaders;
    final List<SectionRow> rows = new ArrayList<>();
    final int padH = dp(18), padTop = dp(6), padBottom = dp(12), spacing = dp(14);
    int widthDp;
    int availablePx;
    int cols = 3;
    int tileWidthPx;
    int coverHeightPx;
    long draggedBookId = -1;

    StarSectionAdapter(
        List<StarSection> sections, boolean showHeaders, int widthDp, int availablePx) {
      this.sections = sections;
      this.showHeaders = showHeaders;
      this.widthDp = widthDp;
      this.availablePx = availablePx;
      rebuild();
    }

    void setThumbWidthDp(int newWidthDp) {
      if (this.widthDp == newWidthDp) return;
      this.widthDp = newWidthDp;
      int oldCols = cols;
      int oldCoverH = coverHeightPx;
      rebuild();
      if (cols != oldCols || Math.abs(coverHeightPx - oldCoverH) >= 2) {
        notifyDataSetChanged();
      }
    }

    void setAvailableWidth(int newAvailablePx) {
      if (newAvailablePx <= 0 || this.availablePx == newAvailablePx) return;
      this.availablePx = newAvailablePx;
      rebuild();
      notifyDataSetChanged();
    }

    void rebuild() {
      int usable = Math.max(dp(120), availablePx - padH * 2);
      int desired = dp(widthDp);
      cols = Math.max(1, (usable + spacing) / Math.max(1, desired + spacing));
      tileWidthPx = Math.max(dp(60), (usable - spacing * (cols - 1)) / cols);
      coverHeightPx = (int) (tileWidthPx * 1.42f);
      rebuildRowsOnly();
    }

    void rebuildRowsOnly() {
      rows.clear();
      for (int s = 0; s < sections.size(); s++) {
        StarSection sec = sections.get(s);
        if (showHeaders) rows.add(new SectionRow(s, sec.rating, sec.items.size()));
        for (int i = 0; i < sec.items.size(); i += cols) {
          rows.add(
              new SectionRow(
                  s, i, new ArrayList<>(sec.items.subList(i, Math.min(i + cols, sec.items.size())))));
        }
      }
    }

    public int getViewTypeCount() {
      return 2;
    }

    public int getItemViewType(int pos) {
      return rows.get(pos).header ? 0 : 1;
    }

    public int getCount() {
      return rows.size();
    }

    public Object getItem(int pos) {
      return rows.get(pos);
    }

    public long getItemId(int pos) {
      return pos;
    }

    public boolean isEnabled(int pos) {
      return false;
    }

    public View getView(int pos, View recycled, ViewGroup parent) {
      SectionRow rowData = rows.get(pos);
      if (rowData.header) {
        LinearLayout hdr =
            recycled instanceof LinearLayout && Integer.valueOf(0).equals(recycled.getTag())
                ? (LinearLayout) recycled
                : col();
        if (hdr.getChildCount() == 0) {
          hdr.setTag(0);
          View divider = new View(Main.this);
          divider.setBackgroundColor(surface);
          LinearLayout.LayoutParams dpLine = new LinearLayout.LayoutParams(-1, dp(1));
          dpLine.setMargins(padH, padTop, padH, dp(8));
          hdr.addView(divider, dpLine);
          LinearLayout line = row();
          pad(line, 18, 4);
          TextView starText = label("", 18, ink, true);
          weight(line, starText);
          TextView sortText = label("", 13, muted, true);
          sortText.setPadding(dp(6), dp(4), dp(7), dp(4));
          sortText.setOnClickListener(v -> pickSort());
          line.addView(sortText);
          TextView countText = label("", 14, countAccentColor(), true);
          line.addView(countText);
          hdr.addView(line);
        }
        hdr.getChildAt(0).setVisibility(pos == 0 ? View.GONE : View.VISIBLE);
        LinearLayout line = (LinearLayout) hdr.getChildAt(1);
        ((TextView) line.getChildAt(0)).setText(styledStars(rowData.rating));
        TextView sortText = (TextView) line.getChildAt(1);
        if (pos == 0) {
          sortText.setText(sortLabel(currentSort(), currentSortDesc()));
          sortText.setVisibility(View.VISIBLE);
        } else {
          sortText.setVisibility(View.GONE);
        }
        TextView countText = (TextView) line.getChildAt(2);
        countText.setTextColor(countAccentColor());
        countText.setText(String.valueOf(rowData.count));
        countText.setVisibility(showShelfCount() ? View.VISIBLE : View.GONE);
        return hdr;
      }
      LinearLayout rowView;
      TileHolder[] holders;
      if (recycled instanceof LinearLayout && recycled.getTag() instanceof TileHolder[]) {
        rowView = (LinearLayout) recycled;
        holders = (TileHolder[]) rowView.getTag();
      } else {
        rowView = row();
        rowView.setGravity(Gravity.TOP);
        rowView.setPadding(padH, padTop, padH, padBottom);
        rowView.setClipChildren(false);
        rowView.setClipToPadding(false);
        holders = null;
      }
      if (holders == null || holders.length != cols) {
        if (holders != null) for (TileHolder old : holders) old.stopWiggle();
        rowView.removeAllViews();
        holders = new TileHolder[cols];
        for (int c = 0; c < cols; c++) {
          LinearLayout tile = col();
          ImageView img = new ImageView(Main.this);
          img.setBackgroundColor(surface);
          img.setScaleType(ImageView.ScaleType.FIT_CENTER);
          tile.addView(img, new LinearLayout.LayoutParams(-1, coverHeightPx));
          TextView name = label("", 15, ink, true);
          one(name);
          name.setPadding(0, dp(8), 0, dp(3));
          tile.addView(name);
          TextView sub = label("", 12, muted, false);
          one(sub);
          tile.addView(sub);
          TileHolder h = new TileHolder(tile, img, name, sub);
          tile.setOnClickListener(
              v -> {
                if (arrangeMode || h.item == null) return;
                remember();
                volumeIndex = volumeOffset = 0;
                selected = h.item.book.grp;
                renderNow(false);
              });
          tile.setOnLongClickListener(
              v -> {
                if (arrangeMode || h.item == null) return false;
                manage(h.item.book);
                return true;
              });
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
          if (c > 0) lp.leftMargin = spacing;
          rowView.addView(tile, lp);
          holders[c] = h;
        }
        rowView.setTag(holders);
      }
      for (int c = 0; c < cols; c++) {
        TileHolder h = holders[c];
        if (c < rowData.items.size()) {
          CoverItem item = rowData.items.get(c);
          h.item = item;
          h.secIndex = rowData.secIndex;
          h.itemIndex = rowData.startItemIndex + c;
          if (h.root.getVisibility() != View.VISIBLE) h.root.setVisibility(View.VISIBLE);
          h.root.setClickable(!arrangeMode);
          h.root.setLongClickable(!arrangeMode);
          ViewGroup.LayoutParams lp = h.img.getLayoutParams();
          if (lp.height != coverHeightPx) {
            lp.height = coverHeightPx;
            h.img.setLayoutParams(lp);
          }
          loadCover(h.img, item.book);
          h.name.setText(item.title);
          h.sub.setText(item.subtitle);
          boolean isDragged = arrangeMode && item.book.id == draggedBookId;
          h.root.setAlpha(isDragged ? 0.16f : 1f);
          h.syncWiggle(arrangeMode && !isDragged, item.book.id + c);
        } else {
          h.item = null;
          h.secIndex = -1;
          h.itemIndex = -1;
          h.stopWiggle();
          if (h.root.getVisibility() != View.INVISIBLE) h.root.setVisibility(View.INVISIBLE);
        }
      }
      return rowView;
    }
  }

  /** 갤럭시 앱 서랍 스타일 꾹 눌러 드래그 배치 컨트롤러 (흔들림 + 부유 고스트 + 가장자리 자동 스크롤 + 슬라이드 스왑). */
  final class ShelfDragController implements View.OnTouchListener {
    final ListView listView;
    final StarSectionAdapter adapter;
    final int touchSlop = ViewConfiguration.get(Main.this).getScaledTouchSlop();
    final DecelerateInterpolator decel = new DecelerateInterpolator();
    final int[] loc = new int[2];
    final int[] frameLoc = new int[2];
    boolean dragging;
    int dragSec = -1, dragIdx = -1;
    long dragBookId = -1;
    float downX, downY, downRawX, downRawY, lastRawX, lastRawY;
    float ghostStartX, ghostStartY;
    ImageView ghost;
    Bitmap ghostBitmap;
    Runnable longPressTask;
    int autoScrollSpeed;
    final Runnable autoScrollTask =
        new Runnable() {
          public void run() {
            if (!dragging || autoScrollSpeed == 0) return;
            listView.scrollListBy(autoScrollSpeed);
            checkSwap(lastRawX, lastRawY);
            handler.postDelayed(this, 16);
          }
        };

    ShelfDragController(ListView listView, StarSectionAdapter adapter) {
      this.listView = listView;
      this.adapter = adapter;
    }

    TileHolder findTileAt(float rawX, float rawY) {
      listView.getLocationOnScreen(loc);
      int listLeft = loc[0], listTop = loc[1];
      for (int i = 0; i < listView.getChildCount(); i++) {
        View child = listView.getChildAt(i);
        if (!(child.getTag() instanceof TileHolder[])) continue;
        int rowLeft = listLeft + child.getLeft();
        int rowTop = listTop + child.getTop();
        TileHolder[] holders = (TileHolder[]) child.getTag();
        for (int c = 0; c < holders.length; c++) {
          TileHolder h = holders[c];
          if (h.item == null || h.root.getVisibility() != View.VISIBLE) continue;
          int left = rowLeft + h.root.getLeft();
          int top = rowTop + h.root.getTop();
          int right = rowLeft + h.root.getRight();
          int bottom = rowTop + h.root.getBottom();
          if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
            return h;
          }
        }
      }
      return null;
    }

    /**
     * 드래그 중 현재 손가락 좌표가 같은 섹션(dragSec)의 어느 슬롯에 해당하는지 계산한다.
     * - 애니메이션 중인 translationX/Y가 아닌 고정 레이아웃 좌표로 판정해 앞→뒤 이동 시 되튕김(핑퐁)을 막는다.
     * - 마지막 줄의 빈 칸, 행 좌우 여백, 마지막 줄 아래 여백으로 끌어도 맨 뒤(secSize - 1)로 이동한다.
     */
    int findDropIndex(float rawX, float rawY) {
      if (dragSec < 0 || dragSec >= adapter.sections.size()) return -1;
      int secSize = adapter.sections.get(dragSec).items.size();
      if (secSize <= 1) return -1;

      listView.getLocationOnScreen(loc);
      int listLeft = loc[0], listTop = loc[1];

      boolean sawDragSec = false;
      int firstSecRowTop = Integer.MAX_VALUE;
      int firstSecRowIdx = -1;
      int lastSecRowBottom = Integer.MIN_VALUE;
      int lastSecRowLastIdx = -1;
      int prevOtherSecBottom = Integer.MIN_VALUE;
      int nextOtherSecTop = Integer.MAX_VALUE;

      int nearestRowDist = Integer.MAX_VALUE;
      View nearestRow = null;
      int nearestRowLeft = 0;

      for (int i = 0; i < listView.getChildCount(); i++) {
        View child = listView.getChildAt(i);
        if (!(child.getTag() instanceof TileHolder[])) continue;
        TileHolder[] holders = (TileHolder[]) child.getTag();
        int rowSec = -1;
        int rowFirstIdx = -1;
        int rowLastIdx = -1;
        for (int c = 0; c < holders.length; c++) {
          if (holders[c].item != null) {
            if (rowSec < 0) {
              rowSec = holders[c].secIndex;
              rowFirstIdx = holders[c].itemIndex;
            }
            rowLastIdx = holders[c].itemIndex;
          }
        }
        if (rowSec < 0) continue;

        int rowLeft = listLeft + child.getLeft();
        int rowTop = listTop + child.getTop();
        int rowBottom = rowTop + child.getHeight();

        if (rowSec != dragSec) {
          if (!sawDragSec) {
            if (rowBottom > prevOtherSecBottom) prevOtherSecBottom = rowBottom;
          } else if (rowTop < nextOtherSecTop) {
            nextOtherSecTop = rowTop;
          }
          continue;
        }

        sawDragSec = true;
        if (rowTop < firstSecRowTop) {
          firstSecRowTop = rowTop;
          firstSecRowIdx = rowFirstIdx;
        }
        if (rowBottom > lastSecRowBottom) {
          lastSecRowBottom = rowBottom;
          lastSecRowLastIdx = rowLastIdx;
        }

        if (rawY >= rowTop && rawY <= rowBottom) {
          return resolveColumnDropIndex(holders, rowLeft, rawX, rowFirstIdx, rowLastIdx, secSize);
        }

        int dist = rawY < rowTop ? Math.round(rowTop - rawY) : Math.round(rawY - rowBottom);
        if (dist < nearestRowDist) {
          nearestRowDist = dist;
          nearestRow = child;
          nearestRowLeft = rowLeft;
        }
      }

      if (!sawDragSec) return -1;
      // 마지막 줄이 화면에 보이고 손가락이 그 아래(다음 섹션 타일 전까지)로 내려간 경우 → 섹션 맨 뒤
      if (lastSecRowLastIdx == secSize - 1
          && rawY > lastSecRowBottom
          && (nextOtherSecTop == Integer.MAX_VALUE || rawY < nextOtherSecTop)) {
        return secSize - 1;
      }
      // 첫 줄이 화면에 보이고 손가락이 그 위(이전 섹션 타일 아래)로 올라간 경우 → 섹션 맨 앞
      if (firstSecRowIdx == 0
          && rawY < firstSecRowTop
          && (prevOtherSecBottom == Integer.MIN_VALUE || rawY > prevOtherSecBottom)) {
        return 0;
      }
      // 섹션 내부 행 사이 미세 간격에 걸린 경우 가장 가까운 행으로 스냅
      if (nearestRow != null && rawY >= firstSecRowTop && rawY <= lastSecRowBottom) {
        TileHolder[] holders = (TileHolder[]) nearestRow.getTag();
        int rowFirstIdx = -1, rowLastIdx = -1;
        for (TileHolder h : holders) {
          if (h.item != null) {
            if (rowFirstIdx < 0) rowFirstIdx = h.itemIndex;
            rowLastIdx = h.itemIndex;
          }
        }
        if (rowFirstIdx >= 0) {
          return resolveColumnDropIndex(
              holders, nearestRowLeft, rawX, rowFirstIdx, rowLastIdx, secSize);
        }
      }
      return -1;
    }

    private int resolveColumnDropIndex(
        TileHolder[] holders,
        int rowLeft,
        float rawX,
        int rowFirstIdx,
        int rowLastIdx,
        int secSize) {
      int bestIdx = -1;
      float bestDist = Float.MAX_VALUE;
      int firstSlotLeft = Integer.MAX_VALUE;
      int lastOccupiedRight = Integer.MIN_VALUE;

      for (int c = 0; c < holders.length; c++) {
        TileHolder h = holders[c];
        int left = rowLeft + h.root.getLeft();
        int right = rowLeft + h.root.getRight();
        if (left < firstSlotLeft) firstSlotLeft = left;
        if (h.item != null && right > lastOccupiedRight) lastOccupiedRight = right;
        int slotIdx = h.item != null ? h.itemIndex : secSize - 1;
        if (rawX >= left && rawX <= right) {
          return slotIdx;
        }
        float center = (left + right) * 0.5f;
        float dist = Math.abs(rawX - center);
        if (dist < bestDist) {
          bestDist = dist;
          bestIdx = slotIdx;
        }
      }
      if (rawX < firstSlotLeft) return rowFirstIdx;
      if (rawX > lastOccupiedRight) return rowLastIdx == secSize - 1 ? secSize - 1 : rowLastIdx;
      return bestIdx;
    }

    void startDrag(TileHolder hit) {
      if (hit == null || hit.item == null || hit.root.getWidth() <= 0 || hit.root.getHeight() <= 0)
        return;
      dragging = true;
      dragSec = hit.secIndex;
      dragIdx = hit.itemIndex;
      dragBookId = hit.item.book.id;
      listView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      hit.stopWiggle();
      int w = hit.root.getWidth(), h = hit.root.getHeight();
      ghostBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(ghostBitmap);
      hit.root.draw(canvas);
      View rowParent = (View) hit.root.getParent();
      if (rowParent != null) {
        rowParent.getLocationOnScreen(loc);
        loc[0] += hit.root.getLeft();
        loc[1] += hit.root.getTop();
      } else {
        hit.root.getLocationOnScreen(loc);
      }
      frame.getLocationOnScreen(frameLoc);
      ghostStartX = loc[0] - frameLoc[0];
      ghostStartY = loc[1] - frameLoc[1];
      ghost = new ImageView(Main.this);
      ghost.setImageBitmap(ghostBitmap);
      ghost.setElevation(dp(14));
      ghost.setTranslationX(ghostStartX);
      ghost.setTranslationY(ghostStartY);
      frame.addView(ghost, new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START));
      ghost.animate().scaleX(1.07f).scaleY(1.07f).alpha(0.92f).setDuration(110).start();
      adapter.draggedBookId = dragBookId;
      hit.root.setAlpha(0.16f);
      listView.requestDisallowInterceptTouchEvent(true);
    }

    void checkSwap(float rawX, float rawY) {
      int targetIdx = findDropIndex(rawX, rawY);
      if (targetIdx < 0 || targetIdx == dragIdx) return;

      // 스왑 전 화면에 보이는 타일 좌표 기록 (부드러운 슬라이드 이동 애니메이션용)
      listView.getLocationOnScreen(loc);
      int prevListLeft = loc[0], prevListTop = loc[1];
      Map<Long, float[]> oldPos = new HashMap<>();
      for (int i = 0; i < listView.getChildCount(); i++) {
        View child = listView.getChildAt(i);
        if (!(child.getTag() instanceof TileHolder[])) continue;
        int rowLeft = prevListLeft + child.getLeft();
        int rowTop = prevListTop + child.getTop();
        for (TileHolder h : (TileHolder[]) child.getTag()) {
          if (h.item == null || h.root.getVisibility() != View.VISIBLE) continue;
          float curX = rowLeft + h.root.getLeft() + h.root.getTranslationX();
          float curY = rowTop + h.root.getTop() + h.root.getTranslationY();
          oldPos.put(h.item.book.id, new float[] {curX, curY});
        }
      }

      StarSection sec = adapter.sections.get(dragSec);
      CoverItem moved = sec.items.remove(dragIdx);
      sec.items.add(targetIdx, moved);
      dragIdx = targetIdx;
      adapter.rebuildRowsOnly();
      adapter.notifyDataSetChanged();
      listView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

      listView
          .getViewTreeObserver()
          .addOnPreDrawListener(
              new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                  listView.getViewTreeObserver().removeOnPreDrawListener(this);
                  listView.getLocationOnScreen(loc);
                  int curListLeft = loc[0], curListTop = loc[1];
                  for (int i = 0; i < listView.getChildCount(); i++) {
                    View child = listView.getChildAt(i);
                    if (!(child.getTag() instanceof TileHolder[])) continue;
                    int rowLeft = curListLeft + child.getLeft();
                    int rowTop = curListTop + child.getTop();
                    for (TileHolder h : (TileHolder[]) child.getTag()) {
                      if (h.item == null || h.root.getVisibility() != View.VISIBLE) continue;
                      h.root.animate().cancel();
                      h.root.setTranslationX(0f);
                      h.root.setTranslationY(0f);
                      if (h.item.book.id == dragBookId) continue;
                      float[] prev = oldPos.get(h.item.book.id);
                      if (prev == null) continue;
                      float layoutX = rowLeft + h.root.getLeft();
                      float layoutY = rowTop + h.root.getTop();
                      float dx = prev[0] - layoutX;
                      float dy = prev[1] - layoutY;
                      if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                        h.root.setTranslationX(dx);
                        h.root.setTranslationY(dy);
                        h.root
                            .animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(180)
                            .setInterpolator(decel)
                            .start();
                      }
                    }
                  }
                  return true;
                }
              });
    }

    void updateAutoScroll(float rawY) {
      listView.getLocationOnScreen(loc);
      float yInList = rawY - loc[1];
      int edge = dp(68);
      int nextSpeed = 0;
      if (yInList < edge) {
        float ratio = Math.max(0.2f, 1f - Math.max(0f, yInList) / edge);
        nextSpeed = -Math.round(dp(11) * ratio);
      } else if (yInList > listView.getHeight() - edge) {
        float ratio =
            Math.max(0.2f, 1f - Math.max(0f, listView.getHeight() - yInList) / edge);
        nextSpeed = Math.round(dp(11) * ratio);
      }
      boolean wasScrolling = autoScrollSpeed != 0;
      autoScrollSpeed = nextSpeed;
      if (nextSpeed != 0 && !wasScrolling) {
        handler.postDelayed(autoScrollTask, 16);
      } else if (nextSpeed == 0 && wasScrolling) {
        handler.removeCallbacks(autoScrollTask);
      }
    }

    void finishDrag() {
      cancelPending();
      if (!dragging) return;
      dragging = false;
      if (dragSec >= 0 && dragSec < adapter.sections.size()) {
        persistSectionCustomOrder(adapter.sections.get(dragSec));
      }
      final ImageView dropping = ghost;
      final Bitmap bmp = ghostBitmap;
      ghost = null;
      ghostBitmap = null;
      TileHolder dest = null;
      for (int i = 0; i < listView.getChildCount(); i++) {
        View child = listView.getChildAt(i);
        if (!(child.getTag() instanceof TileHolder[])) continue;
        for (TileHolder h : (TileHolder[]) child.getTag()) {
          if (h.item != null && h.item.book.id == dragBookId) {
            dest = h;
            break;
          }
        }
      }
      if (dropping != null && dest != null) {
        View rowParent = (View) dest.root.getParent();
        if (rowParent != null) {
          rowParent.getLocationOnScreen(loc);
          loc[0] += dest.root.getLeft();
          loc[1] += dest.root.getTop();
        } else {
          dest.root.getLocationOnScreen(loc);
        }
        frame.getLocationOnScreen(frameLoc);
        float tx = loc[0] - frameLoc[0];
        float ty = loc[1] - frameLoc[1];
        dropping
            .animate()
            .translationX(tx)
            .translationY(ty)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(140)
            .setInterpolator(decel)
            .withEndAction(
                () -> {
                  frame.removeView(dropping);
                  if (bmp != null) bmp.recycle();
                  adapter.draggedBookId = -1;
                  adapter.notifyDataSetChanged();
                })
            .start();
      } else {
        if (dropping != null) frame.removeView(dropping);
        if (bmp != null) bmp.recycle();
        adapter.draggedBookId = -1;
        adapter.notifyDataSetChanged();
      }
      dragSec = dragIdx = -1;
      dragBookId = -1;
    }

    void cancelPending() {
      if (longPressTask != null) {
        handler.removeCallbacks(longPressTask);
        longPressTask = null;
      }
      autoScrollSpeed = 0;
      handler.removeCallbacks(autoScrollTask);
    }

    void cancel() {
      cancelPending();
      dragging = false;
      if (ghost != null) {
        ghost.animate().cancel();
        frame.removeView(ghost);
        ghost = null;
      }
      if (ghostBitmap != null) {
        ghostBitmap.recycle();
        ghostBitmap = null;
      }
      adapter.draggedBookId = -1;
      for (int i = 0; i < listView.getChildCount(); i++) {
        View child = listView.getChildAt(i);
        if (child.getTag() instanceof TileHolder[]) {
          for (TileHolder h : (TileHolder[]) child.getTag()) {
            h.stopWiggle();
            h.root.animate().cancel();
            h.root.setTranslationX(0f);
            h.root.setTranslationY(0f);
          }
        }
      }
    }

    public boolean onTouch(View v, MotionEvent e) {
      int action = e.getActionMasked();
      switch (action) {
        case MotionEvent.ACTION_DOWN:
          cancelPending();
          downX = e.getX();
          downY = e.getY();
          downRawX = lastRawX = e.getRawX();
          downRawY = lastRawY = e.getRawY();
          final TileHolder hit = findTileAt(downRawX, downRawY);
          if (hit != null) {
            longPressTask =
                () -> {
                  longPressTask = null;
                  if (arrangeMode && !isDestroyed()) startDrag(hit);
                };
            handler.postDelayed(longPressTask, 200);
          }
          return false;
        case MotionEvent.ACTION_MOVE:
          lastRawX = e.getRawX();
          lastRawY = e.getRawY();
          if (!dragging) {
            if (longPressTask != null
                && Math.hypot(e.getX() - downX, e.getY() - downY) > touchSlop) {
              cancelPending();
            }
            return false;
          }
          if (ghost != null) {
            ghost.setTranslationX(ghostStartX + (lastRawX - downRawX));
            ghost.setTranslationY(ghostStartY + (lastRawY - downRawY));
          }
          updateAutoScroll(lastRawY);
          checkSwap(lastRawX, lastRawY);
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (dragging) {
            finishDrag();
            return true;
          }
          cancelPending();
          return false;
      }
      return false;
    }
  }

  void starSectionList(LinearLayout pane, List<StarSection> sections, boolean showHeaders) {
    shelfList = new ListView(this);
    shelfList.setTag(false);
    shelfList.setDivider(null);
    shelfList.setDividerHeight(0);
    shelfList.setSelector(new ColorDrawable(Color.TRANSPARENT));
    shelfList.setClipToPadding(false);
    shelfList.setClipChildren(false);
    shelfList.setPadding(0, dp(2), 0, dp(floatingResumeVisible ? 96 : 22));
    int initialWidth = dp(getResources().getConfiguration().screenWidthDp);
    sectionAdapter = new StarSectionAdapter(sections, showHeaders, coverWidthDp(), initialWidth);
    shelfList.setAdapter(sectionAdapter);
    if (arrangeMode) {
      dragController = new ShelfDragController(shelfList, sectionAdapter);
      shelfList.setOnTouchListener(dragController);
    }
    final ListView listView = shelfList;
    final StarSectionAdapter adapter = sectionAdapter;
    listView.addOnLayoutChangeListener(
        (v, left, top, right, bottom, ol, ot, or, ob) -> {
          int w = right - left;
          if (w > 0 && w != or - ol) adapter.setAvailableWidth(w);
        });
    pane.addView(listView, -1, -1);
    listView.setSelectionFromTop(gridIndex, gridOffset);
  }

  int gridTileWidth(GridView g) {
    int desired = dp(coverWidthDp());
    int width = g.getWidth() - g.getPaddingLeft() - g.getPaddingRight();
    if (width <= 0) return g.getColumnWidth() > 0 ? g.getColumnWidth() : desired;
    int spacing = dp(14);
    int cols = Math.max(1, (width + spacing) / Math.max(1, desired + spacing));
    return Math.max(dp(60), (width - spacing * (cols - 1)) / cols);
  }

  void coverGrid(LinearLayout pane, List<CoverItem> list, boolean volumes) {
    grid = new GridView(this);
    grid.setTag(volumes);
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setHorizontalSpacing(dp(14));
    grid.setVerticalSpacing(dp(18));
    grid.setPadding(dp(18), dp(3), dp(18), dp(!volumes && floatingResumeVisible ? 96 : 22));
    grid.setClipToPadding(false);
    final GridView g = grid;
    pane.addView(g, -1, -1);
    int width = coverWidthDp();
    g.setColumnWidth(dp(width));
    g.setNumColumns(GridView.AUTO_FIT);
    g.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return list.size();
          }

          public Object getItem(int p) {
            return list.get(p);
          }

          public long getItemId(int p) {
            return list.get(p).book.id;
          }

          public View getView(int pos, View recycled, ViewGroup parent) {
            TileHolder h;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof TileHolder) {
              h = (TileHolder) recycled.getTag();
            } else {
              LinearLayout tile = col();
              ImageView img = new ImageView(Main.this);
              img.setBackgroundColor(surface);
              img.setScaleType(ImageView.ScaleType.FIT_CENTER);
              tile.addView(img, new LinearLayout.LayoutParams(-1, (int) (dp(coverWidthDp()) * 1.42f)));
              TextView name = label("", 15, ink, true);
              one(name);
              name.setPadding(0, dp(8), 0, dp(3));
              tile.addView(name);
              TextView sub = label("", 12, muted, false);
              one(sub);
              tile.addView(sub);
              h = new TileHolder(tile, img, name, sub);
              tile.setTag(h);
            }
            CoverItem item = list.get(pos);
            int coverH = (int) (gridTileWidth(g) * 1.42f);
            ViewGroup.LayoutParams lp = h.img.getLayoutParams();
            if (lp.height != coverH) {
              lp.height = coverH;
              h.img.setLayoutParams(lp);
            }
            loadCover(h.img, item.book);
            h.name.setText(item.title);
            h.sub.setText(item.subtitle);
            return h.root;
          }
        });
    g.setOnItemClickListener(
        (p, v, pos, id) -> {
          remember();
          Library.Book book = list.get(pos).book;
          if (volumes) {
            open(book);
            return;
          }
          volumeIndex = volumeOffset = 0;
          selected = book.grp;
          renderNow(false);
        });
    g.setOnItemLongClickListener(
        (p, v, pos, id) -> {
          manage(list.get(pos).book);
          return true;
        });
    g.setSelectionFromTop(volumes ? volumeIndex : gridIndex, volumes ? volumeOffset : gridOffset);
  }

  void loadCover(ImageView image, Library.Book b) {
    String key = b.id + "-" + db.coverVersion(b.id);
    if (key.equals(image.getTag()) && image.getDrawable() != null) return;
    image.setTag(key);
    Bitmap cached = coverCache.get(key);
    if (cached != null) {
      image.setImageBitmap(cached);
      return;
    }
    image.setImageResource(R.drawable.book_blank);
    final long id = b.id;
    thumbs.execute(
        () -> {
          // 스크롤로 이미 다른 책에 재사용된 뷰는 디코딩 자체를 건너뛴다.
          if (!key.equals(image.getTag())) return;
          File file = db.coverFile(id);
          if (!file.isFile()) return;
          Bitmap bitmap = BitmapFactory.decodeFile(file.toString());
          if (bitmap != null) {
            coverCache.put(key, bitmap);
            if (!key.equals(image.getTag())) return;
            runOnUiThread(
                () -> {
                  if (key.equals(image.getTag())) image.setImageBitmap(bitmap);
                });
          }
        });
  }

  String shelfTitle(String title) {
    return Order.shelfTitle(title, prefs.getBoolean("hideVolumeTags", true));
  }

  void detailPane(LinearLayout pane) {
    List<Library.Book> volumes = groups.get(selected);
    String name = seriesDisplayName(selected, volumes);
    boolean hideTags = prefs.getBoolean("hideVolumeTags", true);
    LinearLayout title = row();
    pad(title, 16, 10);
    pane.addView(title);
    TextView t = label(name, 23, ink, true);
    t.setMaxLines(2);
    weight(title, t);
    title.addView(label(volumes.size() + "권", 13, muted, true));
    List<CoverItem> covers = new ArrayList<>(volumes.size());
    for (int i = 0; i < volumes.size(); i++) {
      Library.Book b = volumes.get(i);
      String status =
          b.missing
              ? "파일 위치 확인 필요"
              : !b.error.isEmpty()
                  ? "파일 확인 필요"
                  : b.done
                      ? "완독"
                      : b.opened > 0
                          ? (b.page + 1) + " / " + b.pages + "쪽"
                          : b.pages > 0 ? b.pages + "쪽" : "색인 대기";
      covers.add(
          new CoverItem(
              b, Order.volumeDisplayTitle(name, b.title, b.comicNumber, hideTags), status));
    }
    coverGrid(pane, covers, true);
  }

  void pick(String title, String key, String[] labels, Runnable after) {
    int def = key.equals("size") ? 1 : key.equals("tapEffect") ? 2 : 0;
    dialog()
        .setTitle(title)
        .setSingleChoiceItems(
            labels,
            prefs.getInt(key, def),
            (d, i) -> {
              prefs.edit().putInt(key, i).apply();
              d.dismiss();
              after.run();
            })
        .setNegativeButton("닫기", null)
        .show();
  }

  void addMenu() {
    choices(
        "책 추가",
        new String[] {"기기 폴더 연결", "ZIP · CBZ 파일 선택", "Windows 공유 폴더 연결"},
        i -> {
          if (i == 2) {
            smb();
            return;
          }
          Intent in =
              new Intent(i == 0 ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
          if (i == 1) {
            in.setType("*/*");
            in.addCategory(Intent.CATEGORY_OPENABLE);
            in.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
          }
          in.addFlags(
              Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
          startActivityForResult(in, 41 + i);
        });
  }

  protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (code == AppUpdates.ALLOW_INSTALL) {
      updates.afterPermission();
      return;
    }
    if (result != RESULT_OK || data == null || code < 41 || code > 42) return;
    ArrayList<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int i = 0; i < data.getClipData().getItemCount(); i++)
        uris.add(data.getClipData().getItemAt(i).getUri());
    } else if (data.getData() != null) uris.add(data.getData());
    List<Long> ids = new ArrayList<>();
    try {
      for (Uri uri : uris) {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ids.add(db.register(uri.toString(), "", ""));
      }
      scan(ids);
    } catch (Exception e) {
      alert("파일 등록", Library.reason(e));
    }
  }

  void smb() {
    LinearLayout box = col();
    pad(box, 22, 8);
    String[] hints = {"서버 주소 또는 IP", "공유 폴더 (예: Comics/만화)", "계정 또는 도메인\\계정", "비밀번호"};
    EditText[] e = new EditText[4];
    for (int i = 0; i < 4; i++) {
      e[i] = new EditText(this);
      e[i].setSingleLine();
      e[i].setHint(hints[i]);
      box.addView(e[i]);
    }
    e[3].setInputType(129);
    dialog()
        .setTitle("Windows 공유 폴더")
        .setView(box)
        .setPositiveButton(
            "연결",
            (d, w) -> {
              String server = e[0].getText().toString().trim(),
                  share =
                      e[1].getText().toString().trim().replace('\\', '/').replaceAll("^/+|/+$", "");
              if (!server.matches("[a-zA-Z0-9.\\-:\\[\\]]+") || share.isEmpty()) {
                alert("주소 확인", "서버 주소와 공유 폴더 이름을 입력하세요.");
                return;
              }
              try {
                long id =
                    db.register(
                        "smb://" + server + "/" + share + "/",
                        e[2].getText().toString(),
                        e[3].getText().toString());
                db.reconnect(id);
                scan(Collections.singletonList(id));
              } catch (Exception ex) {
                alert("연결 실패", Library.reason(ex));
              }
            })
        .setNegativeButton("취소", null)
        .show();
  }

  final Runnable refresh =
      () -> {
        if (!isDestroyed() && reader == null) library(true);
      };

  final Set<Long> scanning = new HashSet<>();

  boolean refreshSources(boolean pulled) {
    if (!scanning.isEmpty()) return false;
    List<Long> ids = new ArrayList<>();
    for (String s : db.strings("SELECT id FROM sources")) ids.add(Long.valueOf(s));
    if (ids.isEmpty()) {
      if (pulled) toast("연결된 폴더가 없습니다.");
      else addMenu();
      return false;
    }
    scan(ids);
    return true;
  }

  void scan(List<Long> ids) {
    List<Long> batch = new ArrayList<>();
    for (long id : ids) if (scanning.add(id)) batch.add(id);
    if (batch.isEmpty()) return;
    scanMessage = "책과 표지를 확인하고 있습니다…";
    updateScanBanner();
    worker.execute(
        () -> {
          ArrayList<String> problems = new ArrayList<>();
          for (long id : batch)
            try {
              db.scan(
                  id,
                  () -> {
                    handler.removeCallbacks(refresh);
                    handler.postDelayed(refresh, 650);
                  });
            } catch (Exception e) {
              problems.add(Library.reason(e));
            }
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                scanning.removeAll(batch);
                if (scanning.isEmpty()) scanMessage = "";
                library(true);
                if (!problems.isEmpty()) alert("폴더를 읽을 수 없습니다", TextUtils.join("\n", problems));
              });
        });
  }

  void settings() {
    choices(
        "서재 설정",
        new String[] {
          "연결 폴더 다시 읽기",
          "책장 관리",
          "표지 크기 · " + coverWidthDp() + "dp",
          "별점순 표시하기 · " + (groupByRating() ? "켜짐" : "꺼짐"),
          "이어 읽기 플로팅 · " + (showFloatingResume() ? "켜짐" : "꺼짐"),
          "화면 테마",
          "임시 파일 정리",
          "앱 업데이트 확인",
          "책 이름만 표시 · " + (prefs.getBoolean("hideVolumeTags", true) ? "켜짐" : "꺼짐"),
          "작품 수 표시 · " + (showShelfCount() ? "켜짐" : "꺼짐")
        },
        i -> {
          if (i == 0) refreshSources(false);
          if (i == 1) shelves();
          if (i == 2) showThumbSizeSlider();
          if (i == 3) {
            boolean next = !groupByRating();
            prefs.edit().putBoolean("groupByRating", next).apply();
            gridIndex = gridOffset = 0;
            renderNow(false);
            toast(next ? "별점순으로 구분해 표시합니다." : "별점 구분 없이 전체 목록으로 표시합니다.");
          }
          if (i == 4) {
            boolean next = !showFloatingResume();
            prefs.edit().putBoolean("showFloatingResume", next).apply();
            resumeDismissed = false;
            renderNow(true);
            toast(next ? "이어 읽기 플로팅을 표시합니다." : "이어 읽기 플로팅을 숨깁니다.");
          }
          if (i == 5)
            pick("화면 테마", "theme", new String[] {"기기 설정", "밝게", "어둡게"}, () -> renderNow(true));
          if (i == 7) updates.check(true);
          if (i == 8) {
            boolean hide = !prefs.getBoolean("hideVolumeTags", true);
            prefs.edit().putBoolean("hideVolumeTags", hide).apply();
            renderNow(true);
            toast(hide ? "앞뒤 [문구]와 파일 표기를 숨깁니다." : "파일 이름을 그대로 표시합니다.");
          }
          if (i == 9) {
            boolean next = !showShelfCount();
            prefs.edit().putBoolean("showShelfCount", next).apply();
            renderNow(true);
            toast(next ? "책장에 작품 수를 표시합니다." : "책장에서 작품 수를 숨깁니다.");
          }
          if (i == 6) {
            worker.execute(
                () -> {
                  Archive.clearSpools(this);
                  coverCache.evictAll();
                  runOnUiThread(() -> toast("임시 파일을 정리했습니다."));
                });
          }
        });
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  void shelves() {
    List<String> items = new ArrayList<>();
    items.add("전체 책장");
    items.addAll(db.strings("SELECT name FROM shelves ORDER BY name"));
    int n = items.size();
    items.add("새 책장 만들기");
    if (!shelf.isEmpty()) items.add("현재 책장 이름 변경");
    choices(
        "책장",
        items.toArray(new String[0]),
        i -> {
          if (i < n) {
            shelf = i == 0 ? "" : items.get(i);
            selected = "";
            gridIndex = gridOffset = 0;
            renderNow(false);
          } else
            input(
                i == n ? "새 책장" : "책장 이름 변경",
                i == n ? "" : shelf,
                s -> {
                  if (s.isEmpty()) return;
                  if (i != n && db.strings("SELECT name FROM shelves WHERE name=?", s).size() > 0) {
                    toast("같은 이름의 책장이 있습니다.");
                    return;
                  }
                  if (i == n) db.exec("INSERT OR IGNORE INTO shelves VALUES(?)", s);
                  else {
                    db.exec("UPDATE shelves SET name=? WHERE name=?", s, shelf);
                    db.exec("UPDATE books SET shelf=? WHERE shelf=?", s, shelf);
                  }
                  shelf = s;
                  selected = "";
                  library(false);
                });
        });
  }

  void pickRating(Library.Book b) {
    int current = groupRating(b.grp);
    String[] labels = {
      "★★★★★  5점",
      "★★★★☆  4점",
      "★★★☆☆  3점",
      "★★☆☆☆  2점",
      "★☆☆☆☆  1점",
      "☆☆☆☆☆  0점"
    };
    int checked = 5 - Math.max(0, Math.min(5, current));
    dialog()
        .setTitle("별점 설정")
        .setSingleChoiceItems(
            labels,
            checked,
            (d, i) -> {
              int next = 5 - i;
              db.exec("UPDATE books SET rating=? WHERE grp=?", next, b.grp);
              List<Library.Book> group = groups.get(b.grp);
              if (group != null) {
                for (Library.Book item : group) item.rating = next;
                rebuildEntry(b.grp);
              }
              d.dismiss();
              renderNow(true);
            })
        .setNegativeButton("닫기", null)
        .show();
  }

  void manage(Library.Book b) {
    int rating = groupRating(b.grp);
    choices(
        volumeTitle(b),
        new String[] {
          "별점 설정  ·  " + stars(rating),
          "표지 변경",
          "시리즈 대표 표지로 지정",
          b.fav ? "즐겨찾기 해제" : "즐겨찾기",
          b.done ? "읽지 않음으로 표시" : "완독으로 표시",
          "책장에 배치",
          "시리즈명 변경",
          "다른 시리즈에 합치기",
          "이 권을 별도 시리즈로 분리"
        },
        i -> {
          if (i == 0) {
            pickRating(b);
            return;
          }
          if (i == 1) {
            chooseCover(b);
            return;
          }
          if (i == 2) {
            db.exec("UPDATE books SET rep=(id=?) WHERE grp=?", b.id, b.grp);
            List<Library.Book> group = groups.get(b.grp);
            if (group != null) {
              for (Library.Book item : group) item.rep = (item.id == b.id);
              rebuildEntry(b.grp);
            }
            renderNow(true);
            return;
          }
          if (i == 3) {
            b.fav = !b.fav;
            db.exec("UPDATE books SET fav=? WHERE id=?", b.fav ? 1 : 0, b.id);
            rebuildEntry(b.grp);
            renderNow(true);
            return;
          }
          if (i == 4) {
            boolean wasDone = b.done;
            b.done = !wasDone;
            if (wasDone) {
              b.opened = 0;
              b.page = 0;
            }
            db.exec(
                "UPDATE books SET done=?,opened=CASE WHEN ? THEN 0 ELSE opened END,page=CASE WHEN ?"
                    + " THEN 0 ELSE page END WHERE id=?",
                b.done ? 1 : 0,
                wasDone ? 1 : 0,
                wasDone ? 1 : 0,
                b.id);
            rebuildEntry(b.grp);
            renderNow(true);
            return;
          }
          if (i == 5) {
            List<String> list = db.strings("SELECT name FROM shelves ORDER BY name");
            list.add(0, "책장 지정 해제");
            list.add("새 책장…");
            choices(
                "작품을 배치할 책장",
                list.toArray(new String[0]),
                p -> {
                  if (p == list.size() - 1) {
                    input(
                        "새 책장",
                        "",
                        s -> {
                          if (!s.isEmpty()) {
                            db.exec("INSERT OR IGNORE INTO shelves VALUES(?)", s);
                            db.exec("UPDATE books SET shelf=? WHERE grp=?", s, b.grp);
                            List<Library.Book> group = groups.get(b.grp);
                            if (group != null) {
                              for (Library.Book item : group) item.shelf = s;
                              rebuildEntry(b.grp);
                            }
                            renderNow(true);
                          }
                        });
                  } else {
                    String nextShelf = p == 0 ? "" : list.get(p);
                    db.exec("UPDATE books SET shelf=? WHERE grp=?", nextShelf, b.grp);
                    List<Library.Book> group = groups.get(b.grp);
                    if (group != null) {
                      for (Library.Book item : group) item.shelf = nextShelf;
                      rebuildEntry(b.grp);
                    }
                    renderNow(true);
                  }
                });
            return;
          }
          if (i == 6) {
            input(
                "시리즈명 변경",
                b.series,
                s -> {
                  if (!s.isEmpty()) {
                    db.exec("UPDATE books SET manualGroup=1,series=? WHERE grp=?", s, b.grp);
                    List<Library.Book> group = groups.get(b.grp);
                    if (group != null) {
                      for (Library.Book item : group) {
                        item.manualGroup = true;
                        item.series = s;
                      }
                      rebuildEntry(b.grp);
                    }
                    renderNow(true);
                  }
                });
            return;
          }
          if (i == 7) {
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.remove(b.grp);
            if (keys.isEmpty()) {
              toast("합칠 다른 작품이 없습니다.");
              return;
            }
            String[] names = new String[keys.size()];
            for (int k = 0; k < names.length; k++) {
              String key = keys.get(k);
              names[k] = seriesDisplayName(key, groups.get(key));
            }
            choices(
                "합칠 시리즈",
                names,
                k -> {
                  Library.Book dest = groups.get(keys.get(k)).get(0);
                  int mergedRating = Math.max(groupRating(dest.grp), groupRating(b.grp));
                  db.exec("UPDATE books SET manualGroup=1,rating=? WHERE grp=?", mergedRating, dest.grp);
                  db.exec(
                      "UPDATE books SET manualGroup=1,grp=?,series=?,shelf=?,rating=?,rep=0 WHERE"
                          + " grp=?",
                      dest.grp,
                      dest.series,
                      dest.shelf,
                      mergedRating,
                      b.grp);
                  selected = dest.grp;
                  library(true);
                });
            return;
          }
          if (i == 8) {
            db.exec(
                "UPDATE books SET manualGroup=1,grp=?,series=title,rep=0 WHERE id=?",
                "manual:" + b.id,
                b.id);
            selected = "manual:" + b.id;
          }
          library(true);
        });
  }

  void chooseCover(Library.Book b) {
    readerIO.execute(
        () -> {
          try {
            Archive a = db.open(b);
            runOnUiThread(() -> coverDialog(b, a));
          } catch (Exception ex) {
            runOnUiThread(() -> alert("표지 변경", Library.reason(ex)));
          }
        });
  }

  void coverDialog(Library.Book book, Archive a) {
    if (isDestroyed()) {
      try {
        a.close();
      } catch (Exception ignored) {
      }
      return;
    }
    ExecutorService queue = Executors.newSingleThreadExecutor();
    boolean[] active = {true};
    GridView g = new GridView(this);
    g.setNumColumns(GridView.AUTO_FIT);
    g.setColumnWidth(dp(92));
    pad(g, 6, 6);
    g.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return a.pages.size();
          }

          public Object getItem(int p) {
            return a.name(p);
          }

          public long getItemId(int p) {
            return p;
          }

          public View getView(int p, View old, ViewGroup parent) {
            LinearLayout tile = col();
            ImageView img = new ImageView(Main.this);
            tile.addView(img, -1, dp(110));
            TextView n = label((p + 1) + "쪽", 12, muted, false);
            n.setGravity(17);
            tile.addView(n);
            queue.execute(
                () -> {
                  if (!active[0]) return;
                  try {
                    Bitmap bitmap = a.decode(p, 120, 170);
                    runOnUiThread(
                        () -> {
                          if (active[0]) img.setImageBitmap(bitmap);
                        });
                  } catch (Exception ignored) {
                  }
                });
            return tile;
          }
        });
    AlertDialog dlg =
        dialog().setTitle("표지로 쓸 페이지").setView(g).setNegativeButton("닫기", null).create();
    g.setOnItemClickListener(
        (p, v, i, id) -> {
          queue.execute(
              () -> {
                try {
                  Bitmap bitmap = a.decode(i, 360, 500);
                  db.writeCover(book.id, bitmap);
                  db.exec("UPDATE books SET cover=? WHERE id=?", a.name(i), book.id);
                  runOnUiThread(() -> library(true));
                } catch (Exception e) {
                  runOnUiThread(() -> alert("표지 저장 실패", Library.reason(e)));
                }
              });
          dlg.dismiss();
        });
    dlg.setOnDismissListener(
        d -> {
          active[0] = false;
          queue.execute(
              () -> {
                try {
                  a.close();
                } catch (Exception ignored) {
                }
              });
          queue.shutdown();
        });
    dlg.show();
  }

  void open(Library.Book b) {
    open(b, false);
  }

  void open(Library.Book b, boolean fromEnd) {
    remember();
    resumeDismissed = false;
    int token = ++requestId;
    loadingBook = true;
    scanMessage = "책을 여는 중…";
    if (reader == null) updateScanBanner();
    readerIO.execute(
        () -> {
          try {
            Archive a = db.open(b);
            if (a.comicInfo != null && b.comicSeries.isEmpty() && b.comicNumber.isEmpty()) {
              db.applyComicInfo(b, a);
              b.comicSeries = a.comicInfo.series;
              b.comicNumber = a.comicInfo.number;
            }
            runOnUiThread(
                () -> {
                  if (isDestroyed() || token != requestId) {
                    try {
                      a.close();
                    } catch (Exception ignored) {
                    }
                    return;
                  }
                  loadingBook = false;
                  scanMessage = "";
                  if (reader != null) reader.close();
                  grid = null;
                  shelfList = null;
                  sectionAdapter = null;
                  scanBanner = null;
                  frame.removeAllViews();
                  if (fromEnd) b.page = Math.max(0, a.pages.size() - 1);
                  reader = new Reader(this, a, b);
                  frame.addView(reader, -1, -1);
                  overlay = topBar = null;
                  immersive();
                  b.pages = a.pages.size();
                  b.opened = System.currentTimeMillis();
                  db.exec(
                      "UPDATE books SET pages=?,opened=? WHERE id=?",
                      b.pages,
                      b.opened,
                      b.id);
                  reader.save();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (isDestroyed() || token != requestId) return;
                  loadingBook = false;
                  scanMessage = "";
                  updateScanBanner();
                  alert("책을 열 수 없습니다", Library.reason(e));
                });
          }
        });
  }

  static final int READER_LAYOUT =
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE
          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

  void insetMenu(View bar, boolean top) {
    int edge = bar.getPaddingLeft(), vertical = bar.getPaddingTop();
    ViewCompat.setOnApplyWindowInsetsListener(
        bar,
        (view, windowInsets) -> {
          Insets safe =
              Insets.max(
                  windowInsets.getInsets(
                      WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()),
                  windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()));
          view.setPadding(
              edge + safe.left,
              vertical + (top ? safe.top : 0),
              edge + safe.right,
              vertical + (top ? 0 : safe.bottom));
          return windowInsets;
        });
  }

  void immersive() {
    readerImmersive = true;
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    WindowInsetsControllerCompat ctrl =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    ctrl.hide(WindowInsetsCompat.Type.systemBars());
    if (Build.VERSION.SDK_INT < 30) {
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              READER_LAYOUT
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
  }

  void toggleReaderMenu() {
    if (reader == null) return;
    if (overlay != null) {
      frame.removeView(overlay);
      frame.removeView(topBar);
      overlay = topBar = null;
      immersive();
      return;
    }
    readerImmersive = false;
    int fg = 0xfff6f7f4;
    LinearLayout top = row();
    top.setClickable(true);
    top.setBackgroundColor(0xee202421);
    pad(top, 6, 2);
    icon(top, "back", "서재로", fg, this::leaveReader);
    TextView title = label(volumeTitle(reader.book), 16, fg, true);
    one(title);
    weight(top, title);
    directionButton = action("", this::pickDirection);
    directionButton.setTextColor(fg);
    top.addView(directionButton);
    icon(top, "more", "읽기 설정", fg, this::readerSettings);
    topBar = top;
    insetMenu(top, true);
    frame.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    LinearLayout bottom = row();
    bottom.setClickable(true);
    bottom.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    pad(bottom, 8, 2);
    bottom.setBackgroundColor(0xee202421);
    indicator = label("", 13, fg, true);
    indicator.setSingleLine();
    bottom.addView(indicator);
    seek = new SeekBar(this);
    seek.setContentDescription("페이지 탐색");
    seek.setMax(reader.count() - 1);
    bottom.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1));
    TextView mark = action("책갈피", this::marks);
    mark.setTextColor(fg);
    pad(mark, 6, 8);
    mark.setMinWidth(dp(48));
    bottom.addView(mark);
    TextView opt = action("보기 설정", this::readerSettings);
    opt.setTextColor(fg);
    pad(opt, 6, 8);
    bottom.addView(opt);
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {
            indicator.setWidth(indicator.getWidth());
          }

          public void onStopTrackingTouch(SeekBar s) {
            indicator.setMinWidth(0);
            indicator.setMaxWidth(Integer.MAX_VALUE);
            if (reader != null) reader.seek(s.getProgress());
            readerStatus();
          }

          public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (user && reader != null) indicator.setText((p + 1) + " / " + reader.count());
          }
        });
    insetMenu(bottom, false);
    overlay = bottom;
    frame.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    // Edge-to-edge를 유지해 일반 세로 모드에서도 네비게이션 바가 만화 화면을 밀어올리지 않고 위에 겹쳐 표시된다.
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    WindowInsetsControllerCompat ctrl =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    ctrl.setAppearanceLightStatusBars(false);
    ctrl.setAppearanceLightNavigationBars(false);
    ctrl.show(WindowInsetsCompat.Type.systemBars());
    if (Build.VERSION.SDK_INT < 30) {
      getWindow().getDecorView().setSystemUiVisibility(READER_LAYOUT);
    }
    ViewCompat.requestApplyInsets(frame);
    readerStatus();
  }

  void readerStatus() {
    if (reader == null || overlay == null) return;
    int[] s = reader.spread(reader.page);
    directionButton.setText(reader.rtl ? "읽기 방향  ←" : "읽기 방향  →");
    directionButton.setContentDescription("읽기 방향  " + directionLabel(reader.rtl));
    String range = s.length == 1 ? "" + (s[0] + 1) : (s[0] + 1) + "–" + (s[1] + 1);
    indicator.setText(
        range
            + " / "
            + reader.count()
            + " · "
            + Math.round(100f * (s[s.length - 1] + 1) / reader.count())
            + "%");
    seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    seek.setScaleX(reader.rtl ? -1f : 1f);
    seek.setProgress(reader.page);
  }

  TextView directionButton;

  String directionLabel(boolean rtl) {
    return rtl ? "←  오른쪽에서 왼쪽" : "→  왼쪽에서 오른쪽";
  }

  void pickDirection() {
    AlertDialog.Builder builder = dialog();
    LinearLayout box = col();
    pad(box, 20, 6);
    RadioGroup directions = new RadioGroup(builder.getContext());
    box.addView(directions);
    RadioButton right = new RadioButton(builder.getContext()),
        left = new RadioButton(builder.getContext());
    right.setId(View.generateViewId());
    left.setId(View.generateViewId());
    right.setText(directionLabel(true));
    left.setText(directionLabel(false));
    directions.addView(right);
    directions.addView(left);
    directions.check(reader.rtl ? right.getId() : left.getId());
    CheckBox only = new CheckBox(builder.getContext());
    only.setText("이 작품에만 적용 · 모든 권");
    only.setChecked(prefs.contains(reader.directionKey()));
    box.addView(only);
    only.setOnCheckedChangeListener(
        (v, checked) -> {
          if (!checked)
            directions.check(prefs.getBoolean("rtl", true) ? right.getId() : left.getId());
        });
    builder
        .setTitle("읽기 방향")
        .setView(box)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "저장", (v, i) -> reader.saveDirection(right.isChecked(), only.isChecked()))
        .show();
  }

  String tapZoneSummary() {
    float l = reader != null ? reader.tapLeft : prefs.getFloat("tapLeft", 0.33f);
    float r = reader != null ? reader.tapRight : prefs.getFloat("tapRight", 0.67f);
    int lp = Math.round(l * 100f);
    int rp = Math.round((1f - r) * 100f);
    int cp = Math.max(0, 100 - lp - rp);
    return lp + "% · " + cp + "% · " + rp + "%";
  }

  void openTouchZoneEditor() {
    if (reader == null || frame == null) return;
    if (overlay != null) toggleReaderMenu();
    closeTouchZoneEditor();
    touchZoneOverlay =
        new TouchZoneOverlay(
            this,
            reader.tapLeft,
            reader.tapRight,
            reader.rtl,
            reader.doubleZoom,
            new TouchZoneOverlay.Listener() {
              public void onSave(float left, float right) {
                float[] clamped = Reader.clampTapZones(left, right);
                prefs.edit().putFloat("tapLeft", clamped[0]).putFloat("tapRight", clamped[1]).apply();
                if (reader != null) reader.readGestures();
                closeTouchZoneEditor();
                toast("터치 범위를 저장했습니다.");
              }

              public void onCancel() {
                closeTouchZoneEditor();
              }
            });
    frame.addView(touchZoneOverlay, new FrameLayout.LayoutParams(-1, -1));
    touchZoneOverlay.requestApplyInsets();
  }

  void closeTouchZoneEditor() {
    if (touchZoneOverlay != null) {
      if (frame != null) frame.removeView(touchZoneOverlay);
      touchZoneOverlay = null;
    }
  }

  void readerSettings() {
    choices(
        "읽기 설정",
        new String[] {
          "페이지 구성",
          "읽기 방향   " + directionLabel(reader.rtl),
          "넘김 효과",
          "이미지 맞춤",
          reader.solo ? "이 시리즈 첫 장 단독 표시  ✓" : "이 시리즈 첫 장 단독 표시",
          "본문 배경색",
          prefs.getBoolean("autoNext", true) ? "다음 권 자동 열기  ✓" : "다음 권 자동 열기",
          "다음 권 읽기",
          "터치 범위 설정   " + tapZoneSummary(),
          "터치·제스처 설정"
        },
        i -> {
          if (i == 0)
            pick(
                "페이지 구성",
                "mode",
                new String[] {"화면에 맞춰 자동", "한 쪽", "양면", "세로 연속"},
                reader::optionsChanged);
          if (i == 1) pickDirection();
          if (i == 2) turnEffects();
          if (i == 3)
            pick("이미지 맞춤", "fit", new String[] {"화면 맞춤", "너비 맞춤", "높이 맞춤"}, reader::optionsChanged);
          if (i == 4) {
            prefs.edit().putBoolean(reader.firstPageKey(), !reader.solo).apply();
            reader.optionsChanged();
          }
          if (i == 5)
            pick("본문 배경색", "paper", new String[] {"먹색", "종이색", "흰색"}, reader::optionsChanged);
          if (i == 6) {
            prefs.edit().putBoolean("autoNext", !prefs.getBoolean("autoNext", true)).apply();
            toast(prefs.getBoolean("autoNext", true) ? "다음 권을 자동으로 엽니다." : "다음 권을 열기 전에 확인합니다.");
          }
          if (i == 7) nextVolume();
          if (i == 8) openTouchZoneEditor();
          if (i == 9) gestureSettings();
        });
  }

  void turnEffects() {
    choices(
        "페이지 넘김 효과",
        new String[] {"드래그로 넘길 때", "탭으로 넘길 때"},
        i ->
            pick(
                i == 0 ? "드래그로 넘길 때" : "탭으로 넘길 때",
                i == 0 ? "effect" : "tapEffect",
                new String[] {"종이 넘김", "슬라이드", "즉시 전환"},
                reader::optionsChanged));
  }

  void gestureSettings() {
    AlertDialog.Builder builder = dialog();
    ScrollView scroll = new ScrollView(builder.getContext());
    LinearLayout box = col();
    pad(box, 20, 4);
    scroll.addView(box);
    addGestureSwitch(box, "좌우 탭으로 페이지 넘기기", "tapTurn", true);
    box.addView(action("페이지 넘김 효과 설정", this::turnEffects));
    addGestureSwitch(box, "좌우 스와이프로 페이지 넘기기", "swipeTurn", true);
    addGestureSwitch(box, "가운데 더블 탭으로 확대·축소", "doubleZoom", false);
    addGestureSwitch(box, "두 손가락으로 확대·축소", "pinchZoom", true);
    addGestureSwitch(box, "왼쪽 가장자리에서 위아래로 밝기 조절", "brightnessGesture", false);
    final AlertDialog[] holder = new AlertDialog[1];
    box.addView(
        action(
            "터치 범위 설정 (" + tapZoneSummary() + ")",
            () -> {
              if (holder[0] != null) holder[0].dismiss();
              openTouchZoneEditor();
            }));
    holder[0] = builder.setTitle("터치·제스처 설정").setView(scroll).setPositiveButton("완료", null).show();
  }

  void addGestureSwitch(LinearLayout box, String title, String key, boolean initial) {
    Switch control = new Switch(dialog().getContext());
    control.setText(title);
    control.setTextSize(15);
    control.setTextColor(ink);
    control.setMinHeight(dp(48));
    control.setSwitchPadding(dp(10));
    control.setChecked(prefs.getBoolean(key, initial));
    box.addView(control, -1, -2);
    control.setOnCheckedChangeListener(
        (view, on) -> {
          prefs.edit().putBoolean(key, on).apply();
          reader.readGestures();
        });
  }

  void marks() {
    long id = reader.book.id;
    int page = reader.page;
    List<String> marks = db.strings("SELECT page FROM marks WHERE book=? ORDER BY page", "" + id);
    boolean exists = marks.contains("" + page);
    List<String> labels = new ArrayList<>();
    labels.add(exists ? "현재 책갈피 삭제" : "현재 쪽에 책갈피 추가");
    for (String mark : marks) labels.add((Integer.parseInt(mark) + 1) + "쪽");
    choices(
        "책갈피",
        labels.toArray(new String[0]),
        i -> {
          if (i > 0) reader.seek(Integer.parseInt(marks.get(i - 1)));
          else {
            if (exists) db.exec("DELETE FROM marks WHERE book=? AND page=?", id, page);
            else db.exec("INSERT OR IGNORE INTO marks VALUES(?,?)", id, page);
            toast(exists ? "책갈피를 삭제했습니다." : "책갈피를 추가했습니다.");
          }
        });
  }

  void nextVolume() {
    if (reader == null || loadingBook) return;
    List<Library.Book> group = groups.get(reader.book.grp);
    if (group != null)
      for (int i = 0; i + 1 < group.size(); i++)
        if (group.get(i).id == reader.book.id) {
          Library.Book next = group.get(i + 1);
          if (prefs.getBoolean("autoNext", true)) open(next);
          else
            dialog()
                .setTitle("다음 권 읽기")
                .setMessage(next.title)
                .setPositiveButton("열기", (d, w) -> open(next))
                .setNegativeButton("취소", null)
                .show();
          return;
        }
    toast("마지막 권입니다.");
  }

  void previousVolume() {
    if (reader == null || loadingBook) return;
    List<Library.Book> group = groups.get(reader.book.grp);
    if (group != null)
      for (int i = 1; i < group.size(); i++)
        if (group.get(i).id == reader.book.id) {
          Library.Book prev = group.get(i - 1);
          if (prefs.getBoolean("autoNext", true)) open(prev, true);
          else
            dialog()
                .setTitle("이전 권 읽기")
                .setMessage(prev.title)
                .setPositiveButton("열기", (d, w) -> open(prev, true))
                .setNegativeButton("취소", null)
                .show();
          return;
        }
    toast("첫 권입니다.");
  }

  void leaveReader() {
    closeTouchZoneEditor();
    requestId++;
    String closedGrp = reader.book.grp;
    reader.close();
    reader = null;
    overlay = topBar = null;
    if (groups.containsKey(closedGrp)) {
      rebuildEntry(closedGrp);
      renderLibrary();
    } else {
      library(false);
    }
  }

  protected void onPause() {
    if (reader != null) reader.storeScroll();
    super.onPause();
  }

  public void onBackPressed() {
    if (touchZoneOverlay != null) {
      closeTouchZoneEditor();
      return;
    }
    if (reader != null) {
      leaveReader();
      return;
    }
    if (loadingBook) {
      requestId++;
      loadingBook = false;
      scanMessage = "";
      updateScanBanner();
      return;
    }
    if (arrangeMode) {
      arrangeMode = false;
      renderNow(true);
      return;
    }
    if (!selected.isEmpty()) {
      selected = "";
      renderNow(false);
      return;
    }
    if (!query.isEmpty()) {
      query = "";
      renderNow(false);
      return;
    }
    super.onBackPressed();
  }

  protected void onDestroy() {
    if (dragController != null) dragController.cancel();
    if (reader != null) reader.close();
    handler.removeCallbacks(refresh);
    if (pendingThumbResize != null) handler.removeCallbacks(pendingThumbResize);
    worker.shutdown();
    thumbs.shutdown();
    readerIO.shutdown();
    snapshots.shutdown();
    super.onDestroy();
  }
}
