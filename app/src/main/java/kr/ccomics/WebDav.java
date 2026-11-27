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

/** 원격 폴더(WebDAV over HTTPS — PC 서버·PikPak 등) 목록 읽기와 부분 읽기. */
final class WebDav {
  static final String PIKPAK_DEFAULT_URL = "https://dav.pikpak.ai/";

  static final OkHttpClient CLIENT =
      new OkHttpClient.Builder()
          .connectTimeout(12, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .build();

  static boolean isUrl(String url) {
    return url != null && (url.startsWith("https://") || url.startsWith("http://"));
  }

  static boolean isPikPak(String url) {
    if (!isUrl(url)) return false;
    HttpUrl u = HttpUrl.parse(url);
    if (u == null) return false;
    String h = u.host().toLowerCase(Locale.ROOT);
    return h.equals("dav.pikpak.ai")
        || h.endsWith(".pikpak.ai")
        || h.equals("dav.mypikpak.com")
        || h.endsWith(".mypikpak.com")
        || h.equals("dav.pikpakdrive.com")
        || h.endsWith(".pikpakdrive.com");
  }

  /** 원격 폴더 주소를 사용자에게 읽기 쉬운 이름으로 요약한다. */
  static String displayName(String url) {
    if (!isUrl(url)) return url == null ? "" : url;
    HttpUrl u = HttpUrl.parse(url);
    if (u == null) return url;
    List<String> segs = new ArrayList<>();
    for (String s : u.pathSegments()) if (!s.isEmpty()) segs.add(s);
    if (isPikPak(url)) {
      if (segs.isEmpty()) return "PikPak · 최상위";
      return "PikPak · /" + String.join("/", segs);
    }
    String path = segs.isEmpty() ? "" : "/" + String.join("/", segs);
    return "PC 원격 · " + u.host() + path;
  }

  /** 주소 입력을 https://…/ 형식으로 정규화한다. 스킴이 없으면 https://를 붙인다. */
  static HttpUrl parseEndpoint(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    if (!s.contains("://")) s = "https://" + s;
    HttpUrl u = HttpUrl.parse(s);
    if (u == null || !u.isHttps()) return null;
    return u;
  }

  static String auth(String user, String pass) {
    return user == null || user.isEmpty() ? "" : Credentials.basic(user, pass == null ? "" : pass, StandardCharsets.UTF_8);
  }

  static final class Entry {
    String url, name;
    boolean dir;
    long size, modified;
  }

  private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
  private static final String PROPFIND =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:prop>"
          + "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:displayname/></D:prop></D:propfind>";

  /** 폴더 바로 아래 항목을 돌려준다(폴더 자신은 뺀다). 429/일시 오류는 짧게 재시도한다. */
  static List<Entry> list(String dirUrl, String auth) throws IOException {
    HttpUrl base = HttpUrl.get(dirUrl.endsWith("/") ? dirUrl : dirUrl + "/");
    for (int attempt = 0; attempt < 3; attempt++) {
      Request.Builder req =
          new Request.Builder()
              .url(base)
              .header("Depth", "1")
              .method("PROPFIND", RequestBody.create(PROPFIND, XML));
      if (!auth.isEmpty()) req.header("Authorization", auth);
      try (Response res = call(req.build())) {
        if ((res.code() == 429 || res.code() == 503) && attempt < 2) {
          sleepRetry(res, attempt);
          continue;
        }
        if (res.code() != 207 && res.code() != 200) throw fail(res.code(), dirUrl);
        ResponseBody body = res.body();
        if (body == null) throw new IOException(targetName(dirUrl) + "가 빈 응답을 보냈습니다.");
        return parse(base, body.byteStream());
      }
    }
    throw fail(429, dirUrl);
  }

  static long retryDelayMs(Response res, int attempt) {
    if (res != null) {
      String ra = res.header("Retry-After");
      if (ra != null) {
        try {
          long sec = Long.parseLong(ra.trim());
          if (sec > 0) return Math.min(8000L, sec * 1000L);
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return Math.min(6000L, 1200L * (attempt + 1));
  }

  private static void sleepRetry(Response res, int attempt) throws InterruptedIOException {
    try {
      Thread.sleep(retryDelayMs(res, attempt));
    } catch (InterruptedException e) {
      throw new InterruptedIOException();
    }
  }

  static List<Entry> parse(HttpUrl base, InputStream in) throws IOException {
    List<Entry> out = new ArrayList<>();
    try {
      XmlPullParser p = Xml.newPullParser();
      p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      p.setInput(in, "UTF-8");
      Entry cur = null;
      String displayName = null;
      StringBuilder text = null;
      for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
        if (ev == XmlPullParser.START_TAG) {
          String n = p.getName();
          if ("response".equals(n)) {
            cur = new Entry();
            displayName = null;
          } else if ("collection".equals(n) && cur != null) cur.dir = true;
          text = new StringBuilder();
        } else if (ev == XmlPullParser.TEXT) {
          if (text != null) text.append(p.getText());
        } else if (ev == XmlPullParser.END_TAG && cur != null) {
          String n = p.getName();
          String v = text == null ? "" : text.toString().trim();
          if ("href".equals(n)) {
            HttpUrl u = base.resolve(v);
            if (u != null) {
              // TLS 종단 프록시가 href에 http://를 돌려줘도 원래 HTTPS 같은 호스트면 https로 맞춘다.
              if (base.isHttps()
                  && !u.isHttps()
                  && u.host().equalsIgnoreCase(base.host())) {
                HttpUrl.Builder b = u.newBuilder().scheme("https");
                if (u.port() == 80) b.port(443);
                u = b.build();
              }
              cur.url = u.toString();
            }
          } else if ("getcontentlength".equals(n)) {
            try {
              cur.size = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
          } else if ("getlastmodified".equals(n)) {
            cur.modified = httpDate(v);
          } else if ("displayname".equals(n)) {
            if (!v.isEmpty()) displayName = v;
          } else if ("response".equals(n)) {
            if (cur.url != null && !same(cur.url, base.toString())) {
              HttpUrl u = HttpUrl.get(cur.url);
              List<String> seg = u.pathSegments();
              String name = "";
              for (int i = seg.size() - 1; i >= 0 && name.isEmpty(); i--) name = seg.get(i);
              if (name.isEmpty() && displayName != null) name = displayName;
              cur.name = name;
              if (!name.isEmpty() && !name.startsWith(".")) out.add(cur);
            }
            cur = null;
            displayName = null;
          }
          text = null;
        }
      }
    } catch (org.xmlpull.v1.XmlPullParserException e) {
      throw new IOException(targetName(base.toString()) + " 목록 형식을 읽지 못했습니다.");
    }
    return out;
  }

  private static boolean same(String a, String b) {
    HttpUrl ua = HttpUrl.parse(a), ub = HttpUrl.parse(b);
    if (ua != null && ub != null) {
      if (!ua.host().equalsIgnoreCase(ub.host()) || ua.port() != ub.port()) return false;
      return normalizedSegments(ua).equals(normalizedSegments(ub));
    }
    return a.replaceAll("/+$", "").equals(b.replaceAll("/+$", ""));
  }

  private static List<String> normalizedSegments(HttpUrl u) {
    List<String> raw = u.pathSegments();
    int end = raw.size();
    while (end > 0 && raw.get(end - 1).isEmpty()) end--;
    return raw.subList(0, end);
  }

  private static long httpDate(String v) {
    if (v == null || v.isEmpty()) return 0;
    try {
      SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
      f.setTimeZone(TimeZone.getTimeZone("GMT"));
      Date d = f.parse(v);
      return d == null ? 0 : d.getTime();
    } catch (Exception ignored) {
    }
    try {
      String norm = v.replaceFirst("\\.\\d+(Z|[+-]\\d\\d:?\\d\\d)$", "$1").replace("Z", "+0000");
      SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
      Date d = iso.parse(norm);
      return d == null ? 0 : d.getTime();
    } catch (Exception e) {
      return 0;
    }
  }

  private static String targetName(String url) {
    return isPikPak(url) ? "PikPak 서버" : "PC 서버";
  }

  static Response call(Request req) throws IOException {
    return call(CLIENT, req);
  }

  static Response call(OkHttpClient client, Request req) throws IOException {
    try {
      return client.newCall(req).execute();
    } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
      String url = req.url().toString();
      if (isPikPak(url))
        throw new IOException("PikPak 서버에 연결할 수 없습니다. 인터넷 연결과 서버 주소를 확인하세요.");
      throw new IOException("PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.");
    }
  }

  static IOException fail(int code) {
    return fail(code, "");
  }

  static IOException fail(int code, String url) {
    boolean pikpak = isPikPak(url);
    if (code == 401 || code == 403) {
      if (pikpak)
        return new IOException(
            "PikPak WebDAV 인증에 실패했습니다. 프리미엄 상태와 WebDAV 아이디·비밀번호를 확인하세요.");
      return new IOException("PC 서버 계정 또는 비밀번호가 맞지 않습니다.");
    }
    if (code == 404) {
      if (pikpak) return new FileNotFoundException("PikPak 폴더에서 파일 또는 경로를 찾을 수 없습니다.");
      return new FileNotFoundException("PC 폴더에서 파일을 찾을 수 없습니다.");
    }
    if (code == 429) {
      if (pikpak) return new IOException("PikPak 요청이 잠시 제한되었습니다(429). 잠시 뒤 다시 시도하세요.");
      return new IOException("서버 요청이 잠시 제한되었습니다(429). 잠시 뒤 다시 시도하세요.");
    }
    if (code == 502 || code == 503 || code == 504) {
      if (pikpak) return new IOException("PikPak 서버가 일시적으로 응답하지 않습니다(" + code + "). 잠시 뒤 다시 시도하세요.");
      return new IOException("PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.");
    }
    return new IOException(targetName(url) + " 응답 오류 (" + code + ")");
  }

  /**
   * 원격 ZIP 한 파일의 휴대폰 쪽 보관본. 읽기 화면에서 열면 읽는 위치부터 한 번의 긴 요청으로 뒤쪽을 계속
   * 받아 두고(streamAhead), 받은 조각은 캐시 파일에 남겨 다시 열 때 바로 읽는다. 같은 파일은 하나를 함께 쓴다.
   */
  static final class Cache implements Runnable {
    static final int CHUNK = 64 << 10, LOOK = 8;
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
      // 일부 클라우드 WebDAV(예: PikPak)가 bytes=- 접미사 Range를 거부할 수 있어 HEAD/bytes=0-0 폴백을 둔다.
      TailResult tr = fetchTail(url, auth);
      byte[] tail = tr.tail;
      long size = tr.size;
      String mark = tr.mark;
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

    private static final class TailResult {
      final byte[] tail;
      final long size;
      final String mark;

      TailResult(byte[] tail, long size, String mark) {
        this.tail = tail;
        this.size = size;
        this.mark = mark;
      }
    }

    private static long parseContentRangeTotal(String cr) {
      if (cr == null) return -1;
      int slash = cr.lastIndexOf('/');
      if (slash < 0) return -1;
      try {
        long v = Long.parseLong(cr.substring(slash + 1).trim());
        return v > 0 ? v : -1;
      } catch (NumberFormatException e) {
        return -1;
      }
    }

    private static TailResult fetchTail(String url, String auth) throws IOException {
      long tailSpan = 4L * CHUNK;
      // 1) 1회 왕복으로 끝부분을 바로 요청(bytes=-N).
      for (int attempt = 0; attempt < 3; attempt++) {
        Request.Builder req =
            new Request.Builder().url(url).header("Range", "bytes=-" + tailSpan);
        if (!auth.isEmpty()) req.header("Authorization", auth);
        try (Response res = call(req.build())) {
          if ((res.code() == 429 || res.code() == 503) && attempt < 2) {
            sleepRetry(res, attempt);
            continue;
          }
          if (res.code() == 206) {
            long total = parseContentRangeTotal(res.header("Content-Range", ""));
            if (total > 0) {
              ResponseBody body = res.body();
              if (body == null) throw new IOException(targetName(url) + "가 빈 응답을 보냈습니다.");
              byte[] tail = body.bytes();
              return new TailResult(tail, total, total + "|" + res.header("Last-Modified", ""));
            }
          }
          if (res.code() == 200) {
            long cl = res.body() != null ? res.body().contentLength() : -1;
            if (cl > 0 && cl <= tailSpan) {
              byte[] all = res.body().bytes();
              return new TailResult(
                  all, all.length, all.length + "|" + res.header("Last-Modified", ""));
            }
          }
          if (res.code() == 401 || res.code() == 403 || res.code() == 404)
            throw fail(res.code(), url);
          break; // 400/416/501/200(대용량) 등은 명시적 Range 폴백으로 처리
        }
      }
      // 2) 폴백: HEAD 또는 bytes=0-0으로 전체 크기를 먼저 확인한 뒤 명시적 bytes=start-end 요청.
      long total = -1;
      String lastMod = "";
      Request.Builder headReq = new Request.Builder().url(url).head();
      if (!auth.isEmpty()) headReq.header("Authorization", auth);
      try (Response res = call(headReq.build())) {
        if (res.code() == 401 || res.code() == 403 || res.code() == 404)
          throw fail(res.code(), url);
        if (res.isSuccessful()) {
          String cl = res.header("Content-Length", "");
          try {
            long v = Long.parseLong(cl.trim());
            if (v > 0) total = v;
          } catch (NumberFormatException ignored) {
          }
          lastMod = res.header("Last-Modified", "");
        }
      }
      if (total <= 0) {
        Request.Builder probe = new Request.Builder().url(url).header("Range", "bytes=0-0");
        if (!auth.isEmpty()) probe.header("Authorization", auth);
        try (Response res = call(probe.build())) {
          if (res.code() == 206) {
            total = parseContentRangeTotal(res.header("Content-Range", ""));
            if (lastMod.isEmpty()) lastMod = res.header("Last-Modified", "");
          } else if (res.code() == 200 && res.body() != null && res.body().contentLength() > 0) {
            total = res.body().contentLength();
            if (lastMod.isEmpty()) lastMod = res.header("Last-Modified", "");
          } else {
            throw fail(res.code() == 200 ? 416 : res.code(), url);
          }
        }
      }
      if (total <= 0) throw new IOException(targetName(url) + "가 파일 크기를 알려 주지 않았습니다.");
      long tailStart = Math.max(0L, total - tailSpan);
      for (int attempt = 0; attempt < 3; attempt++) {
        Request.Builder req =
            new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + tailStart + "-" + (total - 1));
        if (!auth.isEmpty()) req.header("Authorization", auth);
        try (Response res = call(req.build())) {
          if ((res.code() == 429 || res.code() == 503) && attempt < 2) {
            sleepRetry(res, attempt);
            continue;
          }
          if (res.code() != 206 && !(res.code() == 200 && tailStart == 0))
            throw fail(res.code() == 200 ? 416 : res.code(), url);
          ResponseBody body = res.body();
          if (body == null) throw new IOException(targetName(url) + "가 빈 응답을 보냈습니다.");
          byte[] tail = body.bytes();
          if (lastMod.isEmpty()) lastMod = res.header("Last-Modified", "");
          return new TailResult(tail, total, total + "|" + lastMod);
        }
      }
      throw fail(429, url);
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

    /** 읽는 순서대로 곧 볼 페이지들의 조각 범위(Mihon·Komga 클라이언트처럼 페이지 순서로 미리 받기). */
    final List<int[]> ahead = new ArrayList<>();

    synchronized void prefetch(List<long[]> ranges) {
      ahead.clear();
      for (long[] r : ranges) {
        long a = Math.max(0, r[0]), b = Math.min(length, r[1]);
        if (b > a) ahead.add(new int[] {(int) (a / CHUNK), (int) ((b - 1) / CHUNK)});
      }
      notifyAll();
      ensureWorker();
    }

    private int span(int from, int last) {
      int e = from;
      while (e + 1 <= last && e + 1 < chunks && !have.get(e + 1)) e++;
      return e;
    }

    /** 다음에 받을 {첫 조각, 끝 조각, 우선순위}. 0=지금 읽는 곳, 1=곧 볼 페이지, 2=남는 시간에 나머지. */
    private synchronized int[] pick() {
      if (want >= 0 && want < chunks && !have.get(want)) {
        int last = want + LOOK - 1;
        for (int[] r : ahead) if (want >= r[0] && want <= r[1]) last = Math.max(last, r[1]);
        return new int[] {want, span(want, last), 0};
      }
      for (int[] r : ahead) {
        int c = have.nextClearBit(r[0]);
        if (c <= r[1] && c < chunks) return new int[] {c, span(c, r[1]), 1};
      }
      if (stream) {
        int c = have.nextClearBit(Math.max(0, want));
        if (c >= chunks) c = have.nextClearBit(0);
        if (c < chunks) return new int[] {c, span(c, chunks - 1), 2};
      }
      return null;
    }

    public void run() {
      int failures = 0;
      while (true) {
        int start, end, level;
        synchronized (this) {
          int[] p = closed ? null : pick();
          if (p == null) {
            worker = null;
            if (!closed) save();
            notifyAll();
            return;
          }
          start = p[0];
          end = p[1];
          level = p[2];
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
          if (res.code() != 206) throw fail(res.code() == 200 ? 416 : res.code(), url);
          ResponseBody body = res.body();
          if (body == null) throw new IOException(targetName(url) + "가 빈 응답을 보냈습니다.");
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
              // 더 급한 조각(지금 읽는 곳·곧 볼 페이지)이 이 요청 뒤쪽에 없으면 끊고 그쪽부터 받는다.
              int[] p = pick();
              if (p != null && p[2] < level && !(p[0] > c && p[0] <= end)) break;
            }
          }
        } catch (IOException e) {
          if (closed) continue;
          boolean authFail = e.getMessage() != null && e.getMessage().contains("비밀번호");
          boolean rateLimited = e.getMessage() != null && e.getMessage().contains("(429)");
          int maxFail = rateLimited ? 6 : 4;
          if (++failures >= maxFail || authFail) {
            synchronized (this) {
              error =
                  e instanceof UnknownHostException
                          || e instanceof ConnectException
                          || e instanceof SocketTimeoutException
                      ? new IOException(
                          isPikPak(url)
                              ? "PikPak 서버에 연결할 수 없습니다. 인터넷 연결과 서버 주소를 확인하세요."
                              : "PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.")
                      : e;
              worker = null;
              if (!closed) save();
              notifyAll();
              return;
            }
          }
          try {
            Thread.sleep((rateLimited ? 1500L : 700L) * failures);
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
      // 같은 책을 곧바로 다시 열어도 기록이 다 남은 뒤 열리도록 정리 전체를 OPEN 잠금 안에서 한다.
      synchronized (OPEN) {
        if (--refs > 0) return;
        OPEN.remove(key);
        Call c;
        Thread w;
        synchronized (this) {
          closed = true;
          c = call;
          w = worker;
          notifyAll();
        }
        if (c != null) c.cancel();
        if (w != null && w != Thread.currentThread())
          try {
            w.join(5000);
          } catch (InterruptedException ignored) {
          }
        synchronized (this) {
          save();
          try {
            raf.close();
          } catch (IOException ignored) {
          }
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
      // 받던 요청을 끊고 기다리는 정리는 화면을 멈추지 않도록 따로 한다.
      Thread t = new Thread(cache::release, "ccomics-remote-close");
      t.setDaemon(true);
      t.start();
    }
  }
}
