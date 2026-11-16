package kr.ccomics;

import android.content.Intent;
import android.content.pm.*;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.*;

final class AppUpdates {
  static final String REPO = "h05212317-code/Ccomics";
  static final int ALLOW_INSTALL = 891;
  static final long MAX_APK = 128L << 20;
  final Main host;
  boolean checking, manualRequested, downloading;

  AppUpdates(Main host) {
    this.host = host;
  }

  static int compare(String a, String b) {
    String[] left = parts(a), right = parts(b);
    for (int i = 0; i < 3; i++) {
      int cmp = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
      if (cmp != 0) return cmp;
    }
    return 0;
  }

  static String[] parts(String version) {
    if (!version.matches("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}"))
      throw new IllegalArgumentException("버전 형식을 확인할 수 없습니다.");
    return version.split("\\.");
  }

  static final class Release {
    final String version, url, hash;
    final long size;

    Release(JSONObject data) throws Exception {
      String tag = data.getString("tag_name");
      if (!tag.startsWith("v") || data.optBoolean("draft") || data.optBoolean("prerelease"))
        throw new IOException("정식 배포 버전을 찾을 수 없습니다.");
      version = tag.substring(1);
      parts(version);
      JSONArray assets = data.getJSONArray("assets");
      for (int i = 0; i < assets.length(); i++) {
        JSONObject asset = assets.getJSONObject(i);
        if (!"Ccomics.apk".equals(asset.optString("name"))) continue;
        url = asset.getString("browser_download_url");
        hash = asset.getString("digest");
        size = asset.getLong("size");
        if (!url.equals("https://github.com/" + REPO + "/releases/download/" + tag + "/Ccomics.apk")
            || !hash.matches("sha256:[0-9a-f]{64}")
            || size <= 0
            || size > MAX_APK) throw new IOException("배포 파일 정보가 올바르지 않습니다.");
        return;
      }
      throw new IOException("이 릴리스에 Ccomics.apk가 없습니다.");
    }
  }

  HttpURLConnection connection(String url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(25000);
    c.setRequestProperty("User-Agent", "Ccomics-Android");
    return c;
  }

  boolean live() {
    return !host.isFinishing() && !host.isDestroyed();
  }

  void check(boolean manual) {
    if (downloading) {
      if (manual) host.toast("업데이트 다운로드 중입니다.");
      return;
    }
    manualRequested |= manual;
    if (checking) return;
    if (!manual
        && System.currentTimeMillis() - host.prefs.getLong("updateChecked", 0) < 6 * 3600000L)
      return;
    checking = true;
    if (manual) host.toast("새 버전을 확인합니다.");
    new Thread(
            () -> {
              try {
                HttpURLConnection c =
                    connection("https://api.github.com/repos/" + REPO + "/releases/latest");
                byte[] json;
                try (InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                  copy(in, out, 1024 * 1024);
                  json = out.toByteArray();
                } finally {
                  c.disconnect();
                }
                Release release = new Release(new JSONObject(new String(json, "UTF-8")));
                String installed =
                    host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
                boolean newer = compare(release.version, installed) > 0;
                host.runOnUiThread(
                    () -> {
                      checking = false;
                      boolean requested = manualRequested;
                      manualRequested = false;
                      if (!live()) return;
                      if (!requested && (host.reader != null || host.isFinishing())) return;
                      host.prefs
                          .edit()
                          .putLong("updateChecked", System.currentTimeMillis())
                          .apply();
                      if (newer)
                        host.dialog()
                            .setTitle("Ccomics 업데이트")
                            .setMessage(installed + " → " + release.version)
                            .setNegativeButton("나중에", null)
                            .setPositiveButton("다운로드 및 설치", (d, w) -> download(release))
                            .show();
                      else if (requested) host.alert("앱 업데이트", "최신 버전입니다. (" + installed + ")");
                    });
              } catch (Exception ex) {
                host.runOnUiThread(
                    () -> {
                      checking = false;
                      boolean report = manualRequested;
                      manualRequested = false;
                      if (report && live()) host.alert("업데이트 확인 실패", Library.reason(ex));
                    });
              }
            },
            "release-check")
        .start();
  }

  static long copy(InputStream in, OutputStream out, long limit) throws IOException {
    byte[] buf = new byte[32768];
    int n;
    long total = 0;
    while ((n = in.read(buf)) != -1) {
      total += n;
      if (total > limit) throw new IOException("파일 크기가 배포 정보와 다릅니다.");
      out.write(buf, 0, n);
    }
    return total;
  }

  File apk() {
    return new File(host.getCacheDir(), "updates/Ccomics.apk");
  }

  void validate(File file, String version) throws Exception {
    PackageManager pm = host.getPackageManager();
    PackageInfo candidate =
        pm.getPackageArchiveInfo(file.toString(), PackageManager.GET_SIGNING_CERTIFICATES);
    PackageInfo current =
        pm.getPackageInfo(host.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    if (candidate == null
        || !current.packageName.equals(candidate.packageName)
        || !version.equals(candidate.versionName)
        || candidate.signingInfo == null
        || candidate.getLongVersionCode() <= current.getLongVersionCode()
        || !Arrays.equals(
            current.signingInfo.getApkContentsSigners(),
            candidate.signingInfo.getApkContentsSigners()))
      throw new IOException("앱 서명 또는 업데이트 버전이 일치하지 않습니다.");
  }

  void download(Release release) {
    if (downloading) return;
    downloading = true;
    host.toast("업데이트를 다운로드합니다.");
    new Thread(
            () -> {
              File tmp = null;
              try {
                File dest = apk();
                dest.getParentFile().mkdirs();
                tmp = File.createTempFile("release-", ".apk", dest.getParentFile());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpURLConnection c = connection(release.url);
                try (InputStream in =
                        new java.security.DigestInputStream(c.getInputStream(), digest);
                    OutputStream out = new FileOutputStream(tmp)) {
                  if (copy(in, out, release.size) != release.size)
                    throw new IOException("다운로드가 중단됐습니다.");
                } finally {
                  c.disconnect();
                }
                StringBuilder hex = new StringBuilder("sha256:");
                for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
                if (!release.hash.contentEquals(hex))
                  throw new IOException("다운로드 파일의 해시가 일치하지 않습니다.");
                validate(tmp, release.version);
                java.nio.file.Files.move(
                    tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                host.runOnUiThread(
                    () -> {
                      downloading = false;
                      if (live()) install();
                    });
              } catch (Exception ex) {
                host.runOnUiThread(
                    () -> {
                      downloading = false;
                      if (live()) host.alert("업데이트 실패", Library.reason(ex));
                    });
              } finally {
                if (tmp != null) tmp.delete();
              }
            },
            "release-download")
        .start();
  }

  void afterPermission() {
    if (host.getPackageManager().canRequestPackageInstalls()) install();
    else host.toast("설치 권한을 허용해야 업데이트할 수 있습니다.");
  }

  void install() {
    try {
      if (!host.getPackageManager().canRequestPackageInstalls()) {
        host.startActivityForResult(
            new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + host.getPackageName())),
            ALLOW_INSTALL);
        return;
      }
      Uri uri = FileProvider.getUriForFile(host, "kr.ccomics.updates", apk());
      host.startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (Exception ex) {
      host.alert("설치 화면을 열 수 없습니다", Library.reason(ex));
    }
  }
}
