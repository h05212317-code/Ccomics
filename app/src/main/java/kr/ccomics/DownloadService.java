package kr.ccomics;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 원격 책(PC·PikPak)을 백그라운드에서도 끊기지 않고 끝까지 받기 위한 Foreground Service(dataSync).
 * 화면을 벗어나 다른 앱을 쓰거나 화면이 꺼져도 OS 동결을 막고, 상태바 진행 알림과 취소 버튼을 제공한다.
 */
public final class DownloadService extends Service {
  static final String CHANNEL_ID = "ccomics_download";
  static final String ACTION_START = "kr.ccomics.DOWNLOAD_START";
  static final String ACTION_CANCEL = "kr.ccomics.DOWNLOAD_CANCEL";
  static final int NOTIF_ID = 201;
  static final int DONE_NOTIF_ID = 202;

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

  static final class Target {
    final long bookId;
    final String url, title, auth;

    Target(long bookId, String url, String title, String auth) {
      this.bookId = bookId;
      this.url = url;
      this.title = title == null ? "" : title;
      this.auth = auth == null ? "" : auth;
    }
  }

  interface Listener {
    void onDownloadProgress(int sessionId, String bannerText);

    void onDownloadBookSaved(int sessionId, long bookId);

    void onDownloadFinished(int sessionId, long openBookId, int completedCount, boolean batch);

    void onDownloadFailed(int sessionId, Exception error, boolean batch);

    void onDownloadCancelled(int sessionId);
  }

  static final class Session {
    final int id;
    final List<Target> targets;
    final boolean batch;
    final long openBookId;
    final RemoteBooks.Cancel cancel = new RemoteBooks.Cancel();
    volatile boolean running = true;
    volatile boolean completedInBackground;
    volatile int completedCount;
    volatile String notifTitle = "원격 책 받는 중";
    volatile String notifText = "준비 중…";
    volatile String bannerText = "책을 여는 중…";
    volatile int percent;
    volatile long lastNotifAt;

    Session(int id, List<Target> targets, boolean batch, long openBookId) {
      this.id = id;
      this.targets = targets;
      this.batch = batch;
      this.openBookId = openBookId;
    }
  }

  private static volatile DownloadService instance;
  private static volatile Session active;
  private static volatile Listener listener;
  private static volatile long pendingOpenBookId;
  private static volatile boolean pendingRefresh;

  private PowerManager.WakeLock wakeLock;

  static void setListener(Listener l) {
    listener = l;
  }

  static boolean isRunning() {
    Session s = active;
    return s != null && s.running;
  }

  static int activeSessionId() {
    Session s = active;
    return s != null && s.running ? s.id : 0;
  }

  static String currentBanner() {
    Session s = active;
    return s != null && s.running ? s.bannerText : "";
  }

  static long consumePendingOpenBookId() {
    long id = pendingOpenBookId;
    pendingOpenBookId = 0L;
    return id;
  }

  static boolean consumePendingRefresh() {
    boolean v = pendingRefresh;
    pendingRefresh = false;
    return v;
  }

  static int startSingle(Context ctx, Target target) {
    return enqueue(ctx.getApplicationContext(), Collections.singletonList(target), false, target.bookId);
  }

  static int startBatch(Context ctx, List<Target> targets) {
    if (targets == null || targets.isEmpty()) return 0;
    return enqueue(ctx.getApplicationContext(), new ArrayList<>(targets), true, 0L);
  }

  static void cancelActive(Context ctx) {
    Session prev = active;
    pendingOpenBookId = 0L;
    if (prev != null) {
      prev.running = false;
      prev.cancel.markCancelled();
      EXECUTOR.execute(prev.cancel::cancel);
      if (active == prev) active = null;
    }
    DownloadService svc = instance;
    if (svc != null) svc.stopForegroundClean();
    Listener l = listener;
    if (l != null && prev != null) l.onDownloadCancelled(prev.id);
  }

  private static int enqueue(Context appCtx, List<Target> targets, boolean batch, long openBookId) {
    Session prev = active;
    if (prev != null) {
      prev.running = false;
      prev.cancel.markCancelled();
      EXECUTOR.execute(prev.cancel::cancel);
    }
    pendingOpenBookId = 0L;
    int sid = NEXT_ID.getAndIncrement();
    Session session = new Session(sid, targets, batch, openBookId);
    Target first = targets.get(0);
    String origin = WebDav.isPikPak(first.url) ? "PikPak" : "PC";
    session.notifTitle =
        batch
            ? (targets.size() > 1 ? "[1/" + targets.size() + "] " : "") + origin + "에서 미리 받는 중"
            : origin + "에서 받는 중";
    session.notifText = first.title.isEmpty() ? "다운로드 준비 중…" : first.title;
    session.bannerText = batch ? "미리 받기를 시작합니다…" : "책을 여는 중…";
    active = session;

    try {
      Intent in = new Intent(appCtx, DownloadService.class).setAction(ACTION_START);
      ContextCompat.startForegroundService(appCtx, in);
    } catch (Exception ignored) {
      // 테스트 환경이나 OS 제약 시에도 앱 내부 백그라운드 스레드로 계속 실행한다.
    }

    EXECUTOR.execute(() -> runSession(appCtx, session));
    return sid;
  }

  private static void runSession(Context appCtx, Session session) {
    int completed = 0;
    try {
      for (int idx = 0; idx < session.targets.size(); idx++) {
        if (!session.running || session.cancel.isCancelled() || active != session) {
          throw new InterruptedIOException("취소");
        }
        Target t = session.targets.get(idx);
        if (RemoteBooks.has(appCtx, t.url)) {
          completed++;
          session.completedCount = completed;
          continue;
        }
        int pos = idx + 1, count = session.targets.size();
        String origin = WebDav.isPikPak(t.url) ? "PikPak" : "PC";
        String prefix = session.batch && count > 1 ? "[" + pos + "/" + count + "] " : "";
        String actionLabel = session.batch ? "미리 받는 중" : "받는 중";
        session.notifTitle = prefix + origin + "에서 " + actionLabel;
        final long[] speedState = {0L, 0L};

        RemoteBooks.download(
            appCtx,
            t.url,
            t.auth,
            new RemoteBooks.Progress() {
              public void update(long done, long total) {
                long now = System.currentTimeMillis();
                if (speedState[0] == 0L) {
                  speedState[0] = now;
                  speedState[1] = done;
                }
                int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                String rate = Main.formatSpeed(done - speedState[1], now - speedState[0]);
                String banner =
                    String.format(
                        Locale.KOREA,
                        "%s%s에서 %s %d%%  ·  %d / %dMB%s",
                        prefix,
                        origin,
                        actionLabel,
                        pct,
                        done >> 20,
                        total >> 20,
                        rate);
                String nText =
                    (t.title.isEmpty() ? "" : t.title + " · ")
                        + pct
                        + "% ("
                        + (done >> 20)
                        + "/"
                        + (total >> 20)
                        + "MB)"
                        + (rate.isEmpty() ? "" : rate.replace("  ·  ", " · "));
                session.percent = pct;
                session.bannerText = banner;
                session.notifText = nText;
                Listener l = listener;
                if (l != null && active == session) l.onDownloadProgress(session.id, banner);
                if (now - session.lastNotifAt >= 350) {
                  session.lastNotifAt = now;
                  DownloadService svc = instance;
                  if (svc != null && active == session) svc.updateNotification(session);
                }
              }

              public boolean cancelled() {
                return !session.running || session.cancel.isCancelled() || active != session;
              }
            },
            session.cancel);

        completed++;
        session.completedCount = completed;
        pendingRefresh = true;
        Listener l = listener;
        if (l != null && active == session) l.onDownloadBookSaved(session.id, t.bookId);
      }

      if (active != session || !session.running || session.cancel.isCancelled()) return;
      session.running = false;
      active = null;
      pendingRefresh = true;
      DownloadService svc = instance;
      if (svc != null) svc.stopForegroundClean();

      Listener l = listener;
      if (l != null) {
        l.onDownloadFinished(session.id, session.openBookId, completed, session.batch);
      } else {
        session.completedInBackground = true;
        if (!session.batch && session.openBookId > 0) pendingOpenBookId = session.openBookId;
        postCompletionNotification(appCtx, session, completed);
      }
    } catch (Exception e) {
      boolean wasActive = (active == session);
      session.running = false;
      if (wasActive) active = null;
      DownloadService svc = instance;
      if (svc != null && active == null) svc.stopForegroundClean();
      Listener l = listener;
      if (e instanceof InterruptedIOException) {
        if (l != null && wasActive) l.onDownloadCancelled(session.id);
      } else {
        if (l != null && wasActive) l.onDownloadFailed(session.id, e, session.batch);
        else if (wasActive) postFailureNotification(appCtx, session, Library.reason(e));
      }
    }
  }

  private static void ensureChannel(Context ctx) {
    NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
    if (nm == null) return;
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
    NotificationChannel ch =
        new NotificationChannel(CHANNEL_ID, "원격 책 다운로드", NotificationManager.IMPORTANCE_LOW);
    ch.setDescription("PC·PikPak 원격 폴더에서 책을 백그라운드로 내려받을 때 진행 상태를 표시합니다.");
    ch.setShowBadge(false);
    nm.createNotificationChannel(ch);
  }

  private static PendingIntent openAppIntent(Context ctx) {
    Intent open =
        new Intent(ctx, Main.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(
        ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static PendingIntent cancelServiceIntent(Context ctx) {
    Intent cancel = new Intent(ctx, DownloadService.class).setAction(ACTION_CANCEL);
    return PendingIntent.getService(
        ctx, 1, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static Notification buildProgressNotification(Context ctx, Session s) {
    ensureChannel(ctx);
    String title = s != null ? s.notifTitle : "원격 책 받는 중";
    String text = s != null ? s.notifText : "다운로드 준비 중…";
    int pct = s != null ? s.percent : 0;
    Notification.Builder b =
        new Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_remote)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(ctx))
            .setProgress(100, pct, pct <= 0)
            .addAction(
                new Notification.Action.Builder(null, "취소", cancelServiceIntent(ctx)).build());
    if (Build.VERSION.SDK_INT >= 31) {
      b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    }
    return b.build();
  }

  private static void postCompletionNotification(Context ctx, Session s, int completed) {
    try {
      ensureChannel(ctx);
      NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
      if (nm == null) return;
      String title = s.batch ? "미리 받기 완료" : "다운로드 완료";
      String body =
          s.batch && completed > 1
              ? completed + "개 파일을 휴대폰 보관함에 저장했습니다."
              : (s.targets.isEmpty() || s.targets.get(0).title.isEmpty()
                  ? "휴대폰 보관함에 저장했습니다. 눌러서 바로 읽으세요."
                  : s.targets.get(0).title + " · 눌러서 바로 읽으세요.");
      Notification n =
          new Notification.Builder(ctx, CHANNEL_ID)
              .setSmallIcon(R.drawable.icon_remote)
              .setContentTitle(title)
              .setContentText(body)
              .setAutoCancel(true)
              .setContentIntent(openAppIntent(ctx))
              .build();
      nm.notify(DONE_NOTIF_ID, n);
    } catch (Exception ignored) {
    }
  }

  private static void postFailureNotification(Context ctx, Session s, String reason) {
    try {
      ensureChannel(ctx);
      NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
      if (nm == null) return;
      Notification n =
          new Notification.Builder(ctx, CHANNEL_ID)
              .setSmallIcon(R.drawable.icon_remote)
              .setContentTitle("다운로드 중단됨")
              .setContentText(reason + " (다시 누르면 이어서 받습니다)")
              .setAutoCancel(true)
              .setContentIntent(openAppIntent(ctx))
              .build();
      nm.notify(DONE_NOTIF_ID, n);
    } catch (Exception ignored) {
    }
  }

  private void updateNotification(Session s) {
    try {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm != null) nm.notify(NOTIF_ID, buildProgressNotification(this, s));
    } catch (Exception ignored) {
    }
  }

  private void acquireWakeLock() {
    try {
      if (wakeLock == null) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
          wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ccomics:download");
          wakeLock.setReferenceCounted(false);
        }
      }
      if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(30L * 60L * 1000L);
    } catch (Exception ignored) {
    }
  }

  private void releaseWakeLock() {
    try {
      if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    } catch (Exception ignored) {
    }
  }

  private void stopForegroundClean() {
    releaseWakeLock();
    try {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } catch (Exception ignored) {
    }
    stopSelf();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    ensureChannel(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent != null ? intent.getAction() : null;
    if (ACTION_CANCEL.equals(action)) {
      cancelActive(this);
      return START_NOT_STICKY;
    }
    Session s = active;
    Notification n = buildProgressNotification(this, s);
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(NOTIF_ID, n);
    }
    if (s == null || !s.running) {
      stopForegroundClean();
      return START_NOT_STICKY;
    }
    acquireWakeLock();
    return START_NOT_STICKY;
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    cancelActive(this);
  }

  @Override
  public void onDestroy() {
    releaseWakeLock();
    if (instance == this) instance = null;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
