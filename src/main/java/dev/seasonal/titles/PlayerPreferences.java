package dev.seasonal.titles;

/** Independent of title ownership. A denied selection is retained and rendered as static. */
public record PlayerPreferences(String animation, boolean animations, boolean titles) {
  public static final PlayerPreferences DEFAULT = new PlayerPreferences("default", true, true);
}
