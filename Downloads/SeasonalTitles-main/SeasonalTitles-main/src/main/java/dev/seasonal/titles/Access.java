package dev.seasonal.titles;

import java.util.*;

public final class Access {
  private Access() {}

  public static Optional<Tier> highest(
      Set<String> inheritedGroups, String primaryGroup, Map<String, Tier> ranks) {
    // Primary group is only accepted if it is also effective membership. A stale primary name is
    // not a rank.
    return inheritedGroups.stream()
        .map(ranks::get)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder());
  }

  public static boolean canEquip(
      boolean operator,
      Set<String> owned,
      Set<String> groups,
      Map<String, Set<Tier>> roles,
      Title title) {
    if (title == null) return false;
    if (operator || owned.contains(title.id())) return true;
    return title.tier() != null
        && groups.stream().anyMatch(g -> roles.getOrDefault(g, Set.of()).contains(title.tier()));
  }

  public static void requirePermission(boolean permitted) {
    if (!permitted) throw new IllegalArgumentException("You do not have permission.");
  }
}
