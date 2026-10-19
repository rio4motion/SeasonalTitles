package dev.seasonal.titles;

public record Title(String id, Season season, Tier tier, String customText) {
  public static Title seasonal(Season season, Tier tier) {
    return new Title(
        season.key() + "-" + tier.name().toLowerCase(java.util.Locale.ROOT), season, tier, null);
  }

  public static Title custom(String id, String text) {
    if (!id.matches("[a-z0-9][a-z0-9_-]{0,47}"))
      throw new IllegalArgumentException("Custom ID: 1–48 lowercase letters, digits, _ or -.");
    if (text.isBlank() || text.length() > 256 || text.contains("\n") || text.contains("\r"))
      throw new IllegalArgumentException("Display text must be a single line of 1–256 characters.");
    return new Title("custom:" + id, null, null, text);
  }
}
