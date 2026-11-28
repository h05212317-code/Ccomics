package kr.ccomics;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SettingsUiTest {
  private static void collectTexts(View v, List<String> out) {
    if (v instanceof TextView) {
      CharSequence t = ((TextView) v).getText();
      if (t != null && t.length() > 0) out.add(t.toString());
    }
    if (v instanceof ViewGroup) {
      ViewGroup vg = (ViewGroup) v;
      for (int i = 0; i < vg.getChildCount(); i++) collectTexts(vg.getChildAt(i), out);
    }
  }

  private static TextView findText(View v, String exact) {
    if (v instanceof TextView && exact.equals(String.valueOf(((TextView) v).getText()))) {
      return (TextView) v;
    }
    if (v instanceof ViewGroup) {
      ViewGroup vg = (ViewGroup) v;
      for (int i = 0; i < vg.getChildCount(); i++) {
        TextView found = findText(vg.getChildAt(i), exact);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static Switch findSwitch(View v, String desc) {
    if (v instanceof Switch && desc.equals(String.valueOf(v.getContentDescription()))) {
      return (Switch) v;
    }
    if (v instanceof ViewGroup) {
      ViewGroup vg = (ViewGroup) v;
      for (int i = 0; i < vg.getChildCount(); i++) {
        Switch found = findSwitch(vg.getChildAt(i), desc);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  public void librarySettingsGroupsControlsAndKeepsOverlayOpenOnToggle() throws Exception {
    Main host = Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("settings-ui-test", 0);
    host.prefs.edit().clear().apply();
    host.frame = new FrameLayout(host);
    host.setContentView(host.frame);
    host.theme();
    try (Library lib = new Library(host)) {
      host.db = lib;
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,'https://pc.example/ccomics/','u1','')");
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(2,'https://dav.pikpak.ai/%EB%A7%8C%ED%99%94/','u2','')");

      host.settings();
      assertEquals(Main.SETTINGS_LIBRARY, host.settingsPage);
      assertNotNull(host.settingsOverlay);

      List<String> texts = new ArrayList<>();
      collectTexts(host.settingsOverlay, texts);
      assertTrue(texts.contains("폴더 및 서재"));
      assertTrue(texts.contains("화면 표시"));
      assertTrue(texts.contains("저장 공간"));
      assertTrue(texts.contains("앱 정보"));
      assertTrue(texts.contains("2/2 켜짐"));

      // 스위치 조작 시 설정 화면이 닫히지 않고 즉시 설정이 반영된다.
      Switch ratingSw = findSwitch(host.settingsOverlay, "별점순으로 묶어 표시");
      assertNotNull(ratingSw);
      boolean before = host.groupByRating();
      ratingSw.performClick();
      assertEquals(!before, host.groupByRating());
      assertEquals(Main.SETTINGS_LIBRARY, host.settingsPage);
      assertTrue(host.settingsDirty);

      // 테마 세그먼트 클릭 시 어둡게로 전환되면서 설정 화면이 유지된다.
      TextView darkBtn = findText(host.settingsOverlay, "어둡게");
      assertNotNull(darkBtn);
      darkBtn.performClick();
      assertEquals(2, host.prefs.getInt("theme", 0));
      assertTrue(host.dark);
      assertEquals(Main.SETTINGS_LIBRARY, host.settingsPage);
      assertNotNull(host.settingsOverlay);

      // 원격 폴더 관리 진입 → 1탭으로 보관본만 전환 → 뒤로가기 시 서재 설정 복귀 → 다시 뒤로가기 시 닫힘
      host.openRemoteSourcesSettings();
      assertEquals(Main.SETTINGS_REMOTE, host.settingsPage);
      List<String> remoteTexts = new ArrayList<>();
      collectTexts(host.settingsOverlay, remoteTexts);
      assertTrue(remoteTexts.contains("PikPak"));
      assertTrue(remoteTexts.contains("PC 원격"));

      TextView cachedBtn = findText(host.settingsOverlay, "보관본만");
      assertNotNull(cachedBtn);
      cachedBtn.performClick();
      assertEquals(Main.SOURCE_CACHED_ONLY, host.sourceMode(1, "https://pc.example/ccomics/"));

      host.onBackPressed();
      assertEquals(Main.SETTINGS_LIBRARY, host.settingsPage);
      host.onBackPressed();
      assertEquals(Main.SETTINGS_NONE, host.settingsPage);
      assertNull(host.settingsOverlay);
    }
  }

  @Test
  public void settingsOverlayAppliesSystemBarInsetsWhenAttached() throws Exception {
    Main host = Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("settings-insets-test", 0);
    host.frame = new FrameLayout(host);
    host.setContentView(host.frame);
    host.theme();
    try (Library lib = new Library(host)) {
      host.db = lib;
      host.settings();
      assertNotNull(host.settingsOverlay);
      androidx.core.view.WindowInsetsCompat insets =
          new androidx.core.view.WindowInsetsCompat.Builder()
              .setInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                  androidx.core.graphics.Insets.of(0, 72, 0, 96))
              .build();
      androidx.core.view.ViewCompat.dispatchApplyWindowInsets(host.settingsOverlay, insets);
      assertEquals(72, host.settingsOverlay.getPaddingTop());
      assertEquals(96, host.settingsOverlay.getPaddingBottom());

      // 일반모드(폭 < 680dp)에서는 전체 폭, 와이드모드(폭 >= 680dp)에서는 중앙 정렬 최대 폭으로 배치된다.
      host.getResources().getConfiguration().screenWidthDp = 390;
      host.refreshSettingsView();
      View narrowContent = host.settingsOverlay.findViewWithTag("settings_content");
      assertNotNull(narrowContent);
      assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, narrowContent.getLayoutParams().width);

      host.getResources().getConfiguration().screenWidthDp = 960;
      host.refreshSettingsView();
      View wideContent = host.settingsOverlay.findViewWithTag("settings_content");
      assertNotNull(wideContent);
      assertEquals(host.dp(640), wideContent.getLayoutParams().width);
    }
  }

  @Test
  public void bookManageAndVolumePickerDistinguishPartialCacheAndSelectAll() throws Exception {
    Main host = Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("book-manage-ui-test", 0);
    host.prefs.edit().clear().apply();
    host.frame = new FrameLayout(host);
    host.setContentView(host.frame);
    host.theme();
    RemoteBooks.clear(host);
    try (Library lib = new Library(host)) {
      host.db = lib;
      String u1 = "https://dav.pikpak.ai/conan/1.zip";
      String u2 = "https://dav.pikpak.ai/conan/2.zip";
      String u3 = "https://dav.pikpak.ai/conan/3.zip";
      lib.exec("INSERT INTO sources(id,url,user,secret) VALUES(1,'https://dav.pikpak.ai/conan/','u','')");
      lib.exec(
          "INSERT INTO books(source,url,title,series,grp,size,changed,seen,pages) VALUES"
              + "(1,?,'명탐정 코난 1권','명탐정 코난','series:conan',100000000,1,1,180),"
              + "(1,?,'명탐정 코난 2권','명탐정 코난','series:conan',100000000,1,1,180),"
              + "(1,?,'명탐정 코난 3권','명탐정 코난','series:conan',100000000,1,1,180)",
          u1,
          u2,
          u3);
      List<Library.Book> books = lib.books();
      host.books = books;
      host.groups.put("series:conan", books);

      // 1권은 완료 저장, 2권은 미완료(.part만 존재), 3권은 미보관
      assertTrue(RemoteBooks.file(host, u1).createNewFile());
      assertTrue(new java.io.File(RemoteBooks.file(host, u2).getPath() + ".part").createNewFile());
      assertEquals(100, RemoteBooks.partialPercent(host, u1));
      assertEquals(0, RemoteBooks.partialPercent(host, u2));
      assertEquals(-1, RemoteBooks.partialPercent(host, u3));

      // 2권 상세에서 꾹 누르기 -> '미완료 캐시 지우기'로 구분 표시되어야 함
      host.selected = "series:conan";
      host.manage(books.get(1));
      assertEquals(Main.SETTINGS_BOOK_MANAGE, host.settingsPage);
      List<String> texts = new ArrayList<>();
      collectTexts(host.settingsOverlay, texts);
      assertTrue(texts.contains("원격 보관함 · 미리 받기"));
      assertTrue(texts.contains("미완료 캐시 지우기"));
      assertTrue(texts.contains("여러 권 선택해서 받기"));
      assertTrue(texts.contains("이 시리즈 전체 미리 받기"));

      // 여러 권 선택 화면 진입 -> '미보관 전체' 누르면 2권·3권(2권) 선택됨
      host.openVolumeDownloadPicker(books.get(1), true);
      assertEquals(Main.SETTINGS_VOLUME_PICKER, host.settingsPage);
      TextView allChip = findText(host.settingsOverlay, "미보관 전체");
      assertNotNull(allChip);
      allChip.performClick();
      assertNotNull(findText(host.settingsOverlay, "2권 선택"));

      // 뒤로가기 2번: 권 선택 -> 권 관리 -> 닫힘
      host.onBackPressed();
      assertEquals(Main.SETTINGS_BOOK_MANAGE, host.settingsPage);
      host.onBackPressed();
      assertEquals(Main.SETTINGS_NONE, host.settingsPage);
    } finally {
      RemoteBooks.clear(host);
    }
  }

  @Test
  public void tempCacheRowShowsSizeAndConfirmsBeforeDeleting() throws Exception {
    Main host = Robolectric.buildActivity(Main.class).get();
    host.prefs = host.getSharedPreferences("temp-cache-ui-test", 0);
    host.frame = new FrameLayout(host);
    host.setContentView(host.frame);
    host.theme();
    WebDav.Cache.clear(host);
    java.io.File dummy = new java.io.File(WebDav.Cache.dir(host), "sample.part");
    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dummy, "rw")) {
      raf.setLength(2L << 20); // 2.0MB
    }
    try (Library lib = new Library(host)) {
      host.db = lib;
      host.settings();
      assertEquals(2L << 20, SettingsUi.tempCacheBytes(host));
      assertNotNull(findText(host.settingsOverlay, "2.0MB"));

      TextView tempRowTitle = findText(host.settingsOverlay, "임시 파일 정리");
      assertNotNull(tempRowTitle);
      ((View) tempRowTitle.getParent().getParent()).performClick();

      android.app.AlertDialog dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(dlg);
      assertTrue(dlg.isShowing());
      String msg = org.robolectric.Shadows.shadowOf(dlg).getMessage().toString();
      assertTrue(msg.contains("2.0MB"));
      assertTrue(msg.contains("삭제하시겠습니까"));
      assertEquals("확인", dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText());
      assertEquals("취소", dlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).getText());
      dlg.dismiss();

      // 표지 캐시 삭제도 용량 배지 및 확인/취소 다이얼로그를 표시한다.
      java.io.File coverDummy = new java.io.File(lib.covers, "999.jpg");
      try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(coverDummy, "rw")) {
        raf.setLength(1536L << 10); // 1.5MB
      }
      host.refreshSettingsView();
      assertEquals(1536L << 10, SettingsUi.coverCacheBytes(host));
      assertNotNull(findText(host.settingsOverlay, "1.5MB"));

      TextView coverRowTitle = findText(host.settingsOverlay, "표지 캐시 삭제");
      assertNotNull(coverRowTitle);
      ((View) coverRowTitle.getParent().getParent()).performClick();

      android.app.AlertDialog coverDlg =
          org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(coverDlg);
      assertTrue(coverDlg.isShowing());
      String coverMsg = org.robolectric.Shadows.shadowOf(coverDlg).getMessage().toString();
      assertTrue(coverMsg.contains("1.5MB"));
      assertTrue(coverMsg.contains("삭제하시겠습니까"));
      assertEquals(
          "확인", coverDlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText());
      assertEquals(
          "취소", coverDlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).getText());
      coverDlg.dismiss();
      coverDummy.delete();
    } finally {
      WebDav.Cache.clear(host);
    }
  }
}
