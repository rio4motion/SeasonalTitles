package dev.seasonal.titles;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Pure, deterministic transformations for animated Adventure components. */
public final class AnimationFrames {
  private static final int MAX_CODE_POINTS = 256;
  private static final int MAX_COMPONENTS = 256;
  private static final String GLITCH_GLYPHS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int[][] FIRE = {
    {255, 244, 164}, {255, 190, 70}, {255, 123, 32}, {220, 48, 24}, {255, 213, 92}
  };
  private static final int[][] SPARKLE = {
    {255, 255, 255}, {255, 224, 128}, {150, 240, 255}, {255, 170, 225}
  };
  private static final int[][] PULSE = {{90, 205, 255}, {220, 120, 255}, {255, 120, 185}};
  private static final int[][] GRADIENT = {
    {255, 80, 90}, {255, 190, 75}, {120, 235, 145}, {70, 190, 255}, {190, 115, 255}
  };

  private AnimationFrames() {}

  /**
   * Builds one frame without mutating {@code base}. The input component is never parsed as
   * MiniMessage. Work is bounded to 256 component nodes and 256 Unicode code points. Text nodes
   * longer than 512 UTF-16 units are left unchanged; within the budget, trailing content remains
   * unchanged. Invalid supplied colors are ignored.
   */
  public static Component frame(
      Component base, AnimationEffect effect, long frame, List<String> colors, double speed) {
    if (base == null) return Component.empty();
    if (effect == null || effect == AnimationEffect.STATIC) return base;
    Budget budget = new Budget();
    List<TextColor> palette = parsePalette(colors);
    if (palette.isEmpty()) palette = defaultPalette(effect);
    IdentityHashMap<TextComponent, Integer> typewriterVisibility = new IdentityHashMap<>();
    IdentityHashMap<TextComponent, TextRange> ranges = new IdentityHashMap<>();
    if (effect == AnimationEffect.TYPEWRITER) {
      int total = countTypewriterUnits(base, new Budget(), 0);
      int visible =
          total == 0
              ? 0
              : (int) Math.floor(Math.min(1, phase(frame, safeSpeed(speed)) / 0.30) * total) + 1;
      allocateTypewriterVisibility(base, new Budget(), typewriterVisibility, visible, 0);
    } else {
      int[] total = {0};
      collectTextRanges(base, new Budget(), ranges, total, 0);
      for (TextRange range : ranges.values()) range.total = total[0];
    }
    return transform(
        base, effect, frame, palette, safeSpeed(speed), budget, typewriterVisibility, ranges, 0);
  }

  private static Component transform(
      Component component,
      AnimationEffect effect,
      long frame,
      List<TextColor> palette,
      double speed,
      Budget budget,
      IdentityHashMap<TextComponent, Integer> typewriterVisibility,
      IdentityHashMap<TextComponent, TextRange> ranges,
      int depth) {
    if (budget.nodes >= MAX_COMPONENTS || depth >= MAX_COMPONENTS) return component;
    budget.nodes++;

    List<Component> originalChildren = component.children();
    if (originalChildren.size() > MAX_COMPONENTS - budget.nodes) return component;

    TextComponent text = component instanceof TextComponent candidate ? candidate : null;
    int process = 0;
    if (text != null
        && text.content().length() <= MAX_CODE_POINTS * 2
        && budget.points < MAX_CODE_POINTS) {
      process = countPrefixCodePoints(text.content(), MAX_CODE_POINTS - budget.points);
      budget.points += process;
    }

    List<Component> mappedChildren = new ArrayList<>(originalChildren.size());
    for (Component child : originalChildren) {
      mappedChildren.add(
          transform(
              child,
              effect,
              frame,
              palette,
              speed,
              budget,
              typewriterVisibility,
              ranges,
              depth + 1));
    }

    Component result = component.children(mappedChildren);
    if (text != null && process > 0)
      result =
          transformText(
              text,
              effect,
              frame,
              palette,
              speed,
              process,
              mappedChildren,
              typewriterVisibility.getOrDefault(text, 0),
              ranges.get(text));
    return result;
  }

  private static Component transformText(
      TextComponent original,
      AnimationEffect effect,
      long frame,
      List<TextColor> palette,
      double speed,
      int codePointsToProcess,
      List<Component> mappedChildren,
      int typewriterVisible,
      TextRange range) {
    String content = original.content();
    int prefixEnd = content.offsetByCodePoints(0, codePointsToProcess);
    String prefix = content.substring(0, prefixEnd);
    String suffix = content.substring(prefixEnd);
    List<String> units = graphemes(prefix);
    if (units.isEmpty()) return original;
    double phase = phase(frame, speed);

    if (effect == AnimationEffect.TYPEWRITER) {
      int visible = Math.min(units.size(), typewriterVisible);
      StringBuilder shown = new StringBuilder(content.length());
      for (int i = 0; i < visible; i++) shown.append(units.get(i));
      shown.append(suffix);
      return original.content(shown.toString()).children(mappedChildren);
    }

    List<Component> colored = new ArrayList<>(units.size() + 1);
    long glitchStep = 0;
    boolean obfuscatedBurst = false;
    if (effect == AnimationEffect.GLITCH) {
      glitchStep = (long) Math.max(0, Math.floor(frame * speed));
      int burstStart = 45 + Math.floorMod(mix64(glitchStep / 80), 25);
      obfuscatedBurst = glitchStep % 80 >= burstStart && glitchStep % 80 < burstStart + 7;
    }
    for (int i = 0; i < units.size(); i++) {
      int globalIndex = range == null ? i : range.start + i;
      int total = range == null ? units.size() : range.total;
      int rgb = colorAt(effect, globalIndex, total, phase, palette);
      String glyph = units.get(i);
      Component styled = Component.text(glyph).color(TextColor.color(rgb));
      if (effect == AnimationEffect.GLITCH
          && glyph.codePointCount(0, glyph.length()) == 1
          && Character.isLetterOrDigit(glyph.codePointAt(0))) {
        long random = mix64(glitchStep ^ mix64(globalIndex + 0x9e3779b97f4a7c15L));
        char scrambled = GLITCH_GLYPHS.charAt(Math.floorMod(random, GLITCH_GLYPHS.length()));
        styled =
            Component.text(String.valueOf(scrambled))
                .color(TextColor.color(rgb))
                .decoration(TextDecoration.OBFUSCATED, obfuscatedBurst);
      }
      colored.add(styled);
    }
    if (!suffix.isEmpty()) colored.add(Component.text(suffix));
    Component parent = original.content("").children(List.of());
    List<Component> combined = new ArrayList<>(colored.size() + mappedChildren.size());
    combined.addAll(colored);
    combined.addAll(mappedChildren);
    return parent.children(combined);
  }

  private static int colorAt(
      AnimationEffect effect, int index, int total, double phase, List<TextColor> palette) {
    double position = total <= 1 ? 0 : index / (double) (total - 1);
    int color;
    double angle = phase * Math.PI * 2;
    switch (effect) {
      case RAINBOW -> color = hsv(position * 0.7 - phase, 0.78, 1.0);
      // A cyclic, eased palette has no discontinuity where the moving gradient wraps.
      // Subtracting phase carries a color feature from left to right (forward).
      case GRADIENT -> color = cyclic(palette, position * 0.5 - phase);
      case PULSE -> {
        double breath = (1 - Math.cos(angle)) / 2;
        color = scale(interpolate(palette, position), 0.68 + 0.32 * breath);
      }
      case SHIMMER -> {
        double distance = Math.abs(fract(position * 0.7 - phase + 0.5) - 0.5);
        double shine = Math.exp(-distance * distance * 80);
        color = blend(interpolate(palette, position), 0xFFFFFF, shine * 0.85);
      }
      case SPARKLE -> {
        double offset = unitNoise(index);
        double glint = Math.pow((1 + Math.sin(angle * 2 + offset * Math.PI * 2)) / 2, 12);
        color = blend(interpolate(palette, position), 0xFFFFFF, glint * 0.90);
      }
      case FIRE -> {
        // Continuous waves replace frame-hashed noise, avoiding strobe-like jumps at 20 Hz.
        double heat =
            0.50
                + 0.23 * Math.sin(angle * 2 - position * 5)
                + 0.16 * Math.sin(angle * 3 + position * 11 + unitNoise(index) * 2);
        color = interpolate(palette, heat);
      }
      case GLITCH -> {
        // Keep the neon palette underneath the independently timed scramble/obfuscation.
        double scan = Math.pow((1 + Math.cos(angle * 2 - position * 9)) / 2, 8);
        color = blend(cyclic(palette, position * 0.5 - phase), 0xFFFFFF, scan * 0.65);
      }
      case FLICKER -> {
        double glow = 0.88 + 0.07 * Math.sin(angle * 3) + 0.05 * Math.sin(angle * 7 + position * 2);
        color = scale(interpolate(palette, position), glow);
      }
      default -> color = palette.get(0).value();
    }
    return color;
  }

  private static double unitNoise(int index) {
    return (mix64(index * 0x9e3779b97f4a7c15L) >>> 11) * 0x1.0p-53;
  }

  private static int cyclic(List<TextColor> colors, double at) {
    if (colors.size() == 1) return colors.get(0).value();
    double scaled = fract(at) * colors.size();
    int lower = (int) Math.floor(scaled);
    double ease = (1 - Math.cos((scaled - lower) * Math.PI)) / 2;
    return blend(colors.get(lower).value(), colors.get((lower + 1) % colors.size()).value(), ease);
  }

  private static List<String> graphemes(String text) {
    BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
    iterator.setText(text);
    List<String> result = new ArrayList<>();
    int start = iterator.first();
    for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
      result.add(text.substring(start, end));
    }
    return result;
  }

  private static int countTypewriterUnits(Component component, Budget budget, int depth) {
    if (budget.nodes >= MAX_COMPONENTS || depth >= MAX_COMPONENTS) return 0;
    budget.nodes++;
    List<Component> children = component.children();
    if (children.size() > MAX_COMPONENTS - budget.nodes) return 0;

    int total = 0;
    if (component instanceof TextComponent text
        && text.content().length() <= MAX_CODE_POINTS * 2
        && budget.points < MAX_CODE_POINTS) {
      int process = countPrefixCodePoints(text.content(), MAX_CODE_POINTS - budget.points);
      budget.points += process;
      int end = text.content().offsetByCodePoints(0, process);
      total += graphemes(text.content().substring(0, end)).size();
    }
    for (Component child : children) total += countTypewriterUnits(child, budget, depth + 1);
    return total;
  }

  private static void collectTextRanges(
      Component component,
      Budget budget,
      IdentityHashMap<TextComponent, TextRange> ranges,
      int[] total,
      int depth) {
    if (budget.nodes >= MAX_COMPONENTS || depth >= MAX_COMPONENTS) return;
    budget.nodes++;
    List<Component> children = component.children();
    if (children.size() > MAX_COMPONENTS - budget.nodes) return;

    if (component instanceof TextComponent text
        && text.content().length() <= MAX_CODE_POINTS * 2
        && budget.points < MAX_CODE_POINTS) {
      int process = countPrefixCodePoints(text.content(), MAX_CODE_POINTS - budget.points);
      budget.points += process;
      int end = text.content().offsetByCodePoints(0, process);
      int length = graphemes(text.content().substring(0, end)).size();
      if (length > 0) {
        ranges.put(text, new TextRange(total[0]));
        total[0] += length;
      }
    }
    for (Component child : children) {
      collectTextRanges(child, budget, ranges, total, depth + 1);
    }
  }

  private static int allocateTypewriterVisibility(
      Component component,
      Budget budget,
      IdentityHashMap<TextComponent, Integer> visibility,
      int remaining,
      int depth) {
    if (budget.nodes >= MAX_COMPONENTS || depth >= MAX_COMPONENTS) return remaining;
    budget.nodes++;
    List<Component> children = component.children();
    if (children.size() > MAX_COMPONENTS - budget.nodes) return remaining;

    if (component instanceof TextComponent text
        && text.content().length() <= MAX_CODE_POINTS * 2
        && budget.points < MAX_CODE_POINTS) {
      int process = countPrefixCodePoints(text.content(), MAX_CODE_POINTS - budget.points);
      budget.points += process;
      int end = text.content().offsetByCodePoints(0, process);
      int clusters = graphemes(text.content().substring(0, end)).size();
      int visible = Math.min(clusters, remaining);
      visibility.put(text, visible);
      remaining -= visible;
    }
    for (Component child : children) {
      remaining = allocateTypewriterVisibility(child, budget, visibility, remaining, depth + 1);
    }
    return remaining;
  }

  /** Counts at most {@code limit} code points, so an oversized text node is cheap to inspect. */
  private static int countPrefixCodePoints(String text, int limit) {
    int count = 0;
    for (int offset = 0; offset < text.length() && count < limit; count++) {
      offset += Character.charCount(text.codePointAt(offset));
    }
    return count;
  }

  private static List<TextColor> parsePalette(List<String> colors) {
    List<TextColor> result = new ArrayList<>();
    if (colors == null) return result;
    int inspected = 0;
    for (String value : colors) {
      if (inspected++ >= MAX_COMPONENTS) break;
      if (value == null || value.length() > 16) continue;
      String name = value.trim();
      TextColor color =
          name.matches("(?i)#[0-9a-f]{6}")
              ? TextColor.fromHexString(name)
              : NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
      if (color != null) result.add(color);
    }
    return result;
  }

  private static List<TextColor> defaultPalette(AnimationEffect effect) {
    int[][] colors =
        switch (effect) {
          case FIRE -> FIRE;
          case SPARKLE -> SPARKLE;
          case PULSE -> PULSE;
          default -> GRADIENT;
        };
    List<TextColor> result = new ArrayList<>(colors.length);
    for (int[] rgb : colors) result.add(TextColor.color(rgb[0], rgb[1], rgb[2]));
    return result;
  }

  private static double safeSpeed(double speed) {
    if (!Double.isFinite(speed)) return 1.0;
    return Math.max(-10.0, Math.min(10.0, speed));
  }

  private static double phase(long frame, double speed) {
    // Time is elapsed server ticks, independent of the configured sampling interval.
    double value = frame * speed / 120.0;
    return fract(value);
  }

  private static double fract(double value) {
    double fraction = value - Math.floor(value);
    // Tiny negative values can round to 1.0 after subtraction; keep cyclic indices in range.
    return fraction >= 1 ? 0 : fraction;
  }

  private static int interpolate(List<TextColor> colors, double at) {
    if (colors.size() == 1) return colors.get(0).value();
    double scaled = Math.max(0, Math.min(1, at)) * (colors.size() - 1);
    int lower = (int) Math.floor(scaled);
    int upper = Math.min(colors.size() - 1, lower + 1);
    double amount = scaled - lower;
    int a = colors.get(lower).value(), b = colors.get(upper).value();
    return TextColor.color(
            channel(a, 16) + (int) Math.round((channel(b, 16) - channel(a, 16)) * amount),
            channel(a, 8) + (int) Math.round((channel(b, 8) - channel(a, 8)) * amount),
            channel(a, 0) + (int) Math.round((channel(b, 0) - channel(a, 0)) * amount))
        .value();
  }

  private static int blend(int a, int b, double amount) {
    amount = Math.max(0, Math.min(1, amount));
    return TextColor.color(
            channel(a, 16) + (int) Math.round((channel(b, 16) - channel(a, 16)) * amount),
            channel(a, 8) + (int) Math.round((channel(b, 8) - channel(a, 8)) * amount),
            channel(a, 0) + (int) Math.round((channel(b, 0) - channel(a, 0)) * amount))
        .value();
  }

  private static int scale(int rgb, double scale) {
    return TextColor.color(
            (int) Math.round(channel(rgb, 16) * scale),
            (int) Math.round(channel(rgb, 8) * scale),
            (int) Math.round(channel(rgb, 0) * scale))
        .value();
  }

  private static int channel(int rgb, int shift) {
    return (rgb >>> shift) & 0xFF;
  }

  private static int hsv(double h, double s, double v) {
    double scaled = fract(h) * 6;
    int sector = (int) scaled;
    double f = scaled - sector, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
    double r, g, b;
    switch (sector % 6) {
      case 0 -> {
        r = v;
        g = t;
        b = p;
      }
      case 1 -> {
        r = q;
        g = v;
        b = p;
      }
      case 2 -> {
        r = p;
        g = v;
        b = t;
      }
      case 3 -> {
        r = p;
        g = q;
        b = v;
      }
      case 4 -> {
        r = t;
        g = p;
        b = v;
      }
      default -> {
        r = v;
        g = p;
        b = q;
      }
    }
    return TextColor.color(
            (int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255))
        .value();
  }

  private static long mix64(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }

  private static final class Budget {
    int points;
    int nodes;
  }

  private static final class TextRange {
    final int start;
    int total;

    TextRange(int start) {
      this.start = start;
    }
  }
}
