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
