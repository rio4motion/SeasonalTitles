package dev.seasonal.titles;

import java.util.Locale;

public enum Tier {
  BRONZE,
  SILVER,
  GOLD,
  PLATINUM,
  DIAMOND,
  MASTER,
  LEGEND;

  public static Tier parse(String text) {
    try {
      return valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown tier: " + text);
    }
  }

  public String label() {
    return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
  }
}
