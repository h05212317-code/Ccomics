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
 *
 * <p>진행 중인 다운로드가 있을 때 다른 권을 '미리 받기'하면 기존 받기를 취소하지 않고 FIFO 대기열에 이어서 추가한다.
 * 사용자가 미보관 책을 직접 눌러 열면 해당 책을 대기열 맨 앞으로 우선 배치하고 기존 책은 조각을 유지한 채 뒤로 이어 받는다.
 */
public final class DownloadService extends Service {
  static final String CHANNEL_ID = "ccomics_download";
  static final String ACTION_START = "kr.ccomics.DOWNLOAD_START";
  static final String ACTION_CANCEL = "kr.ccomics.DOWNLOAD_CANCEL";
  static final int NOTIF_ID = 201;
  static final int DONE_NOTIF_ID = 202;
  static final int SCAN_DONE_NOTIF_ID = 203;

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor();
  private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
  private static final Object LOCK = new Object();

  static final class Target {
    final long bookId;
    final String url, title, auth;

    Target(long bookId, String url, String title, String auth) {
      this.bookId = bookId;
      this.url = url;
      this.title = title == null ? "" : title;
      this.auth = auth == null ? "" : auth;
    }

    String outerUrl() {
      return RemoteBooks.outer(url);
    }
  }

  static final class EnqueueResult {
    final int sessionId;
    final int addedCount;
    final boolean appended;

    EnqueueResult(int sessionId, int addedCount, boolean appended) {
      this.sessionId = sessionId;
      this.addedCount = addedCount;
      this.appended = appended;
    }
  }

  interface Listener {
    void onDownloadProgress(int sessionId, String bannerText);

    void onDownloadBookSaved(int sessionId, long bookId, boolean openNow);

    void onDownloadFinished(int sessionId, long openBookId, int completedCount, boolean batch);

    void onDownloadFailed(int sessionId, Exception error, boolean batch);

    void onDownloadCancelled(int sessionId);
  }

  interface ScanListener {
    void onScanChanged();

    void onScanProgress(String bannerText);

    void onScanFinished(List<Long> finishedIds, List<String> problems);
  }

  static final class Session {
    final int id;
    final ArrayDeque<Target> queue = new ArrayDeque<>();
    Target current;
    RemoteBooks.Cancel itemCancel;
    volatile boolean running = true;
    volatile boolean stopAll;
    volatile boolean preempted;
    volatile boolean batchMode;
    volatile long openBookId;
    volatile int totalPlanned;
    volatile int completedCount;
    volatile String notifTitle = "원격 책 받는 중";
    volatile String notifText = "준비 중…";
    volatile String bannerText = "책을 여는 중…";
    volatile int percent;
    volatile long lastNotifAt;

    Session(int id, boolean batchMode, long openBookId) {
      this.id = id;
      this.batchMode = batchMode;
      this.openBookId = openBookId;
    }

    boolean containsOuterLocked(String outer) {
      if (current != null && outer.equals(current.outerUrl())) return true;
      for (Target t : queue) if (outer.equals(t.outerUrl())) return true;
      return false;
    }

    int remainingLocked() {
      return (current != null ? 1 : 0) + queue.size();
    }
  }

  private static volatile DownloadService instance;
  private static volatile Session active;
  private static volatile Listener listener;
  private static volatile long pendingOpenBookId;
  private static volatile boolean pendingRefresh;

  private static final Set<Long> scanningIds = new HashSet<>();
  private static volatile ScanListener scanListener;
  private static volatile boolean scanRunning;
  private static volatile String scanBanner = "";
  private static volatile String scanNotifText = "폴더 목록을 읽고 있습니다…";
  private static volatile int scanDone, scanTotal;
  private static volatile long lastScanNotifAt;
  private static volatile boolean pendingScanRefresh;
  private static volatile String pendingScanProblems = "";

  private PowerManager.WakeLock wakeLock;

  static void setListener(Listener l) {
    listener = l;
  }

  static void setScanListener(ScanListener l) {
    scanListener = l;
  }

  static boolean isScanning() {
    synchronized (LOCK) {
      return scanRunning;
    }
  }

  static Set<Long> activeScanIds() {
    synchronized (LOCK) {
      return new HashSet<>(scanningIds);
    }
  }

  static String currentScanBanner() {
    return scanRunning ? scanBanner : "";
  }

  static boolean consumePendingScanRefresh() {
    boolean v = pendingScanRefresh;
    pendingScanRefresh = false;
    return v;
  }

  static String consumePendingScanProblems() {
    String s = pendingScanProblems;
    pendingScanProblems = "";
    return s;
  }

  private static boolean isAnyWorkRunningLocked() {
    return (active != null && active.running) || scanRunning;
  }

  /** 책과 표지 색인을 포그라운드 서비스에서 실행해 다른 앱 전환·화면 꺼짐 시에도 끝까지 완료한다. */
  static List<Long> enqueueScan(Context ctx, Library db, List<Long> ids) {
    if (db == null || ids == null || ids.isEmpty()) return Collections.emptyList();
    Context appCtx = ctx.getApplicationContext();
    List<Long> batch = new ArrayList<>();
    synchronized (LOCK) {
      for (long id : ids) if (scanningIds.add(id)) batch.add(id);
      if (batch.isEmpty()) return Collections.emptyList();
      if (!scanRunning) {
        scanRunning = true;
        scanDone = 0;
        scanTotal = 0;
        scanBanner = "책과 표지를 확인하고 있습니다…";
        scanNotifText = "폴더 목록을 읽고 있습니다…";
      }
    }
    ensureForegroundStarted(appCtx);
    SCAN_EXECUTOR.execute(() -> runScanBatch(appCtx, db, batch));
    return batch;
  }

  private static void runScanBatch(Context appCtx, Library db, List<Long> batch) {
    ArrayList<String> problems = new ArrayList<>();
    for (long id : batch) {
      Library.Source src = db.source(id);
      try {
        db.scan(
            id,
            new Library.Notice() {
              public void changed() {
                pendingScanRefresh = true;
                ScanListener sl = scanListener;
                if (sl != null) sl.onScanChanged();
              }

              public void progress(int done, int total) {
                if (total <= 1) return;
                scanDone = done;
                scanTotal = total;
                String banner = "책과 표지를 확인하고 있습니다… (" + done + " / " + total + ")";
                scanBanner = banner;
                scanNotifText = done + " / " + total + "권 확인 중";
                ScanListener sl = scanListener;
                if (sl != null) sl.onScanProgress(banner);
                long now = System.currentTimeMillis();
                if (now - lastScanNotifAt >= 350) {
                  lastScanNotifAt = now;
                  DownloadService svc = instance;
                  if (svc != null) svc.refreshActiveNotification();
                }
              }
            });
      } catch (Exception e) {
        String reason = Library.reason(e);
        if (src != null && WebDav.isUrl(src.url))
          problems.add("[" + WebDav.displayName(src.url) + "] " + reason);
        else problems.add(reason);
      }
    }
    boolean stillWorking;
    boolean finishedAllScans;
    synchronized (LOCK) {
      scanningIds.removeAll(batch);
      finishedAllScans = scanningIds.isEmpty();
      if (finishedAllScans) {
        scanRunning = false;
        scanBanner = "";
        scanDone = 0;
        scanTotal = 0;
      }
      stillWorking = isAnyWorkRunningLocked();
    }
    pendingScanRefresh = true;
    DownloadService svc = instance;
    if (svc != null) {
      if (!stillWorking) svc.stopForegroundClean();
      else svc.refreshActiveNotification();
    }
    ScanListener sl = scanListener;
    if (sl != null) {
      sl.onScanFinished(batch, problems);
    } else if (finishedAllScans) {
      if (!problems.isEmpty()) pendingScanProblems = android.text.TextUtils.join("\n\n", problems);
      postScanCompletionNotification(appCtx, problems.isEmpty());
    }
  }

  static boolean isRunning() {
    synchronized (LOCK) {
      return active != null && active.running;
    }
  }

  static boolean isWaitingToOpen() {
    synchronized (LOCK) {
      return active != null && active.running && active.openBookId > 0;
    }
  }

  static int activeSessionId() {
    synchronized (LOCK) {
      return active != null && active.running ? active.id : 0;
    }
  }

  static int queuedCount() {
    synchronized (LOCK) {
      return active != null && active.running ? active.remainingLocked() : 0;
    }
  }

  static boolean isQueuedOrDownloading(String url) {
    if (url == null || url.isEmpty()) return false;
    String outer = RemoteBooks.outer(url);
    synchronized (LOCK) {
      return active != null && active.running && active.containsOuterLocked(outer);
    }
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

  /** 사용자가 직접 누른 책 1권을 최우선으로 받는다. 기존 미리 받기 대기열이 있으면 버리지 않고 이어서 받는다. */
  static int startSingle(Context ctx, Target target) {
    Context appCtx = ctx.getApplicationContext();
    RemoteBooks.Cancel cancelToPreempt = null;
    Session toStart = null;
    int sid;
    synchronized (LOCK) {
      pendingOpenBookId = 0L;
      Session cur = active;
      if (cur != null && cur.running && !cur.stopAll) {
        String outer = target.outerUrl();
        cur.openBookId = target.bookId;
        if (cur.current != null && outer.equals(cur.current.outerUrl())) {
          // 이미 지금 받고 있는 책이면 그대로 끝까지 받고 열기만 연결한다.
          return cur.id;
        }
        // 대기열 안에 이미 있으면 맨 앞으로 옮기고, 없으면 맨 앞에 끼워 넣는다.
        boolean existed = false;
        Iterator<Target> it = cur.queue.iterator();
        while (it.hasNext()) {
          if (outer.equals(it.next().outerUrl())) {
            it.remove();
            existed = true;
            break;
          }
        }
        if (cur.batchMode && cur.current != null) {
          // 기존 미리 받기 책은 조각이 남아 있으므로 뒤로 미루고, 열려는 책을 먼저 받는다.
          cur.queue.addFirst(cur.current);
          cur.queue.addFirst(target);
          if (!existed) cur.totalPlanned++;
          cur.preempted = true;
          cancelToPreempt = cur.itemCancel;
          sid = cur.id;
        } else {
          // 이전 단일 열기 대기였으면 이전 세션을 교체한다.
          cur.stopAll = true;
          cur.running = false;
          cancelToPreempt = cur.itemCancel;
          Session fresh = createSessionLocked(Collections.singletonList(target), false, target.bookId);
          toStart = fresh;
          sid = fresh.id;
        }
      } else {
        Session fresh = createSessionLocked(Collections.singletonList(target), false, target.bookId);
        toStart = fresh;
        sid = fresh.id;
      }
    }
    if (cancelToPreempt != null) {
      RemoteBooks.Cancel c = cancelToPreempt;
      c.markCancelled();
      EXECUTOR.execute(c::cancel);
    }
    if (toStart != null) launchSession(appCtx, toStart);
    return sid;
  }

  /** 미리 받기: 이미 받기가 진행 중이면 취소하지 않고 대기열(FIFO)에 추가한다. */
  static EnqueueResult enqueueBatch(Context ctx, List<Target> targets) {
    if (targets == null || targets.isEmpty()) return new EnqueueResult(0, 0, false);
    Context appCtx = ctx.getApplicationContext();
    Session toStart = null;
    int sid = 0;
    int added = 0;
    boolean appended = false;
    synchronized (LOCK) {
      Session cur = active;
      if (cur != null && cur.running && !cur.stopAll) {
        cur.batchMode = true;
        for (Target t : targets) {
          if (RemoteBooks.has(appCtx, t.url)) continue;
          if (!cur.containsOuterLocked(t.outerUrl())) {
            cur.queue.addLast(t);
            cur.totalPlanned++;
            added++;
          }
        }
        sid = cur.id;
        appended = true;
        if (added > 0) {
          DownloadService svc = instance;
          if (svc != null) svc.updateNotification(cur);
        }
      } else {
        List<Target> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Target t : targets) {
          if (RemoteBooks.has(appCtx, t.url)) continue;
          if (seen.add(t.outerUrl())) deduped.add(t);
        }
        if (!deduped.isEmpty()) {
          toStart = createSessionLocked(deduped, true, 0L);
          sid = toStart.id;
          added = deduped.size();
        }
      }
    }
    if (toStart != null) launchSession(appCtx, toStart);
    return new EnqueueResult(sid, added, appended);
  }

  static int startBatch(Context ctx, List<Target> targets) {
    return enqueueBatch(ctx, targets).sessionId;
  }

  private static Session createSessionLocked(List<Target> targets, boolean batch, long openBookId) {
    int sid = NEXT_ID.getAndIncrement();
    Session session = new Session(sid, batch, openBookId);
    session.queue.addAll(targets);
    session.totalPlanned = targets.size();
    Target first = targets.get(0);
    String origin = WebDav.isPikPak(first.url) ? "PikPak" : "PC";
    session.notifTitle =
        batch
            ? (targets.size() > 1 ? "[1/" + targets.size() + "] " : "") + origin + "에서 미리 받는 중"
            : origin + "에서 받는 중";
    session.notifText = first.title.isEmpty() ? "다운로드 준비 중…" : first.title;
    session.bannerText = batch ? "미리 받기를 시작합니다…" : "책을 여는 중…";
    active = session;
    return session;
  }

  private static void ensureForegroundStarted(Context appCtx) {
    try {
      Intent in = new Intent(appCtx, DownloadService.class).setAction(ACTION_START);
      ContextCompat.startForegroundService(appCtx, in);
    } catch (Exception ignored) {
      // 테스트 환경이나 OS 제약 시에도 앱 내부 백그라운드 스레드로 계속 실행한다.
    }
  }

  private static void launchSession(Context appCtx, Session session) {
    ensureForegroundStarted(appCtx);
    EXECUTOR.execute(() -> runSession(appCtx, session));
  }

  /** 단일 열기 대기만 취소(백그라운드 미리 받기가 돌고 있으면 열기 예약만 해제). */
  static void cancelOpenWaitOnly() {
    synchronized (LOCK) {
      if (active != null && active.running && active.batchMode) {
        active.openBookId = 0L;
        pendingOpenBookId = 0L;
      }
    }
  }

  static void cancelActive(Context ctx) {
    Session prev;
    RemoteBooks.Cancel itemCancel = null;
    boolean stillWorking;
    synchronized (LOCK) {
      prev = active;
      pendingOpenBookId = 0L;
      if (prev != null) {
        prev.stopAll = true;
        prev.running = false;
        prev.queue.clear();
        itemCancel = prev.itemCancel;
        active = null;
      }
      stillWorking = isAnyWorkRunningLocked();
    }
    if (itemCancel != null) {
      RemoteBooks.Cancel c = itemCancel;
      c.markCancelled();
      EXECUTOR.execute(c::cancel);
    }
    DownloadService svc = instance;
    if (svc != null) {
      if (!stillWorking) svc.stopForegroundClean();
      else svc.refreshActiveNotification();
    }
    Listener l = listener;
    if (l != null && prev != null) l.onDownloadCancelled(prev.id);
  }

  private static void runSession(Context appCtx, Session session) {
    try {
      while (true) {
        Target t;
        RemoteBooks.Cancel cancel;
        int pos, count;
        boolean isOpeningTarget;
        synchronized (LOCK) {
          if (session.stopAll || !session.running || active != session) {
            throw new InterruptedIOException("취소");
          }
          t = session.queue.pollFirst();
          if (t == null) {
            session.current = null;
            session.itemCancel = null;
            break;
          }
          session.current = t;
          session.preempted = false;
          cancel = new RemoteBooks.Cancel();
          session.itemCancel = cancel;
          pos = session.completedCount + 1;
          count = Math.max(pos, session.totalPlanned);
          isOpeningTarget = (session.openBookId > 0 && t.bookId == session.openBookId);
        }

        if (RemoteBooks.has(appCtx, t.url)) {
          synchronized (LOCK) {
            session.completedCount++;
            session.current = null;
            session.itemCancel = null;
          }
          notifyBookSaved(session, t, isOpeningTarget);
          continue;
        }

        String origin = WebDav.isPikPak(t.url) ? "PikPak" : "PC";
        String actionLabel = isOpeningTarget ? "받는 중" : (session.batchMode ? "미리 받는 중" : "받는 중");
        final long[] speedState = {0L, 0L};

        try {
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
                  int curPos, curCount;
                  synchronized (LOCK) {
                    curPos = session.completedCount + 1;
                    curCount = Math.max(curPos, session.totalPlanned);
                  }
                  String dynPrefix = curCount > 1 ? "[" + curPos + "/" + curCount + "] " : "";
                  String titlePrefix = curCount > 1 ? "[" + curPos + "/" + curCount + "] " : "";
                  session.notifTitle = titlePrefix + origin + "에서 " + actionLabel;
                  String banner =
                      String.format(
                          Locale.KOREA,
                          "%s%s에서 %s %d%%  ·  %d / %dMB%s",
                          dynPrefix,
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
                  return session.stopAll
                      || !session.running
                      || cancel.isCancelled()
                      || active != session;
                }
              },
              cancel);
        } catch (InterruptedIOException interrupted) {
          boolean wasPreempted;
          synchronized (LOCK) {
            wasPreempted = session.preempted && !session.stopAll && active == session;
            session.preempted = false;
            session.current = null;
            session.itemCancel = null;
          }
          if (wasPreempted) continue; // 우선 열기 책이 앞으로 끼어들어 현재 책은 대기열에 보존됨
          throw interrupted;
        }

        boolean openNow;
        synchronized (LOCK) {
          session.completedCount++;
          session.current = null;
          session.itemCancel = null;
          openNow = session.openBookId > 0 && t.bookId == session.openBookId;
          if (openNow) session.openBookId = 0L;
        }
        notifyBookSaved(session, t, openNow);
      }

      boolean wasActive;
      int completed;
      long openId;
      boolean batch;
      boolean stillWorking;
      synchronized (LOCK) {
        wasActive = (active == session && !session.stopAll);
        session.running = false;
        if (active == session) active = null;
        completed = session.completedCount;
        openId = session.openBookId;
        batch = session.batchMode;
        stillWorking = isAnyWorkRunningLocked();
      }
      if (!wasActive) return;
      pendingRefresh = true;
      DownloadService svc = instance;
      if (svc != null) {
        if (!stillWorking) svc.stopForegroundClean();
        else svc.refreshActiveNotification();
      }

      Listener l = listener;
      if (l != null) {
        l.onDownloadFinished(session.id, openId, completed, batch);
      } else {
        if (!batch && openId > 0) pendingOpenBookId = openId;
        postCompletionNotification(appCtx, session, completed);
      }
    } catch (Exception e) {
      boolean wasActive;
      boolean batch;
      boolean stillWorking;
      synchronized (LOCK) {
        wasActive = (active == session);
        batch = session.batchMode;
        session.running = false;
        session.current = null;
        session.itemCancel = null;
        if (wasActive) active = null;
        stillWorking = isAnyWorkRunningLocked();
      }
      DownloadService svc = instance;
      if (svc != null) {
        if (!stillWorking) svc.stopForegroundClean();
        else svc.refreshActiveNotification();
      }
      Listener l = listener;
      if (e instanceof InterruptedIOException) {
        if (l != null && wasActive) l.onDownloadCancelled(session.id);
      } else {
        if (l != null && wasActive) l.onDownloadFailed(session.id, e, batch);
        else if (wasActive) postFailureNotification(appCtx, session, Library.reason(e));
      }
    }
  }

  private static void notifyBookSaved(Session session, Target t, boolean openNow) {
    pendingRefresh = true;
    Listener l = listener;
    if (l != null && active == session) {
      l.onDownloadBookSaved(session.id, t.bookId, openNow);
    } else if (openNow) {
      pendingOpenBookId = t.bookId;
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
      String title = s.batchMode ? "미리 받기 완료" : "다운로드 완료";
      String body =
          completed > 1
              ? completed + "개 파일을 휴대폰 보관함에 저장했습니다."
              : "휴대폰 보관함에 저장했습니다. 눌러서 바로 읽으세요.";
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

  private static Notification buildActiveNotification(Context ctx) {
    Session s = active;
    if (s != null && s.running) return buildProgressNotification(ctx, s);
    if (scanRunning) return buildScanNotification(ctx);
    return buildProgressNotification(ctx, null);
  }

  private static Notification buildScanNotification(Context ctx) {
    ensureChannel(ctx);
    int total = scanTotal, done = scanDone;
    Notification.Builder b =
        new Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_remote)
            .setContentTitle("책과 표지 확인 중")
            .setContentText(scanNotifText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(ctx))
            .setProgress(Math.max(1, total), Math.max(0, done), total <= 0);
    if (Build.VERSION.SDK_INT >= 31) {
      b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    }
    return b.build();
  }

  private static void postScanCompletionNotification(Context ctx, boolean ok) {
    try {
      ensureChannel(ctx);
      NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
      if (nm == null) return;
      Notification n =
          new Notification.Builder(ctx, CHANNEL_ID)
              .setSmallIcon(R.drawable.icon_remote)
              .setContentTitle(ok ? "책과 표지 확인 완료" : "폴더 확인 중 오류 발생")
              .setContentText(ok ? "서재 목록이 최신 상태로 업데이트되었습니다." : "앱을 열어 자세한 내용을 확인하세요.")
              .setAutoCancel(true)
              .setContentIntent(openAppIntent(ctx))
              .build();
      nm.notify(SCAN_DONE_NOTIF_ID, n);
    } catch (Exception ignored) {
    }
  }

  private void refreshActiveNotification() {
    try {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm != null) nm.notify(NOTIF_ID, buildActiveNotification(this));
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
    boolean hasWork;
    synchronized (LOCK) {
      hasWork = isAnyWorkRunningLocked();
    }
    Notification n = buildActiveNotification(this);
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(NOTIF_ID, n);
    }
    if (!hasWork) {
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
