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
    assertEquals("[개정판] 하늘", Order.series("[개정판] 하늘 1권.zip"));
    assertEquals("하늘 2099", Order.series("하늘 2099.zip"));
    assertTrue(Order.compareVolumes("하늘 Vol.2", "하늘 11권 [원본]") < 0);
    assertEquals("AKIRA", Order.series("AKIRA.zip"));
  }

  @Test
  public void hideOnlyFileTagsFollowingVolumeNumbers() {
    String name = "[개정판] 하늘 2권 (원본) [1500PX]";
    assertEquals("하늘 2권", Order.shelfTitle(name, true));
    assertEquals(name, Order.shelfTitle(name, false));
    assertEquals("하늘 02권", Order.shelfTitle("하늘_02권_원본_1500px", true));
    assertEquals("하늘 (원본)", Order.shelfTitle("하늘 (원본)", true));
    assertEquals("하늘 2권 [외전]", Order.shelfTitle("하늘 2권 [외전]", true));
    assertEquals(
        "그 비스크 돌은 사랑을 한다", Order.shelfTitle("[완결]【스캔】 그_비스크_돌은_사랑을_한다", true));
    assertEquals("[완결]", Order.shelfTitle("[완결]", true));
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
    assertEquals("1", Order.seriesKey("[1]"));
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
