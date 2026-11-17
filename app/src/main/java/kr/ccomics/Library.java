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
    super(c, "library.db", null, 4);
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
    db.execSQL("CREATE INDEX series_index ON books(grp)");
    db.execSQL("CREATE TABLE shelves(name TEXT PRIMARY KEY)");
    db.execSQL("CREATE TABLE marks(book INTEGER,page INTEGER,PRIMARY KEY(book,page))");
  }

  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from < 3) db.execSQL("ALTER TABLE books ADD COLUMN manualGroup INTEGER DEFAULT -1");
    if (from < 2) db.execSQL("ALTER TABLE books ADD COLUMN fingerprint TEXT DEFAULT ''");
    if (from < 4) db.execSQL("ALTER TABLE books ADD COLUMN bundle INTEGER DEFAULT 0");
  }

  static class Book {
    long id, source, size, changed, opened;
    String url, title, series, grp, shelf, cover, error;
    int pages, page;
    boolean fav, done, rep, missing;

    Book(Cursor c) {
      id = c.getLong(0);
      source = c.getLong(1);
      url = c.getString(2);
      title = c.getString(3);
      series = c.getString(4);
      grp = c.getString(5);
      shelf = c.getString(6);
      size = c.getLong(7);
      changed = c.getLong(8);
      pages = c.getInt(9);
      page = c.getInt(10);
      opened = c.getLong(11);
      done = c.getInt(12) != 0;
      fav = c.getInt(13) != 0;
      cover = c.getString(14);
      rep = c.getInt(15) != 0;
      missing = c.getInt(16) != 0;
      error = c.getString(17);
    }
  }

  static final String SELECT =
      "SELECT"
          + " id,source,url,title,series,grp,shelf,size,changed,pages,page,opened,done,fav,cover,rep,missing,error"
          + " FROM books";

  List<Book> books() {
    List<Book> r = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(SELECT + " WHERE missing=0 AND bundle=0", null)) {
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
    CIFSContext old = sessions.remove(id);
    if (old != null)
      try {
        old.close();
      } catch (Exception ignored) {
      }
  }

  Archive open(Book b) throws Exception {
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
    if (url.startsWith("smb://")) {
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
        Bitmap image = a.decode(index, 320, 480);
        writeCover(id, image);
        image.recycle();
        exec(
            "UPDATE books SET pages=?,page=min(page,?),fingerprint=?,error='',bundle=0 WHERE id=?",
            a.pages.size(),
            a.pages.size() - 1,
            a.fingerprint(),
            id);
      } catch (Archive.Bundle bundle) {
        exec("UPDATE books SET bundle=1,pages=0,error='' WHERE id=?", id);
        expandBundle(b, bundle.volumes, token, pending);
      } catch (Exception e) {
        exec("UPDATE books SET error=? WHERE id=?", reason(e), id);
      }
      if (++n % 8 == 0) notice.changed();
    }
    reconcileMoved(source, pending);
    rules.edit().putInt(rulesKey, RULES).apply();
    notice.changed();
  }

  /** 인식 규칙이 바뀐 뒤 한 번, 이미 열리던 책 중 권 ZIP 묶음인 것을 찾아 권별로 나눈다. */
  static final int RULES = 5;

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
        // 그대로 한 권
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
    else if (!parent.equals("single"))
      series = Uri.decode(parent).replaceAll("/+$", "").replaceFirst("^.*[:/]", "");
    else series = title;
    boolean inBundle = url.indexOf(Archive.NESTED) >= 0;
    String key = Order.seriesKey(series);
    // 파일 이름에 작품명이 있으면 권 번호가 없어도 폴더와 상관없이 같은 이름끼리 묶는다.
    boolean named =
        !volume.title.isEmpty() ? volume.number >= 0 || !Order.genericKey(key) : inBundle;
    group = named ? "series:" + key : parent + "\n" + key;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,size,changed,pages,error,grp,series,manualGroup,bundle FROM books WHERE"
                    + " url=?",
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
        if (manual == -1)
          manual = legacyGroup.equals(oldGroup) && legacySeries.equals(c.getString(6)) ? 0 : 1;
        if (manual == 0 && (!group.equals(oldGroup) || !series.equals(c.getString(6)))) {
          copySeriesOptions(oldGroup, group);
          exec("UPDATE books SET grp=?,series=? WHERE id=?", group, series, id);
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
  }
}
