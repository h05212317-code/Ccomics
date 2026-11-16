package kr.ccomics;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import jcifs.*;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.*;
import org.apache.commons.compress.archivers.zip.*;

final class Archive implements Closeable {
  final ZipFile zip;
  final List<ZipArchiveEntry> pages = new ArrayList<>();
  final File spool;
  static final long MAX_PAGE = 128L << 20;
  static final Set<File> activeSpools = new HashSet<>();

  static synchronized void clearSpools(Context ctx) {
    File[] files = ctx.getCacheDir().listFiles((dir, n) -> n.startsWith("pages-"));
    if (files != null) for (File f : files) if (!activeSpools.contains(f)) f.delete();
  }

  static synchronized File spool(Context ctx, Uri uri) throws IOException {
    clearSpools(ctx);
    long budget = 512L << 20;
    for (File active : activeSpools) budget -= active.length();
    File temp = File.createTempFile("pages-", ".zip", ctx.getCacheDir());
    try (InputStream in = ctx.getContentResolver().openInputStream(uri);
        OutputStream out = new FileOutputStream(temp)) {
      if (in == null) throw new IOException("파일을 열 수 없습니다.");
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) != -1) {
        budget -= n;
        if (budget < 0) throw new IOException("임시 저장 공간 한도 512MB를 초과했습니다.");
        out.write(buf, 0, n);
      }
    } catch (IOException | RuntimeException e) {
      temp.delete();
      throw e;
    }
    activeSpools.add(temp);
    return temp;
  }

  static synchronized void release(File temp) {
    if (temp != null) {
      activeSpools.remove(temp);
      temp.delete();
    }
  }

  static final class RemoteChannel implements SeekableByteChannel {
    final SmbRandomAccessFile file;
    final SmbFile resource;
    long pos, length, blockStart = -1;
    byte[] block = new byte[65536];
    int used;
    boolean open = true;

    RemoteChannel(SmbFile resource) throws IOException {
      this.resource = resource;
      file = new SmbRandomAccessFile(resource, "r");
      length = file.length();
    }

    public synchronized int read(ByteBuffer dst) throws IOException {
      if (!dst.hasRemaining()) return 0;
      if (pos >= length) return -1;
      if (pos < blockStart || pos >= blockStart + used) {
        file.seek(pos);
        used = file.read(block);
        blockStart = pos;
        if (used <= 0) return -1;
      }
      int index = (int) (pos - blockStart), n = Math.min(dst.remaining(), used - index);
      dst.put(block, index, n);
      pos += n;
      return n;
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

    public void close() throws IOException {
      open = false;
      try {
        file.close();
      } finally {
        resource.close();
      }
    }
  }

  static CIFSContext server(String user, String pass) throws Exception {
    Properties p = new Properties();
    p.setProperty("jcifs.smb.client.minVersion", "SMB202");
    p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
    p.setProperty("jcifs.smb.client.responseTimeout", "12000");
    String[] parts = user.split("\\\\", 2);
    return new BaseContext(new PropertyConfiguration(p))
        .withCredentials(
            new NtlmPasswordAuthenticator(
                parts.length == 2 ? parts[0] : "", parts[parts.length - 1], pass));
  }

  Archive(Context ctx, String address, CIFSContext server) throws Exception {
    SeekableByteChannel channel;
    File temp = null;
    if (address.startsWith("smb://")) channel = new RemoteChannel(new SmbFile(address, server));
    else {
      Uri uri = Uri.parse(address);
      ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
      if (pfd == null) throw new IOException("파일 접근 권한을 확인하세요.");
      FileChannel local = new ParcelFileDescriptor.AutoCloseInputStream(pfd).getChannel();
      try {
        local.position(0);
        if (local.size() == 0) throw new IOException();
        channel = local;
      } catch (IOException noSeek) {
        local.close();
        temp = spool(ctx, uri);
        try {
          channel = new FileInputStream(temp).getChannel();
        } catch (IOException e) {
          release(temp);
          throw e;
        }
      }
    }
    spool = temp;
    try {
      validateDirectory(channel);
      zip =
          ZipFile.builder()
              .setSeekableByteChannel(channel)
              .setCharset(java.nio.charset.Charset.forName("MS949"))
              .setIgnoreLocalFileHeader(true)
              .get();
      Enumeration<ZipArchiveEntry> all = zip.getEntries();
      while (all.hasMoreElements()) {
        ZipArchiveEntry e = all.nextElement();
        if (!e.isDirectory() && Order.image(e.getName())) pages.add(e);
        if (pages.size() > 50000) throw new IOException("이미지가 너무 많습니다.");
      }
      pages.sort((a, b) -> Order.natural(a.getName(), b.getName()));
      if (pages.isEmpty()) throw new IOException("JPEG, PNG, WebP 이미지가 없습니다.");
      int cover = 0;
      for (int i = 1; i < pages.size(); i++)
        if (Order.coverRank(name(i)) < Order.coverRank(name(cover))) cover = i;
      if (cover != 0 && Order.coverRank(name(cover)) <= 1) pages.add(0, pages.remove(cover));
    } catch (Exception e) {
      try {
        channel.close();
      } finally {
        release(temp);
      }
      throw e;
    }
  }

  static ByteBuffer readRange(SeekableByteChannel channel, long offset, int length)
      throws IOException {
    if (offset < 0) throw new IOException("ZIP 디렉터리 위치 오류");
    ByteBuffer data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    channel.position(offset);
    while (data.hasRemaining())
      if (channel.read(data) <= 0) throw new EOFException("ZIP 디렉터리가 잘렸습니다.");
    data.flip();
    return data;
  }

  static void validateDirectory(SeekableByteChannel channel) throws IOException {
    long length = channel.size();
    int tailSize = (int) Math.min(length, 65557);
    ByteBuffer tail = readRange(channel, length - tailSize, tailSize);
    int end = tailSize - 22;
    while (end >= 0
        && (tail.getInt(end) != 0x06054b50
            || end + 22 + (tail.getShort(end + 20) & 65535) != tailSize)) end--;
    if (end < 0) throw new IOException("ZIP 끝 정보를 찾을 수 없습니다. 파일이 손상되었는지 확인하세요.");
    if (tail.getShort(end + 4) != 0 || tail.getShort(end + 6) != 0)
      throw new IOException("분할 압축 ZIP은 지원하지 않습니다.");
    long entries = tail.getShort(end + 10) & 65535, bytes = tail.getInt(end + 12) & 0xffffffffL;
    long endOffset = length - tailSize + end;
    if (endOffset >= 20) {
      ByteBuffer loc = readRange(channel, endOffset - 20, 20);
      if (loc.getInt(0) == 0x07064b50) {
        ByteBuffer header = readRange(channel, loc.getLong(8), 56);
        if (header.getInt(0) != 0x06064b50) throw new IOException("ZIP64 헤더 오류");
        entries = header.getLong(32);
        bytes = header.getLong(40);
      }
    }
    if (entries < 0 || entries > 100000 || bytes < 0 || bytes > (48L << 20) || bytes > length)
      throw new IOException("ZIP 디렉터리 크기나 항목 수가 허용 범위를 벗어났습니다.");
    channel.position(0);
  }

  String name(int i) {
    return pages.get(i).getName();
  }

  boolean landscape(int page) throws IOException {
    ZipArchiveEntry entry = pages.get(page);
    if (entry.getSize() < 1 || entry.getSize() > MAX_PAGE || !zip.canReadEntryData(entry))
      return false;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream input = limited(entry)) {
      BitmapFactory.decodeStream(input, null, options);
    }
    return options.outWidth > options.outHeight && options.outHeight > 0;
  }

  String fingerprint() throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    for (ZipArchiveEntry e : pages) {
      digest.update(e.getName().getBytes("UTF-8"));
      digest.update((byte) 0);
      digest.update(ByteBuffer.allocate(16).putLong(e.getCrc()).putLong(e.getSize()).array());
    }
    return android.util.Base64.encodeToString(digest.digest(), 2);
  }

  Bitmap decode(int i, int width, int height) throws IOException {
    ZipArchiveEntry e = pages.get(i);
    if (!zip.canReadEntryData(e)) throw new IOException("암호화 또는 지원하지 않는 ZIP 압축 방식입니다.");
    if (e.getSize() < 1 || e.getSize() > MAX_PAGE) throw new IOException("이미지 파일 크기를 읽을 수 없습니다.");
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    try (InputStream in = limited(e)) {
      BitmapFactory.decodeStream(in, null, o);
    }
    if (o.outWidth < 1 || o.outHeight < 1) throw new IOException("손상되었거나 지원하지 않는 이미지입니다.");
    int sample = 1;
    long maxPixels = Math.min(8000000L, Runtime.getRuntime().maxMemory() / 96);
    while ((o.outWidth / (long) sample) * (o.outHeight / (long) sample) > maxPixels
        || Math.max(o.outWidth, o.outHeight) / sample > 8192
        || (o.outWidth / sample > width * 2 && o.outHeight / sample > height * 2)) sample *= 2;
    o.inSampleSize = sample;
    o.inJustDecodeBounds = false;
    try (InputStream in = limited(e)) {
      Bitmap b = BitmapFactory.decodeStream(in, null, o);
      if (b == null) throw new IOException("이미지를 열 수 없습니다.");
      return b;
    }
  }

  InputStream limited(ZipArchiveEntry e) throws IOException {
    return new FilterInputStream(zip.getInputStream(e)) {
      long remaining = e.getSize();

      public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) return -1;
        int n = super.read(b, off, (int) Math.min(remaining, len));
        if (n > 0) remaining -= n;
        return n;
      }

      public int read() throws IOException {
        if (remaining <= 0) return -1;
        int n = super.read();
        if (n >= 0) remaining--;
        return n;
      }

      public long skip(long n) throws IOException {
        long k = super.skip(Math.min(remaining, n));
        remaining -= k;
        return k;
      }
    };
  }

  public void close() throws IOException {
    try {
      zip.close();
    } finally {
      release(spool);
    }
  }
}
