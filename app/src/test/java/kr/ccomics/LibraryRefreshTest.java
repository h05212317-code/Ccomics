package kr.ccomics;

import static org.junit.Assert.*;

import android.view.*;
import android.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LibraryRefreshTest {
  @Test
  public void pullRequiresTopOfListAndSufficientTravel() {
    Main host = Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("refresh", 0);
    host.frame = new FrameLayout(host);
    host.setContentView(host.frame);
    host.renderLibrary();
    ViewGroup body = (ViewGroup) host.root.getChildAt(host.root.getChildCount() - 1);
    SwipeRefreshLayout pull = (SwipeRefreshLayout) body.getChildAt(0);
    int width = host.dp(360), height = host.dp(600);
    pull.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    pull.layout(0, 0, width, height);
    host.grid =
        new GridView(host) {
          public boolean canScrollVertically(int direction) {
            return direction < 0;
          }
        };
    pull.onInterceptTouchEvent(event(0, 5));
    assertFalse(pull.onInterceptTouchEvent(event(2, host.dp(150))));
    host.grid = null;
    pull.onInterceptTouchEvent(event(0, 5));
    assertFalse(pull.onInterceptTouchEvent(event(2, 6)));
    assertTrue(pull.onInterceptTouchEvent(event(2, host.dp(150))));
    pull.onTouchEvent(event(2, host.dp(220)));
    pull.onTouchEvent(event(1, host.dp(220)));
    assertTrue(pull.isRefreshing());
  }

  private static MotionEvent event(int action, float y) {
    return MotionEvent.obtain(0, 10, action, 20, y, 0);
  }

  @Test
  public void repeatedRefreshDoesNotQueueAnotherScan() {
    Main host = Robolectric.buildActivity(Main.class).get();
    host.scanning.add(7L);
    assertFalse(host.refreshSources(true));
    host.scan(java.util.Arrays.asList(7L, 7L));
    assertEquals(1, host.scanning.size());
  }
}
