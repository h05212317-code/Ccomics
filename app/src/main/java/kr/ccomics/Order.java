package kr.ccomics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

final class Order {
  static final Pattern VOLUME =
      Pattern.compile(
          "(?i)^(.+?)[\\s_\\-\\[.(]*(?:vol\\.?\\s*|제\\s*)?(\\d{1,4})(?:\\s*[권巻]|\\s*vol)?[\\s\\]).]*(?:\\s*\\(완결?\\))?$");

  static String stem(String s) {
    return s == null ? "" : s.replaceFirst("(?i)\\.(zip|cbz|rar|cbr|7z|pdf)$", "");
  }

  static String legacySeries(String filename) {
    String title = stem(filename);
    Matcher match = VOLUME.matcher(title);
    return match.matches() ? match.group(1).trim() : title;
  }

  /** Bomulseom + MangaLibraryScanner 공통 노이즈 태그 (단독 토큰/괄호로 등장할 때 제거). */
  private static final String NOISE_TOKEN =
      "(?:원본|공금|스캔\\s*단면|스캔\\s*양면|스캔본|스캔판|스캔|정발본|정발|정식발매|"
          + "(?:AI\\s*)?번역본|(?:AI\\s*)?번역|자체번역|역식|식자|한글|한국어|"
          + "무삭제|무수정|화질보정|업스케일|고화질판|저화질판|고화질|저화질|고해상도|저해상도|"
          + "워터마크\\s*없음|워터마크|no\\s*watermark|전자책|e-?book|e북|단행본|연재본|연재중|연재|"
          + "미완결|완결|미완|완|完|단권|무료|확인불가|합본|통합본|전권|"
          + "개정판|완전판|신장판|애장판|소장판|일반판|특별판|한정판|특장판|복구판|"
          + "19금|15금|19\\+|15\\+|"
          + "scans?|raw|digital|hq|mq|lq|fhd|qhd|4k|fixed|edited|upscale|kor|korean|fin|end|v[12]|"
          + "\\d{3,5}\\s*px|(?:720|1080|1440|2160)p)";

  private static final Pattern TAGS =
      Pattern.compile(
          "(?iu)(?:\\s*(?:\\[\\s*"
              + NOISE_TOKEN
              + "\\s*\\]|\\(\\s*"
              + NOISE_TOKEN
              + "\\s*\\)|\\{\\s*"
              + NOISE_TOKEN
              + "\\s*\\}|【\\s*"
              + NOISE_TOKEN
              + "\\s*】)|\\s+"
              + NOISE_TOKEN
              + ")+$");

  private static final Pattern INLINE_NOISE =
      Pattern.compile(
          "(?iu)(?<![가-힣A-Za-z0-9])(?:" + NOISE_TOKEN + ")(?![가-힣A-Za-z0-9])");

  private static final Pattern LEADING_WEBTOON =
      Pattern.compile("(?iu)^\\s*(?:\\[\\s*웹툰\\s*\\]|웹툰\\s+)");

  private static final Pattern AUTHOR_CREDIT_TAIL =
      Pattern.compile(
          "(?iu)(?:\\s+[가-힣A-Za-z]{2,12}\\s+(?:원작|그림|지음|글|작화|번역))+$");

  /** 1권완, 01권 완결, 10화완 같은 권수 직후 완결 접미사 분리. */
  private static final Pattern VOLUME_COMPLETE_GLUED =
      Pattern.compile(
          "(?iu)(\\d{1,4}(?:\\.\\d+)?)\\s*(권|화|편|회(?!차)|巻)\\s*(?:완결|완|完|fin|end)(?=$|[\\s.\\[\\(\\{【])");

  /** 1~74권, 01-74, 1권~74권, 1~120화, Vol.1-10, 전 74권 같은 범위·전권 표기. */
  static final Pattern RANGE =
      Pattern.compile(
          "(?iu)(?:[\\s._\\-]*(?:(?:vol(?:ume)?|ch(?:apter)?|ep(?:isode)?)\\.?\\s*|(?:제|第)\\s*)?(\\d{1,4})\\s*(권|화|편|회(?!차)|巻|vol\\.?)?\\s*[~\\-–—〜]\\s*(\\d{1,4})\\s*(권|화|편|회(?!차)|巻)?|[\\s._\\-]*전\\s*(\\d{1,4})\\s*권)$");

  /** 권·화·편·Vol·Ch·Ep·No·#·괄호·단독 숫자 추출 패턴 (MLS + Bomulseom 통합). */
  private static final Pattern NUMBER =
      Pattern.compile(
          "(?iu)(?:"
              + "[\\s.\\-_]*(?:제|第)\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*(권|화|편|회(?!차)|巻)"
              + "|[\\s.\\-_]*(\\d{1,4}(?:\\.\\d+)?)\\s*(권|화|편|회(?!차)|巻)"
              + "|[\\s.\\-_]+(?:vol(?:ume)?|no|v)\\.?\\s*(\\d{1,4}(?:\\.\\d+)?)"
              + "|^(?:vol(?:ume)?|no|v)\\.?\\s*(\\d{1,4}(?:\\.\\d+)?)"
              + "|[\\s.\\-_]+(?:ch(?:apter)?|ep(?:isode)?)\\.?\\s*(\\d{1,4}(?:\\.\\d+)?)"
              + "|^(?:ch(?:apter)?|ep(?:isode)?)\\.?\\s*(\\d{1,4}(?:\\.\\d+)?)"
              + "|(?:^|[\\s.\\-_]+)#\\s*(\\d{1,4}(?:\\.\\d+)?)"
              + "|\\s*[\\[\\(【](?:제|第)?\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*(권|화|편|巻)?[\\]\\)】]"
              + "|(?:^|[\\s.\\-_~]+)(\\d{1,3})"
              + ")$");

  /** 상·중·하 권 표기 (MLS 패턴). */
  private static final Pattern PART_VOLUME =
      Pattern.compile(
          "(?iu)(?:^|[\\s.\\-_]+)(?:[\\[\\(【]\\s*)?([상중하上中下])(?:\\s*[권편巻])?(?:\\s*[\\]\\)】])?$");

  /** 내용과 상관없이 괄호 한 덩어리: [..] (..) {..} 【..】 */
  private static final String BRACKET =
      "(?:\\[[^\\[\\]]*\\]|\\([^()]*\\)|\\{[^{}]*\\}|【[^【】]*】)";
  private static final Pattern LEADING_TAGS = Pattern.compile("^(?:\\s*" + BRACKET + ")+\\s*");
  private static final Pattern TRAILING_TAG = Pattern.compile("\\s*(" + BRACKET + ")\\s*$");
  /** [3권], (03), 【12】처럼 권 번호만 담은 괄호는 떼지 않는다. */
  private static final Pattern VOLUME_BRACKET =
      Pattern.compile("(?iu)[\\[\\(【](?:제|第)?\\s*\\d{1,4}(?:\\.\\d+)?\\s*[권화편巻]?[\\]\\)】]");
  /** [1-74권], (1~10권)처럼 범위만 담은 괄호. */
  private static final Pattern RANGE_BRACKET =
      Pattern.compile(
          "(?iu)[\\[\\(【]\\s*(?:(?:제|第)\\s*)?\\d{1,4}\\s*(?:권|화|편|巻)?\\s*[~\\-–—〜]\\s*\\d{1,4}\\s*(?:권|화|편|巻)?\\s*[\\]\\)】]");
  /** 화면 표시에서는 본편과 구별되는 문구를 남긴다. */
  private static final Pattern DISTINCT =
      Pattern.compile(
          "(?iu).*(?:외전|번외|특별|부록|스핀오프|단편|설정집|가이드|팬북|화집|일러스트|앤솔로지|extra|special|side|bonus|ova|oad|another).*");
  private static final Pattern KEY_SEPARATORS =
      Pattern.compile("[\\s_:;\\-~〜()\\[\\]{}【】.,!?'\"+·♡♥❤☆★\\ufe0f]+");
  private static final Pattern GENERIC_KEY =
      Pattern.compile(
          "(?iu)(?:외전|번외|프롤로그|에필로그|후기|부록|단편|특별편|표지|cover|extra|special|bonus|\\d+)");

  /** MLS garbage_exact_match + variant_folder_keywords 기반 무시할 상위 폴더명. */
  private static final Set<String> GARBAGE_FOLDERS =
      new HashSet<>(
          Arrays.asList(
              "scan", "scans", "zip", "rar", "cbz", "cbr", "pdf", "file", "files", "down",
              "download", "downloads", "manhwa", "manga", "comic", "comics", "book", "books",
              "web", "webtoon", "jpg", "png", "webp", "etc", "other", "misc", "new", "temp",
              "tmp", "ak", "ak comics", "잡지", "단행본", "전권", "完", "저화질스캔사진",
              "다른작업물", "모음", "연재중", "만화", "만화책", "웹툰", "완결", "미완결",
              "스캔본", "스캔판", "스캔단면", "스캔양면", "스캔", "원본", "raw", "digital",
              "고화질", "저화질", "고화질판", "저화질판", "고해상도", "저해상도", "화질보정",
              "업스케일", "upscale", "워터마크", "워터마크없음", "구판", "신판", "셀렉션",
              "selection", "collection", "정발", "정발본", "번역", "번역본", "역식", "수정",
              "개정", "개정판", "재판", "무삭제", "무수정", "공금", "전자책", "ebook", "e북",
              "ai번역", "bundle", "single", "primary", "storage", "emulated", "0"));

  private static final Pattern FOLDER_INDEX_OR_RES =
      Pattern.compile(
          "(?iu)^(?:\\d{3,4}\\s*p(?:x)?|4k|qhd|fhd|hq|mq|lq|[ㄱ-ㅎ가-힣a-z]\\s*[-~]\\s*[ㄱ-ㅎ가-힣a-z]|\\d+\\s*[-~]\\s*\\d+|part\\s*\\d+|season\\s*\\d+)$");

  static class Volume {
    final String title;
    final int number;
    final String numberText;
    final double sortNumber;
    final String unit;
    final String partLabel;
    final String rangeLabel;
    final String distinctTag;

    Volume(String title, int number) {
      this(title, number, number >= 0 ? String.valueOf(number) : "", number, "권", "", "", "");
    }

    Volume(
        String title,
        int number,
        String numberText,
        double sortNumber,
        String unit,
        String partLabel,
        String rangeLabel,
        String distinctTag) {
      this.title = title;
      this.number = number;
      this.numberText = numberText;
      this.sortNumber = sortNumber;
      this.unit = unit;
      this.partLabel = partLabel;
      this.rangeLabel = rangeLabel;
      this.distinctTag = distinctTag;
    }
  }

  /** "001" -> "1", "01.50" -> "1.5", "0" -> "0". */
  static String normalizeNumberText(String raw) {
    if (raw == null) return "";
    String s = raw.trim();
    if (!s.matches("\\d+(?:\\.\\d+)?")) return "";
    int dot = s.indexOf('.');
    if (dot < 0) {
      String whole = s.replaceFirst("^0+(?!$)", "");
      return whole;
    }
    String whole = s.substring(0, dot).replaceFirst("^0+(?!$)", "");
    String frac = s.substring(dot + 1).replaceFirst("0+$", "");
    return frac.isEmpty() ? whole : whole + "." + frac;
  }

  static double parseDoubleSafe(String numText, double fallback) {
    if (numText == null || numText.isEmpty()) return fallback;
    try {
      return Double.parseDouble(numText);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  static int parseIntPart(String numText) {
    if (numText == null || numText.isEmpty()) return -1;
    int dot = numText.indexOf('.');
    String whole = dot < 0 ? numText : numText.substring(0, dot);
    try {
      return Integer.parseInt(whole);
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  static Volume volume(String filename) {
    String name =
        Normalizer.normalize(stem(filename), Normalizer.Form.NFKC)
            .replace('_', ' ')
            .trim()
            .replaceAll("\\s+", " ");
    String working = VOLUME_COMPLETE_GLUED.matcher(name).replaceAll("$1$2");
    String distinctTag = "";
    String clean = working, before;
    String rangeLabel = "";
    boolean range = false;

    // 1. 뒤쪽 노이즈 태그·괄호 및 범위 표기 분리 (외전 같은 구별 괄호는 보존)
    do {
      before = clean;
      clean = TAGS.matcher(clean).replaceFirst("").trim();
      Matcher tm = TRAILING_TAG.matcher(clean);
      if (tm.find() && tm.start() > 0) {
        String block = tm.group(1);
        if (RANGE_BRACKET.matcher(block).matches()) {
          String inner = block.substring(1, block.length() - 1).trim();
          Matcher rm = RANGE.matcher(" " + inner);
          if (rm.find()) rangeLabel = formatRange(rm);
          clean = clean.substring(0, tm.start()).trim();
          range = true;
          continue;
        }
        if (!VOLUME_BRACKET.matcher(block).matches()) {
          if (DISTINCT.matcher(block).matches()) {
            if (distinctTag.isEmpty()) distinctTag = block;
          }
          clean = clean.substring(0, tm.start()).trim();
          continue;
        }
      }
      Matcher rm = RANGE.matcher(clean);
      if (rm.find()) {
        String cut = clean.substring(0, rm.start()).trim();
        if (!cut.isEmpty()) {
          rangeLabel = formatRange(rm);
          clean = cut;
          range = true;
        }
      }
    } while (!clean.equals(before));

    // 2. 앞쪽 괄호 태그([원본], [완결], [2024] 등) 및 [웹툰] 제거 (단, 권수 괄호만 남는 경우는 유지)
    clean = stripLeadingNoise(clean);

    // 1~74권 같은 범위 표기는 한 권이 아니라 작품 전체이므로 권 번호 없이 작품명만 쓴다.
    if (range) {
      String seriesClean = cleanSeriesTitle(clean);
      return new Volume(seriesClean, -1, "", -1, "권", "", rangeLabel, distinctTag);
    }

    // 3. 권/화 번호 추출
    Matcher m = NUMBER.matcher(clean);
    if (m.find()) {
      String rawNum = null;
      String unit = "권";
      if (m.group(1) != null) {
        rawNum = m.group(1);
        unit = normalizeUnit(m.group(2));
      } else if (m.group(3) != null) {
        rawNum = m.group(3);
        unit = normalizeUnit(m.group(4));
      } else if (m.group(5) != null) {
        rawNum = m.group(5);
      } else if (m.group(6) != null) {
        rawNum = m.group(6);
      } else if (m.group(7) != null) {
        rawNum = m.group(7);
        unit = "화";
      } else if (m.group(8) != null) {
        rawNum = m.group(8);
        unit = "화";
      } else if (m.group(9) != null) {
        rawNum = m.group(9);
      } else if (m.group(10) != null) {
        rawNum = m.group(10);
        if (m.group(11) != null) unit = normalizeUnit(m.group(11));
      } else if (m.group(12) != null) {
        rawNum = m.group(12);
      }
      if (rawNum != null) {
        String normNum = normalizeNumberText(rawNum);
        int intNum = parseIntPart(normNum);
        double dblNum = parseDoubleSafe(normNum, intNum);
        String left = clean.substring(0, m.start()).trim();
        String seriesClean = left.isEmpty() ? "" : cleanSeriesTitle(left);
        return new Volume(seriesClean, intNum, normNum, dblNum, unit, "", "", distinctTag);
      }
    }

    // 4. 상/중/하 권 표기 추출 (MLS 규칙)
    Matcher pm = PART_VOLUME.matcher(clean);
    if (pm.find() && pm.start() > 0) {
      String left = clean.substring(0, pm.start()).trim();
      String part = normalizePart(pm.group(1));
      if (!left.isEmpty() && !part.isEmpty()) {
        return new Volume(cleanSeriesTitle(left), -1, "", -1, "권", part, "", distinctTag);
      }
    }

    String seriesClean = cleanSeriesTitle(clean);
    return new Volume(seriesClean.isEmpty() ? name : seriesClean, -1, "", -1, "권", "", "", distinctTag);
  }

  private static String normalizeUnit(String u) {
    if (u == null) return "권";
    return "화".equals(u) ? "화" : "권";
  }

  private static String normalizePart(String p) {
    if (p == null) return "";
    switch (p) {
      case "상":
      case "上":
        return "상";
      case "중":
      case "中":
        return "중";
      case "하":
      case "下":
        return "하";
      default:
        return "";
    }
  }

  private static String formatRange(Matcher rm) {
    if (rm.group(5) != null) return "전" + normalizeNumberText(rm.group(5)) + "권";
    String a = normalizeNumberText(rm.group(1));
    String b = normalizeNumberText(rm.group(3));
    if (a.isEmpty() || b.isEmpty()) return "";
    String u = rm.group(4) != null ? rm.group(4) : rm.group(2);
    String unit = "화".equals(u) ? "화" : "권";
    return a + "~" + b + unit;
  }

  private static String stripLeadingNoise(String title) {
    String s = LEADING_WEBTOON.matcher(title).replaceFirst("").trim();
    // 앞쪽 괄호가 권 번호만 담은 괄호([3권], (03))가 아니면 제거
    while (true) {
      Matcher m = Pattern.compile("^\\s*(" + BRACKET + ")\\s*").matcher(s);
      if (!m.find()) break;
      String block = m.group(1);
      if (VOLUME_BRACKET.matcher(block).matches()) break;
      String rest = s.substring(m.end()).trim();
      if (rest.isEmpty()) break;
      s = rest;
    }
    return s;
  }

  /**
   * 작품명 내부/앞뒤에 섞인 노이즈 태그(원본, 공금, 스캔본, 고화질, [완결] 등)를 제거해
   * 깨끗한 만화 제목을 반환한다. (Bomulseom extract_series_name + MLS 규칙 통합)
   */
  static String cleanSeriesTitle(String raw) {
    if (raw == null) return "";
    String s =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('_', ' ')
            .trim()
            .replaceAll("\\s+", " ");
    if (s.isEmpty()) return "";
    String before;
    do {
      before = s;
      s = stripLeadingNoise(s);
      s = stripTrailing(TAGS.matcher(s).replaceFirst("").trim(), false);
      String cutRange = RANGE.matcher(s).replaceFirst("").trim();
      if (!cutRange.isEmpty()) s = cutRange;
      String noAuthor = AUTHOR_CREDIT_TAIL.matcher(s).replaceFirst("").trim();
      if (!noAuthor.isEmpty()) s = noAuthor;
      // 중간에 낀 괄호 태그 ([완결], (원본), [고화질] 등) 제거
      String noInlineBracket =
          s.replaceAll("(?iu)\\s*[\\[\\(\\{【]\\s*" + NOISE_TOKEN + "\\s*[\\]\\)\\}】]\\s*", " ")
              .trim();
      if (!noInlineBracket.isEmpty()) s = noInlineBracket;
      // 중간에 낀 단독 노이즈 단어 제거 (예: "나루토 원본" -> "나루토", "원피스 공금" -> "원피스")
      String noInline = INLINE_NOISE.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
      if (!noInline.isEmpty()) s = noInline;
      s = s.replaceAll("^[\\s\\-–—:~]+|[\\s\\-–—:~]+$", "").trim();
    } while (!s.equals(before));
    return s.isEmpty() ? raw.trim() : s;
  }

  static String withoutLeadingTags(String title) {
    String rest = LEADING_TAGS.matcher(title).replaceFirst("").trim();
    return rest.isEmpty() ? title.trim() : rest;
  }

  /** 뒤쪽 괄호 문구를 차례로 뗀다. keepDistinct면 외전 같은 구별 문구에서 멈춘다. */
  static String stripTrailing(String title, boolean keepDistinct) {
    String s = title.trim();
    while (true) {
      Matcher m = TRAILING_TAG.matcher(s);
      if (!m.find() || m.start() == 0) return s;
      String block = m.group(1);
      if (VOLUME_BRACKET.matcher(block).matches()) return s;
      if (keepDistinct && DISTINCT.matcher(block).matches()) return s;
      s = s.substring(0, m.start()).trim();
    }
  }

  /** 앞뒤 괄호 문구, 띄어쓰기·밑줄·문장부호 차이를 무시하는 시리즈 비교 키. */
  static String seriesKey(String series) {
    String name =
        Normalizer.normalize(series == null ? "" : series, Normalizer.Form.NFKC)
            .replace('_', ' ')
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    String bare = cleanSeriesTitle(name).toLowerCase(Locale.ROOT);
    String key = KEY_SEPARATORS.matcher(bare).replaceAll("");
    return key.isEmpty() ? name.replace(" ", "") : key;
  }

  /** 외전·표지처럼 여러 작품에 흔한 이름은 폴더 밖 같은 이름과 묶지 않는다. */
  static boolean genericKey(String key) {
    return GENERIC_KEY.matcher(key).matches();
  }

  /** MLS resolve_smart_title 규칙: 스캔본/고화질/Download 같은 무의미 폴더를 건너뛰고 작품 폴더명을 찾는다. */
  static boolean isGarbageOrVariantFolder(String name) {
    if (name == null) return true;
    String n =
        Normalizer.normalize(name, Normalizer.Form.NFKC)
            .replace('_', ' ')
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    if (n.isEmpty()) return true;
    if (GARBAGE_FOLDERS.contains(n)) return true;
    if (FOLDER_INDEX_OR_RES.matcher(n).matches()) return true;
    String bare = cleanSeriesTitle(n).toLowerCase(Locale.ROOT);
    return bare.isEmpty() || GARBAGE_FOLDERS.contains(bare) || FOLDER_INDEX_OR_RES.matcher(bare).matches();
  }

  static String percentDecode(String s) {
    if (s == null || s.indexOf('%') < 0) return s == null ? "" : s;
    ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%' && i + 2 < s.length()) {
        int hi = Character.digit(s.charAt(i + 1), 16);
        int lo = Character.digit(s.charAt(i + 2), 16);
        if (hi >= 0 && lo >= 0) {
          out.write((hi << 4) | lo);
          i += 2;
          continue;
        }
      }
      byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
      out.write(utf8, 0, utf8.length);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  static String resolveFolderSeries(String parent) {
    if (parent == null || parent.isEmpty() || "single".equals(parent)) return "";
    String decoded = percentDecode(parent).replace('\\', '/');
    // smb://host/share/dir/... 의 경우 scheme+host+share 제거 (먼저 처리해 ':' 오인 방지)
    String pathPart = decoded;
    if (pathPart.startsWith("smb://")) {
      int s1 = pathPart.indexOf('/', 6);
      int s2 = s1 >= 0 ? pathPart.indexOf('/', s1 + 1) : -1;
      pathPart = s2 >= 0 ? pathPart.substring(s2 + 1) : "";
    } else {
      // content:// tree document id는 "primary:Comics/나루토/스캔본" 형태일 수 있다.
      int colon = pathPart.lastIndexOf(':');
      if (colon >= 0) pathPart = pathPart.substring(colon + 1);
    }
    String[] parts = pathPart.split("/");
    String fallback = "";
    int checked = 0;
    for (int i = parts.length - 1; i >= 0 && checked < 5; i--) {
      String seg = parts[i].trim();
      if (seg.isEmpty() || seg.equals("bundle")) continue;
      checked++;
      String cleaned = cleanSeriesTitle(seg);
      if (fallback.isEmpty() && !cleaned.isEmpty()) fallback = cleaned;
      if (!isGarbageOrVariantFolder(seg) && !cleaned.isEmpty()) return cleaned;
    }
    return fallback;
  }

  static String shelfTitle(String title, boolean hideTags) {
    if (title == null) return "";
    if (!hideTags) return title;
    String normalized =
        Normalizer.normalize(title, Normalizer.Form.NFKC)
            .replace('_', ' ')
            .trim()
            .replaceAll("\\s+", " ");
    String name = withoutLeadingTags(normalized);
    String before;
    do {
      before = name;
      name = stripTrailing(name, true);
      Matcher tags = TAGS.matcher(name);
      if (tags.find() && tags.start() > 0) {
        String shortTitle = name.substring(0, tags.start()).trim();
        if (volume(shortTitle).number >= 0) name = shortTitle;
      }
    } while (!name.equals(before));
    return name;
  }

  static String series(String filename) {
    Volume v = volume(filename);
    return v.title.isEmpty() ? cleanSeriesTitle(stem(filename)) : v.title;
  }

  /**
   * 그룹 내에서 가장 읽기 좋은 대표 작품명을 고른다.
   * ComicInfo.xml의 Series가 있으면 최우선, 사용자가 수동 지정한 이름도 보존,
   * 그 외에는 띄어쓰기·대소문자 가독성이 높은 제목을 선택한다.
   */
  static String bestSeriesTitle(List<Library.Book> group, boolean hideTags) {
    if (group == null || group.isEmpty()) return "";
    if (!hideTags) return group.get(0).series;
    for (Library.Book b : group) {
      if (b.manualGroup && b.series != null && !b.series.trim().isEmpty()) {
        return b.series.trim();
      }
    }
    String comicBest = "";
    for (Library.Book b : group) {
      if (b.comicSeries != null && !b.comicSeries.trim().isEmpty()) {
        String c = cleanSeriesTitle(b.comicSeries);
        if (!c.isEmpty() && readabilityScore(c) > readabilityScore(comicBest)) comicBest = c;
      }
    }
    if (!comicBest.isEmpty()) return comicBest;
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Library.Book b : group) {
      String c = cleanSeriesTitle(b.series);
      if (c.isEmpty()) c = shelfTitle(b.series, true);
      if (!c.isEmpty()) counts.put(c, counts.getOrDefault(c, 0) + 1);
    }
    String best = "";
    int bestScore = Integer.MIN_VALUE;
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      int score = readabilityScore(e.getKey()) * 10 + Math.min(e.getValue(), 9);
      if (score > bestScore) {
        bestScore = score;
        best = e.getKey();
      }
    }
    return best.isEmpty() ? shelfTitle(group.get(0).series, true) : best;
  }

  private static int readabilityScore(String title) {
    if (title == null || title.isEmpty()) return Integer.MIN_VALUE / 2;
    int score = 0;
    for (int i = 0; i < title.length(); i++) {
      char c = title.charAt(i);
      if (c == ' ') score += 4;
      else if (Character.isUpperCase(c)) score += 1;
      else if (c == '[' || c == ']' || c == '(' || c == ')' || c == '_') score -= 5;
    }
    return score;
  }

  /**
   * 내부 책장 권 제목 통일:
   * 만화명이 "나루토"이면 "[원본] 나루토 1권", "나루토01권", "나루토001권" 등을
   * "나루토 1권", "나루토 2권", ..., "나루토 10권", ..., "나루토 100권" 형태로 통일한다.
   */
  static String volumeDisplayTitle(
      String seriesTitle, String rawTitle, String comicNumber, boolean hideTags) {
    if (!hideTags) return rawTitle;
    Volume v = volume(rawTitle);
    String base =
        seriesTitle != null && !seriesTitle.trim().isEmpty()
            ? seriesTitle.trim()
            : (!v.title.isEmpty() ? v.title : shelfTitle(rawTitle, true));
    String numText = normalizeNumberText(comicNumber);
    if (numText.isEmpty() && v.number >= 0) numText = v.numberText;
    if (!numText.isEmpty()) {
      String unit = "화".equals(v.unit) ? "화" : "권";
      StringBuilder sb = new StringBuilder();
      sb.append(base).append(' ').append(numText).append(unit);
      if (!v.distinctTag.isEmpty()) sb.append(' ').append(v.distinctTag);
      return sb.toString();
    }
    if (!v.partLabel.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append(base).append(' ').append(v.partLabel).append('권');
      if (!v.distinctTag.isEmpty()) sb.append(' ').append(v.distinctTag);
      return sb.toString();
    }
    if (!v.rangeLabel.isEmpty()) {
      return base + " " + v.rangeLabel;
    }
    String cleaned = shelfTitle(rawTitle, true);
    if (cleaned.isEmpty()) return base;
    if (v.distinctTag.isEmpty() && seriesKey(cleaned).equals(seriesKey(base))) return base;
    return cleaned;
  }

  private static int partOrder(String part) {
    if ("상".equals(part)) return 1;
    if ("중".equals(part)) return 2;
    if ("하".equals(part)) return 3;
    return Integer.MAX_VALUE;
  }

  static int compareBooks(Library.Book a, Library.Book b) {
    double x = bookSortNumber(a), y = bookSortNumber(b);
    int n = Double.compare(x < 0 ? Double.MAX_VALUE : x, y < 0 ? Double.MAX_VALUE : y);
    if (n != 0) return n;
    Volume va = volume(a.title), vb = volume(b.title);
    int p = Integer.compare(partOrder(va.partLabel), partOrder(vb.partLabel));
    return p == 0 ? natural(a.title, b.title) : p;
  }

  private static double bookSortNumber(Library.Book b) {
    String cn = normalizeNumberText(b.comicNumber);
    if (!cn.isEmpty()) return parseDoubleSafe(cn, -1);
    return volume(b.title).sortNumber;
  }

  static int compareVolumes(String a, String b) {
    Volume va = volume(a), vb = volume(b);
    int n =
        Double.compare(
            va.sortNumber < 0 ? Double.MAX_VALUE : va.sortNumber,
            vb.sortNumber < 0 ? Double.MAX_VALUE : vb.sortNumber);
    if (n != 0) return n;
    int p = Integer.compare(partOrder(va.partLabel), partOrder(vb.partLabel));
    return p == 0 ? natural(a, b) : p;
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
        // 파일 관리자와 같은 순서: 기호(_ ! ( - 등) → 숫자 → 글자. 표지를 앞에 두려고 붙인 _.jpg가 첫 장이 된다.
        int d = rank(x) - rank(y);
        if (d != 0) return d;
        if (x != y) return x - y;
        i++;
        j++;
      }
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  static int rank(char c) {
    if (c >= '0' && c <= '9') return 1;
    return Character.isLetter(c) ? 2 : 0;
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
