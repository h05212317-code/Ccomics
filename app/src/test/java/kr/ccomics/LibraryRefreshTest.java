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

  @Test
  public void failedListKeepsBooksAndSuccessfulEmptyListHidesThem() throws Exception {
    Main host = Robolectric.buildActivity(Main.class).get();
    String url = "content://comics.test/document/1";
    final int[] state = {0};
    android.content.ContentProvider provider =
        new android.content.ContentProvider() {
          public boolean onCreate() {
            return true;
          }

          public android.database.Cursor query(
              android.net.Uri u, String[] fields, String s, String[] args, String order) {
            android.database.MatrixCursor result = new android.database.MatrixCursor(fields);
            if (state[0] == 0) {
              android.os.Bundle extras = new android.os.Bundle();
              extras.putString(android.provider.DocumentsContract.EXTRA_ERROR, "offline");
              result.setExtras(extras);
            } else if (state[0] == 2)
              result.addRow(new Object[] {"1", "하늘 01권.zip", "application/zip", 10, 20});
            return result;
          }

          public String getType(android.net.Uri u) {
            return "application/zip";
          }

          public android.net.Uri insert(android.net.Uri u, android.content.ContentValues values) {
            throw new UnsupportedOperationException();
          }

          public int delete(android.net.Uri u, String s, String[] args) {
            throw new UnsupportedOperationException();
          }

          public int update(
              android.net.Uri u, android.content.ContentValues values, String s, String[] args) {
            throw new UnsupportedOperationException();
          }
        };
    android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
    info.authority = "comics.test";
    provider.attachInfo(host, info);
    org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
        info.authority, provider);
    try (Library lib = new Library(host)) {
      lib.exec("INSERT INTO sources(id,url) VALUES(1,?)", url);
      lib.record(1, url, "하늘 01권.zip", "single", 10, 20, 1, new java.util.ArrayList<>());
      long id = lib.books().get(0).id;
      lib.exec(
          "UPDATE books SET page=4,pages=8,fav=1,manualGroup=-1,grp=?,series=? WHERE id=?",
          "single\n하늘",
          "하늘",
          id);
      assertTrue(lib.coverFile(id).createNewFile());
      assertThrows(java.io.IOException.class, () -> lib.scan(1, () -> {}));
      assertEquals(1, lib.books().size());
      state[0] = 1;
      lib.scan(1, () -> {});
      assertTrue(lib.books().isEmpty());
      state[0] = 2;
      lib.scan(1, () -> {});
      Library.Book restored = lib.books().get(0);
      assertEquals(id, restored.id);
      assertEquals(4, restored.page);
      assertTrue(restored.fav);
      lib.record(1, url + "b", "하늘_2권_[원본].zip", "another", 10, 20, 5, new java.util.ArrayList<>());
      for (Library.Book b : lib.books()) assertEquals(restored.grp, b.grp);
      lib.exec("UPDATE books SET manualGroup=1,series='내 분류' WHERE id=?", id);
      lib.scan(1, () -> {});
      assertEquals("내 분류", lib.get(id).series);
    }
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
