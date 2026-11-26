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

  /** HTTP Range 요청으로 원격 ZIP을 블록 단위로 읽는다. 최근 블록 몇 개를 메모리에 둔다. */
  static final class Channel implements SeekableByteChannel {
    static final int BLOCK = 1 << 20;
    final String url, auth;
    long pos, length;
    boolean open = true;
    final LinkedHashMap<Long, byte[]> blocks =
        new LinkedHashMap<Long, byte[]>(8, 0.75f, true) {
          protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
            return size() > 6;
          }
        };

    Channel(String url, String auth) throws IOException {
      this.url = url;
      this.auth = auth;
      // ZIP은 끝(중앙 디렉터리)부터 읽으므로 마지막 블록을 먼저 받아 전체 크기도 함께 얻는다.
      tail = fetch("bytes=-" + BLOCK, true);
      tailStart = length - tail.length;
    }

    byte[] tail;
    long tailStart;

    private byte[] fetch(String range, boolean learnLength) throws IOException {
      IOException last = null;
      for (int attempt = 0; attempt < 3; attempt++) {
        Request.Builder req = new Request.Builder().url(url).header("Range", range);
        if (!auth.isEmpty()) req.header("Authorization", auth);
        try (Response res = call(req.build())) {
          if (res.code() != 206) throw fail(res.code() == 200 ? 416 : res.code());
          if (learnLength) {
            String cr = res.header("Content-Range", "");
            int slash = cr.lastIndexOf('/');
            if (slash < 0) throw new IOException("PC 서버가 파일 크기를 알려 주지 않았습니다.");
            length = Long.parseLong(cr.substring(slash + 1).trim());
          }
          ResponseBody body = res.body();
          if (body == null) throw new IOException("PC 서버가 빈 응답을 보냈습니다.");
          return body.bytes();
        } catch (FileNotFoundException e) {
          throw e;
        } catch (IOException e) {
          last = e;
          if (e.getMessage() != null && e.getMessage().contains("비밀번호")) throw e;
        }
      }
      throw last;
    }

    private byte[] block(long start) throws IOException {
      byte[] b = blocks.get(start);
      if (b == null && start >= tailStart) {
        int from = (int) (start - tailStart);
        b = Arrays.copyOfRange(tail, from, (int) Math.min(tail.length, from + (long) BLOCK));
        blocks.put(start, b);
      }
      if (b == null) {
        long end = Math.min(start + BLOCK, length) - 1;
        b = fetch("bytes=" + start + "-" + end, false);
        blocks.put(start, b);
      }
      return b;
    }

    public synchronized int read(ByteBuffer dst) throws IOException {
      if (!open) throw new ClosedChannelException();
      if (!dst.hasRemaining()) return 0;
      if (pos >= length) return -1;
      int total = 0;
      while (dst.hasRemaining() && pos < length) {
        long start = pos / BLOCK * BLOCK;
        byte[] b = block(start);
        int index = (int) (pos - start);
        if (index >= b.length) break;
        int n = Math.min(dst.remaining(), b.length - index);
        dst.put(b, index, n);
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
      return length;
    }

    public SeekableByteChannel truncate(long n) {
      throw new NonWritableChannelException();
    }

    public boolean isOpen() {
      return open;
    }

    public synchronized void close() {
      open = false;
      blocks.clear();
      tail = null;
    }
  }
}
