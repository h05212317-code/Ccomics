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
}
