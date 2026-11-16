package kr.ccomics;

import java.util.*;

/** The displayed spread owns its images until another complete spread replaces it. */
final class PageWindow<T> {
  static final int[] NONE = new int[0];
  private int[] visible = NONE;
  private final Map<Integer, T> pages = new HashMap<>();
  private final Set<Integer> wanted = new LinkedHashSet<>();

  void plan(int[] current, int[] next, int[] previous) {
    wanted.clear();
    for (int[] spread : new int[][] {current, next, previous, visible})
      for (int page : spread) wanted.add(page);
    pages.keySet().retainAll(wanted);
  }

  void loaded(int i, T image) {
    if (wanted.contains(i)) pages.put(i, image);
  }

  boolean has(int i) {
    return pages.containsKey(i);
  }

  T image(int i) {
    return pages.get(i);
  }

  boolean ready(int[] spread) {
    for (int i : spread) if (!has(i)) return false;
    return true;
  }

  boolean present(int[] spread) {
    if (!ready(spread)) return false;
    visible = spread;
    return true;
  }

  int[] visible() {
    return visible;
  }

  void clear() {
    visible = NONE;
    wanted.clear();
    pages.clear();
  }
}
