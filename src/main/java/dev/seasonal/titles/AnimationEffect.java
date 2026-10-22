package dev.seasonal.titles;

import java.util.Locale;

/** The visual treatment used when rendering an animated title. */
public enum AnimationEffect {
  STATIC,
  RAINBOW,
  GRADIENT,
  PULSE,
  SHIMMER,
  SPARKLE,
  FIRE,
  GLITCH,
  FLICKER,
  TYPEWRITER;

  /** Returns the lowercase configuration id for this effect. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses an effect id without regard to case; unknown or missing ids use the static effect. */
  public static AnimationEffect parse(String id) {
    if (id == null) return STATIC;
    try {
      return valueOf(id.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unknown animation effect: " + id, exception);
    }
  }
}
