package dev.seasonal.titles;

import java.util.*;

/** Main-thread only, monotonic tick clock. Never writes equipped records. */
public final class Preview {
  private record Entry(String title, long expires) {}

  private final Map<UUID, Entry> entries = new HashMap<>();

  public void start(UUID player, String title, long tick) {
    if (active(player, tick) != null)
      throw new IllegalArgumentException("A preview is already running.");
    entries.put(player, new Entry(title, tick + 200));
  }

  public String active(UUID player, long tick) {
    Entry e = entries.get(player);
    if (e != null && tick < e.expires) return e.title;
    entries.remove(player);
    return null;
  }

  public String displayed(UUID player, long tick, String equipped) {
    String preview = active(player, tick);
    return preview == null ? equipped : preview;
  }

  public void clearAll() {
    entries.clear();
  }

  public void clear(UUID player) {
    entries.remove(player);
  }
}
