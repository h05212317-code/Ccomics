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
  ZipFile zip;
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

  /** 묶음 ZIP 안의 권 ZIP 주소: 바깥 파일 주소 + 줄바꿈 + 안쪽 항목 경로. */
  static final char NESTED = '\n';

  static String nested(String outer, String entry) {
    return outer + NESTED + entry;
  }

  static boolean volumeFile(String name) {
    return name.toLowerCase(Locale.ROOT).matches("(?s).*\\.(cbz|zip)$");
  }

  /** 이미지가 거의 없고 권 ZIP을 담은 묶음 파일일 때 스캔 단계로 안쪽 목록을 알린다. */
  static final class Bundle extends IOException {
    final List<ZipArchiveEntry> volumes;

    Bundle(List<ZipArchiveEntry> volumes) {
      super("권 ZIP 묶음");
      this.volumes = volumes;
    }
  }

  /** 바깥 ZIP의 무압축 항목을 그대로 읽는 구간 채널. */
  static final class Slice implements SeekableByteChannel {
    final SeekableByteChannel base;
    final long start, length;
    long pos;

    Slice(SeekableByteChannel base, long start, long length) {
      this.base = base;
      this.start = start;
      this.length = length;
    }

    public int read(ByteBuffer dst) throws IOException {
      if (pos >= length) return -1;
      synchronized (base) {
        int limit = dst.limit();
        if (dst.remaining() > length - pos) dst.limit(dst.position() + (int) (length - pos));
        try {
          base.position(start + pos);
          int n = base.read(dst);
          if (n > 0) pos += n;
          return n;
        } finally {
          dst.limit(limit);
        }
      }
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
      return base.isOpen();
    }

    public void close() {}
  }

  static ZipFile zipFile(SeekableByteChannel channel) throws IOException {
    validateDirectory(channel);
    return ZipFile.builder()
        .setSeekableByteChannel(channel)
        .setCharset(java.nio.charset.Charset.forName("MS949"))
        .setIgnoreLocalFileHeader(true)
        .get();
  }

  static long dataOffset(SeekableByteChannel channel, ZipArchiveEntry e) throws IOException {
    long header = e.getLocalHeaderOffset();
    ByteBuffer local = readRange(channel, header, 30);
    if (local.getInt(0) != 0x04034b50) throw new IOException("묶음 ZIP 항목 헤더 오류");
    return header + 30 + (local.getShort(26) & 65535) + (local.getShort(28) & 65535);
  }

  /** 안쪽 권 ZIP을 연다. 무압축이면 바깥 파일에서 바로 읽고, 압축돼 있으면 임시 파일로 푼다. */
  static SeekableByteChannel inner(Context ctx, ZipFile outer, SeekableByteChannel channel,
      String entry, File[] spooled) throws IOException {
    ZipArchiveEntry e = outer.getEntry(entry);
    if (e == null) throw new FileNotFoundException("묶음 ZIP 안에서 " + entry + " 파일을 찾을 수 없습니다.");
    if (!outer.canReadEntryData(e)) throw new IOException("암호화 또는 지원하지 않는 ZIP 압축 방식입니다.");
    if (e.getMethod() == ZipArchiveEntry.STORED && e.getSize() > 0)
      return new Slice(channel, dataOffset(channel, e), e.getSize());
    synchronized (Archive.class) {
      clearSpools(ctx);
      long budget = 512L << 20;
      for (File active : activeSpools) budget -= active.length();
      if (e.getSize() > budget) throw new IOException("임시 저장 공간 한도 512MB를 초과했습니다.");
      File temp = File.createTempFile("pages-", ".zip", ctx.getCacheDir());
      activeSpools.add(temp);
      spooled[0] = temp;
    }
    try (InputStream in = outer.getInputStream(e);
        OutputStream out = new FileOutputStream(spooled[0])) {
      byte[] buf = new byte[65536];
      long left = 512L << 20;
      int n;
      while ((n = in.read(buf)) != -1) {
        if ((left -= n) < 0) throw new IOException("임시 저장 공간 한도 512MB를 초과했습니다.");
        out.write(buf, 0, n);
      }
    }
    return new FileInputStream(spooled[0]).getChannel();
  }

  ZipFile outer;
  File innerSpool;

  Archive(Context ctx, String address, CIFSContext server) throws Exception {
    SeekableByteChannel channel;
    File temp = null;
    int split = address.indexOf(NESTED);
    String entry = split < 0 ? null : address.substring(split + 1);
    if (split >= 0) address = address.substring(0, split);
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
    File[] spooled = new File[1];
    ZipFile container = null;
    try {
      if (entry != null) {
        container = zipFile(channel);
        channel = inner(ctx, container, channel, entry, spooled);
      }
      zip = zipFile(channel);
      List<ZipArchiveEntry> volumes = new ArrayList<>();
      long imageBytes = 0, volumeBytes = 0;
      Enumeration<ZipArchiveEntry> all = zip.getEntries();
      while (all.hasMoreElements()) {
        ZipArchiveEntry e = all.nextElement();
        if (e.isDirectory()) continue;
        if (Order.image(e.getName())) {
          pages.add(e);
          imageBytes += Math.max(0, e.getSize());
        } else if (entry == null && volumeFile(e.getName()) && Order.image(e.getName() + ".jpg")) {
          volumes.add(e);
          volumeBytes += Math.max(0, e.getSize());
        }
        if (pages.size() > 50000) throw new IOException("이미지가 너무 많습니다.");
      }
      // 권 ZIP이 내용 대부분이면 표지 이미지가 함께 있어도 묶음으로 본다.
      if (!volumes.isEmpty() && (pages.size() < 5 || volumeBytes >= imageBytes)) {
        volumes.sort((a, b) -> Order.natural(a.getName(), b.getName()));
        throw new Bundle(volumes);
      }
      pages.sort((a, b) -> Order.natural(a.getName(), b.getName()));
      if (pages.isEmpty()) throw new IOException("JPEG, PNG, WebP 이미지가 없습니다.");
      int cover = 0;
      for (int i = 1; i < pages.size(); i++)
        if (Order.coverRank(name(i)) < Order.coverRank(name(cover))) cover = i;
      if (cover != 0 && Order.coverRank(name(cover)) <= 1) pages.add(0, pages.remove(cover));
      outer = container;
      innerSpool = spooled[0];
    } catch (Exception e) {
      try {
        if (zip != null) zip.close();
        if (container != null) container.close();
        channel.close();
      } finally {
        release(temp);
        release(spooled[0]);
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
      if (outer != null) outer.close();
    } finally {
      release(spool);
      release(innerSpool);
    }
  }
}
