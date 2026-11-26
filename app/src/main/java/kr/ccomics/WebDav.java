package kr.ccomics;

import android.util.Xml;
import java.io.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.xmlpull.v1.XmlPullParser;

/** PC 원격 폴더(WebDAV over HTTPS, 예: rclone serve webdav + Tailscale Funnel) 목록 읽기와 부분 읽기. */
final class WebDav {
  static final OkHttpClient CLIENT =
      new OkHttpClient.Builder()
          .connectTimeout(12, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .build();

  static boolean isUrl(String url) {
    return url.startsWith("https://") || url.startsWith("http://");
  }

  static String auth(String user, String pass) {
    return user.isEmpty() ? "" : Credentials.basic(user, pass, StandardCharsets.UTF_8);
  }

  static final class Entry {
    String url, name;
    boolean dir;
    long size, modified;
  }

  private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
  private static final String PROPFIND =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:prop>"
          + "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/></D:prop></D:propfind>";

  /** 폴더 바로 아래 항목을 돌려준다(폴더 자신은 뺀다). */
  static List<Entry> list(String dirUrl, String auth) throws IOException {
    HttpUrl base = HttpUrl.get(dirUrl.endsWith("/") ? dirUrl : dirUrl + "/");
    Request.Builder req =
        new Request.Builder()
            .url(base)
            .header("Depth", "1")
            .method("PROPFIND", RequestBody.create(PROPFIND, XML));
    if (!auth.isEmpty()) req.header("Authorization", auth);
    try (Response res = call(req.build())) {
      if (res.code() != 207 && res.code() != 200) throw fail(res.code());
      ResponseBody body = res.body();
      if (body == null) throw new IOException("PC 서버가 빈 응답을 보냈습니다.");
      return parse(base, body.byteStream());
    }
  }

  static List<Entry> parse(HttpUrl base, InputStream in) throws IOException {
    List<Entry> out = new ArrayList<>();
    try {
      XmlPullParser p = Xml.newPullParser();
      p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      p.setInput(in, "UTF-8");
      Entry cur = null;
      String text = null;
      for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
        if (ev == XmlPullParser.START_TAG) {
          String n = p.getName();
          if ("response".equals(n)) cur = new Entry();
          else if ("collection".equals(n) && cur != null) cur.dir = true;
          text = null;
        } else if (ev == XmlPullParser.TEXT) {
          text = p.getText();
        } else if (ev == XmlPullParser.END_TAG && cur != null) {
          String n = p.getName();
          String v = text == null ? "" : text.trim();
          if ("href".equals(n)) {
            HttpUrl u = base.resolve(v);
            if (u != null) cur.url = u.toString();
          } else if ("getcontentlength".equals(n)) {
            try {
              cur.size = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
          } else if ("getlastmodified".equals(n)) {
            cur.modified = httpDate(v);
          } else if ("response".equals(n)) {
            if (cur.url != null && !same(cur.url, base.toString())) {
              HttpUrl u = HttpUrl.get(cur.url);
              List<String> seg = u.pathSegments();
              String name = "";
              for (int i = seg.size() - 1; i >= 0 && name.isEmpty(); i--) name = seg.get(i);
              cur.name = name;
              if (!name.isEmpty() && !name.startsWith(".")) out.add(cur);
            }
            cur = null;
          }
          text = null;
        }
      }
    } catch (org.xmlpull.v1.XmlPullParserException e) {
      throw new IOException("PC 서버 목록 형식을 읽지 못했습니다.");
    }
    return out;
  }

  private static boolean same(String a, String b) {
    return a.replaceAll("/+$", "").equals(b.replaceAll("/+$", ""));
  }

  private static long httpDate(String v) {
    try {
      SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
      f.setTimeZone(TimeZone.getTimeZone("GMT"));
      Date d = f.parse(v);
      return d == null ? 0 : d.getTime();
    } catch (Exception e) {
      return 0;
    }
  }

  static Response call(Request req) throws IOException {
    try {
      return CLIENT.newCall(req).execute();
    } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
      throw new IOException("PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.");
    }
  }

  static IOException fail(int code) {
    if (code == 401 || code == 403)
      return new IOException("PC 서버 계정 또는 비밀번호가 맞지 않습니다.");
    if (code == 404) return new FileNotFoundException("PC 폴더에서 파일을 찾을 수 없습니다.");
    if (code == 502 || code == 503 || code == 504)
      return new IOException("PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.");
    return new IOException("PC 서버 응답 오류 (" + code + ")");
  }

  /**
   * 원격 ZIP 한 파일의 휴대폰 쪽 보관본. 읽기 화면에서 열면 읽는 위치부터 한 번의 긴 요청으로 뒤쪽을 계속
   * 받아 두고(streamAhead), 받은 조각은 캐시 파일에 남겨 다시 열 때 바로 읽는다. 같은 파일은 하나를 함께 쓴다.
   */
  static final class Cache implements Runnable {
    static final int CHUNK = 256 << 10, LOOK = 4, JUMP = 24;
    static final long BUDGET = 1536L << 20;
    static final Map<String, Cache> OPEN = new HashMap<>();

    final String url, auth, key, stamp;
    final File data, map;
    final long length;
    final int chunks;
    int refs;
    BitSet have = new BitSet();
    final RandomAccessFile raf;
    Thread worker;
    Call call;
    volatile boolean closed, stream;
    int want = -1;
    IOException error;

    static File dir(android.content.Context ctx) {
      File d = new File(ctx.getCacheDir(), "remote");
      d.mkdirs();
      return d;
    }

    static Cache open(android.content.Context ctx, String url, String auth) throws IOException {
      String key = Integer.toHexString(url.hashCode()) + "-" + Integer.toHexString(url.length());
      synchronized (OPEN) {
        Cache c = OPEN.get(key);
        if (c != null) {
          c.refs++;
          return c;
        }
        c = new Cache(dir(ctx), url, auth, key);
        c.refs = 1;
        OPEN.put(key, c);
        prune(dir(ctx));
        return c;
      }
    }

    private static String keyOf(File f) {
      String n = f.getName();
      int dot = n.lastIndexOf('.');
      return dot < 0 ? n : n.substring(0, dot);
    }

    /** 보관본 합계가 한도를 넘으면 오래 안 쓴 것부터 지운다(열려 있는 책은 제외). */
    static void prune(File dir) {
      File[] files = dir.listFiles((d, n) -> n.endsWith(".part"));
      if (files == null) return;
      Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
      long total = 0;
      for (File f : files) {
        total += f.length();
        if (total > BUDGET && !OPEN.containsKey(keyOf(f))) {
          f.delete();
          new File(dir, keyOf(f) + ".map").delete();
        }
      }
    }

    /** 임시 파일 정리: 열려 있지 않은 원격 보관본을 모두 지운다. */
    static void clear(android.content.Context ctx) {
      synchronized (OPEN) {
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) if (!OPEN.containsKey(keyOf(f))) f.delete();
      }
    }

    private Cache(File dir, String url, String auth, String key) throws IOException {
      this.url = url;
      this.auth = auth;
      this.key = key;
      data = new File(dir, key + ".part");
      map = new File(dir, key + ".map");
      // 끝부분(ZIP 목차)을 먼저 받으며 전체 크기·수정 시각을 확인한다.
      Request.Builder req =
          new Request.Builder().url(url).header("Range", "bytes=-" + (4 * CHUNK));
      if (!auth.isEmpty()) req.header("Authorization", auth);
      byte[] tail;
      long size;
      String mark;
      try (Response res = call(req.build())) {
        if (res.code() != 206) throw fail(res.code() == 200 ? 416 : res.code());
        String cr = res.header("Content-Range", "");
        int slash = cr.lastIndexOf('/');
        if (slash < 0) throw new IOException("PC 서버가 파일 크기를 알려 주지 않았습니다.");
        size = Long.parseLong(cr.substring(slash + 1).trim());
        mark = size + "|" + res.header("Last-Modified", "");
        ResponseBody body = res.body();
        if (body == null) throw new IOException("PC 서버가 빈 응답을 보냈습니다.");
        tail = body.bytes();
      }
      length = size;
      stamp = mark;
      chunks = (int) ((length + CHUNK - 1) / CHUNK);
      if (data.isFile() && map.isFile()) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(map))) {
          if (stamp.equals(in.readUTF())) {
            byte[] bits = new byte[in.readInt()];
            in.readFully(bits);
            have = BitSet.valueOf(bits);
          }
        } catch (IOException ignored) {
          have = new BitSet();
        }
      }
      if (have.isEmpty()) {
        data.delete();
        map.delete();
      }
      raf = new RandomAccessFile(data, "rw");
      if (raf.length() != length) raf.setLength(length);
      data.setLastModified(System.currentTimeMillis());
      long tailStart = length - tail.length;
      raf.seek(tailStart);
      raf.write(tail);
      int first = (int) (tailStart / CHUNK);
      if ((long) first * CHUNK < tailStart) first++;
      for (int c = first; c < chunks; c++) have.set(c);
    }

    long chunkEnd(int c) {
      return Math.min(length, (long) (c + 1) * CHUNK);
    }

    /** 읽기 화면에서 연 책은 읽는 위치부터 끝까지 미리 받는다. 표지 확인 같은 짧은 열기는 필요한 조각만 받는다. */
    synchronized void streamAhead() {
      stream = true;
      if (want < 0) want = 0;
      ensureWorker();
    }

    synchronized void ensureWorker() {
      if (worker == null && !closed && have.cardinality() < chunks) {
        error = null;
        worker = new Thread(this, "ccomics-remote");
        worker.setDaemon(true);
        worker.start();
      }
    }

    synchronized int read(long pos, ByteBuffer dst) throws IOException {
      if (pos >= length) return -1;
      int c = (int) (pos / CHUNK);
      long deadline = System.currentTimeMillis() + 45000;
      while (!have.get(c)) {
        if (closed) throw new ClosedChannelException();
        if (error != null && worker == null) {
          IOException e = error;
          error = null;
          throw e;
        }
        if (want != c) {
          want = c;
          notifyAll();
        }
        ensureWorker();
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) throw new IOException("PC 응답이 너무 느립니다. 잠시 뒤 다시 열어 주세요.");
        try {
          wait(Math.min(left, 1000));
        } catch (InterruptedException e) {
          throw new InterruptedIOException();
        }
      }
      want = c;
      int n = (int) Math.min(dst.remaining(), chunkEnd(c) - pos);
      byte[] buf = new byte[n];
      raf.seek(pos);
      raf.readFully(buf);
      dst.put(buf);
      return n;
    }

    /** 지금 받아야 할 첫 조각. 없으면 -1. */
    private synchronized int next() {
      int from = Math.max(0, want);
      int c = have.nextClearBit(from);
      if (c < chunks && (stream || c < from + LOOK)) return c;
      if (stream) {
        c = have.nextClearBit(0);
        if (c < chunks) return c;
      }
      return -1;
    }

    public void run() {
      int failures = 0;
      while (true) {
        int start, end;
        synchronized (this) {
          start = closed ? -1 : next();
          if (start < 0) {
            worker = null;
            save();
            notifyAll();
            return;
          }
          int limit = Math.min(chunks, stream ? chunks : start + LOOK);
          end = start;
          while (end + 1 < limit && !have.get(end + 1)) end++;
        }
        Request.Builder req =
            new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + (long) start * CHUNK + "-" + (chunkEnd(end) - 1));
        if (!auth.isEmpty()) req.header("Authorization", auth);
        Call current = CLIENT.newCall(req.build());
        synchronized (this) {
          call = current;
        }
        try (Response res = current.execute()) {
          if (res.code() != 206) throw fail(res.code() == 200 ? 416 : res.code());
          ResponseBody body = res.body();
          if (body == null) throw new IOException("PC 서버가 빈 응답을 보냈습니다.");
          InputStream in = body.byteStream();
          byte[] buf = new byte[CHUNK];
          for (int c = start; c <= end; c++) {
            int size = (int) (chunkEnd(c) - (long) c * CHUNK), got = 0;
            while (got < size) {
              int n = in.read(buf, got, size - got);
              if (n < 0) throw new EOFException();
              got += n;
            }
            failures = 0;
            synchronized (this) {
              if (closed) break;
              raf.seek((long) c * CHUNK);
              raf.write(buf, 0, size);
              have.set(c);
              notifyAll();
              // 읽는 위치가 받는 구간에서 멀리 벗어나면(건너뛰기) 그 위치부터 다시 받는다.
              if (want >= 0 && !have.get(want) && (want < c || want > c + JUMP)) break;
            }
          }
        } catch (IOException e) {
          if (closed) continue;
          boolean auth = e.getMessage() != null && e.getMessage().contains("비밀번호");
          if (++failures >= 4 || auth) {
            synchronized (this) {
              error =
                  e instanceof UnknownHostException
                          || e instanceof ConnectException
                          || e instanceof SocketTimeoutException
                      ? new IOException("PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.")
                      : e;
              worker = null;
              save();
              notifyAll();
              return;
            }
          }
          try {
            Thread.sleep(700L * failures);
          } catch (InterruptedException ignored) {
          }
        }
      }
    }

    private synchronized void save() {
      try (DataOutputStream out = new DataOutputStream(new FileOutputStream(map))) {
        out.writeUTF(stamp);
        byte[] bits = have.toByteArray();
        out.writeInt(bits.length);
        out.write(bits);
      } catch (IOException ignored) {
      }
    }

    void release() {
      synchronized (OPEN) {
        if (--refs > 0) return;
        OPEN.remove(key);
      }
      Call c;
      synchronized (this) {
        closed = true;
        c = call;
        notifyAll();
      }
      if (c != null) c.cancel();
      synchronized (this) {
        save();
        try {
          raf.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  /** 원격 ZIP을 위치만 따로 갖고 Cache에서 읽는다. */
  static final class Channel implements SeekableByteChannel {
    final Cache cache;
    long pos;
    boolean open = true;

    Channel(android.content.Context ctx, String url, String auth) throws IOException {
      cache = Cache.open(ctx, url, auth);
    }

    public int read(ByteBuffer dst) throws IOException {
      if (!open) throw new ClosedChannelException();
      if (!dst.hasRemaining()) return 0;
      int total = 0;
      while (dst.hasRemaining()) {
        int n = cache.read(pos, dst);
        if (n < 0) break;
        pos += n;
        total += n;
      }
      return total == 0 ? -1 : total;
    }

    public int write(ByteBuffer src) {
      throw new NonWritableChannelException();
    }

    public long position() {
      return pos;
    }

    public SeekableByteChannel position(long p) {
      if (p < 0) throw new IllegalArgumentException();
      pos = p;
      return this;
    }

    public long size() {
      return cache.length;
    }

    public SeekableByteChannel truncate(long n) {
      throw new NonWritableChannelException();
    }

    public boolean isOpen() {
      return open;
    }

    public void close() {
      if (!open) return;
      open = false;
      cache.release();
    }
  }
}
