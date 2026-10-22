package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationFilesTest {
  @TempDir Path folder;

  @Test
  void migratesRanksPreservingOrderGroupsColorsMessagesAndOriginalConfig() throws Exception {
    String original =
        "initial-season:\n"
            + "  name: Summer\n"
            + "  year: 2031\n"
            + "ranks:\n"
            + "  first-group: Gold\n"
            + "  second-group: gOlD\n"
            + "  third-group: Bronze\n"
            + "colors:\n"
            + "  GoLd: '#123456'\n"
            + "geometry:\n"
            + "  offset: 1.25\n"
            + "messages:\n"
            + "  saved: '<aqua>Mine</aqua>'\n"
            + "  private-key: 'keep me'\n";
    Path config = folder.resolve("config.yml");
    Files.writeString(config, original);

    ConfigurationFiles files = new ConfigurationFiles(folder);
    YamlConfiguration merged = files.load();

    assertEquals("Summer", merged.getString("initial-season.name"));
    assertEquals(2031, merged.getInt("initial-season.year"));
    assertEquals(1.25, merged.getDouble("geometry.offset"));
    assertEquals("Gold", merged.getString("ranks.first-group"));
    assertEquals("#123456", merged.getString("tiers.gold.color"));
    assertEquals(
        java.util.List.of("first-group", "second-group"),
        merged.getStringList("tiers.gold.groups"));
    assertEquals(java.util.List.of("third-group"), merged.getStringList("tiers.bronze.groups"));
    assertEquals("<aqua>Mine</aqua>", merged.getString("messages.saved"));
    assertEquals("keep me", merged.getString("messages.private-key"));
    assertNotNull(merged.getString("messages.preview"), "bundled messages are filled in");

    Path backup = folder.resolve("config.yml.pre-2.0.bak");
    assertEquals(original, Files.readString(config), "migration leaves config.yml unchanged");
    assertEquals(original, Files.readString(backup));
    byte[] tiers = Files.readAllBytes(folder.resolve("tiers.yml"));
    byte[] messages = Files.readAllBytes(folder.resolve("messages.yml"));

    YamlConfiguration reloaded = files.load();
    assertEquals(merged.saveToString(), reloaded.saveToString());
    assertArrayEquals(tiers, Files.readAllBytes(folder.resolve("tiers.yml")));
    assertArrayEquals(messages, Files.readAllBytes(folder.resolve("messages.yml")));
    assertEquals(original, Files.readString(backup), "the one-time backup remains unchanged");
  }

  @Test
  void existingSplitFilesRemainByteForByteAndReplaceInlineSections() throws Exception {
    String original =
        "initial-season:\n"
            + "  name: Winter\n"
            + "  year: 2042\n"
            + "render:\n"
            + "  offset: 2.0\n"
            + "tiers:\n"
            + "  inline:\n"
            + "    groups: [wrong]\n"
            + "custom-titles:\n"
            + "  inline: {display: Inline}\n"
            + "animations:\n"
            + "  enabled: false\n"
            + "messages:\n"
            + "  saved: Inline\n";
    Files.writeString(folder.resolve("config.yml"), original);
    String tiers = "tiers:\n  custom:\n    groups: [one, two]\n    color: '#ABCDEF'\n";
    String titles = "custom-titles:\n  seasonal:\n    display: '<gold>Seasonal</gold>'\n";
    String animations = "animations:\n  enabled: false\n  default: pulse\n";
    String messages = "messages:\n  saved: Custom\n  own: Value\n";
    Files.writeString(folder.resolve("tiers.yml"), tiers);
    Files.writeString(folder.resolve("titles.yml"), titles);
    Files.writeString(folder.resolve("animations.yml"), animations);
    Files.writeString(folder.resolve("messages.yml"), messages);

    YamlConfiguration merged = new ConfigurationFiles(folder).load();

    assertEquals(java.util.List.of("one", "two"), merged.getStringList("tiers.custom.groups"));
    assertFalse(merged.contains("tiers.inline"));
    assertTrue(merged.contains("custom-titles.seasonal"));
    assertFalse(merged.contains("custom-titles.inline"));
    assertFalse(merged.getBoolean("animations.enabled", true));
    assertEquals("pulse", merged.getString("animations.default"));
    assertEquals("Custom", merged.getString("messages.saved"));
    assertEquals("Value", merged.getString("messages.own"));
    assertNotNull(merged.getString("messages.preview"));
    assertEquals(2.0, merged.getDouble("render.offset"));
    assertEquals(2042, merged.getInt("initial-season.year"));

    assertEquals(tiers, Files.readString(folder.resolve("tiers.yml")));
    assertEquals(titles, Files.readString(folder.resolve("titles.yml")));
    assertEquals(animations, Files.readString(folder.resolve("animations.yml")));
    assertEquals(messages, Files.readString(folder.resolve("messages.yml")));
    assertFalse(Files.exists(folder.resolve("config.yml.pre-2.0.bak")));
  }
}
