package kr.ccomics;

import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.*;
import java.util.function.*;

/**
 * 서재 설정 · 원격 폴더 관리 · 읽기 설정을 카드 섹션형 전체 화면 UI로 구성한다.
 * 토글·세그먼트·슬라이더를 팝업 닫힘 없이 즉시 조작할 수 있다.
 */
final class SettingsUi {
  final Main host;

  SettingsUi(Main host) {
    this.host = host;
  }

  private int cardBg() {
    return host.dark ? 0xff222623 : 0xffffffff;
  }

  private int cardStroke() {
    return host.dark ? 0xff303632 : 0xffe4e7df;
  }

  private int dividerColor() {
    return host.dark ? 0xff2c322e : 0xffedf0e9;
  }

  private int trackBg() {
    return host.dark ? 0xff181b19 : 0xffebeee7;
  }

  private int dangerColor() {
    return host.dark ? 0xffff7b7b : 0xffc62828;
  }

  private int activePillText() {
    return host.dark ? 0xff161a17 : 0xffffffff;
  }

  private GradientDrawable cardShape() {
    GradientDrawable d = new GradientDrawable();
    d.setColor(cardBg());
    d.setCornerRadius(host.dp(16));
    d.setStroke(host.dp(1), cardStroke());
    return d;
  }

  private GradientDrawable pillShape(int fill, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(host.dp(radiusDp));
    return d;
  }

  private GradientDrawable outlinePill(int strokeColor, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(Color.TRANSPARENT);
    d.setCornerRadius(host.dp(radiusDp));
    d.setStroke(host.dp(1), strokeColor);
    return d;
  }

  /** 전체 화면 설정 껍데기(상단 바 + 스크롤 본문 + 안전 영역 인셋 + 터치 차단). */
  private LinearLayout shell(
      String title,
      Runnable onBack,
      Consumer<LinearLayout> topAction,
      Consumer<LinearLayout> populate,
      int scrollY) {
    LinearLayout root = host.col();
    root.setBackgroundColor(host.bg);
    root.setClickable(true);
    root.setFocusable(true);

    LinearLayout bar = host.row();
    bar.setPadding(host.dp(8), host.dp(8), host.dp(12), host.dp(8));
    bar.setMinimumHeight(host.dp(56));
    ToolsIcon backBtn = new ToolsIcon(host, "back", host.ink, "뒤로");
    backBtn.setOnClickListener(v -> onBack.run());
    bar.addView(backBtn, host.dp(44), host.dp(44));

    TextView titleView = host.label(title, 20, host.ink, true);
    titleView.setPadding(host.dp(4), 0, host.dp(8), 0);
    host.one(titleView);
    host.weight(bar, titleView);

    if (topAction != null) topAction.accept(bar);
    root.addView(bar, -1, -2);

    View headerLine = new View(host);
    headerLine.setBackgroundColor(dividerColor());
    root.addView(headerLine, new LinearLayout.LayoutParams(-1, host.dp(1)));

    ScrollView scroll = new ScrollView(host);
    scroll.setTag("settings_scroll");
    scroll.setClipToPadding(false);
    scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    int screenW = host.getResources().getConfiguration().screenWidthDp;
    boolean wide = screenW >= 680;
    LinearLayout content = host.col();
    content.setTag("settings_content");
    int hPad = host.dp(wide ? 24 : (screenW >= 600 ? 20 : 16));
    content.setPadding(hPad, host.dp(10), hPad, host.dp(28));
    populate.accept(content);
    FrameLayout.LayoutParams contentLp =
        new FrameLayout.LayoutParams(
            wide ? host.dp(640) : ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    if (wide) contentLp.gravity = Gravity.CENTER_HORIZONTAL;
    scroll.addView(content, contentLp);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    if (scrollY > 0) scroll.post(() -> scroll.scrollTo(0, scrollY));

    applySafeInsets(root, ViewCompat.getRootWindowInsets(host.frame));
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, windowInsets) -> {
          applySafeInsets(view, windowInsets);
          return windowInsets;
        });
    root.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          public void onViewAttachedToWindow(View v) {
            applySafeInsets(v, ViewCompat.getRootWindowInsets(v));
            ViewCompat.requestApplyInsets(v);
          }

          public void onViewDetachedFromWindow(View v) {}
        });
    return root;
  }

  private void applySafeInsets(View view, WindowInsetsCompat windowInsets) {
    if (view == null || windowInsets == null) return;
    Insets safe =
        Insets.max(
            windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()),
            windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()));
    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
  }

  private LinearLayout section(LinearLayout parent, String title) {
    TextView hdr = host.label(title, 12, host.accent, true);
    hdr.setPadding(host.dp(6), host.dp(14), host.dp(6), host.dp(7));
    parent.addView(hdr, -1, -2);

    LinearLayout card = host.col();
    card.setBackground(cardShape());
    card.setClipToOutline(true);
    parent.addView(card, -1, -2);
    return card;
  }

  private void addDividerIfNeeded(LinearLayout card) {
    if (card.getChildCount() == 0) return;
    View line = new View(host);
    line.setBackgroundColor(dividerColor());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, host.dp(1));
    lp.leftMargin = host.dp(16);
    lp.rightMargin = host.dp(16);
    card.addView(line, lp);
  }

  private void addActionRow(
      LinearLayout card, String title, String subtitle, String badge, Runnable onClick) {
    addActionRow(card, title, subtitle, badge, host.ink, host.accent, onClick);
  }

  private void addActionRow(
      LinearLayout card,
      String title,
      String subtitle,
      String badge,
      int titleColor,
      int badgeColor,
      Runnable onClick) {
    addDividerIfNeeded(card);
    LinearLayout row = host.row();
    row.setPadding(host.dp(16), host.dp(13), host.dp(12), host.dp(13));
    row.setMinimumHeight(host.dp(56));
    row.setFocusable(true);
    row.setOnClickListener(v -> onClick.run());

    LinearLayout textCol = host.col();
    TextView titleView = host.label(title, 15, titleColor, true);
    textCol.addView(titleView);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView subView = host.label(subtitle, 12, host.muted, false);
      subView.setPadding(0, host.dp(3), 0, 0);
      subView.setMaxLines(2);
      subView.setEllipsize(TextUtils.TruncateAt.END);
      textCol.addView(subView);
    }
    host.weight(row, textCol);

    if (badge != null && !badge.isEmpty()) {
      TextView b = host.label(badge, 12, badgeColor, true);
      b.setPadding(host.dp(9), host.dp(4), host.dp(9), host.dp(4));
      b.setBackground(pillShape(trackBg(), 12));
      LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
      blp.leftMargin = host.dp(8);
      row.addView(b, blp);
    }

    ToolsIcon chev = new ToolsIcon(host, "chevron_right", host.muted, "");
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(host.dp(24), host.dp(24));
    clp.leftMargin = host.dp(4);
    row.addView(chev, clp);

    card.addView(row, -1, -2);
  }

  private void addSwitchRow(
      LinearLayout card,
      String title,
      String subtitle,
      boolean checked,
      Consumer<Boolean> onToggle) {
    addDividerIfNeeded(card);
    LinearLayout row = host.row();
    row.setPadding(host.dp(16), host.dp(12), host.dp(14), host.dp(12));
    row.setMinimumHeight(host.dp(56));

    LinearLayout textCol = host.col();
    TextView titleView = host.label(title, 15, host.ink, true);
    textCol.addView(titleView);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView subView = host.label(subtitle, 12, host.muted, false);
      subView.setPadding(0, host.dp(3), 0, 0);
      subView.setMaxLines(2);
      subView.setEllipsize(TextUtils.TruncateAt.END);
      textCol.addView(subView);
    }
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
    tlp.rightMargin = host.dp(12);
    row.addView(textCol, tlp);

    Switch sw = new Switch(host);
    sw.setContentDescription(title);
    sw.setChecked(checked);
    int[][] states = {{android.R.attr.state_checked}, {}};
    sw.setThumbTintList(
        new ColorStateList(states, new int[] {host.accent, host.dark ? 0xff8e948e : 0xffcfd4cb}));
    sw.setTrackTintList(
        new ColorStateList(
            states,
            new int[] {
              (host.accent & 0x00ffffff) | 0x55000000, host.dark ? 0x55777d77 : 0x55a8afa3
            }));
    sw.setOnCheckedChangeListener((v, isChecked) -> onToggle.accept(isChecked));
    row.addView(sw, -2, -2);

    row.setOnClickListener(v -> sw.toggle());
    card.addView(row, -1, -2);
  }

  /** 카드 내부에 제목 + 2~4단 세그먼트 선택기를 추가한다. */
  private void addSegmentRow(
      LinearLayout card,
      String title,
      String subtitle,
      String[] options,
      int selectedIndex,
      IntConsumer onSelect) {
    addDividerIfNeeded(card);
    LinearLayout block = host.col();
    block.setPadding(host.dp(16), host.dp(13), host.dp(16), host.dp(14));

    TextView titleView = host.label(title, 15, host.ink, true);
    block.addView(titleView);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView subView = host.label(subtitle, 12, host.muted, false);
      subView.setPadding(0, host.dp(2), 0, 0);
      block.addView(subView);
    }

    LinearLayout track = buildSegmentTrack(options, selectedIndex, onSelect);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = host.dp(10);
    block.addView(track, lp);
    card.addView(block, -1, -2);
  }

  private LinearLayout buildSegmentTrack(
      String[] options, int initialSelected, IntConsumer onSelect) {
    LinearLayout track = host.row();
    track.setBackground(pillShape(trackBg(), 12));
    track.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(3));

    final int[] current = {initialSelected};
    final TextView[] btns = new TextView[options.length];
    Runnable refreshStyle =
        () -> {
          for (int i = 0; i < btns.length; i++) {
            boolean active = i == current[0];
            btns[i].setBackground(active ? pillShape(host.accent, 9) : null);
            btns[i].setTextColor(active ? activePillText() : host.muted);
          }
        };

    for (int i = 0; i < options.length; i++) {
      final int idx = i;
      TextView btn = host.label(options[i], 13, host.muted, true);
      btn.setGravity(Gravity.CENTER);
      btn.setPadding(host.dp(6), host.dp(9), host.dp(6), host.dp(9));
      btn.setMinHeight(host.dp(36));
      host.one(btn);
      btn.setOnClickListener(
          v -> {
            if (current[0] == idx) return;
            current[0] = idx;
            refreshStyle.run();
            onSelect.accept(idx);
          });
      btns[i] = btn;
      track.addView(btn, new LinearLayout.LayoutParams(0, -2, 1));
    }
    refreshStyle.run();
    return track;
  }

  private String appVersionLabel() {
    try {
      PackageInfo info = host.getPackageManager().getPackageInfo(host.getPackageName(), 0);
      if (info != null && info.versionName != null && !info.versionName.isEmpty())
        return "v" + info.versionName;
    } catch (Exception ignored) {
    }
    return "";
  }

  /** 1) 서재 설정 화면. */
  View buildLibrarySettings(int scrollY) {
    return shell(
        "서재 설정",
        host::closeSettings,
        null,
        content -> {
          // ── 1. 폴더 및 서재 ──
          LinearLayout folderCard = section(content, "폴더 및 서재");

          List<Library.Source> remoteSrcs = host.db != null ? host.db.remoteSources() : Collections.emptyList();
          int activeRemote = 0, cachedOnlyRemote = 0;
          for (Library.Source s : remoteSrcs) {
            int m = host.sourceMode(s.id, s.url);
            if (m == Main.SOURCE_ON) activeRemote++;
            else if (m == Main.SOURCE_CACHED_ONLY) cachedOnlyRemote++;
          }
          String remoteBadge;
          String remoteSub;
          if (remoteSrcs.isEmpty()) {
            remoteBadge = "없음";
            remoteSub = "PC 원격 폴더나 PikPak 폴더를 연결하고 관리합니다";
          } else {
            remoteBadge = activeRemote + "/" + remoteSrcs.size() + " 켜짐";
            remoteSub =
                cachedOnlyRemote > 0
                    ? "PC · PikPak 폴더별 모드 설정 (" + cachedOnlyRemote + "개 보관본만 보기)"
                    : "PC · PikPak 폴더별 켜기·보관본만·비밀번호 관리";
          }
          addActionRow(
              folderCard, "원격 폴더 관리", remoteSub, remoteBadge, host::openRemoteSourcesSettings);

          addActionRow(
              folderCard,
              "폴더 · 파일 추가",
              "기기 폴더, Windows 공유, PC 원격, PikPak 연결",
              null,
              host::addMenu);

          addActionRow(
              folderCard,
              "연결 폴더 다시 읽기",
              "활성화된 폴더에서 새 책과 표지를 확인합니다",
              null,
              () -> {
                host.closeSettings();
                host.refreshSources(false);
              });

          String shelfName = host.shelf.isEmpty() ? "전체 책장" : host.shelf;
          addActionRow(folderCard, "책장 관리", "책장을 만들거나 이름을 바꿉니다", shelfName, host::shelves);

          // ── 2. 화면 표시 ──
          LinearLayout displayCard = section(content, "화면 표시");

          int themeChoice = Math.max(0, Math.min(2, host.prefs.getInt("theme", 0)));
          addSegmentRow(
              displayCard,
              "화면 테마",
              null,
              new String[] {"기기 설정", "밝게", "어둡게"},
              themeChoice,
              idx -> {
                host.prefs.edit().putInt("theme", idx).apply();
                host.renderNow(true);
              });

          addCoverSizeRow(displayCard);

          addSwitchRow(
              displayCard,
              "별점순으로 묶어 표시",
              "서재를 별점 구간별로 나눠 보여 줍니다",
              host.groupByRating(),
              on -> {
                host.prefs.edit().putBoolean("groupByRating", on).apply();
                host.gridIndex = host.gridOffset = 0;
                host.settingsDirty = true;
              });

          addSwitchRow(
              displayCard,
              "책 이름만 깔끔하게 표시",
              "앞뒤 [문구]와 파일 표기를 숨깁니다",
              host.prefs.getBoolean("hideVolumeTags", true),
              on -> {
                host.prefs.edit().putBoolean("hideVolumeTags", on).apply();
                host.settingsDirty = true;
              });

          addSwitchRow(
              displayCard,
              "책장 작품 수 표시",
              "상단 필터 옆에 작품 수를 함께 표시합니다",
              host.showShelfCount(),
              on -> {
                host.prefs.edit().putBoolean("showShelfCount", on).apply();
                host.settingsDirty = true;
              });

          addSwitchRow(
              displayCard,
              "이어 읽기 플로팅 버튼",
              "하단에 최근 읽던 책 바로가기 버튼을 띄웁니다",
              host.showFloatingResume(),
              on -> {
                host.prefs.edit().putBoolean("showFloatingResume", on).apply();
                host.resumeDismissed = false;
                host.settingsDirty = true;
              });

          // ── 3. 저장 공간 ──
          LinearLayout storageCard = section(content, "저장 공간");

          long[] remoteUsage = RemoteBooks.usage(host);
          String storageBadge =
              remoteUsage[1] > 0 ? RemoteBooks.formatSize(remoteUsage[1]) : "비어 있음";
          String storageSub =
              remoteUsage[0] > 0
                  ? "휴대폰에 받아 둔 원격 책 " + remoteUsage[0] + "개 파일 관리"
                  : "PC·PikPak에서 받아 둔 오프라인 책을 관리합니다";
          addActionRow(storageCard, "원격 보관함 관리", storageSub, storageBadge, host::remoteStorage);

          addActionRow(
              storageCard,
              "임시 파일 정리",
              "표지 썸네일·스트리밍 임시 캐시만 비웁니다 (저장본 유지)",
              null,
              () ->
                  host.worker.execute(
                      () -> {
                        Archive.clearSpools(host);
                        WebDav.Cache.clear(host);
                        host.coverCache.evictAll();
                        host.runOnUiThread(() -> host.toast("임시 파일을 정리했습니다."));
                      }));

          // ── 4. 앱 정보 ──
          LinearLayout appCard = section(content, "앱 정보");
          addActionRow(
              appCard,
              "앱 업데이트 확인",
              "GitHub 최신 정식 버전을 확인하고 설치합니다",
              appVersionLabel(),
              () -> {
                if (host.updates != null) host.updates.check(true);
              });
        },
        scrollY);
  }

  private void addCoverSizeRow(LinearLayout card) {
    addDividerIfNeeded(card);
    int initial = host.coverWidthDp();
    final int[] latest = {initial};

    LinearLayout block = host.col();
    block.setPadding(host.dp(16), host.dp(13), host.dp(16), host.dp(10));

    LinearLayout top = host.row();
    TextView title = host.label("표지 크기", 15, host.ink, true);
    host.weight(top, title);

    TextView value = host.label(initial + "dp", 13, host.accent, true);
    value.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
    value.setBackground(pillShape(trackBg(), 10));
    top.addView(value);

    TextView reset = host.label("기본", 12, host.muted, true);
    reset.setPadding(host.dp(10), host.dp(4), host.dp(10), host.dp(4));
    reset.setBackground(outlinePill(cardStroke(), 10));
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
    rlp.leftMargin = host.dp(8);
    top.addView(reset, rlp);
    block.addView(top, -1, -2);

    LinearLayout sliderRow = host.row();
    sliderRow.setPadding(0, host.dp(6), 0, 0);
    sliderRow.addView(host.label("작게", 12, host.muted, false));

    SeekBar bar = new SeekBar(host);
    bar.setContentDescription("표지 크기 조절 바");
    bar.setMax(Main.THUMB_MAX_DP - Main.THUMB_MIN_DP);
    bar.setProgress(initial - Main.THUMB_MIN_DP);
    bar.setProgressTintList(ColorStateList.valueOf(host.accent));
    bar.setThumbTintList(ColorStateList.valueOf(host.accent));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, host.dp(40), 1);
    sp.setMargins(host.dp(8), 0, host.dp(8), 0);
    sliderRow.addView(bar, sp);
    sliderRow.addView(host.label("크게", 12, host.muted, false));
    block.addView(sliderRow, -1, -2);

    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            int w = Main.THUMB_MIN_DP + progress;
            latest[0] = w;
            value.setText(w + "dp");
            if (fromUser) host.applyThumbWidth(w, false);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {
            host.applyThumbWidth(latest[0], true);
          }
        });

    reset.setOnClickListener(
        v -> {
          latest[0] = Main.THUMB_DEFAULT_DP;
          bar.setProgress(Main.THUMB_DEFAULT_DP - Main.THUMB_MIN_DP);
          value.setText(Main.THUMB_DEFAULT_DP + "dp");
          host.applyThumbWidth(Main.THUMB_DEFAULT_DP, true);
          host.toast("표지 크기를 기본값(" + Main.THUMB_DEFAULT_DP + "dp)으로 맞췄습니다.");
        });

    card.addView(block, -1, -2);
  }

  /** 2) 원격 폴더 관리 화면 (PC 원격 · PikPak 폴더별 카드형 관리). */
  View buildRemoteSourcesSettings(int scrollY) {
    return shell(
        "원격 폴더 관리",
        host::backFromRemoteSources,
        bar -> {
          ToolsIcon addBtn = new ToolsIcon(host, "add", host.ink, "원격 폴더 추가");
          addBtn.setOnClickListener(
              v ->
                  host.choices(
                      "원격 폴더 추가",
                      new String[] {"PikPak 폴더 연결 (PC 꺼져도 사용)", "PC 원격 폴더 연결"},
                      i -> {
                        if (i == 0) host.pikpak();
                        else host.remote();
                      }));
          bar.addView(addBtn, host.dp(44), host.dp(44));
        },
        content -> {
          List<Library.Source> list =
              host.db != null ? host.db.remoteSources() : Collections.emptyList();

          if (list.isEmpty()) {
            LinearLayout empty = host.col();
            empty.setBackground(cardShape());
            empty.setPadding(host.dp(20), host.dp(24), host.dp(20), host.dp(20));
            TextView t = host.label("연결된 원격 폴더가 없습니다", 16, host.ink, true);
            empty.addView(t);
            TextView sub =
                host.label(
                    "PC 원격 폴더나 PikPak 특정 폴더를 연결하면 휴대폰 용량을 아끼면서 책을 읽을 수 있습니다.",
                    13,
                    host.muted,
                    false);
            sub.setPadding(0, host.dp(6), 0, host.dp(18));
            empty.addView(sub);

            TextView pikBtn = host.label("PikPak 폴더 연결", 14, activePillText(), true);
            pikBtn.setGravity(Gravity.CENTER);
            pikBtn.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12));
            pikBtn.setBackground(pillShape(host.accent, 12));
            pikBtn.setOnClickListener(v -> host.pikpak());
            empty.addView(pikBtn, new LinearLayout.LayoutParams(-1, -2));

            TextView pcBtn = host.label("PC 원격 폴더 연결", 14, host.ink, true);
            pcBtn.setGravity(Gravity.CENTER);
            pcBtn.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12));
            pcBtn.setBackground(outlinePill(cardStroke(), 12));
            pcBtn.setOnClickListener(v -> host.remote());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = host.dp(10);
            empty.addView(pcBtn, plp);

            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
            elp.topMargin = host.dp(12);
            content.addView(empty, elp);
            return;
          }

          TextView hint =
              host.label(
                  "PC가 꺼져 있거나 PikPak이 일반 상태일 때는 해당 폴더를 '보관본만'으로 바꾸면 오류 없이 받아 둔 책만 볼 수 있습니다.",
                  12,
                  host.muted,
                  false);
          hint.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(10));
          content.addView(hint, -1, -2);

          for (Library.Source src : list) {
            content.addView(buildRemoteSourceCard(src));
          }

          // 하단 빠른 추가 버튼 행
          LinearLayout addRow = host.row();
          LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(-1, -2);
          arLp.topMargin = host.dp(14);
          addRow.setLayoutParams(arLp);

          TextView addPik = host.label("+ PikPak 추가", 13, host.accent, true);
          addPik.setGravity(Gravity.CENTER);
          addPik.setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12));
          addPik.setBackground(cardShape());
          addPik.setOnClickListener(v -> host.pikpak());
          LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1);
          p1.rightMargin = host.dp(6);
          addRow.addView(addPik, p1);

          TextView addPc = host.label("+ PC 원격 추가", 13, host.ink, true);
          addPc.setGravity(Gravity.CENTER);
          addPc.setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12));
          addPc.setBackground(cardShape());
          addPc.setOnClickListener(v -> host.remote());
          LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1);
          p2.leftMargin = host.dp(6);
          addRow.addView(addPc, p2);

          content.addView(addRow);
        },
        scrollY);
  }

  private View buildRemoteSourceCard(Library.Source src) {
    boolean pikpak = WebDav.isPikPak(src.url);
    int mode = host.sourceMode(src.id, src.url);
    int count = host.db.sourceBookCount(src.id);

    LinearLayout card = host.col();
    card.setBackground(cardShape());
    card.setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(12));
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
    clp.topMargin = host.dp(10);
    card.setLayoutParams(clp);

    // 상단: 종류 배지 + 경로 + 권수
    LinearLayout top = host.row();
    TextView kind = host.label(pikpak ? "PikPak" : "PC 원격", 11, activePillText(), true);
    kind.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
    kind.setBackground(pillShape(pikpak ? host.remoteCachedColor() : host.accent, 8));
    top.addView(kind);

    String pathTitle = WebDav.displayName(src.url);
    int dot = pathTitle.indexOf(" · ");
    if (dot >= 0) pathTitle = pathTitle.substring(dot + 3);
    TextView title = host.label(pathTitle, 15, host.ink, true);
    host.one(title);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
    tlp.leftMargin = host.dp(8);
    tlp.rightMargin = host.dp(8);
    top.addView(title, tlp);

    top.addView(host.label(count + "권", 12, host.muted, true));
    card.addView(top, -1, -2);

    // 모드 설명
    TextView desc = host.label(modeDescription(mode), 12, host.muted, false);
    desc.setPadding(0, host.dp(8), 0, host.dp(8));
    card.addView(desc, -1, -2);

    // 3단 세그먼트: 켜짐 / 보관본만 / 숨김
    LinearLayout seg =
        buildSegmentTrack(
            new String[] {"켜짐", "보관본만", "숨김"},
            mode,
            nextMode -> {
              host.setSourceMode(src.id, nextMode);
              desc.setText(modeDescription(nextMode));
              host.selected = "";
              host.settingsDirty = true;
              if (nextMode == Main.SOURCE_ON) {
                host.toast("전체 책을 표시하고 새로고침 시 연결합니다.");
                host.scan(Collections.singletonList(src.id));
              } else if (nextMode == Main.SOURCE_CACHED_ONLY) {
                host.toast("미리 받아 둔 보관본만 표시하고 서버에 연결하지 않습니다.");
              } else {
                host.toast("이 폴더의 책을 숨기고 서버에 연결하지 않습니다.");
              }
            });
    card.addView(seg, -1, -2);

    // 하단 액션 버튼: 다시 읽기 · 계정 수정 · 연결 해제
    View line = new View(host);
    line.setBackgroundColor(dividerColor());
    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, host.dp(1));
    dlp.topMargin = host.dp(12);
    dlp.bottomMargin = host.dp(4);
    card.addView(line, dlp);

    LinearLayout actions = host.row();
    actions.addView(
        smallCardAction(
            "다시 읽기",
            host.ink,
            () -> {
              if (host.sourceMode(src.id, src.url) != Main.SOURCE_ON) {
                host.setSourceMode(src.id, Main.SOURCE_ON);
              }
              host.closeSettings();
              host.scan(Collections.singletonList(src.id));
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(
        smallCardAction("계정 수정", host.ink, () -> host.editSourceAuthDialog(src)),
        new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(
        smallCardAction("연결 해제", dangerColor(), () -> host.confirmRemoveSource(src)),
        new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(actions, -1, -2);

    return card;
  }

  private String modeDescription(int mode) {
    if (mode == Main.SOURCE_CACHED_ONLY) return "미리 받아 둔 보관본만 표시하고 서버에 연결하지 않습니다";
    if (mode == Main.SOURCE_HIDDEN) return "이 폴더의 책을 숨기고 서버에 연결하지 않습니다";
    return "모든 책을 표시하고 새로고침 시 서버에 연결합니다";
  }

  private TextView smallCardAction(String text, int color, Runnable onClick) {
    TextView tv = host.label(text, 13, color, true);
    tv.setGravity(Gravity.CENTER);
    tv.setPadding(host.dp(4), host.dp(10), host.dp(4), host.dp(6));
    tv.setMinHeight(host.dp(38));
    tv.setOnClickListener(v -> onClick.run());
    return tv;
  }

  /** 3) 읽기 설정 화면 (보기 방식 · 넘김 및 이동 · 터치 및 제스처 통합). */
  View buildReaderSettings(int scrollY) {
    Reader r = host.reader;
    return shell(
        "읽기 설정",
        host::closeSettings,
        null,
        content -> {
          if (r == null) return;

          // ── 1. 보기 방식 ──
          LinearLayout viewCard = section(content, "보기 방식");

          int mode = Math.max(0, Math.min(3, host.prefs.getInt("mode", 0)));
          addSegmentRow(
              viewCard,
              "페이지 구성",
              null,
              new String[] {"자동", "한 쪽", "양면", "세로"},
              mode,
              idx -> {
                host.prefs.edit().putInt("mode", idx).apply();
                r.optionsChanged();
              });

          boolean onlyThisSeries = host.prefs.contains(r.directionKey());
          final boolean[] curRtl = {r.rtl};
          final boolean[] curOnly = {onlyThisSeries};
          addSegmentRow(
              viewCard,
              "읽기 방향",
              curOnly[0] ? "이 작품의 모든 권에만 적용 중" : "전체 작품 기본 방향에 적용 중",
              new String[] {"← 오른쪽에서 왼쪽", "→ 왼쪽에서 오른쪽"},
              r.rtl ? 0 : 1,
              idx -> {
                curRtl[0] = (idx == 0);
                r.saveDirection(curRtl[0], curOnly[0]);
              });

          addSwitchRow(
              viewCard,
              "이 작품에만 방향 따로 저장",
              "끄면 전체 작품의 기본 읽기 방향을 따릅니다",
              onlyThisSeries,
              on -> {
                curOnly[0] = on;
                if (!on) curRtl[0] = host.prefs.getBoolean("rtl", true);
                r.saveDirection(curRtl[0], on);
                host.refreshSettingsView();
              });

          int fit = Math.max(0, Math.min(2, host.prefs.getInt("fit", 0)));
          addSegmentRow(
              viewCard,
              "이미지 맞춤",
              null,
              new String[] {"화면 맞춤", "너비 맞춤", "높이 맞춤"},
              fit,
              idx -> {
                host.prefs.edit().putInt("fit", idx).apply();
                r.optionsChanged();
              });

          int paper = Math.max(0, Math.min(2, host.prefs.getInt("paper", 0)));
          addSegmentRow(
              viewCard,
              "본문 배경색",
              null,
              new String[] {"먹색", "종이색", "흰색"},
              paper,
              idx -> {
                host.prefs.edit().putInt("paper", idx).apply();
                r.optionsChanged();
              });

          addSwitchRow(
              viewCard,
              "첫 장 단독 표시",
              "양면 보기에서 이 시리즈의 표지를 한 쪽으로 표시합니다",
              r.solo,
              on -> {
                host.prefs.edit().putBoolean(r.firstPageKey(), on).apply();
                r.optionsChanged();
              });

          // ── 2. 넘김 및 이동 ──
          LinearLayout navCard = section(content, "넘김 및 이동");

          int dragEff = Math.max(0, Math.min(2, host.prefs.getInt("effect", 0)));
          addSegmentRow(
              navCard,
              "드래그로 넘길 때 효과",
              null,
              new String[] {"종이 넘김", "슬라이드", "즉시 전환"},
              dragEff,
              idx -> {
                host.prefs.edit().putInt("effect", idx).apply();
                r.optionsChanged();
              });

          int tapEff = Math.max(0, Math.min(2, host.prefs.getInt("tapEffect", 2)));
          addSegmentRow(
              navCard,
              "탭으로 넘길 때 효과",
              null,
              new String[] {"종이 넘김", "슬라이드", "즉시 전환"},
              tapEff,
              idx -> {
                host.prefs.edit().putInt("tapEffect", idx).apply();
                r.optionsChanged();
              });

          addSwitchRow(
              navCard,
              "다음 권 자동 열기",
              "마지막 장을 넘길 때 확인 없이 바로 다음 권을 엽니다",
              host.prefs.getBoolean("autoNext", true),
              on -> host.prefs.edit().putBoolean("autoNext", on).apply());

          addActionRow(
              navCard,
              "다음 권 읽기",
              "현재 시리즈의 다음 권으로 바로 이동합니다",
              null,
              () -> {
                host.closeSettings();
                host.nextVolume();
              });

          // ── 3. 터치 및 제스처 ──
          LinearLayout gestureCard = section(content, "터치 및 제스처");

          addActionRow(
              gestureCard,
              "터치 범위 설정",
              "화면 왼쪽 · 가운데 · 오른쪽 탭 영역 비율을 조절합니다",
              host.tapZoneSummary(),
              () -> {
                host.closeSettings();
                host.openTouchZoneEditor();
              });

          addGestureSwitch(gestureCard, "좌우 탭으로 페이지 넘기기", null, "tapTurn", true, r);
          addGestureSwitch(
              gestureCard,
              "다음·이전 영역 길게 눌러 권 이동",
              "다음 페이지 쪽을 꾹 누르면 다음 권, 이전 쪽을 꾹 누르면 이전 권으로 이동",
              "longPressVolume",
              true,
              r);
          addGestureSwitch(gestureCard, "좌우 스와이프로 페이지 넘기기", null, "swipeTurn", true, r);
          addGestureSwitch(gestureCard, "가운데 더블 탭으로 확대·축소", null, "doubleZoom", false, r);
          addGestureSwitch(gestureCard, "두 손가락으로 확대·축소", null, "pinchZoom", true, r);
          addGestureSwitch(
              gestureCard,
              "왼쪽 가장자리 밝기 조절",
              "화면 왼쪽 끝을 위아래로 쓸어 밝기를 조절합니다",
              "brightnessGesture",
              false,
              r);
        },
        scrollY);
  }

  private void addGestureSwitch(
      LinearLayout card, String title, String subtitle, String key, boolean initial, Reader r) {
    addSwitchRow(
        card,
        title,
        subtitle,
        host.prefs.getBoolean(key, initial),
        on -> {
          host.prefs.edit().putBoolean(key, on).apply();
          r.readGestures();
        });
  }

  /** 4) 책 · 작품 길게 누르기 관리 화면 (설정 화면과 동일한 카드 섹션형 UI, 일반·와이드모드 대응). */
  View buildBookManageSettings(Library.Book b, boolean inSeriesDetail, int scrollY) {
    List<Library.Book> group = host.groups.getOrDefault(b.grp, Collections.singletonList(b));
    String seriesName = host.seriesDisplayName(b.grp, group);
    String screenTitle = inSeriesDetail ? host.volumeTitle(b) : seriesName;

    List<Library.Book> remoteGroupBooks = new ArrayList<>();
    List<Library.Book> uncachedSeriesFiles = new ArrayList<>();
    Set<String> seenUncachedOuter = new HashSet<>();
    LinkedHashSet<String> seriesCacheFiles = new LinkedHashSet<>();
    long seriesCachedBytes = 0;
    int seriesCompleteCount = 0, seriesPartialCount = 0, uncachedSeriesVolumes = 0;
    for (Library.Book item : group) {
      if (!WebDav.isUrl(item.url)) continue;
      remoteGroupBooks.add(item);
      boolean complete = RemoteBooks.has(host, item.url);
      boolean hasAny = RemoteBooks.hasAny(host, item.url);
      if (complete) seriesCompleteCount++;
      else {
        uncachedSeriesVolumes++;
        if (hasAny) seriesPartialCount++;
        if (seenUncachedOuter.add(RemoteBooks.outer(item.url))) uncachedSeriesFiles.add(item);
      }
      if (hasAny) {
        String fn = RemoteBooks.fileName(item.url);
        if (seriesCacheFiles.add(fn)) seriesCachedBytes += RemoteBooks.itemBytes(host, item.url);
      }
    }

    int singlePct = WebDav.isUrl(b.url) ? RemoteBooks.partialPercent(host, b.url) : -1;
    boolean singleComplete = singlePct == 100;
    boolean singlePartial = singlePct >= 0 && singlePct < 100;
    long singleCacheBytes = WebDav.isUrl(b.url) ? RemoteBooks.itemBytes(host, b.url) : 0;
    boolean singleQueued = WebDav.isUrl(b.url) && DownloadService.isQueuedOrDownloading(b.url);

    final long fSeriesCachedBytes = seriesCachedBytes;
    final int fSeriesCompleteCount = seriesCompleteCount;
    final int fSeriesPartialCount = seriesPartialCount;
    final int fUncachedSeriesVolumes = uncachedSeriesVolumes;

    return shell(
        inSeriesDetail ? "권 관리" : "작품 관리",
        host::closeSettings,
        null,
        content -> {
          // ── 상단 책/작품 요약 카드 ──
          LinearLayout summary = host.row();
          summary.setBackground(cardShape());
          summary.setPadding(host.dp(14), host.dp(14), host.dp(16), host.dp(14));
          LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
          slp.topMargin = host.dp(6);
          summary.setLayoutParams(slp);

          ImageView thumb = new ImageView(host);
          thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
          thumb.setBackground(pillShape(trackBg(), 6));
          thumb.setClipToOutline(true);
          host.loadCover(thumb, b);
          LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(host.dp(48), host.dp(68));
          tlp.rightMargin = host.dp(14);
          summary.addView(thumb, tlp);

          LinearLayout meta = host.col();
          TextView tTitle = host.label(screenTitle, 16, host.ink, true);
          tTitle.setMaxLines(2);
          tTitle.setEllipsize(TextUtils.TruncateAt.END);
          meta.addView(tTitle);

          StringBuilder metaLine = new StringBuilder();
          if (inSeriesDetail) {
            if (b.pages > 0) metaLine.append(b.pages).append("쪽");
            if (WebDav.isUrl(b.url)) {
              if (metaLine.length() > 0) metaLine.append("  ·  ");
              if (singleComplete) metaLine.append("휴대폰 보관됨");
              else if (singleQueued) metaLine.append("다운로드 대기열");
              else if (singlePartial) metaLine.append("미완료 ").append(singlePct).append("%");
              else metaLine.append(WebDav.isPikPak(b.url) ? "PikPak 원격" : "PC 원격");
            } else {
              if (metaLine.length() > 0) metaLine.append("  ·  ");
              metaLine.append("기기 저장소");
            }
          } else {
            metaLine.append("총 ").append(group.size()).append("권");
            if (!remoteGroupBooks.isEmpty()) {
              metaLine.append("  ·  보관함 ").append(fSeriesCompleteCount).append("/").append(remoteGroupBooks.size()).append("권");
            }
            if (!b.shelf.isEmpty()) metaLine.append("  ·  ").append(b.shelf);
          }
          TextView tSub = host.label(metaLine.toString(), 12, host.muted, false);
          tSub.setPadding(0, host.dp(5), 0, 0);
          meta.addView(tSub);
          host.weight(summary, meta);
          content.addView(summary);

          // ── 1. 원격 보관함 · 미리 받기 ──
          if (!remoteGroupBooks.isEmpty()) {
            LinearLayout cacheCard = section(content, "원격 보관함 · 미리 받기");

            if (inSeriesDetail && WebDav.isUrl(b.url)) {
              if (!singleComplete) {
                if (singleQueued) {
                  addActionRow(
                      cacheCard,
                      "다운로드 대기열에 등록됨",
                      "진행 중인 받기가 끝나면 순서대로 자동 저장됩니다",
                      "대기 중",
                      host.ink,
                      host.accent,
                      () -> host.toast("이미 다운로드 중이거나 대기열에 있습니다."));
                } else {
                  String dlTitle = singlePartial ? "이 권 이어서 미리 받기" : "이 권 미리 받기";
                  String dlBadge =
                      singlePartial
                          ? singlePct + "%"
                          : (b.size > 0 ? RemoteBooks.formatSize(b.size) : null);
                  addActionRow(
                      cacheCard,
                      dlTitle,
                      "다른 권을 받는 중이면 취소하지 않고 대기열에 이어서 추가합니다",
                      dlBadge,
                      () -> {
                        host.closeSettings();
                        host.prefetchRemoteBooks(Collections.singletonList(b));
                      });
                }
              }

              if (remoteGroupBooks.size() > 1) {
                addActionRow(
                    cacheCard,
                    "여러 권 선택해서 받기",
                    "원하는 권만 골라 백그라운드 대기열에 한 번에 추가합니다",
                    "총 " + remoteGroupBooks.size() + "권",
                    () -> host.openVolumeDownloadPicker(b, true));
              }

              if (uncachedSeriesFiles.size() > 1) {
                addActionRow(
                    cacheCard,
                    "이 시리즈 전체 미리 받기",
                    "미보관 " + fUncachedSeriesVolumes + "권 모두 백그라운드 대기열에 추가합니다",
                    fUncachedSeriesVolumes + "권",
                    () -> {
                      host.closeSettings();
                      host.prefetchRemoteBooks(uncachedSeriesFiles);
                    });
              }

              if (singleComplete || singlePartial) {
                String delTitle = singleComplete ? "원격 보관함에서 삭제" : "미완료 캐시 지우기";
                String delSub =
                    singleComplete
                        ? "휴대폰 저장본만 지우며 원본 파일은 유지됩니다"
                        : "받다가 중단된 임시 조각(" + singlePct + "%)만 지웁니다";
                addActionRow(
                    cacheCard,
                    delTitle,
                    delSub,
                    RemoteBooks.formatSize(singleCacheBytes),
                    dangerColor(),
                    dangerColor(),
                    () ->
                        host.confirmDeleteBookCache(
                            host.volumeTitle(b),
                            Collections.singletonList(RemoteBooks.fileName(b.url)),
                            singleCacheBytes,
                            !singleComplete));
              }
            } else {
              // 메인 책장(시리즈 꾹 누르기)
              if (!uncachedSeriesFiles.isEmpty()) {
                addActionRow(
                    cacheCard,
                    "전체 미리 받기",
                    "미보관 " + fUncachedSeriesVolumes + "권을 백그라운드 대기열에 모두 추가합니다",
                    fUncachedSeriesVolumes + "권",
                    () -> {
                      host.closeSettings();
                      host.prefetchRemoteBooks(uncachedSeriesFiles);
                    });
              } else {
                addActionRow(
                    cacheCard,
                    "모든 권이 보관함에 저장됨",
                    "오프라인에서도 전체 " + remoteGroupBooks.size() + "권을 바로 읽을 수 있습니다",
                    "완료",
                    host.ink,
                    host.remoteCachedColor(),
                    () -> {});
              }

              if (remoteGroupBooks.size() > 1) {
                addActionRow(
                    cacheCard,
                    "원하는 권 선택해서 받기",
                    "받을 권만 골라 백그라운드 대기열에 추가합니다",
                    "총 " + remoteGroupBooks.size() + "권",
                    () -> host.openVolumeDownloadPicker(b, false));
              }

              if (!seriesCacheFiles.isEmpty()) {
                String delTitle =
                    fSeriesCompleteCount > 0 ? "이 작품 보관함 비우기" : "이 작품 미완료 캐시 지우기";
                String delSub;
                if (fSeriesCompleteCount > 0 && fSeriesPartialCount > 0) {
                  delSub =
                      "완료 " + fSeriesCompleteCount + "권 · 미완료 " + fSeriesPartialCount + "권 (원본 유지)";
                } else if (fSeriesCompleteCount > 0) {
                  delSub = "완료 " + fSeriesCompleteCount + "권 · 휴대폰 저장본만 삭제합니다";
                } else {
                  delSub = "받다 만 미완료 파일 " + fSeriesPartialCount + "건만 정리합니다";
                }
                addActionRow(
                    cacheCard,
                    delTitle,
                    delSub,
                    RemoteBooks.formatSize(fSeriesCachedBytes),
                    dangerColor(),
                    dangerColor(),
                    () ->
                        host.confirmDeleteBookCache(
                            seriesName,
                            seriesCacheFiles,
                            fSeriesCachedBytes,
                            fSeriesCompleteCount == 0));
              }
            }
          }

          // ── 2. 읽기 상태 · 평가 ──
          LinearLayout stateCard = section(content, "읽기 상태 · 평가");

          int curRating = host.groupRating(b.grp);
          addSegmentRow(
              stateCard,
              "별점",
              null,
              new String[] {"없음", "★1", "★2", "★3", "★4", "★5"},
              Math.max(0, Math.min(5, curRating)),
              next -> {
                host.db.exec("UPDATE books SET rating=? WHERE grp=?", next, b.grp);
                for (Library.Book item : group) item.rating = next;
                host.rebuildEntry(b.grp);
                host.settingsDirty = true;
              });

          addSwitchRow(
              stateCard,
              "즐겨찾기",
              inSeriesDetail ? "이 권을 즐겨찾기에 표시합니다" : "이 작품을 즐겨찾기에 표시합니다",
              b.fav,
              on -> {
                b.fav = on;
                host.db.exec("UPDATE books SET fav=? WHERE id=?", on ? 1 : 0, b.id);
                host.rebuildEntry(b.grp);
                host.settingsDirty = true;
              });

          addSwitchRow(
              stateCard,
              "완독으로 표시",
              "끄면 읽던 페이지를 처음으로 초기화합니다",
              b.done,
              on -> {
                boolean wasDone = b.done;
                b.done = on;
                if (wasDone && !on) {
                  b.opened = 0;
                  b.page = 0;
                }
                host.db.exec(
                    "UPDATE books SET done=?,opened=CASE WHEN ? THEN 0 ELSE opened END,page=CASE"
                        + " WHEN ? THEN 0 ELSE page END WHERE id=?",
                    on ? 1 : 0,
                    wasDone && !on ? 1 : 0,
                    wasDone && !on ? 1 : 0,
                    b.id);
                host.rebuildEntry(b.grp);
                host.settingsDirty = true;
              });

          addActionRow(
              stateCard,
              "책장에 배치",
              "이 작품을 원하는 책장 분류에 넣습니다",
              b.shelf.isEmpty() ? "미지정" : b.shelf,
              () -> host.assignBookShelf(b));

          // ── 3. 표지 · 시리즈 정리 ──
          LinearLayout orgCard = section(content, "표지 · 시리즈 정리");

          addActionRow(
              orgCard,
              "표지 변경",
              "책 안의 다른 페이지를 표지로 고릅니다",
              null,
              () -> {
                host.closeSettings();
                host.chooseCover(b);
              });

          addActionRow(
              orgCard,
              "시리즈 대표 표지로 지정",
              "서재 목록에 이 권의 표지를 대표로 표시합니다",
              null,
              () -> {
                host.db.exec("UPDATE books SET rep=(id=?) WHERE grp=?", b.id, b.grp);
                for (Library.Book item : group) item.rep = (item.id == b.id);
                host.rebuildEntry(b.grp);
                host.settingsDirty = true;
                host.toast("시리즈 대표 표지로 지정했습니다.");
              });

          addActionRow(
              orgCard,
              "시리즈명 변경",
              "서재에 표시되는 작품 이름을 직접 수정합니다",
              null,
              () -> host.renameSeriesDialog(b));

          addActionRow(
              orgCard,
              "다른 시리즈에 합치기",
              "이 시리즈의 모든 권을 다른 작품으로 병합합니다",
              null,
              () -> host.mergeSeriesDialog(b));

          addActionRow(
              orgCard,
              "이 권을 별도 시리즈로 분리",
              "선택한 권만 따로 독립된 작품으로 나눕니다",
              null,
              () -> host.splitVolumeFromSeries(b));
        },
        scrollY);
  }

  /** 5) 여러 권 선택해서 미리 받기 화면 (빠른 선택 칩 + 권별 체크리스트 + 하단 고정 실행 버튼). */
  View buildVolumePickerSettings(Library.Book anchor, boolean fromDetail, int scrollY) {
    List<Library.Book> group = host.groups.getOrDefault(anchor.grp, Collections.singletonList(anchor));
    String seriesName = host.seriesDisplayName(anchor.grp, group);
    List<Library.Book> remoteVolumes = new ArrayList<>();
    for (Library.Book item : group) if (WebDav.isUrl(item.url)) remoteVolumes.add(item);

    int anchorIdx = -1;
    for (int i = 0; i < remoteVolumes.size(); i++) {
      if (remoteVolumes.get(i).id == anchor.id) {
        anchorIdx = i;
        break;
      }
    }
    final int fAnchorIdx = anchorIdx;

    // 초기 선택: 권 상세에서 들어왔고 해당 권이 미보관이면 그 권 1권만 기본 체크
    final Set<Long> selectedIds = new LinkedHashSet<>();
    if (fromDetail
        && fAnchorIdx >= 0
        && !RemoteBooks.has(host, anchor.url)
        && !DownloadService.isQueuedOrDownloading(anchor.url)) {
      selectedIds.add(anchor.id);
    }

    int screenW = host.getResources().getConfiguration().screenWidthDp;
    boolean wide = screenW >= 680;
    int colWidth = wide ? host.dp(640) : ViewGroup.LayoutParams.MATCH_PARENT;
    int hPad = host.dp(wide ? 24 : (screenW >= 600 ? 20 : 16));

    LinearLayout root = host.col();
    root.setBackgroundColor(host.bg);
    root.setClickable(true);
    root.setFocusable(true);

    // 상단 바
    LinearLayout bar = host.row();
    bar.setPadding(host.dp(8), host.dp(8), host.dp(14), host.dp(8));
    bar.setMinimumHeight(host.dp(56));
    ToolsIcon backBtn = new ToolsIcon(host, "back", host.ink, "뒤로");
    backBtn.setOnClickListener(v -> host.backFromVolumePicker());
    bar.addView(backBtn, host.dp(44), host.dp(44));

    LinearLayout titleCol = host.col();
    titleCol.setPadding(host.dp(4), 0, host.dp(8), 0);
    TextView titleView = host.label("받을 권 선택", 18, host.ink, true);
    host.one(titleView);
    titleCol.addView(titleView);
    TextView subSeries = host.label(seriesName, 12, host.muted, false);
    host.one(subSeries);
    titleCol.addView(subSeries);
    host.weight(bar, titleCol);

    TextView countBadge = host.label("", 12, host.accent, true);
    countBadge.setPadding(host.dp(10), host.dp(5), host.dp(10), host.dp(5));
    countBadge.setBackground(pillShape(trackBg(), 12));
    bar.addView(countBadge);
    root.addView(bar, -1, -2);

    View headerLine = new View(host);
    headerLine.setBackgroundColor(dividerColor());
    root.addView(headerLine, new LinearLayout.LayoutParams(-1, host.dp(1)));

    // 본문 스크롤
    ScrollView scroll = new ScrollView(host);
    scroll.setTag("settings_scroll");
    scroll.setClipToPadding(false);
    LinearLayout content = host.col();
    content.setTag("settings_content");
    content.setPadding(hPad, host.dp(12), hPad, host.dp(20));

    // 빠른 선택 칩
    HorizontalScrollView chipScroll = new HorizontalScrollView(host);
    chipScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout chipRow = host.row();
    chipScroll.addView(chipRow);
    LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-1, -2);
    chipLp.bottomMargin = host.dp(12);
    content.addView(chipScroll, chipLp);

    LinearLayout listCard = host.col();
    listCard.setBackground(cardShape());
    listCard.setClipToOutline(true);
    content.addView(listCard, -1, -2);

    FrameLayout.LayoutParams contentLp =
        new FrameLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
    if (wide) contentLp.gravity = Gravity.CENTER_HORIZONTAL;
    scroll.addView(content, contentLp);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    // 하단 고정 실행 바
    View bottomLine = new View(host);
    bottomLine.setBackgroundColor(dividerColor());
    root.addView(bottomLine, new LinearLayout.LayoutParams(-1, host.dp(1)));

    FrameLayout bottomWrap = new FrameLayout(host);
    bottomWrap.setBackgroundColor(cardBg());
    LinearLayout bottomInner = host.row();
    bottomInner.setPadding(hPad, host.dp(12), hPad, host.dp(12));
    TextView submitBtn = host.label("", 15, activePillText(), true);
    submitBtn.setGravity(Gravity.CENTER);
    submitBtn.setPadding(host.dp(16), host.dp(13), host.dp(16), host.dp(13));
    submitBtn.setMinHeight(host.dp(48));
    bottomInner.addView(submitBtn, new LinearLayout.LayoutParams(-1, -2));
    FrameLayout.LayoutParams bLp =
        new FrameLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
    if (wide) bLp.gravity = Gravity.CENTER_HORIZONTAL;
    bottomWrap.addView(bottomInner, bLp);
    root.addView(bottomWrap, -1, -2);

    final Map<Long, CheckBox> checkMap = new LinkedHashMap<>();

    Runnable updateSubmit =
        () -> {
          int n = selectedIds.size();
          long estBytes = 0;
          Set<String> seenOuter = new HashSet<>();
          for (Library.Book item : remoteVolumes) {
            if (selectedIds.contains(item.id) && seenOuter.add(RemoteBooks.outer(item.url))) {
              if (item.size > 0) estBytes += item.size;
            }
          }
          countBadge.setText(n + "권 선택");
          if (n == 0) {
            submitBtn.setText("받을 권을 선택하세요");
            submitBtn.setBackground(pillShape(trackBg(), 12));
            submitBtn.setTextColor(host.muted);
            submitBtn.setEnabled(false);
          } else {
            String sizeSuffix = estBytes > 0 ? "  ·  " + RemoteBooks.formatSize(estBytes) : "";
            submitBtn.setText("선택한 " + n + "권 미리 받기" + sizeSuffix);
            submitBtn.setBackground(pillShape(host.accent, 12));
            submitBtn.setTextColor(activePillText());
            submitBtn.setEnabled(true);
          }
        };

    java.util.function.Function<String, TextView> makeQuickChip =
        label -> {
          TextView c = host.label(label, 12, host.ink, true);
          c.setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8));
          c.setBackground(cardShape());
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
          lp.rightMargin = host.dp(8);
          c.setLayoutParams(lp);
          return c;
        };

    Runnable syncCheckboxes =
        () -> {
          for (Map.Entry<Long, CheckBox> e : checkMap.entrySet()) {
            CheckBox cb = e.getValue();
            if (cb.isEnabled()) cb.setChecked(selectedIds.contains(e.getKey()));
          }
          updateSubmit.run();
        };

    TextView allChip = makeQuickChip.apply("미보관 전체");
    allChip.setTextColor(host.accent);
    allChip.setOnClickListener(
        v -> {
          selectedIds.clear();
          for (Library.Book item : remoteVolumes) {
            if (!RemoteBooks.has(host, item.url)
                && !DownloadService.isQueuedOrDownloading(item.url)) {
              selectedIds.add(item.id);
            }
          }
          syncCheckboxes.run();
        });
    chipRow.addView(allChip);

    if (fromDetail && fAnchorIdx >= 0) {
      TextView fromHereChip = makeQuickChip.apply("이 권부터 끝까지");
      fromHereChip.setOnClickListener(
          v -> {
            selectedIds.clear();
            for (int i = fAnchorIdx; i < remoteVolumes.size(); i++) {
              Library.Book item = remoteVolumes.get(i);
              if (!RemoteBooks.has(host, item.url)
                  && !DownloadService.isQueuedOrDownloading(item.url)) {
                selectedIds.add(item.id);
              }
            }
            syncCheckboxes.run();
          });
      chipRow.addView(fromHereChip);

      TextView next5Chip = makeQuickChip.apply("이 권부터 5권");
      next5Chip.setOnClickListener(
          v -> {
            selectedIds.clear();
            int added = 0;
            for (int i = fAnchorIdx; i < remoteVolumes.size() && added < 5; i++) {
              Library.Book item = remoteVolumes.get(i);
              if (!RemoteBooks.has(host, item.url)
                  && !DownloadService.isQueuedOrDownloading(item.url)) {
                selectedIds.add(item.id);
                added++;
              }
            }
            syncCheckboxes.run();
          });
      chipRow.addView(next5Chip);
    }

    TextView clearChip = makeQuickChip.apply("선택 해제");
    clearChip.setTextColor(host.muted);
    clearChip.setOnClickListener(
        v -> {
          selectedIds.clear();
          syncCheckboxes.run();
        });
    chipRow.addView(clearChip);

    // 권별 행 렌더링
    for (int i = 0; i < remoteVolumes.size(); i++) {
      Library.Book item = remoteVolumes.get(i);
      addDividerIfNeeded(listCard);
      int pct = RemoteBooks.partialPercent(host, item.url);
      boolean cached = pct == 100;
      boolean queued = !cached && DownloadService.isQueuedOrDownloading(item.url);
      boolean selectable = !cached && !queued;

      LinearLayout row = host.row();
      row.setPadding(host.dp(12), host.dp(10), host.dp(14), host.dp(10));
      row.setMinimumHeight(host.dp(52));
      if (!selectable) row.setAlpha(0.55f);

      CheckBox cb = new CheckBox(host);
      cb.setEnabled(selectable);
      cb.setChecked(cached || selectedIds.contains(item.id));
      cb.setOnCheckedChangeListener(
          (btn, isChecked) -> {
            if (!selectable) return;
            if (isChecked) selectedIds.add(item.id);
            else selectedIds.remove(item.id);
            updateSubmit.run();
          });
      checkMap.put(item.id, cb);
      row.addView(cb, new LinearLayout.LayoutParams(-2, -2));

      LinearLayout textCol = host.col();
      textCol.setPadding(host.dp(6), 0, host.dp(8), 0);
      String vol = Order.volumeLabel(item.title, item.comicNumber);
      String rowTitle = !vol.isEmpty() ? vol : host.volumeTitle(item);
      TextView tvTitle = host.label(rowTitle, 14, host.ink, true);
      host.one(tvTitle);
      textCol.addView(tvTitle);

      String sub;
      if (cached) sub = item.size > 0 ? RemoteBooks.formatSize(item.size) + "  ·  휴대폰에 저장됨" : "휴대폰에 저장됨";
      else if (queued) sub = "다운로드 중 또는 대기열에 있음";
      else if (pct >= 0) sub = "미완료 " + pct + "% (이어서 받기)" + (item.size > 0 ? "  ·  " + RemoteBooks.formatSize(item.size) : "");
      else sub = item.size > 0 ? RemoteBooks.formatSize(item.size) : "미보관";
      TextView tvSub = host.label(sub, 12, host.muted, false);
      tvSub.setPadding(0, host.dp(2), 0, 0);
      host.one(tvSub);
      textCol.addView(tvSub);
      host.weight(row, textCol);

      String badgeText = cached ? "보관됨" : (queued ? "대기열" : (pct >= 0 ? pct + "%" : null));
      if (badgeText != null) {
        int bColor = cached ? host.remoteCachedColor() : (queued ? host.accent : dangerColor());
        TextView bdg = host.label(badgeText, 11, bColor, true);
        bdg.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
        bdg.setBackground(pillShape(trackBg(), 10));
        row.addView(bdg);
      }

      if (selectable) row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
      listCard.addView(row, -1, -2);
    }

    submitBtn.setOnClickListener(
        v -> {
          List<Library.Book> chosen = new ArrayList<>();
          Set<String> seenOuter = new HashSet<>();
          for (Library.Book item : remoteVolumes) {
            if (selectedIds.contains(item.id)
                && !RemoteBooks.has(host, item.url)
                && seenOuter.add(RemoteBooks.outer(item.url))) {
              chosen.add(item);
            }
          }
          if (chosen.isEmpty()) return;
          host.closeSettings();
          host.prefetchRemoteBooks(chosen);
        });

    updateSubmit.run();
    if (scrollY > 0) scroll.post(() -> scroll.scrollTo(0, scrollY));

    applySafeInsets(root, ViewCompat.getRootWindowInsets(host.frame));
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, windowInsets) -> {
          applySafeInsets(view, windowInsets);
          return windowInsets;
        });
    root.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          public void onViewAttachedToWindow(View v) {
            applySafeInsets(v, ViewCompat.getRootWindowInsets(v));
            ViewCompat.requestApplyInsets(v);
          }

          public void onViewDetachedFromWindow(View v) {}
        });
    return root;
  }
}
