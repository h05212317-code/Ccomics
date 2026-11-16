package kr.ccomics;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class Main extends Activity {
  AppUpdates updates;
  Library db;
  SharedPreferences prefs;
  final ExecutorService worker = Executors.newSingleThreadExecutor(),
      readerIO = Executors.newSingleThreadExecutor(),
      thumbs = Executors.newFixedThreadPool(2);
  final LruCache<String, Bitmap> coverCache =
      new LruCache<String, Bitmap>(16 << 20) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  final Handler handler = new Handler(Looper.getMainLooper());
  FrameLayout frame;
  LinearLayout root;
  GridView grid;
  Reader reader;
  View overlay, topBar;
  TextView indicator;
  SeekBar seek;
  List<Library.Book> books = new ArrayList<>();
  Map<String, List<Library.Book>> groups = new LinkedHashMap<>();
  String selected = "", shelf = "", query = "", scanMessage = "";
  int filter, requestId, gridIndex, gridOffset, volumeIndex, volumeOffset;
  boolean busy, loadingBook, dark;
  int bg, ink, muted, surface, accent;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    db = new Library(getApplicationContext());
    prefs = getSharedPreferences("options", 0);
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
    }
    frame = new FrameLayout(this);
    setContentView(frame);
    library(false);
    if (state == null) updates.check(false);
    if (state != null && state.getLong("reading") > 0) {
      Library.Book b = db.get(state.getLong("reading"));
      if (b != null) open(b);
    }
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
    if (reader != null) b.putLong("reading", reader.book.id);
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    if (reader == null) library(true);
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
    bar.addView(v, dp(44), dp(48));
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
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  void remember() {
    if (grid != null && grid.getChildCount() > 0) {
      if (Boolean.TRUE.equals(grid.getTag())) {
        volumeIndex = grid.getFirstVisiblePosition();
        volumeOffset = grid.getChildAt(0).getTop() - grid.getPaddingTop();
        return;
      }
      gridIndex = grid.getFirstVisiblePosition();
      gridOffset = grid.getChildAt(0).getTop() - grid.getPaddingTop();
    }
  }

  final ExecutorService snapshots = Executors.newSingleThreadExecutor();
  int snapshotId;

  void library(boolean keep) {
    if (isDestroyed() || reader != null) return;
    if (keep) remember();
    int token = ++snapshotId;
    snapshots.execute(
        () -> {
          List<Library.Book> all = db.books();
          Map<String, List<Library.Book>> sorted = new LinkedHashMap<>();
          for (Library.Book b : all) sorted.computeIfAbsent(b.grp, k -> new ArrayList<>()).add(b);
          for (List<Library.Book> group : sorted.values())
            group.sort((a, b) -> Order.natural(a.title, b.title));
          runOnUiThread(
              () -> {
                if (isDestroyed() || token != snapshotId) return;
                books = all;
                groups = sorted;
                if (reader == null) renderLibrary();
              });
        });
  }

  void renderLibrary() {
    grid = null;
    theme();
    frame.removeAllViews();
    if (!groups.containsKey(selected)) selected = "";
    root = col();
    frame.addView(root, -1, -1);
    LinearLayout bar = row();
    pad(bar, 8, 3);
    root.addView(bar, -1, dp(54));
    boolean showFilters = selected.isEmpty();
    if (!showFilters)
      icon(
          bar,
          "back",
          "책장으로",
          ink,
          () -> {
            selected = "";
            library(false);
          });
    HorizontalScrollView sc = new HorizontalScrollView(this);
    sc.setHorizontalScrollBarEnabled(false);
    LinearLayout tabs = row();
    pad(tabs, 3, 0);
    sc.addView(tabs);
    bar.addView(sc, new LinearLayout.LayoutParams(0, -2, 1));
    if (showFilters) {
      String[] labels = {"전체", "읽는 중", "완독", "즐겨찾기"};
      for (int i = 0; i < labels.length; i++) {
        final int index = i;
        TextView tab =
            action(
                labels[i],
                () -> {
                  filter = index;
                  selected = "";
                  gridIndex = 0;
                  library(false);
                });
        pad(tab, 7, 0);
        tab.setSingleLine();
        tab.setSelected(filter == i);
        tab.setTextColor(filter == i ? bg : muted);
        if (filter == i) tab.setBackground(shape(ink, 22));
        tabs.addView(tab);
      }
    }
    tabs.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    int available =
        dp(getResources().getConfiguration().screenWidthDp - 16 - (showFilters ? 0 : 44))
            - tabs.getMeasuredWidth();
    boolean showSearch = available >= dp(176), showAdd = available >= dp(132);
    if (showSearch) icon(bar, "search", "책 검색", ink, this::searchLibrary);
    if (showFilters)
      icon(
          bar,
          "sort",
          "정렬",
          ink,
          () -> pick("정렬", "sort", new String[] {"제목순", "최근 읽은순", "최근 추가순"}, () -> library(true)));
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
    if (!scanMessage.isEmpty()) {
      TextView t = label(scanMessage, 13, accent, true);
      pad(t, 20, 6);
      root.addView(t);
    }
    LinearLayout body = row();
    body.setGravity(Gravity.TOP);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout pane = col();
    SwipeRefreshLayout pull = new SwipeRefreshLayout(this);
    body.addView(pull, -1, -1);
    pull.addView(pane, -1, -1);
    pull.setColorSchemeColors(accent);
    pull.setProgressBackgroundColorSchemeColor(surface);
    pull.setOnChildScrollUpCallback(
        (parent, child) -> grid != null && grid.canScrollVertically(-1));
    pull.setOnRefreshListener(
        () -> {
          if (!refreshSources(true)) pull.setRefreshing(false);
        });
    pull.setRefreshing(!scanning.isEmpty());
    if (selected.isEmpty()) libraryPane(pane);
    else detailPane(pane);
  }

  void searchLibrary() {
    input(
        "책 검색",
        query,
        s -> {
          query = s;
          selected = "";
          gridIndex = 0;
          library(false);
        });
  }

  void libraryPane(LinearLayout pane) {
    Library.Book last = null;
    for (Library.Book b : books)
      if (b.opened > 0
          && !b.done
          && (last == null || b.opened > last.opened)
          && (shelf.isEmpty() || shelf.equals(b.shelf))) last = b;
    if (last != null && filter == 0 && query.isEmpty()) {
      final Library.Book recent = last;
      LinearLayout resume = row();
      resume.setBackground(shape(surface, 9));
      pad(resume, 12, 6);
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(72));
      rp.setMargins(dp(18), dp(14), dp(18), 0);
      pane.addView(resume, rp);
      ImageView small = new ImageView(this);
      resume.addView(small, dp(39), dp(56));
      loadCover(small, recent);
      LinearLayout tx = col();
      pad(tx, 12, 0);
      weight(resume, tx);
      tx.addView(label("이어 읽기", 12, accent, true));
      TextView name = label(recent.title, 16, ink, true);
      one(name);
      tx.addView(name);
      resume.addView(label((recent.page + 1) + " / " + recent.pages, 12, muted, false));
      resume.setOnClickListener(v -> open(recent));
    }
    List<List<Library.Book>> list = new ArrayList<>();
    String q = query.toLowerCase(Locale.ROOT);
    for (List<Library.Book> group : groups.values()) {
      boolean ok = false;
      for (Library.Book b : group)
        if ((shelf.isEmpty() || shelf.equals(b.shelf))
            && (b.series.toLowerCase(Locale.ROOT).contains(q)
                || b.title.toLowerCase(Locale.ROOT).contains(q))
            && (filter == 0
                || filter == 1 && b.opened > 0 && !b.done
                || filter == 2 && b.done
                || filter == 3 && b.fav)) {
          ok = true;
          break;
        }
      if (ok) list.add(group);
    }
    int sort = prefs.getInt("sort", 0);
    list.sort(
        (a, b) -> {
          int n = sort == 0 ? 0 : Long.compare(groupOrder(b, sort), groupOrder(a, sort));
          return n == 0 ? Order.natural(a.get(0).series, b.get(0).series) : n;
        });
    pane.addView(shelfSummary(list.size()), -1, LinearLayout.LayoutParams.WRAP_CONTENT);
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
    List<CoverItem> covers = new ArrayList<>();
    for (List<Library.Book> group : list) {
      Library.Book rep = group.get(0);
      for (Library.Book b : group)
        if (b.rep) {
          rep = b;
          break;
        }
      covers.add(new CoverItem(rep, rep.series, group.size() + "권" + (rep.fav ? "  ·  즐겨찾기" : "")));
    }
    coverGrid(pane, covers, false);
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

  void coverGrid(LinearLayout pane, List<CoverItem> list, boolean volumes) {
    grid = new GridView(this);
    grid.setTag(volumes);
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setHorizontalSpacing(dp(14));
    grid.setVerticalSpacing(dp(18));
    grid.setPadding(dp(18), dp(3), dp(18), dp(22));
    grid.setClipToPadding(false);
    final GridView g = grid;
    pane.addView(g, -1, -1);
    int pref = prefs.getInt("size", 1), width = new int[] {100, 133, 165}[pref];
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
            LinearLayout tile = recycled instanceof LinearLayout ? (LinearLayout) recycled : col();
            if (tile.getChildCount() == 0) {
              tile.addView(new ImageView(Main.this));
              tile.addView(label("", 15, ink, true));
              tile.addView(label("", 12, muted, false));
            }
            CoverItem item = list.get(pos);
            Library.Book rep = item.book;
            ImageView img = (ImageView) tile.getChildAt(0);
            int cw = g.getColumnWidth() > 0 ? g.getColumnWidth() : dp(width);
            img.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) (cw * 1.42)));
            img.setBackgroundColor(surface);
            loadCover(img, rep);
            TextView name = (TextView) tile.getChildAt(1);
            one(name);
            name.setText(item.title);
            name.setPadding(0, dp(8), 0, dp(3));
            ((TextView) tile.getChildAt(2)).setText(item.subtitle);
            return tile;
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
          library(false);
        });
    g.setOnItemLongClickListener(
        (p, v, pos, id) -> {
          manage(list.get(pos).book);
          return true;
        });
    g.setSelectionFromTop(volumes ? volumeIndex : gridIndex, volumes ? volumeOffset : gridOffset);
  }

  long groupOrder(List<Library.Book> group, int sort) {
    long n = 0;
    for (Library.Book b : group) n = Math.max(n, sort == 1 ? b.opened : b.id);
    return n;
  }

  void loadCover(ImageView image, Library.Book b) {
    File file = db.coverFile(b.id);
    String key = b.id + "-" + file.lastModified();
    image.setTag(key);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    Bitmap cached = coverCache.get(key);
    if (cached != null) {
      image.setImageBitmap(cached);
      return;
    }
    image.setImageResource(R.drawable.book_blank);
    if (!file.isFile()) return;
    thumbs.execute(
        () -> {
          Bitmap bitmap = BitmapFactory.decodeFile(file.toString());
          if (bitmap != null) {
            coverCache.put(key, bitmap);
            runOnUiThread(
                () -> {
                  if (key.equals(image.getTag())) image.setImageBitmap(bitmap);
                });
          }
        });
  }

  void detailPane(LinearLayout pane) {
    List<Library.Book> volumes = groups.get(selected);
    String name = volumes.get(0).series;
    LinearLayout title = row();
    pad(title, 16, 10);
    pane.addView(title);
    TextView t = label(name, 23, ink, true);
    t.setMaxLines(2);
    weight(title, t);
    title.addView(label(volumes.size() + "권", 13, muted, true));
    List<CoverItem> covers = new ArrayList<>();
    for (Library.Book b : volumes) {
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
      covers.add(new CoverItem(b, b.title, status));
    }
    coverGrid(pane, covers, true);
  }

  void pick(String title, String key, String[] labels, Runnable after) {
    int def = key.equals("size") ? 1 : 0;
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
    library(true);
    worker.execute(
        () -> {
          ArrayList<String> problems = new ArrayList<>();
          for (long id : batch)
            try {
              db.scan(
                  id,
                  () -> {
                    handler.removeCallbacks(refresh);
                    handler.postDelayed(refresh, 180);
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
        new String[] {"연결 폴더 다시 읽기", "책장 관리", "표지 크기", "화면 테마", "임시 파일 정리", "앱 업데이트 확인"},
        i -> {
          if (i == 0) refreshSources(false);
          if (i == 1) shelves();
          if (i == 2) pick("표지 크기", "size", new String[] {"작게", "기본", "크게"}, () -> library(true));
          if (i == 3)
            pick("화면 테마", "theme", new String[] {"기기 설정", "밝게", "어둡게"}, () -> library(true));
          if (i == 5) updates.check(true);
          if (i == 4) {
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
            gridIndex = 0;
            library(false);
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

  void manage(Library.Book b) {
    choices(
        b.title,
        new String[] {
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
            chooseCover(b);
            return;
          }
          if (i == 1) db.exec("UPDATE books SET rep=(id=?) WHERE grp=?", b.id, b.grp);
          if (i == 2) db.exec("UPDATE books SET fav=? WHERE id=?", b.fav ? 0 : 1, b.id);
          if (i == 3)
            db.exec(
                "UPDATE books SET done=?,opened=CASE WHEN ? THEN 0 ELSE opened END,page=CASE WHEN ?"
                    + " THEN 0 ELSE page END WHERE id=?",
                b.done ? 0 : 1,
                b.done ? 1 : 0,
                b.done ? 1 : 0,
                b.id);
          if (i == 4) {
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
                            library(true);
                          }
                        });
                  } else {
                    db.exec(
                        "UPDATE books SET shelf=? WHERE grp=?", p == 0 ? "" : list.get(p), b.grp);
                    library(true);
                  }
                });
            return;
          }
          if (i == 5) {
            input(
                "시리즈명 변경",
                b.series,
                s -> {
                  if (!s.isEmpty()) {
                    db.exec("UPDATE books SET series=? WHERE grp=?", s, b.grp);
                    library(true);
                  }
                });
            return;
          }
          if (i == 6) {
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.remove(b.grp);
            if (keys.isEmpty()) {
              toast("합칠 다른 작품이 없습니다.");
              return;
            }
            String[] names = new String[keys.size()];
            for (int k = 0; k < names.length; k++) names[k] = groups.get(keys.get(k)).get(0).series;
            choices(
                "합칠 시리즈",
                names,
                k -> {
                  Library.Book dest = groups.get(keys.get(k)).get(0);
                  db.exec(
                      "UPDATE books SET grp=?,series=?,shelf=?,rep=0 WHERE grp=?",
                      dest.grp,
                      dest.series,
                      dest.shelf,
                      b.grp);
                  selected = dest.grp;
                  library(true);
                });
            return;
          }
          if (i == 7) {
            db.exec("UPDATE books SET grp=?,series=title,rep=0 WHERE id=?", "manual:" + b.id, b.id);
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
    remember();
    int token = ++requestId;
    loadingBook = true;
    scanMessage = "책을 여는 중…";
    if (reader == null) library(true);
    readerIO.execute(
        () -> {
          try {
            Archive a = db.open(b);
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
                  frame.removeAllViews();
                  reader = new Reader(this, a, b);
                  frame.addView(reader, -1, -1);
                  overlay = topBar = null;
                  immersive();
                  b.pages = a.pages.size();
                  db.exec(
                      "UPDATE books SET pages=?,opened=? WHERE id=?",
                      b.pages,
                      System.currentTimeMillis(),
                      b.id);
                  reader.save();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (isDestroyed() || token != requestId) return;
                  loadingBook = false;
                  scanMessage = "";
                  library(true);
                  alert("책을 열 수 없습니다", Library.reason(e));
                });
          }
        });
  }

  void immersive() {
    getWindow().getDecorView().setSystemUiVisibility(5894);
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
    int fg = 0xfff6f7f4;
    LinearLayout top = row();
    top.setBackgroundColor(0xee202421);
    pad(top, 6, 2);
    icon(top, "back", "서재로", fg, this::leaveReader);
    TextView title = label(reader.book.title, 16, fg, true);
    one(title);
    weight(top, title);
    directionButton = action("", this::pickDirection);
    directionButton.setTextColor(fg);
    top.addView(directionButton);
    icon(top, "more", "읽기 설정", fg, this::readerSettings);
    topBar = top;
    frame.addView(top, new FrameLayout.LayoutParams(-1, dp(55), Gravity.TOP));
    LinearLayout bottom = col();
    pad(bottom, 15, 9);
    bottom.setBackgroundColor(0xee202421);
    LinearLayout tools = row();
    bottom.addView(tools);
    indicator = label("", 14, fg, true);
    weight(tools, indicator);
    TextView mark = action("책갈피", this::marks);
    mark.setTextColor(fg);
    tools.addView(mark);
    TextView opt = action("보기 설정", this::readerSettings);
    opt.setTextColor(fg);
    tools.addView(opt);
    seek = new SeekBar(this);
    seek.setMax(reader.count() - 1);
    bottom.addView(seek, -1, dp(38));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {
            if (reader != null) reader.seek(s.getProgress());
          }

          public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (user && reader != null) indicator.setText((p + 1) + " / " + reader.count());
          }
        });
    overlay = bottom;
    frame.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
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
            + "   ·   "
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
          if (i == 2)
            pick(
                "넘김 효과", "effect", new String[] {"종이 넘김", "슬라이드", "즉시 전환"}, reader::optionsChanged);
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
          if (i == 8) gestureSettings();
        });
  }

  LinearLayout shelfSummary(int works) {
    LinearLayout count = row();
    pad(count, 20, 0);
    count.setMinimumHeight(dp(48));

    weight(
        count,
        label((query.isEmpty() ? "작품" : "‘" + query + "’ 검색 결과") + "  " + works, 14, muted, true));
    if (!query.isEmpty())
      count.addView(
          action(
              "검색 해제",
              () -> {
                query = "";
                library(true);
              }));
    else count.addView(action(shelf.isEmpty() ? "책장 선택" : shelf, this::shelves));
    return count;
  }

  void gestureSettings() {
    AlertDialog.Builder builder = dialog();
    ScrollView scroll = new ScrollView(builder.getContext());
    LinearLayout box = col();
    pad(box, 20, 4);
    scroll.addView(box);
    addGestureSwitch(box, "좌우 탭으로 페이지 넘기기", "tapTurn", true);
    addGestureSwitch(box, "탭으로 넘길 때 애니메이션", "tapAnimation", true);
    addGestureSwitch(box, "좌우 스와이프로 페이지 넘기기", "swipeTurn", true);
    addGestureSwitch(box, "가운데 더블 탭으로 확대·축소", "doubleZoom", false);
    addGestureSwitch(box, "두 손가락으로 확대·축소", "pinchZoom", true);
    addGestureSwitch(box, "왼쪽 가장자리에서 위아래로 밝기 조절", "brightnessGesture", false);
    TextView width = label("좌우 탭 영역", 14, ink, true);
    pad(width, 0, 8);
    box.addView(width);
    RadioGroup sizes = new RadioGroup(builder.getContext());
    sizes.setOrientation(LinearLayout.HORIZONTAL);
    box.addView(sizes);
    String[] choices = {"좁게", "기본", "넓게"};
    for (int i = 0; i < choices.length; i++) {
      RadioButton option = new RadioButton(builder.getContext());
      option.setText(choices[i]);
      option.setId(View.generateViewId());
      sizes.addView(option);
      if (i == prefs.getInt("tapWidth", 1)) sizes.check(option.getId());
    }
    sizes.setOnCheckedChangeListener(
        (group, id) -> {
          prefs.edit().putInt("tapWidth", group.indexOfChild(group.findViewById(id))).apply();
          reader.readGestures();
        });
    builder.setTitle("터치·제스처 설정").setView(scroll).setPositiveButton("완료", null).show();
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

  void leaveReader() {
    requestId++;
    reader.close();
    reader = null;
    overlay = topBar = null;
    library(false);
  }

  protected void onPause() {
    if (reader != null) reader.storeScroll();
    super.onPause();
  }

  public void onBackPressed() {
    if (reader != null) {
      leaveReader();
      return;
    }
    if (loadingBook) {
      requestId++;
      loadingBook = false;
      scanMessage = "";
      library(true);
      return;
    }
    if (!selected.isEmpty()) {
      selected = "";
      library(false);
      return;
    }
    if (!query.isEmpty()) {
      query = "";
      library(false);
      return;
    }
    super.onBackPressed();
  }

  protected void onDestroy() {
    if (reader != null) reader.close();
    handler.removeCallbacks(refresh);
    worker.shutdown();
    thumbs.shutdown();
    readerIO.shutdown();
    snapshots.shutdown();
    super.onDestroy();
  }
}
