package dev.seasonal.titles;

import java.util.*;

/** Exact-action confirmation, scoped to sender and active season; 30 second lifetime. */
public final class Confirmations {
  private record Pending(String action, long until) {}

  private final Map<String, Pending> pending = new HashMap<>();

  public boolean confirm(String sender, String action, boolean confirmed, long now) {
    pending.entrySet().removeIf(e -> e.getValue().until < now);
    Pending old = pending.get(sender);
    if (confirmed && old != null && old.action.equals(action) && now <= old.until) {
      pending.remove(sender);
      return true;
    }
    pending.put(sender, new Pending(action, now + 30_000));
    return false;
  }
}
