package dev.seasonal.titles;

import java.util.*;

public record Snapshot(
    Season season,
    Map<String, Title> titles,
    Map<UUID, Set<String>> owned,
    Map<UUID, String> equipped,
    Map<String, Set<Tier>> roles,
    Set<UUID> enrolled,
    Set<UUID> pending,
    Map<UUID, String> names) {
  public Snapshot {
    titles = Map.copyOf(titles);
    equipped = Map.copyOf(equipped);
    names = Map.copyOf(names);
    owned = freeze(owned);
    roles = freeze(roles);
    enrolled = Set.copyOf(enrolled);
    pending = Set.copyOf(pending);
  }

  private static <K, V> Map<K, Set<V>> freeze(Map<K, Set<V>> source) {
    Map<K, Set<V>> copy = new HashMap<>();
    source.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
    return Map.copyOf(copy);
  }

  public Set<String> owned(UUID id) {
    return owned.getOrDefault(id, Set.of());
  }
}
