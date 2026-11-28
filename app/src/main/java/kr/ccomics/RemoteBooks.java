package kr.ccomics;

import android.content.Context;
import java.io.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;

/**
 * PC 원격 책 보관함. 권을 처음 열 때 ZIP 전체를 여러 연결로 나눠 받아 휴대폰에 저장하고, 이후에는 PC·네트워크
 * 없이 저장본으로 연다. 사용자가 지우기 전까지 계속 남는다(서재 설정 → PC 원격 보관함 관리).
 *
 * <p>휴대폰 망은 잠깐씩 끊기므로 다운로드 관리 앱처럼 2MB 조각 단위로 받고, 받은 조각은 기록해 두었다가
 * 끊기거나 취소돼도 다음에 이어서 받는다.
 */
final class RemoteBooks {
  /** Funnel 실측: 연결 1개 0.47MB/s, 4개 1.74MB/s, 8개 2.31MB/s, 16개 2.38MB/s. */
  static final int PARALLEL = 8, PIECE = 2 << 20, ATTEMPTS = 8;
  /** PikPak은 probe에서 1회 302로 얻은 CDN 직링크를 6개 연결로 직접 받아 게이트웨이 429를 피하면서 대역폭을 뽑는다. */
  static final int PIKPAK_PARALLEL = 6, PIKPAK_PIECE = 4 << 20;

  static int parallelFor(String url) {
    return WebDav.isPikPak(url) ? PIKPAK_PARALLEL : PARALLEL;
  }

  static int pieceFor(String url) {
    return WebDav.isPikPak(url) ? PIKPAK_PIECE : PIECE;
  }

  private static final long PIECE_MAGIC = 0x43435032L;
  private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

  static final OkHttpClient CLIENT;

  static {
    Dispatcher d = new Dispatcher();
    d.setMaxRequests(PARALLEL * 2);
    d.setMaxRequestsPerHost(PARALLEL);
    // HTTP/2는 연결 하나에 몰아 보내 속도가 1개 연결 수준에 머문다. 연결을 여러 개 쓰도록 HTTP/1.1로 고정한다.
    CLIENT =
        WebDav.CLIENT
            .newBuilder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .dispatcher(d)
            .connectionPool(new ConnectionPool(PARALLEL, 5, TimeUnit.MINUTES))
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
  }

  interface Progress {
    void update(long done, long total);

    boolean cancelled();
  }

  /** 진행 중인 원격 다운로드를 즉시 끊기 위한 취소 핸들. */
  static final class Cancel {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService pool;

    boolean isCancelled() {
      return cancelled.get();
    }

    /** UI 스레드에서 즉시 취소 플래그만 세운다(소켓 종료는 백그라운드에서 cancel() 호출). */
    void markCancelled() {
      cancelled.set(true);
    }

    void track(Call c) {
      calls.add(c);
      if (cancelled.get()) c.cancel();
    }

    void untrack(Call c) {
      calls.remove(c);
    }

    void bind(ExecutorService p) {
      pool = p;
      if (cancelled.get() && p != null) p.shutdownNow();
    }

    void cancel() {
      cancelled.set(true);
      for (Call c : calls) c.cancel();
      ExecutorService p = pool;
      if (p != null) p.shutdownNow();
    }
  }

  /** SocketTimeoutException(InterruptedIOException의 하위 타입)과 실제 사용자 취소를 구분한다. */
  private static final class CancelledException extends InterruptedIOException {
    CancelledException() {
      super("취소");
    }
  }

  /** 받기가 끝나지 못했을 때. 받은 만큼은 남아 있어 다시 열면 이어 받는다. */
  static final class Incomplete extends IOException {
    final long done, total;

    Incomplete(String reason, long done, long total) {
      super(reason);
      this.done = done;
      this.total = total;
    }
  }

  static File dir(Context ctx) {
    File d = new File(ctx.getFilesDir(), "remote-books");
    d.mkdirs();
    return d;
  }

  /** 묶음 ZIP 안의 권이면 바깥 ZIP 주소. */
  static String outer(String url) {
    int split = url.indexOf(Archive.NESTED);
    return split < 0 ? url : url.substring(0, split);
  }

  static String fileName(String url) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-1").digest(outer(url).getBytes("UTF-8"));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
      return sb + ".zip";
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static File file(Context ctx, String url) {
    return new File(dir(ctx), fileName(url));
  }

  static boolean has(Context ctx, String url) {
    return file(ctx, url).isFile();
  }

  /** 보관함에 완료 저장된 .zip 파일명 집합을 한 번의 디렉터리 조회로 가져온다. */
  static Set<String> downloadedNames(Context ctx) {
    if (ctx == null || ctx.getFilesDir() == null) return Collections.emptySet();
    File d = new File(ctx.getFilesDir(), "remote-books");
    if (!d.isDirectory()) return Collections.emptySet();
    String[] list = d.list();
    if (list == null || list.length == 0) return Collections.emptySet();
    Set<String> names = new HashSet<>(list.length * 2);
    for (String n : list) if (n.endsWith(".zip")) names.add(n);
    return names;
  }

  static boolean has(Set<String> downloaded, String url) {
    return downloaded != null && !downloaded.isEmpty() && downloaded.contains(fileName(url));
  }

  /** 저장본을 여는 주소(묶음 안 권이면 안쪽 경로를 붙인다). */
  static String localAddress(Context ctx, String url) {
    String local = android.net.Uri.fromFile(file(ctx, url)).toString();
    int split = url.indexOf(Archive.NESTED);
    return split < 0 ? local : local + url.substring(split);
  }

  static final class Item {
    final String fileName;
    final long bytes;
    final long modified;
    final boolean complete;
    final int percent;

    Item(String fileName, long bytes, long modified, boolean complete, int percent) {
      this.fileName = fileName;
      this.bytes = bytes;
      this.modified = modified;
      this.complete = complete;
      this.percent = percent;
    }
  }

  static String baseName(String rawName) {
    if (rawName == null) return "";
    if (rawName.endsWith(".zip.pieces.tmp"))
      return rawName.substring(0, rawName.length() - ".pieces.tmp".length());
    if (rawName.endsWith(".zip.pieces"))
      return rawName.substring(0, rawName.length() - ".pieces".length());
    if (rawName.endsWith(".zip.part"))
      return rawName.substring(0, rawName.length() - ".part".length());
    return rawName;
  }

  static String formatSize(long bytes) {
    if (bytes <= 0) return "0MB";
    if (bytes >= (1L << 30))
      return String.format(Locale.KOREA, "%.1fGB", bytes / (double) (1L << 30));
    if (bytes >= (10L << 20)) return Math.round(bytes / (double) (1L << 20)) + "MB";
    if (bytes >= (1L << 20))
      return String.format(Locale.KOREA, "%.1fMB", bytes / (double) (1L << 20));
    if (bytes >= 1024) return Math.max(1, bytes >> 10) + "KB";
    return bytes + "B";
  }

  static long[] usage(Context ctx) {
    long bytes = 0, count = 0;
    File[] files = dir(ctx).listFiles();
    if (files != null)
      for (File f : files) {
        bytes += f.length();
        if (f.getName().endsWith(".zip")) count++;
      }
    return new long[] {count, bytes};
  }

  /** 보관함에 저장된 완료/미완료 파일 목록을 파일 단위로 묶어 돌려준다(기본: 용량 큰 순). */
  static List<Item> list(Context ctx) {
    File d = dir(ctx);
    File[] files = d.listFiles();
    if (files == null || files.length == 0) return Collections.emptyList();
    Map<String, List<File>> grouped = new LinkedHashMap<>();
    for (File f : files) {
      if (!f.isFile()) continue;
      grouped.computeIfAbsent(baseName(f.getName()), k -> new ArrayList<>(2)).add(f);
    }
    List<Item> out = new ArrayList<>(grouped.size());
    for (Map.Entry<String, List<File>> e : grouped.entrySet()) {
      String base = e.getKey();
      long bytes = 0, modified = 0;
      File zipFile = null, partFile = null, piecesFile = null;
      for (File f : e.getValue()) {
        bytes += f.length();
        modified = Math.max(modified, f.lastModified());
        String n = f.getName();
        if (n.equals(base) && n.endsWith(".zip")) zipFile = f;
        else if (n.equals(base + ".part")) partFile = f;
        else if (n.equals(base + ".pieces")) piecesFile = f;
      }
      boolean complete = zipFile != null;
      int percent = complete ? 100 : 0;
      if (!complete && partFile != null && piecesFile != null) {
        long total = partFile.length();
        if (total > 0) {
          PieceInfo info = loadPiecesInfo(piecesFile, total);
          if (info.pieceSize > 0) {
            int count = (int) ((total + info.pieceSize - 1) / info.pieceSize);
            long got = 0;
            for (int i = info.done.nextSetBit(0);
                i >= 0 && i < count;
                i = info.done.nextSetBit(i + 1))
              got += Math.min(total, (long) (i + 1) * info.pieceSize) - (long) i * info.pieceSize;
            percent = (int) Math.min(99, got * 100 / total);
          }
        }
      }
      out.add(new Item(base, bytes, modified, complete, percent));
    }
    out.sort(
        (a, b) -> {
          int c = Long.compare(b.bytes, a.bytes);
          return c != 0 ? c : Long.compare(b.modified, a.modified);
        });
    return out;
  }

  static long itemBytes(Context ctx, String url) {
    File d = dir(ctx);
    String base = fileName(url);
    long total = 0;
    for (String suffix : new String[] {"", ".part", ".pieces", ".pieces.tmp"}) {
      File f = new File(d, base + suffix);
      if (f.isFile()) total += f.length();
    }
    return total;
  }

  static boolean hasAny(Context ctx, String url) {
    File d = dir(ctx);
    String base = fileName(url);
    return new File(d, base).isFile()
        || new File(d, base + ".part").isFile()
        || new File(d, base + ".pieces").isFile();
  }

  /** 완료 저장본이면 100, 받다 만 미완료 파일이면 0~99, 아무것도 없으면 -1을 돌려준다. */
  static int partialPercent(Context ctx, String url) {
    File d = dir(ctx);
    String base = fileName(url);
    if (new File(d, base).isFile()) return 100;
    File part = new File(d, base + ".part");
    File pieces = new File(d, base + ".pieces");
    if (!part.isFile() && !pieces.isFile()) return -1;
    long total = part.isFile() ? part.length() : 0;
    if (total <= 0 || !pieces.isFile()) return 0;
    PieceInfo info = loadPiecesInfo(pieces, total);
    if (info.pieceSize <= 0) return 0;
    int count = (int) ((total + info.pieceSize - 1) / info.pieceSize);
    long got = 0;
    for (int i = info.done.nextSetBit(0); i >= 0 && i < count; i = info.done.nextSetBit(i + 1))
      got += Math.min(total, (long) (i + 1) * info.pieceSize) - (long) i * info.pieceSize;
    return (int) Math.min(99, got * 100 / total);
  }

  static boolean delete(Context ctx, String fileName) {
    if (fileName == null || fileName.isEmpty()) return false;
    File d = dir(ctx);
    String base = baseName(fileName);
    boolean deleted = false;
    for (String suffix : new String[] {"", ".part", ".pieces", ".pieces.tmp"}) {
      File f = new File(d, base + suffix);
      if (f.exists() && f.delete()) deleted = true;
    }
    if (!base.equals(fileName)) {
      File raw = new File(d, fileName);
      if (raw.exists() && raw.delete()) deleted = true;
    }
    return deleted;
  }

  static int deleteAll(Context ctx, Collection<String> fileNames) {
    if (fileNames == null || fileNames.isEmpty()) return 0;
    int removed = 0;
    for (String name : fileNames) if (delete(ctx, name)) removed++;
    return removed;
  }

  static void clear(Context ctx) {
    File[] files = dir(ctx).listFiles();
    if (files != null) for (File f : files) f.delete();
  }

  private static Request.Builder request(String url, String auth) {
    Request.Builder r = new Request.Builder().url(url);
    if (!auth.isEmpty()) r.header("Authorization", auth);
    return r;
  }

  private static boolean isCancelled(Cancel cancel, Progress progress) {
    return (cancel != null && cancel.isCancelled()) || (progress != null && progress.cancelled());
  }

  private static Response executeTracked(Call call, String url) throws IOException {
    try {
      return call.execute();
    } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
      if (WebDav.isPikPak(url))
        throw new IOException("PikPak 서버에 연결할 수 없습니다. 인터넷 연결과 서버 주소를 확인하세요.");
      throw new IOException("PC에 연결할 수 없습니다. PC 전원과 Ccomics PC 서버 실행을 확인하세요.");
    }
  }

  private static final class ProbeResult {
    final long total;
    final String directUrl;
    final boolean directNeedsAuth;

    ProbeResult(long total, String directUrl, boolean directNeedsAuth) {
      this.total = total;
      this.directUrl = directUrl;
      this.directNeedsAuth = directNeedsAuth;
    }
  }

  private static boolean sameHost(String a, String b) {
    HttpUrl ua = HttpUrl.parse(a), ub = HttpUrl.parse(b);
    return ua != null
        && ub != null
        && ua.host().equalsIgnoreCase(ub.host())
        && ua.port() == ub.port()
        && ua.scheme().equalsIgnoreCase(ub.scheme());
  }

  private static ProbeResult probe(String url, String auth, Cancel cancel, Progress progress)
      throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (isCancelled(cancel, progress)) throw new CancelledException();
      Call call = CLIENT.newCall(request(url, auth).header("Range", "bytes=0-0").build());
      cancel.track(call);
      try (Response res = executeTracked(call, url)) {
        if (res.code() == 429) {
          last = WebDav.fail(429, url);
          pauseMs(WebDav.retryDelayMs(res, attempt), cancel, progress);
          continue;
        }
        String resolved = res.request().url().toString();
        boolean keepAuth = sameHost(url, resolved);
        if (res.code() == 206) {
          String cr = res.header("Content-Range", "");
          int slash = cr.lastIndexOf('/');
          if (slash < 0)
            throw new IOException(
                (WebDav.isPikPak(url) ? "PikPak 서버" : "PC 서버") + "가 파일 크기를 알려 주지 않았습니다.");
          long total = Long.parseLong(cr.substring(slash + 1).trim());
          return new ProbeResult(total, resolved, keepAuth);
        }
        if (res.code() == 200) {
          long cl = res.body() != null ? res.body().contentLength() : -1;
          if (cl > 0) return new ProbeResult(cl, resolved, keepAuth);
        }
        throw WebDav.fail(res.code() == 200 ? 416 : res.code(), url);
      } catch (IOException e) {
        if (isCancelled(cancel, progress)) throw new CancelledException();
        last = e;
        if (fatal(e)) throw e;
        pause(attempt, cancel, progress);
      } finally {
        cancel.untrack(call);
      }
    }
    throw last;
  }

  private static boolean fatal(IOException e) {
    String m = e.getMessage();
    return e instanceof FileNotFoundException || (m != null && m.contains("비밀번호"));
  }

  private static void pause(int attempt, Cancel cancel, Progress progress)
      throws CancelledException {
    pauseMs(Math.min(8000L, 500L << attempt), cancel, progress);
  }

  private static void pauseMs(long delayMs, Cancel cancel, Progress progress)
      throws CancelledException {
    long until = System.currentTimeMillis() + Math.max(100L, delayMs);
    while (true) {
      if (isCancelled(cancel, progress)) throw new CancelledException();
      long left = until - System.currentTimeMillis();
      if (left <= 0) return;
      try {
        Thread.sleep(Math.min(left, 100L));
      } catch (InterruptedException e) {
        throw new CancelledException();
      }
    }
  }

  private static final class PieceInfo {
    final int pieceSize;
    final BitSet done;

    PieceInfo(int pieceSize, BitSet done) {
      this.pieceSize = pieceSize;
      this.done = done;
    }
  }

  private static PieceInfo loadPiecesInfo(File map, long total) {
    try (DataInputStream in = new DataInputStream(new FileInputStream(map))) {
      if (in.readLong() != PIECE_MAGIC) return new PieceInfo(0, new BitSet());
      int pieceSize = in.readInt();
      if (pieceSize <= 0 || in.readLong() != total) return new PieceInfo(0, new BitSet());
      byte[] bits = new byte[in.readInt()];
      in.readFully(bits);
      return new PieceInfo(pieceSize, BitSet.valueOf(bits));
    } catch (IOException e) {
      return new PieceInfo(0, new BitSet());
    }
  }

  /** 받은 조각 기록: 매직 + 조각 크기 + 전체 크기 + 조각별 완료 여부. */
  private static BitSet loadPieces(File map, long total, int pieceSize) {
    PieceInfo info = loadPiecesInfo(map, total);
    return info.pieceSize == pieceSize ? info.done : new BitSet();
  }

  private static void savePieces(File map, long total, int pieceSize, BitSet done) {
    File tmp = new File(map.getPath() + ".tmp");
    try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
      out.writeLong(PIECE_MAGIC);
      out.writeInt(pieceSize);
      out.writeLong(total);
      byte[] bits = done.toByteArray();
      out.writeInt(bits.length);
      out.write(bits);
    } catch (IOException e) {
      return;
    }
    if (!tmp.renameTo(map)) {
      map.delete();
      tmp.renameTo(map);
    }
  }

  static File download(Context ctx, String bookUrl, String auth, Progress progress)
      throws IOException {
    return download(ctx, bookUrl, auth, progress, null);
  }

  /** 권 전체를 받아 저장본 파일을 돌려준다. 이미 있으면 바로, 받다 만 것이 있으면 이어서 받는다. */
  static File download(
      Context ctx, String bookUrl, String auth, Progress progress, Cancel externalCancel)
      throws IOException {
    File out = file(ctx, bookUrl);
    if (out.isFile()) return out;
    Cancel cancel = externalCancel != null ? externalCancel : new Cancel();
    String url = outer(bookUrl);
    int parallel = parallelFor(url);
    int pieceSize = pieceFor(url);
    boolean pikpak = WebDav.isPikPak(url);
    Object lock = LOCKS.computeIfAbsent(url, k -> new Object());
    synchronized (lock) {
      if (out.isFile()) return out;
      if (isCancelled(cancel, progress)) throw new CancelledException();
      File part = new File(out.getPath() + ".part"), map = new File(out.getPath() + ".pieces");
      ProbeResult probed = probe(url, auth, cancel, progress);
      long total = probed.total;
      AtomicReference<ProbeResult> targetRef = new AtomicReference<>(probed);
      int pieces = (int) ((total + pieceSize - 1) / pieceSize);
      BitSet done =
          part.isFile() && part.length() == total
              ? loadPieces(map, total, pieceSize)
              : new BitSet();
      long already = 0;
      for (int i = done.nextSetBit(0); i >= 0 && i < pieces; i = done.nextSetBit(i + 1))
        already += Math.min(total, (long) (i + 1) * pieceSize) - (long) i * pieceSize;
      AtomicLong received = new AtomicLong(already);
      ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
      for (int i = 0; i < pieces; i++) if (!done.get(i)) queue.add(i);
      AtomicReference<IOException> error = new AtomicReference<>();
      ExecutorService pool = Executors.newFixedThreadPool(parallel);
      cancel.bind(pool);
      try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
        if (raf.length() != total) raf.setLength(total);
        FileChannel channel = raf.getChannel();
        for (int w = 0; w < parallel; w++)
          pool.execute(
              () -> {
                byte[] buf = new byte[64 << 10];
                Integer i;
                while (error.get() == null
                    && !isCancelled(cancel, progress)
                    && (i = queue.poll()) != null) {
                  long start = (long) i * pieceSize, end = Math.min(total, start + pieceSize) - 1;
                  long pos = start;
                  for (int attempt = 0; ; attempt++) {
                    if (isCancelled(cancel, progress)) {
                      error.compareAndSet(null, new CancelledException());
                      cancel.cancel();
                      return;
                    }
                    if (error.get() != null) return;
                    ProbeResult curTarget = targetRef.get();
                    String fetchUrl = curTarget != null ? curTarget.directUrl : url;
                    String fetchAuth =
                        curTarget == null || curTarget.directNeedsAuth ? auth : "";
                    Call call =
                        CLIENT.newCall(
                            request(fetchUrl, fetchAuth)
                                .header("Range", "bytes=" + pos + "-" + end)
                                .build());
                    cancel.track(call);
                    try (Response res = executeTracked(call, url)) {
                      if ((res.code() == 401
                              || res.code() == 403
                              || res.code() == 404
                              || res.code() == 410)
                          && curTarget != null
                          && !curTarget.directUrl.equals(url)) {
                        // CDN 서명 링크가 만료됐으면 원래 WebDAV 주소로 1회 재발급받는다.
                        targetRef.compareAndSet(curTarget, null);
                        if (attempt + 1 >= ATTEMPTS) throw WebDav.fail(res.code(), url);
                        continue;
                      }
                      if (res.code() == 429) {
                        if (attempt + 1 >= ATTEMPTS) {
                          error.compareAndSet(null, WebDav.fail(429, url));
                          cancel.cancel();
                          return;
                        }
                        pauseMs(WebDav.retryDelayMs(res, attempt), cancel, progress);
                        continue;
                      }
                      if (res.code() != 206 && !(res.code() == 200 && pos == 0 && end == total - 1))
                        throw WebDav.fail(res.code() == 200 ? 416 : res.code(), url);
                      if (curTarget == null) {
                        String resolved = res.request().url().toString();
                        targetRef.compareAndSet(
                            null, new ProbeResult(total, resolved, sameHost(url, resolved)));
                      }
                      ResponseBody body = res.body();
                      if (body == null)
                        throw new IOException((pikpak ? "PikPak 서버" : "PC 서버") + "가 빈 응답을 보냈습니다.");
                      InputStream in = body.byteStream();
                      int n;
                      while (pos <= end
                          && (n = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1))) > 0) {
                        if (isCancelled(cancel, progress)) throw new CancelledException();
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        long at = pos;
                        while (bb.hasRemaining()) at += channel.write(bb, at);
                        pos += n;
                        received.addAndGet(n);
                      }
                      if (pos <= end)
                        throw new EOFException((pikpak ? "PikPak" : "PC") + " 연결이 중간에 끊겼습니다.");
                      synchronized (done) {
                        done.set(i);
                        savePieces(map, total, pieceSize, done);
                      }
                      break;
                    } catch (CancelledException e) {
                      error.compareAndSet(null, e);
                      cancel.cancel();
                      return;
                    } catch (IOException e) {
                      if (isCancelled(cancel, progress)) {
                        error.compareAndSet(null, new CancelledException());
                        cancel.cancel();
                        return;
                      }
                      if (error.get() != null) return;
                      IOException wrapped =
                          e instanceof SocketTimeoutException
                              ? new IOException((pikpak ? "PikPak" : "PC") + " 응답이 지연되었습니다.")
                              : e;
                      // 끊긴 지점부터 다시 요청한다(받은 바이트는 그대로 둔다).
                      if (fatal(wrapped) || attempt + 1 >= ATTEMPTS) {
                        error.compareAndSet(null, wrapped);
                        cancel.cancel();
                        return;
                      }
                      try {
                        pause(attempt, cancel, progress);
                      } catch (CancelledException ce) {
                        error.compareAndSet(null, ce);
                        cancel.cancel();
                        return;
                      }
                    } finally {
                      cancel.untrack(call);
                    }
                  }
                }
              });
        pool.shutdown();
        while (!pool.awaitTermination(150, TimeUnit.MILLISECONDS)) {
          if (isCancelled(cancel, progress)) {
            error.compareAndSet(null, new CancelledException());
            cancel.cancel();
            pool.shutdownNow();
          } else {
            progress.update(received.get(), total);
          }
        }
        if (!isCancelled(cancel, progress)) progress.update(received.get(), total);
      } catch (InterruptedException e) {
        cancel.cancel();
        pool.shutdownNow();
        error.compareAndSet(null, new CancelledException());
      } finally {
        cancel.bind(null);
      }
      synchronized (done) {
        savePieces(map, total, pieceSize, done);
      }
      if (error.get() != null) {
        if (error.get() instanceof CancelledException) throw error.get();
        throw new Incomplete(Library.reason(error.get()), received.get(), total);
      }
      if (done.cardinality() < pieces)
        throw new Incomplete("일부 조각을 받지 못했습니다.", received.get(), total);
      if (!part.renameTo(out)) {
        out.delete();
        if (!part.renameTo(out)) throw new IOException("저장 공간에 책을 저장하지 못했습니다.");
      }
      map.delete();
      return out;
    }
  }
}
