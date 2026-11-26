package kr.ccomics;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;

/**
 * PC 원격 책 보관함. 권을 처음 열 때 ZIP 전체를 여러 연결로 나눠 받아 휴대폰에 저장하고, 이후에는 PC·네트워크
 * 없이 저장본으로 연다. 사용자가 지우기 전까지 계속 남는다(서재 설정 → PC 원격 보관함).
 */
final class RemoteBooks {
  /** Funnel 실측: 연결 1개 0.47MB/s, 4개 1.74MB/s, 8개 2.31MB/s, 16개 2.38MB/s. */
  static final int PARALLEL = 8, PIECE = 4 << 20;

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
            .build();
  }

  interface Progress {
    void update(long done, long total);

    boolean cancelled();
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

  static File file(Context ctx, String url) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-1").digest(outer(url).getBytes("UTF-8"));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
      return new File(dir(ctx), sb + ".zip");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean has(Context ctx, String url) {
    return file(ctx, url).isFile();
  }

  /** 저장본을 여는 주소(묶음 안 권이면 안쪽 경로를 붙인다). */
  static String localAddress(Context ctx, String url) {
    String local = android.net.Uri.fromFile(file(ctx, url)).toString();
    int split = url.indexOf(Archive.NESTED);
    return split < 0 ? local : local + url.substring(split);
  }

  static long[] usage(Context ctx) {
    long bytes = 0, count = 0;
    File[] files = dir(ctx).listFiles((d, n) -> n.endsWith(".zip"));
    if (files != null)
      for (File f : files) {
        bytes += f.length();
        count++;
      }
    return new long[] {count, bytes};
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

  private static long probe(String url, String auth) throws IOException {
    try (Response res = WebDav.call(CLIENT, request(url, auth).header("Range", "bytes=0-0").build())) {
      if (res.code() != 206) throw WebDav.fail(res.code() == 200 ? 416 : res.code());
      String cr = res.header("Content-Range", "");
      int slash = cr.lastIndexOf('/');
      if (slash < 0) throw new IOException("PC 서버가 파일 크기를 알려 주지 않았습니다.");
      return Long.parseLong(cr.substring(slash + 1).trim());
    }
  }

  /** 권 전체를 받아 저장본 파일을 돌려준다. 이미 있으면 바로 돌려준다. */
  static File download(Context ctx, String bookUrl, String auth, Progress progress)
      throws IOException {
    File out = file(ctx, bookUrl);
    if (out.isFile()) return out;
    String url = outer(bookUrl);
    File part = new File(out.getPath() + ".part");
    long total = probe(url, auth);
    int pieces = (int) ((total + PIECE - 1) / PIECE);
    AtomicInteger next = new AtomicInteger();
    AtomicLong done = new AtomicLong();
    AtomicReference<IOException> error = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
    try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
      raf.setLength(total);
      FileChannel channel = raf.getChannel();
      for (int w = 0; w < PARALLEL; w++)
        pool.execute(
            () -> {
              byte[] buf = new byte[64 << 10];
              int i;
              while (error.get() == null && (i = next.getAndIncrement()) < pieces) {
                long start = (long) i * PIECE, end = Math.min(total, start + PIECE) - 1;
                long pos = start;
                for (int attempt = 0; ; attempt++) {
                  try (Response res =
                      WebDav.call(
                          CLIENT,
                          request(url, auth).header("Range", "bytes=" + pos + "-" + end).build())) {
                    if (res.code() != 206) throw WebDav.fail(res.code() == 200 ? 416 : res.code());
                    ResponseBody body = res.body();
                    if (body == null) throw new IOException("PC 서버가 빈 응답을 보냈습니다.");
                    InputStream in = body.byteStream();
                    int n;
                    while (pos <= end && (n = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1))) > 0) {
                      if (progress.cancelled()) throw new InterruptedIOException("취소");
                      ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                      long at = pos;
                      while (bb.hasRemaining()) at += channel.write(bb, at);
                      pos += n;
                      done.addAndGet(n);
                    }
                    if (pos <= end) throw new EOFException("PC 연결이 중간에 끊겼습니다.");
                    break;
                  } catch (InterruptedIOException e) {
                    error.compareAndSet(null, e);
                    return;
                  } catch (IOException e) {
                    boolean fatal = e.getMessage() != null && e.getMessage().contains("비밀번호");
                    if (fatal || attempt >= 5) {
                      error.compareAndSet(null, e);
                      return;
                    }
                    try {
                      Thread.sleep(600L * (attempt + 1));
                    } catch (InterruptedException ie) {
                      error.compareAndSet(null, new InterruptedIOException("취소"));
                      return;
                    }
                  }
                }
              }
            });
      pool.shutdown();
      while (!pool.awaitTermination(300, TimeUnit.MILLISECONDS)) {
        progress.update(done.get(), total);
        if (progress.cancelled()) error.compareAndSet(null, new InterruptedIOException("취소"));
      }
      progress.update(done.get(), total);
    } catch (InterruptedException e) {
      pool.shutdownNow();
      error.compareAndSet(null, new InterruptedIOException("취소"));
    }
    if (error.get() != null || done.get() != total) {
      part.delete();
      throw error.get() != null ? error.get() : new IOException("받은 크기가 맞지 않습니다.");
    }
    if (!part.renameTo(out)) {
      part.delete();
      throw new IOException("저장 공간에 책을 저장하지 못했습니다.");
    }
    return out;
  }
}
