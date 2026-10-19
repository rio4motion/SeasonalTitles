package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PresentationTest {
  static YamlConfiguration config() throws Exception {
    try (var reader =
        new InputStreamReader(
            PresentationTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
      return YamlConfiguration.loadConfiguration(reader);
    }
  }

  @Test
  void automaticPaletteAndCaseInsensitiveOverridesKeepCustomFormatting() throws Exception {
    var c = config();
    c.set("colors", null);
    var settings = Settings.read(c);
    for (Tier tier : Tier.values()) {
      assertEquals(
          Settings.automaticColor(tier.label().toUpperCase(Locale.ROOT)),
          settings.colors().get(tier));
      var text = settings.text(Title.seasonal(Season.parse("Winter", "2026"), tier));
      assertEquals(TextColor.fromHexString(settings.colors().get(tier)), text.color());
    }
    assertEquals("#FFFFFF", Settings.automaticColor("Unknown custom tier"));
    c.set("colors.LeGeNd", "#123456");
    assertEquals("#123456", Settings.read(c).colors().get(Tier.LEGEND));
    assertEquals(
        TextColor.fromHexString("#12AB34"),
        settings.text(Title.custom("test", "<#12AB34>Founder")).color());
    assertEquals(
        TextColor.fromHexString("#FFFFFF"), settings.text(Title.custom("test", "Founder")).color());
  }

  @Test
  void automaticColorAlsoAppliesWithoutAColorPlaceholderAndKeepsExplicitFormatColor()
      throws Exception {
    var c = config();
    c.set("format", "<season> <year> • <tier>");
    var title = Title.seasonal(Season.parse("Winter", "2026"), Tier.LEGEND);
    assertEquals(TextColor.fromHexString("#FFAA00"), Settings.read(c).text(title).color());
    c.set("format", "<#123456><tier>");
    assertEquals(TextColor.fromHexString("#123456"), Settings.read(c).text(title).color());
    c.set("colors.legend", "not_a_color");
    assertThrows(IllegalArgumentException.class, () -> Settings.read(c));
  }

  @Test
  void legacyOffsetCannotOverlapConfiguredNametagTop() throws Exception {
    var c = config();
    c.set("render.offset", 0.55);
    var settings = Settings.read(c);
    assertEquals(0.9f, settings.offset(), 0.0001);
    c.set("render.nametag-top", 1.2);
    c.set("render.gap", 0.3);
    assertEquals(1.5f, Settings.read(c).offset(), 0.0001);
    c.set("render.offset", 2.0);
    assertEquals(2.0f, Settings.read(c).offset());
    c.set("render.gap", -1);
    assertThrows(IllegalArgumentException.class, () -> Settings.read(c));
  }

  @Test
  void orderUsesConfigurationAndWithinTierOrderWithCustomTitlesLast() throws Exception {
    var c = config();
    Title bronzeWinter = Title.seasonal(Season.parse("Winter", "2026"), Tier.BRONZE);
    Title bronzeSpring = Title.seasonal(Season.parse("Spring", "2026"), Tier.BRONZE);
    Title silver = Title.seasonal(Season.parse("Winter", "2026"), Tier.SILVER);
    Title customA = Title.custom("aaa", "A"), customZ = Title.custom("zzz", "Z");
    List<Title> input = List.of(customZ, silver, bronzeSpring, customA, bronzeWinter);
    assertEquals(
        List.of(bronzeWinter, bronzeSpring, silver, customA, customZ),
        input.stream().sorted(TitleSuggestions.titles(Settings.read(c))).toList());
    c.set("ranks", null);
    c.set("ranks.first", "SILVER");
    c.set("ranks.second", "bronze");
    c.set("ranks.duplicate", "silver");
    c.set("suggestions.title-order", List.of(bronzeSpring.id(), bronzeWinter.id()));
    for (int i = 0; i < 3; i++) {
      var settings =
          Settings.read(YamlConfiguration.loadConfiguration(new StringReader(c.saveToString())));
      assertEquals(List.of(Tier.SILVER, Tier.BRONZE), settings.tierOrder());
      assertEquals(
          List.of(silver, bronzeSpring, bronzeWinter, customA, customZ),
          input.stream().sorted(TitleSuggestions.titles(settings)).toList());
    }
  }

  @Test
  void finalPaperSuggestionsKeepRangesTooltipsAndOnlyReorderExistingEntries() throws Exception {
    var plugin = mock(SeasonalTitlesPlugin.class);
    var c = config();
    var bronze = Title.seasonal(Season.parse("Winter", "2026"), Tier.BRONZE);
    var diamond = Title.seasonal(Season.parse("Winter", "2026"), Tier.DIAMOND);
    var silver = Title.seasonal(Season.parse("Winter", "2026"), Tier.SILVER);
    var catalog = Map.of(bronze.id(), bronze, silver.id(), silver, diamond.id(), diamond);
    when(plugin.settings()).thenReturn(Settings.read(c));
    when(plugin.state())
        .thenReturn(
            new Snapshot(
                bronze.season(),
                catalog,
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Map.of()));
    var event = mock(AsyncPlayerSendSuggestionsEvent.class);
    when(event.getBuffer()).thenReturn("/title equip 2026-");
    var range = StringRange.between(13, 18);
    var tip = new com.mojang.brigadier.LiteralMessage("Owned");
    var d = new Suggestion(range, diamond.id(), tip);
    var s = new Suggestion(range, silver.id());
    when(event.getSuggestions()).thenReturn(new Suggestions(range, List.of(d, s)));
    new TitleSuggestions(plugin).send(event);
    var capture = org.mockito.ArgumentCaptor.forClass(Suggestions.class);
    verify(event).setSuggestions(capture.capture());
    assertEquals(List.of(s, d), capture.getValue().getList()); // no unauthorized bronze added
    assertEquals(range, capture.getValue().getRange());
    assertSame(tip, capture.getValue().getList().getLast().getTooltip());
    for (String buffer :
        List.of(
            "/titles equip ",
            "/title preview ",
            "/seasonaltitles:title equip ",
            "/sti give Player ",
            "/sti revoke Player ",
            "/sti delete ")) assertTrue(TitleSuggestions.titleArgument(buffer), buffer);
    for (String buffer :
        List.of(
            "/another:titles equip ",
            "/titles update ",
            "/sti enroll ",
            "/titles preview winter 2026 "))
      assertFalse(TitleSuggestions.titleArgument(buffer), buffer);
  }
}
