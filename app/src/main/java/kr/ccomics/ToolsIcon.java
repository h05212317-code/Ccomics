package kr.ccomics;

import android.content.Context;
import android.graphics.*;
import android.view.View;

final class ToolsIcon extends View {
  final String symbol;
  final int ink;
  final Paint p = new Paint(1);

  ToolsIcon(Context c, String symbol, int color, String label) {
    super(c);
    this.symbol = symbol;
    ink = color;
    setContentDescription(label);
    setFocusable(true);
  }

  protected void onDraw(Canvas c) {
    float s = getResources().getDisplayMetrics().density;
    c.save();
    c.translate(getWidth() / 2f - 12 * s, getHeight() / 2f - 12 * s);
    c.scale(s, s);
    p.setColor(ink);
    p.setStrokeWidth(1.7f);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    switch (symbol) {
      case "search":
        c.drawCircle(10.5f, 10.5f, 6.3f, p);
        line(c, 15, 15, 20, 20);
        break;
      case "add":
        line(c, 12, 4, 12, 20);
        line(c, 4, 12, 20, 12);
        break;
      case "sort":
        line(c, 4, 7, 20, 7);
        line(c, 4, 12, 16, 12);
        line(c, 4, 17, 12, 17);
        break;
      case "sort_desc":
        line(c, 3, 7, 14, 7);
        line(c, 3, 12, 11, 12);
        line(c, 3, 17, 8, 17);
        line(c, 17, 6, 17, 18);
        line(c, 14, 15, 17, 18);
        line(c, 20, 15, 17, 18);
        break;
      case "sort_asc":
        line(c, 3, 7, 14, 7);
        line(c, 3, 12, 11, 12);
        line(c, 3, 17, 8, 17);
        line(c, 17, 18, 17, 6);
        line(c, 14, 9, 17, 6);
        line(c, 20, 9, 17, 6);
        break;
      case "back":
        line(c, 5, 12, 19, 12);
        line(c, 5, 12, 11, 6);
        line(c, 5, 12, 11, 18);
        break;
      case "more":
        p.setStyle(Paint.Style.FILL);
        for (int x = 5; x <= 19; x += 7) c.drawCircle(x, 12, 1.65f, p);
        break;
      case "book":
        line(c, 3, 5, 10, 7);
        line(c, 10, 7, 12, 9);
        line(c, 12, 9, 14, 7);
        line(c, 14, 7, 21, 5);
        line(c, 3, 5, 3, 18);
        line(c, 3, 18, 10, 20);
        line(c, 10, 20, 12, 22);
        line(c, 12, 22, 14, 20);
        line(c, 14, 20, 21, 18);
        line(c, 21, 18, 21, 5);
        line(c, 12, 9, 12, 22);
        break;
    }
    c.restore();
  }

  void line(Canvas c, int a, int b, int d, int e) {
    c.drawLine(a, b, d, e, p);
  }
}
