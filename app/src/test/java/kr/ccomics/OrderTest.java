package kr.ccomics;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class OrderTest {
  @Test
  public void naturalSortIncludesNestedFoldersAndBigNumbers() {
    List<String> files =
        Arrays.asList("part10/1.jpg", "part2/10.jpg", "part2/2.jpg", "part2/1.jpg");
    files.sort(Order::natural);
    assertEquals(
        Arrays.asList("part2/1.jpg", "part2/2.jpg", "part2/10.jpg", "part10/1.jpg"), files);
    assertTrue(Order.natural("9999999999999999999", "10000000000000000000") < 0);
    List<String> pages =
        new ArrayList<>(
            Arrays.asList(
                "000001.jpg", "a.jpg", "000000.jpg", "_.jpg", "!표지.jpg", "(cover).jpg", "-0.jpg",
                "001.jpg", "표지.jpg"));
    pages.sort(Order::natural);
    assertEquals("!표지.jpg", pages.get(0));
    assertTrue(pages.indexOf("_.jpg") < pages.indexOf("000000.jpg"));
    assertTrue(pages.indexOf("(cover).jpg") < pages.indexOf("000000.jpg"));
    assertTrue(pages.indexOf("-0.jpg") < pages.indexOf("000000.jpg"));
    assertTrue(pages.indexOf("000001.jpg") < pages.indexOf("a.jpg"));
    assertEquals("표지.jpg", pages.get(pages.size() - 1));
    List<String> nested = new ArrayList<>(Arrays.asList("권/000000.jpg", "권/_.jpg", "권/000001.jpg"));
    nested.sort(Order::natural);
    assertEquals(Arrays.asList("권/_.jpg", "권/000000.jpg", "권/000001.jpg"), nested);
  }

  @Test
  public void coversAndMetadata() {
    assertTrue(Order.coverRank("book/COVER.PNG") < Order.coverRank("cover/01.jpg"));
    assertTrue(Order.coverRank("cover/01.jpg") < Order.coverRank("pages/1.jpg"));
    assertFalse(Order.image("__MACOSX/._01.jpg"));
    assertTrue(Order.image("만화/1.WeBP"));
    assertFalse(Order.image("목차.txt"));
  }

  @Test
  public void volumeNames() {
    assertEquals("하늘", Order.series("하늘 02권.zip"));
    assertEquals("Atlas", Order.series("Atlas 10.cbz"));
    assertEquals("하늘 길", Order.series("하늘_길_제３권_완결_원본_1500px.zip"));
    assertEquals("하늘 길", Order.series("하늘 길 2권 (AI번역)[1500PX].zip"));
    assertEquals("하늘", Order.series("[개정판] 하늘 1권.zip"));
    assertEquals("하늘 2099", Order.series("하늘 2099.zip"));
    assertTrue(Order.compareVolumes("하늘 Vol.2", "하늘 11권 [원본]") < 0);
    assertEquals("AKIRA", Order.series("AKIRA.zip"));
  }

  @Test
  public void mlsAndBomulseomFilenameRecognitionAndUnifiedVolumeTitles() {
    // Bomulseom 중간 노이즈 단어 + MLS 완결/확장 패턴 인식
    assertEquals("나루토", Order.series("나루토_원본_03.zip"));
    assertEquals(3, Order.volume("나루토_원본_03.zip").number);
    assertEquals("원피스", Order.series("원피스_공금_02.zip"));
    assertEquals(2, Order.volume("원피스_공금_02.zip").number);
    assertEquals("나루토", Order.series("나루토 [완결] 01권.zip"));
    assertEquals(1, Order.volume("나루토 [완결] 01권.zip").number);
    assertEquals("나루토", Order.series("나루토 01권완.zip"));
    assertEquals(1, Order.volume("나루토 01권완.zip").number);
    assertEquals("나루토", Order.series("[원본] 나루토 1권.zip"));
    assertEquals("나루토", Order.series("나루토01권.zip"));
    assertEquals("나루토", Order.series("나루토001권.zip"));
    assertEquals("나루토", Order.series("나루토_010권_고화질.cbz"));
    assertEquals("나루토", Order.series("나루토 100권 (AI번역)[1500px].zip"));
    assertEquals(100, Order.volume("나루토 100권 (AI번역)[1500px].zip").number);
    assertEquals("죠죠 2부", Order.series("죠죠 2부 01권.zip"));
    assertEquals(1, Order.volume("죠죠 2부 01권.zip").number);
    assertEquals("작품명", Order.series("작품명 상권.zip"));
    assertEquals("상", Order.volume("작품명 상권.zip").partLabel);
    assertTrue(Order.compareVolumes("작품명 상권.zip", "작품명 하권.zip") < 0);
    assertTrue(Order.compareVolumes("나루토 1권.zip", "나루토 1.5권.zip") < 0);
    assertTrue(Order.compareVolumes("나루토 1.5권.zip", "나루토 2권.zip") < 0);

    // 내부 책장 권 제목 통일: 나루토 1권, 나루토 2권, ..., 나루토 10권, ..., 나루토 100권
    String[] rawNaruto = {
      "[원본] 나루토 1권",
      "나루토01권",
      "나루토001권",
      "나루토_원본_001",
      "[완결] 나루토 Vol.01 [1500px]"
    };
    for (String r : rawNaruto) {
      assertEquals(r, "나루토 1권", Order.volumeDisplayTitle("나루토", r, "", true));
    }
    assertEquals("나루토 2권", Order.volumeDisplayTitle("나루토", "나루토_002권_스캔본", "", true));
    assertEquals("나루토 10권", Order.volumeDisplayTitle("나루토", "[원본] 나루토010권", "", true));
    assertEquals("나루토 100권", Order.volumeDisplayTitle("나루토", "나루토 100권 [고화질]", "", true));
    assertEquals("나루토 1.5권", Order.volumeDisplayTitle("나루토", "나루토 01.5권", "", true));
    assertEquals("나루토 2권 [외전]", Order.volumeDisplayTitle("나루토", "나루토 02권 [외전]", "", true));
    assertEquals("나루토 상권", Order.volumeDisplayTitle("나루토", "나루토 상", "", true));
    assertEquals("나루토 7권", Order.volumeDisplayTitle("나루토", "007", "07", true));
    assertEquals("[원본] 나루토 1권", Order.volumeDisplayTitle("나루토", "[원본] 나루토 1권", "", false));

    // MLS 스마트 상위 폴더 인식 (스캔본/고화질/Download 등 무의미 폴더 건너뛰기)
    assertEquals("나루토", Order.resolveFolderSeries("smb://192.168.0.2/Comics/나루토/스캔본/"));
    assertEquals("나루토", Order.resolveFolderSeries("primary:Download/[완결] 나루토 1~72권/고화질"));
    assertEquals("원피스", Order.resolveFolderSeries("primary:만화/원피스/1500px/원본"));
  }

  @Test
  public void largeLibraryParsingAndSortingStaysFast() {
    List<String> titles = new ArrayList<>(2000);
    for (int s = 1; s <= 200; s++) {
      for (int v = 1; v <= 10; v++) {
        titles.add("[원본] 만화작품" + s + "_스캔본_" + String.format("%03d", v) + "권 [1500px].zip");
      }
    }
    long start = System.nanoTime();
    for (String t : titles) {
      Order.Volume vol = Order.volume(t);
      assertTrue(vol.number >= 1 && vol.number <= 10);
      Order.cleanSeriesTitle(vol.title);
    }
    // 2회차 캐시 히트 + natural 정렬 포함 전체 처리가 충분히 빨라야 한다.
    titles.sort(Order::natural);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    assertTrue("2000개 파싱·정렬이 너무 느립니다: " + elapsedMs + "ms", elapsedMs < 1500);
  }

  @Test
  public void hideOnlyFileTagsFollowingVolumeNumbers() {
    String name = "[개정판] 하늘 2권 (원본) [1500PX]";
    assertEquals("하늘 2권", Order.shelfTitle(name, true));
    assertEquals(name, Order.shelfTitle(name, false));
    assertEquals("하늘 02권", Order.shelfTitle("하늘_02권_원본_1500px", true));
    assertEquals("하늘", Order.shelfTitle("하늘 (원본)", true));
    assertEquals("하늘 2권 [외전]", Order.shelfTitle("하늘 2권 [외전]", true));
    assertEquals("하늘 2권 [외전]", Order.shelfTitle("하늘 2권 [외전] (아무 문구)", true));
    assertEquals(
        "그 비스크 돌은 사랑을 한다", Order.shelfTitle("[완결]【스캔】 그_비스크_돌은_사랑을_한다", true));
    assertEquals("[완결]", Order.shelfTitle("[완결]", true));
    for (String s :
        new String[] {
          "블리치 (문구)", "블리치 [아무 말]", "블리치 {문구}", "블리치【문구】", "(문구) 블리치",
          "{문구} 블리치 (또 문구) [1500px]", "블리치_(문구)"
        }) assertEquals(s, "블리치", Order.shelfTitle(s, true));
    assertEquals("블리치 3권", Order.shelfTitle("블리치 3권 (문구)", true));
    assertEquals("블리치 3권", Order.shelfTitle("(문구) 블리치_3권_원본 [고화질]", true));
  }

  @Test
  public void anyBracketTextBeforeOrAfterTitleIsIgnored() {
    String key = Order.seriesKey("블리치");
    for (String f :
        new String[] {
          "블리치.zip", "블리치 (문구).zip", "블리치 [문구].cbz", "블리치 {문구}.zip", "블리치【문구】.zip",
          "(문구) 블리치.zip", "[문구] 블리치 (문구2).zip", "블리치 3권 (문구).zip", "블리치 (문구) 4권.zip",
          "(문구) 블리치_5권.zip", "블리치 6권 [원본] (문구) {또}.zip", "블리치_7권_완결_(문구).zip",
          "블리치 Vol.8 (문구).zip", "블리치 [9권] (문구).zip", "블리치 10 (문구).zip"
        }) assertEquals(f, key, Order.seriesKey(Order.series(f)));
    assertEquals(3, Order.volume("블리치 3권 (문구).zip").number);
    assertEquals(4, Order.volume("블리치 (문구) 4권.zip").number);
    assertEquals(9, Order.volume("블리치 [9권] (문구).zip").number);
    assertEquals(10, Order.volume("블리치 10 (문구).zip").number);
    assertEquals(2, Order.volume("하늘 2권 [외전].zip").number);
    assertEquals(-1, Order.volume("블리치 (문구).zip").number);
    for (String f :
        new String[] {
          "블리치1~74권.zip", "블리치 1~74권.zip", "블리치 1-74.zip", "블리치_01~74권_완결.zip",
          "블리치 1권~74권.zip", "블리치 (1-74권).zip", "[완결] 블리치 1~74권 (문구).zip", "블리치 전74권.zip",
          "블리치 1~686화.zip"
        }) {
      assertEquals(f, key, Order.seriesKey(Order.series(f)));
      assertEquals(f, "블리치", Order.shelfTitle(Order.series(f), true));
      assertEquals(f, -1, Order.volume(f).number);
    }
    assertEquals(key, Order.seriesKey("블리치1~74권"));
    // 특정 제목이 아니라 모든 제목에 같은 규칙이 적용되는지 확인
    String[][] works = {
      {"원피스", "[완결] 원피스_1~105권 (스캔).zip", "원피스 3권 [원본].zip", "(고화질) 원피스_004권.cbz"},
      {"진격의 거인", "진격의_거인_전34권.zip", "{문구} 진격의 거인 12권 (AI번역).zip", "진격의거인 Vol.5.zip"},
      {"NARUTO", "NARUTO 1-72 [scan].zip", "naruto 01 (문구).cbz", "【문구】Naruto_02권.zip"},
      {"그 비스크 돌은 사랑을 한다", "그_비스크_돌은_사랑을_한다_1~13권.zip", "[문구] 그 비스크 돌은 사랑을 한다 (문구).zip"}
    };
    for (String[] w : works)
      for (int i = 1; i < w.length; i++) {
        assertEquals(w[i], Order.seriesKey(w[0]), Order.seriesKey(Order.series(w[i])));
        assertEquals(w[i], w[0].toLowerCase(), Order.shelfTitle(Order.series(w[i]), true).toLowerCase().replace("진격의거인", "진격의 거인"));
      }
    assertEquals(3, Order.volume("원피스 3권 [원본].zip").number);
    assertEquals(12, Order.volume("{문구} 진격의 거인 12권 (AI번역).zip").number);
    assertEquals(1, Order.volume("naruto 01 (문구).cbz").number);
    assertTrue(Order.genericKey(Order.seriesKey("외전")));
    assertFalse(Order.genericKey(key));
  }

  @Test
  public void seriesKeyIgnoresLeadingTagsSpacingAndPunctuation() {
    String key = Order.seriesKey(Order.series("그 비스크 돌은 사랑을 한다 1권.zip"));
    assertEquals(key, Order.seriesKey(Order.series("그_비스크_돌은_사랑을_한다_02권.zip")));
    assertEquals(key, Order.seriesKey(Order.series("[완결] 그 비스크 돌은 사랑을 한다 3권 [원본].zip")));
    assertEquals(key, Order.seriesKey(Order.series("[스캔][고화질] 그 비스크 돌은, 사랑을 한다! 4권.cbz")));
    assertEquals(key, Order.seriesKey("그비스크돌은사랑을한다"));
    assertNotEquals(key, Order.seriesKey("그 비스크 돌은 사랑을 한다 외전"));
    assertEquals(Order.seriesKey("Re:Zero"), Order.seriesKey("re zero"));
    assertEquals(Order.seriesKey("86 -에이티식스-"), Order.seriesKey("86 —에이티식스—"));
    assertEquals("1", Order.seriesKey("[1]"));
  }

  @Test
  public void hashtagsAndEmbeddedTrailingTextAfterVolumeGroupCleanly() {
    String key = Order.seriesKey("체인소 맨");
    String[] chainsawFiles = {
      "체인소 맨 1권.zip",
      "체인소 맨 15권 #정발.zip",
      "체인소 맨 15권 #정발본.cbz",
      "체인소 맨 15권 #정발판.zip",
      "체인소 맨 16권 #정식.zip",
      "체인소 맨 16권 #정식발매.zip",
      "체인소 맨 17권 #정발_고화질.zip",
      "체인소 맨 17권 #정발 #고화질.zip",
      "체인소 맨 18권#정발판.zip",
      "체인소 맨 18 #정발판.zip",
      "체인소 맨 19권 #정발 (1500px).cbz",
      "체인소 맨 19권 - 악마의 심장.zip",
      "체인소 맨 15권 부제목.zip",
      "체인소 맨 16권 리디북스.zip",
      "체인소 맨 17권 2024-05-01.zip",
      "체인소 맨 18권 20240501.zip",
      "체인소 맨 19권 @후지모토.zip",
      "체인소 맨 15권 [] 후지모토 타츠키.zip",
      "체인소 맨 15 [] Tatsuki Fujimoto.zip",
      "체인소 맨 15 - Tatsuki Fujimoto.zip",
      "체인소 맨 #15권.zip",
      "체인소 맨 #16권 #정발.zip",
      "체인소 맨 17권완 #정발.zip",
      "체인소 맨 18완.zip",
      "체인소 맨 Vol.19 #정발판.zip",
      "체인소 맨 [15권] #정발판.zip",
      "체인소 맨 [16권] - 부제.zip",
      "#정발 체인소 맨 17권.zip",
      "체인소 맨 #정발 18권.zip",
      "체인소 맨19권 #정발판.zip",
      "체인소 맨 15.5권 #정발.zip",
      "체인소 맨 1~14권 #정발판.zip",
      "체인소 맨 1~14권 리디스캔.zip",
      "체인소 맨 전19권 #정발.zip",
      "체인소 맨 상권 #정발.zip",
      "체인소 맨 하권 - 부제.zip",
      "체인소 맨.15권.#정발.zip",
      "체인소 맨.15.#정발.zip",
      "체인소 맨.15권 - 부제.zip",
      "체인소 맨 15권 # 정발판.zip",
      "체인소 맨 15권 #정발(고화질).zip",
      "체인소 맨 15권 #정발본#고화질.zip",
      "체인소 맨 15권 ＃정발.zip"
    };
    for (String f : chainsawFiles) {
      assertEquals(f, key, Order.seriesKey(Order.series(f)));
      assertEquals(f, "체인소 맨", Order.series(f));
    }

    assertEquals(15, Order.volume("체인소 맨 15권 #정발판.zip").number);
    assertEquals(16, Order.volume("체인소 맨 16권 #정식.zip").number);
    assertEquals(17, Order.volume("체인소 맨 17권 #정발_고화질.zip").number);
    assertEquals(18, Order.volume("체인소 맨 18권#정발판.zip").number);
    assertEquals(18, Order.volume("체인소 맨 18 #정발판.zip").number);
    assertEquals(19, Order.volume("체인소 맨 19권 - 악마의 심장.zip").number);
    assertEquals(15, Order.volume("체인소 맨 15권 부제목.zip").number);
    assertEquals(15, Order.volume("체인소 맨 #15권.zip").number);
    assertEquals(17, Order.volume("체인소 맨 17권완 #정발.zip").number);
    assertEquals(18, Order.volume("체인소 맨 18완.zip").number);
    assertEquals("15.5", Order.volume("체인소 맨 15.5권 #정발.zip").numberText);
    assertEquals("상", Order.volume("체인소 맨 상권 #정발.zip").partLabel);
    assertEquals("하", Order.volume("체인소 맨 하권 - 부제.zip").partLabel);
    assertEquals(-1, Order.volume("체인소 맨 1~14권 #정발판.zip").number);
    assertEquals("1~14권", Order.volume("체인소 맨 1~14권 리디스캔.zip").rangeLabel);
    assertEquals("전19권", Order.volume("체인소 맨 전19권 #정발.zip").rangeLabel);

    assertEquals(
        "체인소 맨 15권",
        Order.volumeDisplayTitle("체인소 맨", "체인소 맨 15권 #정발판", "", true));
    assertEquals(
        "체인소 맨 16권",
        Order.volumeDisplayTitle("체인소 맨", "체인소 맨 16권 - 악마의 심장", "", true));
    assertEquals(
        "체인소 맨 15권 [외전]",
        Order.volumeDisplayTitle("체인소 맨", "체인소 맨 15권 외전", "", true));
    assertEquals(
        "체인소 맨 15권 [외전]",
        Order.volumeDisplayTitle("체인소 맨", "체인소 맨 15권 [외전] #정발", "", true));
    assertEquals("체인소 맨 15권", Order.shelfTitle("체인소 맨 15권 #정발판", true));

    // 오탐 방지: #으로 시작하는 작품명, 연도, 회차, 부, 일반 단어 포함 제목 보존
    assertEquals("#DRCL", Order.series("#DRCL 1권.zip"));
    assertEquals("#DRCL", Order.series("#DRCL 1권 #정발.zip"));
    assertEquals(1, Order.volume("#DRCL 1권 #정발.zip").number);
    assertEquals("하늘 2099", Order.series("하늘 2099.zip"));
    assertEquals("하늘 2099", Order.series("하늘 2099 1권 #정발.zip"));
    assertEquals(1, Order.volume("하늘 2099 1권 #정발.zip").number);
    assertEquals("1회차 인생", Order.series("1회차 인생 3권 #정발.zip"));
    assertEquals(3, Order.volume("1회차 인생 3권 #정발.zip").number);
    assertEquals("1권부터 시작하는 이세계", Order.series("1권부터 시작하는 이세계.zip"));
    assertEquals(-1, Order.volume("1권부터 시작하는 이세계.zip").number);
    assertEquals("죠죠 2부", Order.series("죠죠 2부 01권 #정발.zip"));
    assertEquals(1, Order.volume("죠죠 2부 01권 #정발.zip").number);
    assertEquals("수정 공주", Order.series("수정 공주 1권.zip"));
    assertEquals("Raw Hero", Order.series("Raw Hero 1권.zip"));

    // ComicInfo Series 정리: 명시적 권수·해시태그는 제거하되 끝 단독 숫자는 유지
    assertEquals("체인소 맨", Order.cleanComicSeries("체인소 맨 15권 #정발판"));
    assertEquals("체인소 맨", Order.cleanComicSeries("[완결] 체인소 맨 1~19권"));
    assertEquals("Mob Psycho 100", Order.cleanComicSeries("Mob Psycho 100"));
    assertEquals("하늘 2099", Order.cleanComicSeries("하늘 2099"));

    // 폴더명 인식: 권수 폴더(15권, 01, Vol.15)를 건너뛰고 상위 작품 폴더를 찾는다.
    assertEquals("체인소 맨", Order.resolveFolderSeries("smb://192.168.0.2/Comics/체인소 맨/15권/"));
    assertEquals("체인소 맨", Order.resolveFolderSeries("primary:Comics/체인소 맨 1~19권 #정발/Vol.15"));
    assertEquals("", Order.volume("15권 #정발.zip").title);
    assertEquals(15, Order.volume("15권 #정발.zip").number);
  }

  @Test
  public void everyPageOccursOnce() {
    for (int count = 1; count < 45; count++)
      for (boolean split : new boolean[] {false, true})
        for (boolean cover : new boolean[] {false, true}) {
          int page = 0;
          while (page < count) {
            int[] pair = Order.spread(page, count, split, cover);
            assertEquals(page, pair[0]);
            for (int p : pair) {
              assertEquals(page++, p);
              assertArrayEquals(pair, Order.spread(p, count, split, cover));
            }
            if (page < count) {
              int[] next = Order.spread(page, count, split, cover);
              assertArrayEquals(pair, Order.spread(next[0] - 1, count, split, cover));
            }
          }
          assertEquals(count, page);
        }
  }
}
