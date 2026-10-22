package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class AnimationFramesTest {
  @Test
  void parsesStableIdsAndDefaultsUnknownValuesToStatic() {
    for (AnimationEffect effect : AnimationEffect.values()) {
      assertEquals(effect, AnimationEffect.parse(effect.id().toUpperCase()));
    }
    assertEquals(AnimationEffect.STATIC, AnimationEffect.parse(null));
    assertThrows(IllegalArgumentException.class, () -> AnimationEffect.parse("unknown"));
    assertEquals("gradient", AnimationEffect.GRADIENT.id());
  }

  @Test
  void staticReturnsTheExactComponentAndRetainsFormattingAndEvents() {
    Component base =
        Component.text("Hello", NamedTextColor.GOLD)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/help"));

    Component output = AnimationFrames.frame(base, AnimationEffect.STATIC, 42, List.of(), 1.0);

    assertSame(base, output);
    assertEquals(base, output);
  }

  @Test
  void everyAnimatedEffectIsFrameDependentAndDeterministic() {
    Component base = Component.text("A title with enough glyphs to animate");
    List<String> colors = List.of("#223344", "#EEDDCC", "invalid");
    for (AnimationEffect effect : AnimationEffect.values()) {
      if (effect == AnimationEffect.STATIC) continue;
      Component first = AnimationFrames.frame(base, effect, 3, colors, 1.0);
      Component same = AnimationFrames.frame(base, effect, 3, colors, 1.0);
      Component later = AnimationFrames.frame(base, effect, 20, colors, 1.0);
      assertEquals(first, same, effect + " must be deterministic");
      assertNotEquals(first, later, effect + " should change with its frame");
    }
  }

  @Test
  void preservesUnicodeCodepointsAndNestedComponentStyles() {
    Component base =
        Component.text("A😀e\u0301")
            .color(NamedTextColor.WHITE)
            .append(
                Component.text(" linked", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/help")));
    for (AnimationEffect effect : AnimationEffect.values()) {
      Component output = AnimationFrames.frame(base, effect, 17, List.of(), 1.0);
      assertFalse(
          hasUnpairedSurrogate(plainText(output)), effect + " must not break a surrogate pair");
    }
    Component output =
        AnimationFrames.frame(base, AnimationEffect.GRADIENT, 5, List.of("red", "#0000FF"), 1);
    Component linked = output.children().get(output.children().size() - 1);
    assertEquals(ClickEvent.runCommand("/help"), linked.clickEvent());
  }

  @Test
  void doesNotMutateTheBaseAndBoundsLongText() {
    Component base = Component.text("x".repeat(400));
    Component before = base;
    Component output = AnimationFrames.frame(base, AnimationEffect.RAINBOW, 8, List.of(), 1.0);
    assertSame(before, base);
    assertEquals(400, plainText(output).codePointCount(0, plainText(output).length()));

    Component typewriter =
        AnimationFrames.frame(base, AnimationEffect.TYPEWRITER, 0, List.of(), 1.0);
    assertEquals(
        145, plainText(typewriter).length()); // one revealed glyph plus the untouched suffix
  }

  @Test
  void typewriterProgressSpansStyledComponentSegments() {
    Component base =
        Component.text("ab", NamedTextColor.GOLD).append(Component.text("cd", NamedTextColor.AQUA));

    Component firstFrame =
        AnimationFrames.frame(base, AnimationEffect.TYPEWRITER, 0, List.of(), 1.0);
    Component laterFrame =
        AnimationFrames.frame(base, AnimationEffect.TYPEWRITER, 10, List.of(), 1.0);

    assertEquals("a", plainText(firstFrame));
    assertTrue(plainText(laterFrame).length() > 1);
  }

  @Test
  void gradientUsesOneTitleWideRangeAcrossStyledTextNodes() {
    Component base =
        Component.text("AB", NamedTextColor.GOLD).append(Component.text("CD", NamedTextColor.AQUA));

    Component output =
        AnimationFrames.frame(base, AnimationEffect.GRADIENT, 0, List.of("#FF0000", "#0000FF"), 0);
    List<TextColor> glyphColors = coloredGlyphColors(output);

    assertEquals(4, glyphColors.size());
    assertEquals(TextColor.fromHexString("#FF0000"), glyphColors.get(0));
    assertEquals(TextColor.fromHexString("#0000FF"), glyphColors.get(3));
    assertNotEquals(glyphColors.get(0), glyphColors.get(2));
  }

  @Test
  void gradientMovesForwardAndWrapsWithoutAColorJumpAtOneTickCadence() {
    Component base = Component.text("x".repeat(31));
    var palette = List.of("#FF0000", "#0000FF");
    var initial =
        coloredGlyphColors(AnimationFrames.frame(base, AnimationEffect.GRADIENT, 0, palette, 1));
    var moved =
        coloredGlyphColors(AnimationFrames.frame(base, AnimationEffect.GRADIENT, 2, palette, 1));
    assertEquals(
        initial.get(4), moved.get(5), "features move one character to the right per two ticks");
    assertNotEquals(initial.get(4), moved.get(3), "flow must not move backwards");
    for (int tick = 0; tick < 240; tick++) {
      var first =
          coloredGlyphColors(
              AnimationFrames.frame(base, AnimationEffect.GRADIENT, tick, palette, 1));
      var next =
          coloredGlyphColors(
              AnimationFrames.frame(base, AnimationEffect.GRADIENT, tick + 1, palette, 1));
      for (int i = 0; i < first.size(); i++)
        assertTrue(maxChannelChange(first.get(i), next.get(i)) <= 14);
    }
  }

  @Test
  void colorEffectsStayReadableAndContinuousAndTypewriterHoldsTheCompleteTitle() {
    Component base = Component.text("A lovely title");
    for (AnimationEffect effect : AnimationEffect.values()) {
      if (effect == AnimationEffect.TYPEWRITER || effect == AnimationEffect.STATIC) continue;
      for (int tick = 0; tick < 120; tick++) {
        var a = AnimationFrames.frame(base, effect, tick, List.of(), 1);
        var b = AnimationFrames.frame(base, effect, tick + 1, List.of(), 1);
        assertEquals("A lovely title", plainText(a), "no character corruption in " + effect);
        var before = coloredGlyphColors(a);
        var after = coloredGlyphColors(b);
        for (int i = 0; i < before.size(); i++)
          assertTrue(
              maxChannelChange(before.get(i), after.get(i)) <= 65,
              effect + " changes too abruptly at tick " + tick);
      }
    }
    assertEquals(
        "A lovely title",
        plainText(AnimationFrames.frame(base, AnimationEffect.TYPEWRITER, 50, List.of(), 1)));
    assertEquals(
        "A lovely title",
        plainText(AnimationFrames.frame(base, AnimationEffect.TYPEWRITER, 110, List.of(), 1)));
    assertEquals(
        AnimationFrames.frame(base, AnimationEffect.GRADIENT, 40, List.of(), 1),
        AnimationFrames.frame(base, AnimationEffect.GRADIENT, 20, List.of(), 2),
        "speed scales elapsed ticks");
  }

  private static int maxChannelChange(TextColor a, TextColor b) {
    return Math.max(
        Math.abs(a.red() - b.red()),
        Math.max(Math.abs(a.green() - b.green()), Math.abs(a.blue() - b.blue())));
  }

  @Test
  void glitchFreezesAtZeroSpeedAndChangesWhenSpeedAdvancesItsPhase() {
    Component base =
        Component.text("A title with enough glyphs to exercise glitch effects.".repeat(4));

    Component frozenA = AnimationFrames.frame(base, AnimationEffect.GLITCH, 20, List.of(), 0);
    Component frozenB = AnimationFrames.frame(base, AnimationEffect.GLITCH, 300, List.of(), 0);
    Component moving = AnimationFrames.frame(base, AnimationEffect.GLITCH, 20, List.of(), 1);

    assertEquals(frozenA, frozenB);
    assertNotEquals(frozenA, moving);
  }

  private static String plainText(Component component) {
    StringBuilder result = new StringBuilder();
    appendText(component, result);
    return result.toString();
  }

  private static List<TextColor> coloredGlyphColors(Component component) {
    List<TextColor> result = new java.util.ArrayList<>();
    collectColoredGlyphColors(component, result);
    return result;
  }

  private static void collectColoredGlyphColors(Component component, List<TextColor> result) {
    if (component instanceof TextComponent text
        && !text.content().isEmpty()
        && text.color() != null) {
      int count = text.content().codePointCount(0, text.content().length());
      for (int i = 0; i < count; i++) result.add(text.color());
    }
    for (Component child : component.children()) collectColoredGlyphColors(child, result);
  }

  private static void appendText(Component component, StringBuilder result) {
    if (component instanceof TextComponent text) result.append(text.content());
    for (Component child : component.children()) appendText(child, result);
  }

  private static boolean hasUnpairedSurrogate(String text) {
    for (int i = 0; i < text.length(); i++) {
      char current = text.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i))) return true;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }
}
