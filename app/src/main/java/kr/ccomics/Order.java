package kr.ccomics;

import java.util.*;
import java.util.regex.*;

final class Order {
  static final Pattern VOLUME =
      Pattern.compile(
          "(?i)^(.+?)[\\s_\\-\\[.(]*(?:vol\\.?\\s*|제\\s*)?(\\d{1,4})(?:\\s*[권巻]|\\s*vol)?[\\s\\]).]*(?:\\s*\\(완결?\\))?$");

  static String stem(String s) {
    return s.replaceFirst("(?i)\\.(zip|cbz)$", "");
  }

  static String legacySeries(String filename) {
    String title = stem(filename);
    Matcher match = VOLUME.matcher(title);
    return match.matches() ? match.group(1).trim() : title;
  }

  private static final String TAG =
      "(?:원본|정발(?:본)?|(?:AI\\s*)?번역(?:본)?|완결|완|스캔본|전자책|\\d{3,5}\\s*px)";
  private static final Pattern TAGS =
      Pattern.compile(
          "(?iu)(?:\\s*(?:\\[\\s*" + TAG + "\\s*\\]|\\(\\s*" + TAG + "\\s*\\))|\\s+" + TAG + ")+$");
  private static final Pattern NUMBER =
      Pattern.compile(
          "(?iu)(?:[\\s.\\-]*(?:제|第)?\\s*(\\d{1,4})\\s*[권巻]|[\\s.\\-]*vol(?:ume)?\\.?\\s*(\\d{1,4})|(?:^|[\\s.\\-]+)(\\d{1,3})|\\s*[\\[(](?:제|第)?\\s*(\\d{1,4})\\s*[권巻]?[\\])])$");

  static class Volume {
    final String title;
    final int number;

    Volume(String title, int number) {
      this.title = title;
      this.number = number;
    }
  }

  static Volume volume(String filename) {
    String name =
        java.text.Normalizer.normalize(stem(filename), java.text.Normalizer.Form.NFKC)
            .replace('_', ' ')
            .trim()
            .replaceAll("\\s+", " ");
    String clean = TAGS.matcher(name).replaceFirst("").trim();
    Matcher m = NUMBER.matcher(clean);
    if (m.find()) {
      for (int i = 1; i <= m.groupCount(); i++)
        if (m.group(i) != null)
          return new Volume(clean.substring(0, m.start()).trim(), Integer.parseInt(m.group(i)));
    }
    return new Volume(name, -1);
  }

  private static final Pattern LEADING_TAGS =
      Pattern.compile("^(?:\\s*[\\[【][^\\]】]*[\\]】])+\\s*");
  private static final Pattern KEY_SEPARATORS =
      Pattern.compile("[\\s_:;\\-~〜()\\[\\]{}【】.,!?'\"+·♡♥❤☆★\\ufe0f]+");

  static String withoutLeadingTags(String title) {
    String rest = LEADING_TAGS.matcher(title).replaceFirst("").trim();
    return rest.isEmpty() ? title.trim() : rest;
  }

  /** 앞쪽 [문구], 띄어쓰기·밑줄·문장부호 차이를 무시하는 시리즈 비교 키. */
  static String seriesKey(String series) {
    String name =
        java.text.Normalizer.normalize(series, java.text.Normalizer.Form.NFKC)
            .replace('_', ' ')
            .toLowerCase(Locale.ROOT);
    String key = KEY_SEPARATORS.matcher(withoutLeadingTags(name)).replaceAll("");
    return key.isEmpty() ? name.replace(" ", "") : key;
  }

  static String shelfTitle(String title, boolean hideTags) {
    if (!hideTags) return title;
    String name = withoutLeadingTags(title.replace('_', ' ').trim().replaceAll("\\s+", " "));
    Matcher tags = TAGS.matcher(name);
    if (tags.find()) {
      String shortTitle = name.substring(0, tags.start()).trim();
      if (volume(shortTitle).number >= 0) return shortTitle;
    }
    return name;
  }

  static String series(String filename) {
    Volume v = volume(filename);
    return v.title.isEmpty() ? stem(filename) : v.title;
  }

  static int compareVolumes(String a, String b) {
    int x = volume(a).number, y = volume(b).number;
    int n = Integer.compare(x < 0 ? Integer.MAX_VALUE : x, y < 0 ? Integer.MAX_VALUE : y);
    return n == 0 ? natural(a, b) : n;
  }

  static int natural(String a, String b) {
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      char x = Character.toLowerCase(a.charAt(i)), y = Character.toLowerCase(b.charAt(j));
      if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
        int e = i, f = j;
        while (e < a.length() && a.charAt(e) >= '0' && a.charAt(e) <= '9') e++;
        while (f < b.length() && b.charAt(f) >= '0' && b.charAt(f) <= '9') f++;
        String p = a.substring(i, e).replaceFirst("^0+(?!$)", ""),
            q = b.substring(j, f).replaceFirst("^0+(?!$)", "");
        int d = Integer.compare(p.length(), q.length());
        if (d == 0) d = p.compareTo(q);
        if (d != 0) return d;
        i = e;
        j = f;
      } else {
        if (x != y) return x - y;
        i++;
        j++;
      }
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  static int coverRank(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    String name = p.substring(p.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "");
    if (name.matches("cover|front|frontcover|표지|表紙")) return 0;
    if (name.matches(".*(?:back|rear|뒤표지|뒷표지|裏表紙).*")) return 4;
    if (name.matches(".*(?:cover|front|표지|表紙).*") || p.matches(".*(?:^|/)(?:covers?|표지)/.*"))
      return 1;
    return 3;
  }

  static boolean image(String path) {
    String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
    for (String part : p.split("/"))
      if (part.startsWith(".") || part.equals("__macosx")) return false;
    return p.matches("(?s).*\\.(jpe?g|png|webp)$");
  }

  static int[] spreadWithWidePages(
      int page, int count, boolean dual, boolean coverSolo, BitSet wide) {
    if (!dual || wide.isEmpty()) return spread(page, count, dual, coverSolo);
    if (wide.get(page)) return new int[] {page};
    int start = Math.max(coverSolo ? 1 : 0, wide.previousSetBit(page) + 1);
    if (page < start) return new int[] {page};
    int first = start + (page - start) / 2 * 2;
    return first + 1 < count && !wide.get(first + 1)
        ? new int[] {first, first + 1}
        : new int[] {first};
  }

  static int[] spread(int page, int count, boolean dual, boolean coverSolo) {
    page = Math.max(0, Math.min(page, count - 1));
    if (!dual || (coverSolo && page == 0)) return new int[] {page};
    int offset = coverSolo ? 1 : 0;
    int first = offset + (page - offset) / 2 * 2;
    return first + 1 < count ? new int[] {first, first + 1} : new int[] {first};
  }
}
