package kr.ccomics;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.security.keystore.*;
import android.util.Base64;
import java.io.*;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import jcifs.*;
import jcifs.smb.*;

final class Library extends SQLiteOpenHelper {
  final Context ctx;
  final File covers;

  Library(Context c) {
    super(c, "library.db", null, 6);
    ctx = c;
    setWriteAheadLoggingEnabled(true);
    covers = new File(c.getFilesDir(), "covers");
    covers.mkdirs();
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE sources(id INTEGER PRIMARY KEY,url TEXT UNIQUE,user TEXT,secret TEXT)");
    db.execSQL(
        "CREATE TABLE books(id INTEGER PRIMARY KEY,source INTEGER,url TEXT UNIQUE,title TEXT,series"
            + " TEXT,grp TEXT,shelf TEXT DEFAULT '',size INTEGER,changed INTEGER,seen"
            + " INTEGER,missing INTEGER DEFAULT 0,pages INTEGER DEFAULT 0,page INTEGER DEFAULT"
            + " 0,opened INTEGER DEFAULT 0,done INTEGER DEFAULT 0,fav INTEGER DEFAULT 0,cover TEXT"
            + " DEFAULT '',rep INTEGER DEFAULT 0,error TEXT DEFAULT '')");
    db.execSQL("ALTER TABLE books ADD COLUMN fingerprint TEXT DEFAULT ''");
    db.execSQL("ALTER TABLE books ADD COLUMN manualGroup INTEGER DEFAULT 0");
    db.execSQL("ALTER TABLE books ADD COLUMN bundle INTEGER DEFAULT 0");
    db.execSQL("ALTER TABLE books ADD COLUMN rating INTEGER DEFAULT 0");
    db.execSQL("ALTER TABLE books ADD COLUMN comicSeries TEXT DEFAULT ''");
    db.execSQL("ALTER TABLE books ADD COLUMN comicNumber TEXT DEFAULT ''");
    db.execSQL("ALTER TABLE books ADD COLUMN customOrder INTEGER DEFAULT 0");
    db.execSQL("CREATE INDEX series_index ON books(grp)");
    db.execSQL("CREATE TABLE shelves(name TEXT PRIMARY KEY)");
    db.execSQL("CREATE TABLE marks(book INTEGER,page INTEGER,PRIMARY KEY(book,page))");
  }

  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from < 3) db.execSQL("ALTER TABLE books ADD COLUMN manualGroup INTEGER DEFAULT -1");
    if (from < 2) db.execSQL("ALTER TABLE books ADD COLUMN fingerprint TEXT DEFAULT ''");
    if (from < 4) db.execSQL("ALTER TABLE books ADD COLUMN bundle INTEGER DEFAULT 0");
    if (from < 5) {
      db.execSQL("ALTER TABLE books ADD COLUMN rating INTEGER DEFAULT 0");
      db.execSQL("ALTER TABLE books ADD COLUMN comicSeries TEXT DEFAULT ''");
      db.execSQL("ALTER TABLE books ADD COLUMN comicNumber TEXT DEFAULT ''");
    }
    if (from < 6) {
      db.execSQL("ALTER TABLE books ADD COLUMN customOrder INTEGER DEFAULT 0");
    }
  }

  static class Book {
    long id, source, size, changed, opened;
    String url, title, series, grp, shelf, cover, error, comicSeries, comicNumber;
    int pages, page, rating, customOrder;
    boolean fav, done, rep, missing, manualGroup;

    Book(Cursor c) {
      id = c.getLong(0);
      source = c.getLong(1);
      url = str(c, 2);
      title = str(c, 3);
      series = str(c, 4);
      grp = str(c, 5);
      shelf = str(c, 6);
      size = c.getLong(7);
      changed = c.getLong(8);
      pages = c.getInt(9);
      page = c.getInt(10);
      opened = c.getLong(11);
      done = c.getInt(12) != 0;
      fav = c.getInt(13) != 0;
      cover = str(c, 14);
      rep = c.getInt(15) != 0;
      missing = c.getInt(16) != 0;
      error = str(c, 17);
      rating = c.getInt(18);
      manualGroup = c.getInt(19) == 1;
      comicSeries = str(c, 20);
      comicNumber = str(c, 21);
      customOrder = c.getInt(22);
    }

    private static String str(Cursor c, int index) {
      String s = c.getString(index);
      return s == null ? "" : s;
    }
  }

  static final String SELECT =
      "SELECT"
          + " id,source,url,title,series,grp,shelf,size,changed,pages,page,opened,done,fav,cover,rep,missing,error,rating,manualGroup,comicSeries,comicNumber,customOrder"
          + " FROM books";

  List<Book> books() {
    List<Book> r = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(SELECT + " WHERE missing=0 AND bundle=0", null)) {
      while (c.moveToNext()) r.add(new Book(c));
    }
    return r;
  }

  /** 원격 보관함 매칭용: 숨김·누락·묶음 원본을 포함해 PC 원격(HTTP/HTTPS) 책 전체를 돌려준다. */
  List<Book> allRemoteBooks() {
    List<Book> r = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(SELECT + " WHERE url LIKE 'http://%' OR url LIKE 'https://%'", null)) {
      while (c.moveToNext()) r.add(new Book(c));
    }
    return r;
  }

  Book get(long id) {
    try (Cursor c =
        getReadableDatabase().rawQuery(SELECT + " WHERE id=?", new String[] {"" + id})) {
      return c.moveToFirst() ? new Book(c) : null;
    }
  }

  void exec(String sql, Object... args) {
    getWritableDatabase().execSQL(sql, args);
  }

  List<String> strings(String sql, String... args) {
    List<String> r = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
      while (c.moveToNext()) r.add(c.getString(0));
    }
    return r;
  }

  static SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (ks.containsAlias("ccomics.smb")) return (SecretKey) ks.getKey("ccomics.smb", null);
    KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
    gen.init(
        new KeyGenParameterSpec.Builder(
                "ccomics.smb", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return gen.generateKey();
  }

  static String encrypt(String password) throws Exception {
    if (password.isEmpty()) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(cipher.getIV(), 2)
        + "."
        + Base64.encodeToString(cipher.doFinal(password.getBytes("UTF-8")), 2);
  }

  static String decrypt(String value) throws Exception {
    if (value.isEmpty()) return "";
    String[] s = value.split("\\.");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(s[0], 2)));
    return new String(cipher.doFinal(Base64.decode(s[1], 2)), "UTF-8");
  }

  static final class Source {
    final long id;
    final String url, user;

    Source(long id, String url, String user) {
      this.id = id;
      this.url = url;
      this.user = user;
    }
  }

  List<Source> remoteSources() {
    List<Source> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,url,user FROM sources WHERE url LIKE 'http://%' OR url LIKE 'https://%' ORDER BY id",
                null)) {
      while (c.moveToNext())
        out.add(
            new Source(
                c.getLong(0),
                c.getString(1) == null ? "" : c.getString(1),
                c.getString(2) == null ? "" : c.getString(2)));
    }
    return out;
  }

  Source source(long id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id,url,user FROM sources WHERE id=?", new String[] {"" + id})) {
      if (!c.moveToFirst()) return null;
      return new Source(
          c.getLong(0),
          c.getString(1) == null ? "" : c.getString(1),
          c.getString(2) == null ? "" : c.getString(2));
    }
  }

  int sourceBookCount(long id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT count(*) FROM books WHERE source=? AND missing=0 AND bundle=0",
                new String[] {"" + id})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  void updateSourceAuth(long id, String user, String password) throws Exception {
    exec("UPDATE sources SET user=?,secret=? WHERE id=?", user, encrypt(password), id);
    reconnect(id);
  }

  /** 원격·로컬 폴더 연결 해제: 해당 소스의 책·북마크·표지·캐시를 정리하고 소스 레코드를 지운다. */
  void removeSource(long id) {
    List<String> bookIds = strings("SELECT id FROM books WHERE source=?", "" + id);
    List<String> bookUrls = strings("SELECT url FROM books WHERE source=?", "" + id);
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransactionNonExclusive();
    try {
      database.execSQL("DELETE FROM marks WHERE book IN (SELECT id FROM books WHERE source=?)", new Object[] {id});
      database.execSQL("DELETE FROM books WHERE source=?", new Object[] {id});
      database.execSQL("DELETE FROM sources WHERE id=?", new Object[] {id});
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
    reconnect(id);
    for (String bid : bookIds) coverFile(Long.parseLong(bid)).delete();
    Set<String> cacheFiles = new HashSet<>();
    for (String u : bookUrls) if (WebDav.isUrl(u)) cacheFiles.add(RemoteBooks.fileName(u));
    if (!cacheFiles.isEmpty()) RemoteBooks.deleteAll(ctx, cacheFiles);
  }

  long register(String url, String user, String password) throws Exception {
    String secret = encrypt(password);
    exec("INSERT OR IGNORE INTO sources(url,user,secret) VALUES(?,?,?)", url, user, secret);
    exec("UPDATE sources SET user=?,secret=? WHERE url=?", user, secret, url);
    return Long.parseLong(strings("SELECT id FROM sources WHERE url=?", url).get(0));
  }

  final Map<Long, CIFSContext> sessions = new HashMap<>();

  synchronized CIFSContext session(long id) throws Exception {
    CIFSContext s = sessions.get(id);
    if (s == null) {
      try (Cursor c =
          getReadableDatabase()
              .rawQuery("SELECT user,secret FROM sources WHERE id=?", new String[] {"" + id})) {
        if (!c.moveToFirst()) throw new IOException("연결 정보가 없습니다.");
        s = Archive.server(c.getString(0), decrypt(c.getString(1)));
        sessions.put(id, s);
      }
    }
    return s;
  }

  synchronized void reconnect(long id) {
    httpAuths.remove(id);
    CIFSContext old = sessions.remove(id);
    if (old != null)
      try {
        old.close();
      } catch (Exception ignored) {
      }
  }

  final Map<Long, String> httpAuths = new HashMap<>();

  /** PC 원격 폴더(WebDAV) 접속용 인증 헤더. */
  synchronized String httpAuth(long id) throws Exception {
    String a = httpAuths.get(id);
    if (a == null) {
      try (Cursor c =
          getReadableDatabase()
              .rawQuery("SELECT user,secret FROM sources WHERE id=?", new String[] {"" + id})) {
        if (!c.moveToFirst()) throw new IOException("연결 정보가 없습니다.");
        a = WebDav.auth(c.getString(0), decrypt(c.getString(1)));
        httpAuths.put(id, a);
      }
    }
    return a;
  }

  /** 기기 밖(Windows 공유·PC 원격 폴더)에서 읽는 책인지. */
  static boolean external(Book b) {
    return b.url.startsWith("smb://") || WebDav.isUrl(b.url);
  }

  Archive open(Book b) throws Exception {
    if (WebDav.isUrl(b.url)) {
      // 보관함에 받아 둔 권은 PC·네트워크 없이 저장본으로 연다.
      if (RemoteBooks.has(ctx, b.url)) return new Archive(ctx, RemoteBooks.localAddress(ctx, b.url), null);
      return new Archive(ctx, b.url, null, httpAuth(b.source), b.size);
    }
    return new Archive(ctx, b.url, b.url.startsWith("smb://") ? session(b.source) : null);
  }

  final class IndexBatch {
    final long source, token;
    final List<Long> pending;
    final List<Runnable> updates = new ArrayList<>(128);

    IndexBatch(long source, long token, List<Long> pending) {
      this.source = source;
      this.token = token;
      this.pending = pending;
    }

    void add(String url, String name, String parent, long size, long modified) {
      updates.add(() -> record(source, url, name, parent, size, modified, token, pending));
      if (updates.size() >= 128) flush();
    }

    void flush() {
      if (updates.isEmpty()) return;
      SQLiteDatabase database = getWritableDatabase();
      database.beginTransactionNonExclusive();
      try {
        for (Runnable write : updates) write.run();
        database.setTransactionSuccessful();
      } finally {
        database.endTransaction();
        updates.clear();
      }
    }
  }

  interface Notice {
    void changed();

    default void progress(int done, int total) {}
  }

  /** WebDAV 하위 폴더들을 4개 스레드로 병렬 탐색해 초기 색인 목록 수집 지연을 줄인다. */
  private void scanWebDavTree(String rootUrl, String auth, IndexBatch batch) throws Exception {
    final int workers = 4;
    final ArrayDeque<String> queue = new ArrayDeque<>();
    final HashSet<String> visited = new HashSet<>();
    final java.util.concurrent.atomic.AtomicReference<Exception> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    final Object lock = new Object();
    final int[] active = {0};
    queue.add(rootUrl);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(workers);
    try {
      for (int i = 0; i < workers; i++) {
        pool.execute(
            () -> {
              while (true) {
                String parent;
                synchronized (lock) {
                  while (queue.isEmpty() && active[0] > 0 && failure.get() == null) {
                    try {
                      lock.wait();
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                  if (failure.get() != null || queue.isEmpty()) return;
                  parent = queue.remove();
                  if (!visited.add(parent)) continue;
                  active[0]++;
                }
                try {
                  List<WebDav.Entry> entries = WebDav.list(parent, auth);
                  synchronized (lock) {
                    for (WebDav.Entry e : entries) {
                      if (e.dir) queue.add(e.url.endsWith("/") ? e.url : e.url + "/");
                      else batch.add(e.url, e.name, parent, e.size, e.modified);
                    }
                  }
                } catch (Exception e) {
                  failure.compareAndSet(null, e);
                } finally {
                  synchronized (lock) {
                    active[0]--;
                    lock.notifyAll();
                  }
                }
              }
            });
      }
      pool.shutdown();
      pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
    } finally {
      pool.shutdownNow();
    }
    if (failure.get() != null) throw failure.get();
  }

  /** WebDAV 원격 책 표지·쪽수 색인을 4개 스레드로 병렬 처리한다(표지 1장만 읽어 낭비 제거). */
  private void indexPendingWebDav(long token, ArrayList<Long> pending, Notice notice) {
    final int workers = Math.min(4, Math.max(1, pending.size()));
    final ArrayDeque<Long> queue = new ArrayDeque<>(pending);
    final Object queueLock = new Object();
    final Object dbLock = new Object();
    final int[] active = {0};
    final int[] done = {0};
    final int[] total = {pending.size()};
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(workers);
    try {
      for (int w = 0; w < workers; w++) {
        pool.execute(
            () -> {
              while (true) {
                long id;
                synchronized (queueLock) {
                  while (queue.isEmpty() && active[0] > 0) {
                    try {
                      queueLock.wait();
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                  if (queue.isEmpty()) return;
                  id = queue.remove();
                  active[0]++;
                }
                try {
                  Book b;
                  synchronized (dbLock) {
                    b = get(id);
                  }
                  if (b == null) continue;
                  try (Archive a = open(b)) {
                    int index = 0;
                    for (int i = 0; i < a.pages.size(); i++)
                      if (a.name(i).equals(b.cover)) {
                        index = i;
                        break;
                      }
                    Bitmap image = a.decodeCover(index, 320, 480);
                    writeCover(id, image);
                    image.recycle();
                    int pageCount = a.pages.size();
                    String fp = a.fingerprint();
                    synchronized (dbLock) {
                      exec(
                          "UPDATE books SET pages=?,page=min(page,?),fingerprint=?,error='',bundle=0 WHERE id=?",
                          pageCount,
                          pageCount - 1,
                          fp,
                          id);
                      applyComicInfo(b, a);
                    }
                  } catch (Archive.Bundle bundle) {
                    ArrayList<Long> added = new ArrayList<>();
                    synchronized (dbLock) {
                      exec("UPDATE books SET bundle=1,pages=0,error='' WHERE id=?", id);
                      expandBundle(b, bundle.volumes, token, added);
                      pending.addAll(added);
                    }
                    if (!added.isEmpty()) {
                      synchronized (queueLock) {
                        queue.addAll(added);
                        total[0] += added.size();
                        queueLock.notifyAll();
                      }
                    }
                  } catch (Exception e) {
                    synchronized (dbLock) {
                      exec("UPDATE books SET error=? WHERE id=?", reason(e), id);
                    }
                  }
                  int finished, curTotal;
                  synchronized (queueLock) {
                    finished = ++done[0];
                    curTotal = total[0];
                  }
                  notice.progress(finished, curTotal);
                  if (finished % 4 == 0) notice.changed();
                } finally {
                  synchronized (queueLock) {
                    active[0]--;
                    queueLock.notifyAll();
                  }
                }
              }
            });
      }
      pool.shutdown();
      pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      pool.shutdownNow();
    }
  }

  void scan(long source, Notice notice) throws Exception {
    String url = strings("SELECT url FROM sources WHERE id=?", "" + source).get(0);
    long token = System.currentTimeMillis();
    ArrayList<Long> pending = new ArrayList<>();
    if (ctx.getSharedPreferences("options", 0).getInt("rules-" + source, 0) < 4)
      // 페이지 정렬 규칙이 바뀌어 첫 장(표지)이 달라질 수 있으므로 표지 썸네일을 한 번 다시 만든다.
      for (String id : strings("SELECT id FROM books WHERE source=?", "" + source))
        coverFile(Long.parseLong(id)).delete();
    IndexBatch batch = new IndexBatch(source, token, pending);
    boolean isWebDav = WebDav.isUrl(url);
    if (isWebDav) {
      scanWebDavTree(url, httpAuth(source), batch);
    } else if (url.startsWith("smb://")) {
      CIFSContext smb = session(source);
      ArrayDeque<String> dirs = new ArrayDeque<>();
      dirs.add(url);
      HashSet<String> visited = new HashSet<>();
      while (!dirs.isEmpty()) {
        String parent = dirs.remove();
        if (!visited.add(parent)) continue;
        try (SmbFile dir = new SmbFile(parent, smb)) {
          for (SmbFile f : dir.listFiles())
            try (SmbFile child = f) {
              if (child.isDirectory()) {
                if ((child.getAttributes() & 0x400) == 0) dirs.add(child.getCanonicalPath());
              } else
                batch.add(
                    child.getCanonicalPath(),
                    child.getName(),
                    parent,
                    child.length(),
                    child.lastModified());
            }
        }
      }
    } else {
      Uri uri = Uri.parse(url);
      String[] fields = {"document_id", "_display_name", "mime_type", "_size", "last_modified"};
      boolean tree = DocumentsContract.isTreeUri(uri);
      ArrayDeque<String> dirs = new ArrayDeque<>();
      dirs.add(tree ? DocumentsContract.getTreeDocumentId(uri) : "single");
      HashSet<String> visited = new HashSet<>();
      while (!dirs.isEmpty()) {
        String parent = dirs.remove();
        if (!visited.add(parent)) continue;
        Uri listing = tree ? DocumentsContract.buildChildDocumentsUriUsingTree(uri, parent) : uri;
        try (Cursor c = ctx.getContentResolver().query(listing, fields, null, null, null)) {
          if (c == null) throw new IOException("폴더 목록을 읽을 수 없습니다.");
          if (c.getExtras().containsKey(DocumentsContract.EXTRA_ERROR))
            throw new IOException("저장소에서 목록을 읽지 못했습니다.");
          if (c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING))
            throw new IOException("파일 공급자가 목록을 준비하고 있습니다. 다시 갱신하세요.");
          while (c.moveToNext()) {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
              dirs.add(c.getString(0));
              continue;
            }
            Uri file =
                tree ? DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0)) : uri;
            batch.add(
                file.toString(),
                c.getString(1),
                tree ? parent : "single",
                c.getLong(3),
                c.getLong(4));
          }
        }
      }
    }
    batch.flush();
    exec("UPDATE books SET missing=1 WHERE source=? AND seen<>?", source, token);
    notice.changed();
    SharedPreferences rules = ctx.getSharedPreferences("options", 0);
    String rulesKey = "rules-" + source;
    if (rules.getInt(rulesKey, 0) < RULES) recheckBundles(source, token, pending);
    if (isWebDav && pending.size() > 1) {
      indexPendingWebDav(token, pending, notice);
    } else {
      int n = 0;
      for (int k = 0; k < pending.size(); k++) {
        long id = pending.get(k);
        Book b = get(id);
        try (Archive a = open(b)) {
          int index = 0;
          for (int i = 0; i < a.pages.size(); i++)
            if (a.name(i).equals(b.cover)) {
              index = i;
              break;
            }
          Bitmap image = a.decodeCover(index, 320, 480);
          writeCover(id, image);
          image.recycle();
          exec(
              "UPDATE books SET pages=?,page=min(page,?),fingerprint=?,error='',bundle=0 WHERE id=?",
              a.pages.size(),
              a.pages.size() - 1,
              a.fingerprint(),
              id);
          applyComicInfo(b, a);
        } catch (Archive.Bundle bundle) {
          exec("UPDATE books SET bundle=1,pages=0,error='' WHERE id=?", id);
          expandBundle(b, bundle.volumes, token, pending);
        } catch (Exception e) {
          exec("UPDATE books SET error=? WHERE id=?", reason(e), id);
        }
        notice.progress(k + 1, pending.size());
        if (++n % 8 == 0) notice.changed();
      }
    }
    reconcileMoved(source, pending);
    rules.edit().putInt(rulesKey, RULES).apply();
    notice.changed();
  }

  /** 인식 규칙이 바뀐 뒤 한 번, 이미 열리던 책 중 권 ZIP 묶음인 것을 찾아 권별로 나눈다. */
  static final int RULES = 5;
  /** 로컬 DB 제목·시리즈 재정리 버전(저장소 재접속 없이 백그라운드 1회 실행). */
  static final int RENORM_VERSION = 8;

  final java.util.concurrent.ConcurrentHashMap<Long, Integer> coverVersions =
      new java.util.concurrent.ConcurrentHashMap<>();

  int coverVersion(long id) {
    Integer v = coverVersions.get(id);
    return v == null ? 0 : v;
  }

  void bumpCoverVersion(long id) {
    coverVersions.merge(id, 1, Integer::sum);
  }

  int groupRating(String group) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT coalesce(max(rating),0) FROM books WHERE grp=?", new String[] {group})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  int groupCustomOrder(String group) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT coalesce(max(customOrder),0) FROM books WHERE grp=?",
                new String[] {group})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  void saveCustomOrders(Map<String, Integer> orders) {
    if (orders == null || orders.isEmpty()) return;
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransactionNonExclusive();
    try {
      SQLiteStatement stmt = database.compileStatement("UPDATE books SET customOrder=? WHERE grp=?");
      try {
        for (Map.Entry<String, Integer> e : orders.entrySet()) {
          stmt.bindLong(1, e.getValue());
          stmt.bindString(2, e.getKey());
          stmt.executeUpdateDelete();
        }
      } finally {
        stmt.close();
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  void applyComicInfo(Book b, Archive a) {
    String cs = a.comicInfo != null ? a.comicInfo.series : "";
    String cn = a.comicInfo != null ? a.comicInfo.number : "";
    exec("UPDATE books SET comicSeries=?,comicNumber=? WHERE id=?", cs, cn, b.id);
    if (!cs.isEmpty() && !b.manualGroup) {
      String cleanSeries = Order.cleanComicSeries(cs);
      if (cleanSeries.isEmpty()) cleanSeries = cs;
      String key = Order.seriesKey(cleanSeries);
      if (!key.isEmpty() && !Order.genericKey(key)) {
        String newGroup = "series:" + key;
        if (!newGroup.equals(b.grp) || !cleanSeries.equals(b.series)) {
          copySeriesOptions(b.grp, newGroup);
          int rating = Math.max(b.rating, groupRating(newGroup));
          int customOrder = groupCustomOrder(newGroup);
          if (customOrder == 0) customOrder = b.customOrder;
          exec(
              "UPDATE books SET grp=?,series=?,rating=?,customOrder=? WHERE id=?",
              newGroup,
              cleanSeries,
              rating,
              customOrder,
              b.id);
        }
      }
    }
  }

  static String parentFromUrl(String url) {
    if (url == null || url.isEmpty()) return "";
    int nested = url.indexOf(Archive.NESTED);
    if (nested >= 0) {
      String outer = Order.percentDecode(url.substring(0, nested)).replace('\\', '/');
      int slash = outer.lastIndexOf('/');
      String outerName = slash >= 0 ? outer.substring(slash + 1) : outer;
      String outerSeries = Order.series(outerName);
      if (!outerSeries.isEmpty()) return bundleParent(outerSeries);
      return slash > 0 ? outer.substring(0, slash) : "";
    }
    String decoded = Order.percentDecode(url).replace('\\', '/');
    int slash = decoded.lastIndexOf('/');
    return slash > 0 ? decoded.substring(0, slash) : "";
  }

  /** 업데이트 직후 저장소 연결 없이도 기존 자동 분류 책들의 작품명·그룹을 최신 규칙으로 빠르게 재정리한다. */
  boolean renormalizeBooks() {
    SQLiteDatabase database = getWritableDatabase();
    Map<String, Integer> ratings = new HashMap<>();
    Map<String, Integer> customOrders = new HashMap<>();
    try (Cursor c =
        database.rawQuery(
            "SELECT grp, coalesce(max(rating),0), coalesce(max(customOrder),0) FROM books GROUP BY grp",
            null)) {
      while (c.moveToNext()) {
        String g = c.getString(0);
        if (g != null) {
          ratings.put(g, c.getInt(1));
          customOrders.put(g, c.getInt(2));
        }
      }
    }
    boolean changed = false;
    database.beginTransactionNonExclusive();
    try {
      for (Book b : books()) {
        if (b.manualGroup) continue;
        String targetSeries;
        String targetGroup;
        if (!b.comicSeries.isEmpty()) {
          targetSeries = Order.cleanComicSeries(b.comicSeries);
          if (targetSeries.isEmpty()) targetSeries = b.comicSeries;
          String key = Order.seriesKey(targetSeries);
          targetGroup = !key.isEmpty() && !Order.genericKey(key) ? "series:" + key : b.grp;
        } else {
          Order.Volume v = Order.volume(b.title + ".zip");
          if (!v.title.isEmpty()) {
            targetSeries = v.title;
            String key = Order.seriesKey(targetSeries);
            boolean named = v.number >= 0 || !Order.genericKey(key);
            if (!named) continue;
            targetGroup = "series:" + key;
          } else {
            Order.Volume sv = Order.volume(b.series + ".zip");
            String cleaned =
                !sv.title.isEmpty() && !Order.isGarbageOrVariantFolder(sv.title)
                    ? Order.cleanComicSeries(sv.title)
                    : "";
            if (cleaned.isEmpty()) {
              String fromUrl = Order.resolveFolderSeries(parentFromUrl(b.url));
              if (!fromUrl.isEmpty() && !Order.isGarbageOrVariantFolder(fromUrl)) {
                cleaned = fromUrl;
              }
            }
            if (cleaned.isEmpty()) cleaned = Order.cleanComicSeries(b.series);
            if (cleaned.isEmpty()) continue;
            targetSeries = cleaned;
            String key = Order.seriesKey(cleaned);
            targetGroup =
                !key.isEmpty()
                        && !Order.genericKey(key)
                        && !Order.isGarbageOrVariantFolder(cleaned)
                    ? "series:" + key
                    : b.grp;
          }
        }
        if (!targetGroup.equals(b.grp) || !targetSeries.equals(b.series)) {
          copySeriesOptions(b.grp, targetGroup);
          int rating = Math.max(b.rating, ratings.getOrDefault(targetGroup, 0));
          ratings.put(targetGroup, rating);
          int existingOrder = customOrders.getOrDefault(targetGroup, 0);
          int order =
              !targetGroup.equals(b.grp) && existingOrder > 0 ? existingOrder : b.customOrder;
          if (existingOrder == 0 && order > 0) customOrders.put(targetGroup, order);
          exec(
              "UPDATE books SET grp=?,series=?,rating=?,customOrder=? WHERE id=?",
              targetGroup,
              targetSeries,
              rating,
              order,
              b.id);
          changed = true;
        }
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
    return changed;
  }

  void recheckBundles(long source, long token, List<Long> pending) {
    Set<Long> queued = new HashSet<>(pending);
    List<String> ids =
        strings(
            "SELECT id FROM books WHERE source=? AND missing=0 AND bundle=0 AND pages>0 AND"
                + " instr(url,char(10))=0",
            "" + source);
    for (String s : ids) {
      long id = Long.parseLong(s);
      if (queued.contains(id)) continue;
      Book b = get(id);
      try (Archive a = open(b)) {
        applyComicInfo(b, a);
      } catch (Archive.Bundle bundle) {
        exec("UPDATE books SET bundle=1,pages=0,error='' WHERE id=?", id);
        expandBundle(b, bundle.volumes, token, pending);
      } catch (Exception ignored) {
      }
    }
  }

  /** 묶음 ZIP 안의 권 ZIP마다 책을 등록한다. 권 번호만 있는 이름은 바깥 파일 이름을 작품명으로 쓴다. */
  void expandBundle(Book outer, List<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> volumes,
      long token, List<Long> pending) {
    String parent = bundleParent(Order.series(outer.title + ".zip"));
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransactionNonExclusive();
    try {
      for (org.apache.commons.compress.archivers.zip.ZipArchiveEntry e : volumes) {
        String path = e.getName();
        String bare = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String name = bare.substring(bare.lastIndexOf('/') + 1);
        if (path.endsWith("/")) name += ".zip"; // 권 폴더도 같은 이름 규칙으로 읽는다.
        record(outer.source, Archive.nested(outer.url, path), name, parent, e.getSize(),
            outer.changed, token, pending);
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  static String bundleParent(String outerTitle) {
    return "bundle/" + outerTitle.replace(':', '：').replace('/', '／');
  }

  void reconcileMoved(long source, List<Long> scanned) {
    if (scanned.isEmpty()) return;
    Set<Long> eligible = new HashSet<>(scanned);
    SQLiteDatabase database = getWritableDatabase();
    String sql =
        "SELECT min(CASE WHEN missing=1 THEN id END),min(CASE WHEN missing=0 THEN id END) FROM"
            + " books WHERE source=? AND fingerprint<>'' GROUP BY fingerprint HAVING count(*)=2 AND"
            + " sum(missing)=1";
    database.beginTransaction();
    try (Cursor pairs = database.rawQuery(sql, new String[] {"" + source})) {
      while (pairs.moveToNext()) {
        long old = pairs.getLong(0), fresh = pairs.getLong(1);
        Book b = get(fresh);
        if (!eligible.contains(fresh)
            || b.opened != 0
            || b.fav
            || b.rep
            || !b.cover.isEmpty()
            || !b.shelf.isEmpty()) continue;
        exec("DELETE FROM books WHERE id=?", fresh);
        exec(
            "UPDATE books SET url=?,title=?,size=?,changed=?,missing=0,seen=? WHERE id=?",
            b.url,
            b.title,
            b.size,
            b.changed,
            System.currentTimeMillis(),
            old);
        coverFile(fresh).delete();
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  static String reason(Exception e) {
    String m = e.getMessage();
    return m == null ? e.getClass().getSimpleName() : m;
  }

  void record(
      long src,
      String url,
      String filename,
      String parent,
      long size,
      long changed,
      long token,
      List<Long> pending) {
    if (filename == null || !filename.toLowerCase(Locale.ROOT).matches("(?s).*\\.(cbz|zip)$"))
      return;
    String title = Order.stem(filename), series = Order.legacySeries(filename);
    if (series.isEmpty()) series = title;
    String group = parent + "\n" + series;
    if (parent.equals("single")) group = "single\n" + series;
    if (title.matches("(?i)^(?:vol\\.?\\s*)?\\d+[권巻]?$") && !parent.equals("single")) {
      String folder = Uri.decode(parent).replaceAll("/+$", "").replaceFirst("^.*[:/]", "");
      if (!folder.isEmpty()) {
        series = folder;
        group = parent + "\n" + series;
      }
    }
    String legacyGroup = group, legacySeries = series;
    Order.Volume volume = Order.volume(filename);
    if (!volume.title.isEmpty()) series = volume.title;
    else if (!parent.equals("single")) {
      String resolved = Order.resolveFolderSeries(parent);
      series =
          resolved.isEmpty()
              ? Uri.decode(parent).replaceAll("/+$", "").replaceFirst("^.*[:/]", "")
              : resolved;
    } else series = title;
    String cleanedSeries = Order.cleanSeriesTitle(series);
    if (!cleanedSeries.isEmpty()) series = cleanedSeries;
    boolean inBundle = url.indexOf(Archive.NESTED) >= 0;
    String key = Order.seriesKey(series);
    // 파일 이름에 작품명이 있거나 무의미 폴더를 건너뛰어 작품 폴더를 찾았으면 같은 이름끼리 묶는다.
    boolean named =
        !volume.title.isEmpty()
            ? volume.number >= 0 || !Order.genericKey(key)
            : (inBundle || (!Order.genericKey(key) && !Order.isGarbageOrVariantFolder(series)));
    group = named ? "series:" + key : parent + "\n" + key;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,size,changed,pages,error,grp,series,manualGroup,bundle,comicSeries,rating"
                    + " FROM books WHERE url=?",
                new String[] {url})) {
      if (c.moveToFirst()) {
        long id = c.getLong(0);
        if (c.getInt(8) == 1 && c.getLong(1) == size && c.getLong(2) == changed) {
          String prefix = url + Archive.NESTED;
          exec(
              "UPDATE books SET seen=?,missing=0 WHERE source=? AND instr(url,?)=1",
              token,
              src,
              prefix);
          // 묶음이 그대로여도 안쪽 권의 표지가 없거나 오류가 있으면 다시 만든다.
          try (Cursor inner =
              getReadableDatabase()
                  .rawQuery(
                      "SELECT id,pages,error FROM books WHERE source=? AND instr(url,?)=1",
                      new String[] {"" + src, prefix})) {
            while (inner.moveToNext()) {
              long child = inner.getLong(0);
              if (inner.getInt(1) == 0
                  || !inner.getString(2).isEmpty()
                  || !coverFile(child).isFile()) pending.add(child);
            }
          }
          exec("UPDATE books SET title=?,seen=?,missing=0 WHERE id=?", title, token, id);
          return;
        }
        int manual = c.getInt(7);
        String oldGroup = c.getString(5);
        String comicSeries = c.getString(9) == null ? "" : c.getString(9);
        int curRating = c.getInt(10);
        if (manual == -1)
          manual = legacyGroup.equals(oldGroup) && legacySeries.equals(c.getString(6)) ? 0 : 1;
        if (manual == 0 && !comicSeries.isEmpty()) {
          String cs = Order.cleanComicSeries(comicSeries);
          if (!cs.isEmpty()) {
            series = cs;
            String ckey = Order.seriesKey(cs);
            if (!ckey.isEmpty() && !Order.genericKey(ckey)) group = "series:" + ckey;
          }
        }
        if (manual == 0 && (!group.equals(oldGroup) || !series.equals(c.getString(6)))) {
          copySeriesOptions(oldGroup, group);
          int rating = Math.max(curRating, groupRating(group));
          exec("UPDATE books SET grp=?,series=?,rating=? WHERE id=?", group, series, rating, id);
        }
        if (c.getInt(7) == -1) exec("UPDATE books SET manualGroup=? WHERE id=?", manual, id);
        if (c.getLong(1) != size
            || c.getLong(2) != changed
            || c.getInt(3) == 0
            || !c.getString(4).isEmpty()
            || !coverFile(id).isFile()) pending.add(id);
        exec(
            "UPDATE books SET title=?,size=?,changed=?,seen=?,missing=0 WHERE id=?",
            title,
            size,
            changed,
            token,
            id);
        return;
      }
    }
    ContentValues v = new ContentValues();
    v.put("manualGroup", 0);
    v.put("source", src);
    v.put("url", url);
    v.put("title", title);
    v.put("series", series);
    v.put("grp", group);
    v.put("rating", groupRating(group));
    v.put("customOrder", groupCustomOrder(group));
    v.put("size", size);
    v.put("changed", changed);
    v.put("seen", token);
    pending.add(getWritableDatabase().insertOrThrow("books", null, v));
  }

  void copySeriesOptions(String oldGroup, String group) {
    SharedPreferences prefs = ctx.getSharedPreferences("options", 0);
    for (String prefix : new String[] {"solo-", "rtl-"}) {
      String old =
          prefix
              + Base64.encodeToString(
                  oldGroup.getBytes(java.nio.charset.StandardCharsets.UTF_8), 2);
      String key =
          prefix
              + Base64.encodeToString(group.getBytes(java.nio.charset.StandardCharsets.UTF_8), 2);
      if (prefs.contains(old) && !prefs.contains(key))
        prefs.edit().putBoolean(key, prefs.getBoolean(old, false)).apply();
    }
  }

  File coverFile(long id) {
    return new File(covers, id + ".jpg");
  }

  void writeCover(long id, Bitmap image) throws IOException {
    int w = Math.min(400, image.getWidth()),
        h = Math.max(1, Math.round(image.getHeight() * w / (float) image.getWidth()));
    if (h > 640) {
      w = Math.max(1, Math.round(w * 640f / h));
      h = 640;
    }
    Bitmap thumb = Bitmap.createScaledBitmap(image, w, h, true);
    File temp = File.createTempFile("thumb-", null, covers);
    try (FileOutputStream out = new FileOutputStream(temp)) {
      if (!thumb.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("표지 저장 실패");
    } finally {
      if (thumb != image) thumb.recycle();
    }
    if (!temp.renameTo(coverFile(id))) {
      temp.delete();
      throw new IOException("표지 저장 실패");
    }
    bumpCoverVersion(id);
  }
}
