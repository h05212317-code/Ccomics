package kr.ccomics;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/**
 * 리더 위 전체 화면에 왼쪽·가운데·오른쪽 터치 영역과 경계선을 한눈에 표시하고,
 * 경계선 핸들(또는 가운데 영역)을 좌우로 드래그해 터치 범위를 직접 조절하는 오버레이.
 */
final class TouchZoneOverlay extends FrameLayout {
  interface Listener {
    void onSave(float left, float right);

    void onCancel();
  }

  static final int DRAG_NONE = 0;
  static final int DRAG_LEFT = 1;
  static final int DRAG_RIGHT = 2;
  static final int DRAG_CENTER = 3;

  final ZoneCanvas canvas;
  final TextView readout;
  final LinearLayout controls;
  final Listener listener;
  final boolean rtl, doubleZoom;
  float left, right;
  int dragTarget = DRAG_NONE;
  float dragOffsetPx;
  float centerDragStartX, centerStartLeft, centerStartRight;

  TouchZoneOverlay(
      Context ctx,
      float initialLeft,
      float initialRight,
      boolean rtl,
      boolean doubleZoom,
      Listener listener) {
    super(ctx);
    this.rtl = rtl;
    this.doubleZoom = doubleZoom;
    this.listener = listener;
    float[] init = Reader.clampTapZones(initialLeft, initialRight);
    this.left = init[0];
    this.right = init[1];

    setClickable(true);
    setFocusable(true);

    canvas = new ZoneCanvas(ctx);
    addView(canvas, new LayoutParams(-1, -1));

    controls = new LinearLayout(ctx);
    controls.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable cardBg = new GradientDrawable();
    cardBg.setColor(0xEB181B1F);
    cardBg.setCornerRadius(dp(18));
    cardBg.setStroke(dp(1), 0x33FFFFFF);
    controls.setBackground(cardBg);
    controls.setPadding(dp(18), dp(14), dp(18), dp(14));
    controls.setElevation(dp(10));
    controls.setClickable(true);

    TextView title = new TextView(ctx);
    title.setText("터치 범위 설정");
    title.setTextColor(0xFFFFFFFF);
    title.setTextSize(15);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    controls.addView(title);

    TextView hint = new TextView(ctx);
    hint.setText("세로 경계선을 좌우로 드래그해 터치 영역을 조절하세요");
    hint.setTextColor(0xFFB8C0CC);
    hint.setTextSize(12);
    hint.setPadding(0, dp(3), 0, dp(8));
    controls.addView(hint);

    readout = new TextView(ctx);
    readout.setTextColor(0xFFE8EEF5);
    readout.setTextSize(13);
    readout.setTypeface(Typeface.DEFAULT_BOLD);
    readout.setPadding(0, 0, 0, dp(12));
    controls.addView(readout);

    LinearLayout buttons = new LinearLayout(ctx);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    buttons.setGravity(Gravity.CENTER_VERTICAL);
    controls.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

    TextView resetBtn = makeButton("초기화", 0x26FFFFFF, 0xFFE2E8F0);
    resetBtn.setOnClickListener(
        v -> {
          setZones(0.33f, 0.67f);
          performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        });
    TextView cancelBtn = makeButton("취소", 0x26FFFFFF, 0xFFE2E8F0);
    cancelBtn.setOnClickListener(
        v -> {
          if (listener != null) listener.onCancel();
        });
    TextView saveBtn = makeButton("저장", 0xFF2F6FEB, 0xFFFFFFFF);
    saveBtn.setOnClickListener(
        v -> {
          if (listener != null) listener.onSave(left, right);
        });

    LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
    b1.rightMargin = dp(8);
    buttons.addView(resetBtn, b1);
    LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
    b2.rightMargin = dp(8);
    buttons.addView(cancelBtn, b2);
    LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0, dp(42), 1.2f);
    buttons.addView(saveBtn, b3);

    LayoutParams barLp = new LayoutParams(-1, -2, Gravity.BOTTOM);
    barLp.setMargins(dp(16), dp(16), dp(16), dp(18));
    addView(controls, barLp);

    setOnApplyWindowInsetsListener(
        (v, insets) -> {
          Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
          LayoutParams lp = (LayoutParams) controls.getLayoutParams();
          lp.bottomMargin = dp(18) + nav.bottom;
          controls.setLayoutParams(lp);
          canvas.invalidate();
          return insets;
        });

    updateReadout();
  }

  private TextView makeButton(String text, int bgColor, int fgColor) {
    TextView btn = new TextView(getContext());
    btn.setText(text);
    btn.setTextColor(fgColor);
    btn.setTextSize(14);
    btn.setTypeface(Typeface.DEFAULT_BOLD);
    btn.setGravity(Gravity.CENTER);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(bgColor);
    bg.setCornerRadius(dp(12));
    btn.setBackground(bg);
    return btn;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  void setZones(float newLeft, float newRight) {
    float[] clamped = Reader.clampTapZones(newLeft, newRight);
    left = clamped[0];
    right = clamped[1];
    updateReadout();
    canvas.invalidate();
  }

  int[] percentages() {
    int l = Math.round(left * 100f);
    int r = Math.round((1f - right) * 100f);
    int c = Math.max(0, 100 - l - r);
    return new int[] {l, c, r};
  }

  private void updateReadout() {
    int[] p = percentages();
    String leftRole = rtl ? "다음" : "이전";
    String rightRole = rtl ? "이전" : "다음";
    readout.setText(
        "왼쪽(" + leftRole + ") " + p[0] + "%  ·  가운데(메뉴) " + p[1] + "%  ·  오른쪽(" + rightRole + ") " + p[2] + "%");
  }

  final class ZoneCanvas extends View {
    final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF pillRect = new RectF();

    ZoneCanvas(Context ctx) {
      super(ctx);
      textPaint.setColor(0xFFFFFFFF);
      textPaint.setTypeface(Typeface.DEFAULT_BOLD);
      textPaint.setTextAlign(Paint.Align.CENTER);
      subPaint.setTextAlign(Paint.Align.CENTER);
      subPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    protected void onDraw(Canvas c) {
      int w = getWidth(), h = getHeight();
      if (w <= 0 || h <= 0) return;
      float xL = w * left;
      float xR = w * right;
      int[] pct = percentages();

      // 1. 세 영역 반투명 색상 채우기 (아래 만화 페이지가 비쳐 보여 한눈에 범위 파악)
      fillPaint.setColor(0x5A2563EB); // 왼쪽: 블루
      c.drawRect(0, 0, xL, h, fillPaint);

      fillPaint.setColor(0x4A334155); // 가운데: 슬레이트
      c.drawRect(xL, 0, xR, h, fillPaint);

      fillPaint.setColor(0x5A10B981); // 오른쪽: 에메랄드
      c.drawRect(xR, 0, w, h, fillPaint);

      // 하단 컨트롤 바 위쪽의 가시 영역 중앙에 라벨과 핸들을 배치
      float usableBottom = controls.getHeight() > 0 ? controls.getTop() - dp(12) : h * 0.78f;
      float centerY = Math.max(dp(110), usableBottom * 0.48f);

      // 2. 영역 라벨 (어두운 반투명 배지 위에 선명하게 표시)
      String leftAction = rtl ? "다음 페이지" : "이전 페이지";
      String leftShort = rtl ? "다음" : "이전";
      String rightAction = rtl ? "이전 페이지" : "다음 페이지";
      String rightShort = rtl ? "이전" : "다음";
      String centerAction = doubleZoom ? "메뉴·확대" : "메뉴 열기";

      drawZoneBadge(c, 0, xL, centerY, leftAction, leftShort, pct[0] + "%", 0xFF93C5FD);
      drawZoneBadge(c, xL, xR, centerY, centerAction, "메뉴", pct[1] + "%", 0xFFE2E8F0);
      drawZoneBadge(c, xR, w, centerY, rightAction, rightShort, pct[2] + "%", 0xFF6EE7B7);

      // 3. 세로 경계선 + 드래그 핸들
      drawBoundary(c, xL, h, centerY, dragTarget == DRAG_LEFT || dragTarget == DRAG_CENTER, pct[0] + "%");
      drawBoundary(c, xR, h, centerY, dragTarget == DRAG_RIGHT || dragTarget == DRAG_CENTER, pct[2] + "%");
    }

    private void drawZoneBadge(
        Canvas c,
        float startX,
        float endX,
        float centerY,
        String fullTitle,
        String shortTitle,
        String pctText,
        int accentColor) {
      float zoneW = endX - startX;
      if (zoneW < dp(24)) return;
      float cx = (startX + endX) * 0.5f;
      float d = getResources().getDisplayMetrics().density;

      boolean compact = zoneW < dp(96);
      boolean tiny = zoneW < dp(54);
      String main = tiny ? "" : (compact ? shortTitle : fullTitle);

      textPaint.setTextSize((compact ? 13f : 15f) * d);
      subPaint.setTextSize((tiny ? 11f : 13f) * d);
      subPaint.setColor(accentColor);

      float mainW = main.isEmpty() ? 0f : textPaint.measureText(main);
      float subW = subPaint.measureText(pctText);
      float padH = dp(tiny ? 5 : 12);
      float padV = dp(tiny ? 6 : 9);
      float pillW = Math.min(zoneW - dp(8), Math.max(mainW, subW) + padH * 2);
      float lineGap = main.isEmpty() ? 0f : dp(4);
      float mainH = main.isEmpty() ? 0f : (textPaint.descent() - textPaint.ascent());
      float subH = subPaint.descent() - subPaint.ascent();
      float pillH = padV * 2 + mainH + lineGap + subH;

      pillRect.set(cx - pillW * 0.5f, centerY - pillH * 0.5f, cx + pillW * 0.5f, centerY + pillH * 0.5f);
      fillPaint.setColor(0xBD11161C);
      c.drawRoundRect(pillRect, dp(12), dp(12), fillPaint);

      float y = pillRect.top + padV;
      if (!main.isEmpty()) {
        c.drawText(main, cx, y - textPaint.ascent(), textPaint);
        y += mainH + lineGap;
      }
      c.drawText(pctText, cx, y - subPaint.ascent(), subPaint);
    }

    private void drawBoundary(
        Canvas c, float x, int h, float handleY, boolean active, String bubbleText) {
      float d = getResources().getDisplayMetrics().density;
      shadowPaint.setColor(0x99000000);
      shadowPaint.setStrokeWidth((active ? 6f : 5f) * d);
      c.drawLine(x, 0, x, h, shadowPaint);

      linePaint.setColor(active ? 0xFFFFD54F : 0xF2FFFFFF);
      linePaint.setStrokeWidth((active ? 3.5f : 2.5f) * d);
      c.drawLine(x, 0, x, h, linePaint);

      // 드래그 핸들 알약 (중앙에서 살짝 아래에 배치해 라벨과 겹치지 않게 함)
      float hy = handleY + dp(62);
      float hw = dp(active ? 16 : 14);
      float hh = dp(active ? 28 : 24);
      pillRect.set(x - hw, hy - hh, x + hw, hy + hh);

      handlePaint.setStyle(Paint.Style.FILL);
      handlePaint.setColor(active ? 0xFFFFD54F : 0xFFFFFFFF);
      c.drawRoundRect(pillRect, hw, hw, handlePaint);

      handlePaint.setStyle(Paint.Style.STROKE);
      handlePaint.setStrokeWidth(1.5f * d);
      handlePaint.setColor(0xAA0F172A);
      c.drawRoundRect(pillRect, hw, hw, handlePaint);

      // 핸들 내부 좌우 화살표 아이콘 (◀ ▶)
      textPaint.setTextSize(11f * d);
      textPaint.setColor(0xFF0F172A);
      float arrowY = hy - (textPaint.ascent() + textPaint.descent()) * 0.5f;
      c.drawText("◀▶", x, arrowY, textPaint);
      textPaint.setColor(0xFFFFFFFF);

      // 드래그 중일 때 손가락 위쪽에 현재 비율 말풍선 표시
      if (active) {
        subPaint.setTextSize(12f * d);
        subPaint.setColor(0xFF0F172A);
        float bw = subPaint.measureText(bubbleText) + dp(14);
        float bh = dp(24);
        float bx = Math.max(bw * 0.5f + dp(6), Math.min(getWidth() - bw * 0.5f - dp(6), x));
        float by = hy - hh - dp(18);
        pillRect.set(bx - bw * 0.5f, by - bh * 0.5f, bx + bw * 0.5f, by + bh * 0.5f);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFD54F);
        c.drawRoundRect(pillRect, dp(12), dp(12), handlePaint);
        float ty = by - (subPaint.ascent() + subPaint.descent()) * 0.5f;
        c.drawText(bubbleText, bx, ty, subPaint);
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      int w = getWidth();
      if (w <= 0) return false;
      float x = e.getX();
      int action = e.getActionMasked();
      switch (action) {
        case MotionEvent.ACTION_DOWN:
          float xL = w * left;
          float xR = w * right;
          float distL = Math.abs(x - xL);
          float distR = Math.abs(x - xR);
          float hitSlop = dp(34);
          if (distL <= hitSlop || distR <= hitSlop) {
            if (distL <= distR) {
              dragTarget = DRAG_LEFT;
              dragOffsetPx = x - xL;
            } else {
              dragTarget = DRAG_RIGHT;
              dragOffsetPx = x - xR;
            }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
          } else if (x > xL && x < xR) {
            dragTarget = DRAG_CENTER;
            centerDragStartX = x;
            centerStartLeft = left;
            centerStartRight = right;
          } else {
            dragTarget = DRAG_NONE;
          }
          invalidate();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (dragTarget == DRAG_LEFT) {
            float candidate = (x - dragOffsetPx) / w;
            float clamped =
                Math.max(Reader.TAP_MIN_EDGE, Math.min(right - Reader.TAP_MIN_CENTER, candidate));
            if (clamped != left) {
              left = clamped;
              updateReadout();
              invalidate();
            }
          } else if (dragTarget == DRAG_RIGHT) {
            float candidate = (x - dragOffsetPx) / w;
            float clamped =
                Math.max(left + Reader.TAP_MIN_CENTER, Math.min(1f - Reader.TAP_MIN_EDGE, candidate));
            if (clamped != right) {
              right = clamped;
              updateReadout();
              invalidate();
            }
          } else if (dragTarget == DRAG_CENTER) {
            float delta = (x - centerDragStartX) / w;
            float minDelta = Reader.TAP_MIN_EDGE - centerStartLeft;
            float maxDelta = (1f - Reader.TAP_MIN_EDGE) - centerStartRight;
            float d = Math.max(minDelta, Math.min(maxDelta, delta));
            float nextL = centerStartLeft + d;
            float nextR = centerStartRight + d;
            if (nextL != left || nextR != right) {
              left = nextL;
              right = nextR;
              updateReadout();
              invalidate();
            }
          }
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          dragTarget = DRAG_NONE;
          invalidate();
          return true;
      }
      return super.onTouchEvent(e);
    }
  }
}
