package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomizationTest {
  @TempDir Path folder;
  private final Season winter = Season.parse("Winter", "2026");

  @Test
  void tierRenameAndMultipleGroupsKeepStableAwardsAndTierOrder() throws Exception {
    var c = PresentationTest.config();
    c.set("ranks", null);
    c.set("tiers.bronze.groups", List.of("speed", "winter_speed"));
    c.set("tiers.legend.groups", List.of("santa", "summer_hero"));
    var settings = Settings.read(c);
    assertEquals(Tier.BRONZE, settings.ranks().get("winter_speed"));
    assertEquals(List.of(Tier.BRONZE, Tier.LEGEND), settings.tierOrder());
    UUID id = UUID.randomUUID();
    try (Store s = new Store(folder.resolve("titles.db"), winter)) {
      s.enroll(id, "test");
      s.reconcile(
          id,
          winter,
          Access.highest(Set.of("summer_hero"), "summer_hero", settings.ranks()).orElseThrow());
      String stable = Title.seasonal(winter, Tier.LEGEND).id();
      c.set("tiers.legend.groups", List.of("autumn_hero"));
      Settings renamed = Settings.read(c);
      assertNull(renamed.ranks().get("santa"));
      s.reconcile(
          id, winter, Access.highest(Set.of("santa"), "santa", renamed.ranks()).orElse(null));
      assertTrue(s.snapshot().owned(id).contains(stable));
      c.set("tiers.bronze.groups", List.of("autumn_hero"));
      assertThrows(IllegalArgumentException.class, () -> Settings.read(c));
      c.set("tiers.invalid.groups", List.of("unknown"));
      assertThrows(IllegalArgumentException.class, () -> Settings.read(c));
    }
  }

  @Test
  void removingAllOriginalGroupsPreservesHistoricalDefinitionsOwnershipAndEquipmentAfterRestart()
      throws Exception {
    Path db = folder.resolve("history.db");
    UUID player = UUID.randomUUID();
    Title historical = Title.seasonal(winter, Tier.LEGEND);
    try (Store store = new Store(db, winter)) {
      store.enroll(player, "verified purchase");
      store.reconcile(player, winter, Tier.LEGEND);
      store.equip(player, historical.id());
      var config = PresentationTest.config();
      config.set("ranks", null);
      config.set("tiers.legend.groups", List.of());
      Settings removed = Settings.read(config);
      assertTrue(removed.ranks().isEmpty());
      store.reconcile(player, winter, null);
      Season spring = Season.parse("Spring", "2027");
      store.transition(winter, spring, "admin");
      store.reconcile(player, spring, null);
      assertThrows(
          IllegalArgumentException.class,
          () -> store.delete(historical.id(), true),
          "seasonal definitions cannot be deleted through custom deletion");
    }
    try (Store store = new Store(db, Season.parse("Autumn", "2099"))) {
      Snapshot snapshot = store.snapshot();
      assertEquals(historical, snapshot.titles().get(historical.id()));
      assertTrue(snapshot.owned(player).contains(historical.id()));
      assertEquals(historical.id(), snapshot.equipped().get(player));
      assertTrue(
          Access.canEquip(false, snapshot.owned(player), Set.of(), Map.of(), historical),
          "ownership grants equip without any current LP groups or role access");
      assertFalse(
          snapshot
              .owned(player)
              .contains(Title.seasonal(Season.parse("Spring", "2027"), Tier.LEGEND).id()),
          "removed groups must not generate future rewards");
    }
  }

  @Test
  void oldDatabaseMigrationKeepsSeasonAwardsAndEquipmentWhilePreferencesSurviveRestart()
      throws Exception {
    Path db = folder.resolve("old.db");
    UUID id = UUID.randomUUID();
    try (Store s = new Store(db, winter)) {
      s.enroll(id, "old donor");
      s.reconcile(id, winter, Tier.GOLD);
      s.equip(id, Title.seasonal(winter, Tier.GOLD).id());
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement sql = c.createStatement()) {
      sql.execute("DROP TABLE preferences");
      sql.execute("PRAGMA user_version=1");
    }
    try (Store s = new Store(db, Season.parse("Autumn", "2040"))) {
      assertEquals(winter, s.season());
      assertEquals(PlayerPreferences.DEFAULT, s.snapshot().preferences(id));
      s.preference(id, "animation", "rainbow");
      s.preference(id, "animations", "off");
      s.preference(id, "titles", "off");
      assertEquals(Title.seasonal(winter, Tier.GOLD).id(), s.snapshot().equipped().get(id));
      assertTrue(s.snapshot().owned(id).contains(Title.seasonal(winter, Tier.GOLD).id()));
    }
    try (Store s = new Store(db, winter)) {
      assertEquals(new PlayerPreferences("rainbow", false, false), s.snapshot().preferences(id));
    }
  }

  @Test
  void editableCustomDefinitionsRetainGrantsAndGradientsAndNeverDeleteOnOmission()
      throws Exception {
    var c = PresentationTest.config();
    c.set(
        "custom-titles.beta_supporter.display",
        "<gradient:#00AAFF:#AA00FF><bold>✦ Beta Supporter</bold></gradient>");
    c.set("custom-titles.beta_supporter.default-animation", "shimmer");
    var settings = Settings.read(c);
    var def = settings.customTitles().get("custom:beta_supporter");
    assertEquals(
        "✦ Beta Supporter",
        PlainTextComponentSerializer.plainText().serialize(settings.text(def.title())));
    UUID id = UUID.randomUUID();
    try (Store s = new Store(folder.resolve("custom.db"), winter)) {
      s.definitions(settings.customTitles().values());
      s.grant(id, def.title().id(), true);
      s.equip(id, def.title().id());
      c.set("custom-titles.beta_supporter.display", "<#123456><italic>Edited");
      s.definitions(Settings.read(c).customTitles().values());
      assertEquals(
          "<#123456><italic>Edited", s.snapshot().titles().get(def.title().id()).customText());
      assertTrue(s.snapshot().owned(id).contains(def.title().id()));
      s.definitions(List.of());
      assertNotNull(s.snapshot().titles().get(def.title().id()));
      assertThrows(IllegalArgumentException.class, () -> s.delete(def.title().id(), false));
      s.grant(id, def.title().id(), false);
      assertFalse(s.snapshot().owned(id).contains(def.title().id()));
      assertEquals(def.title().id(), s.snapshot().equipped().get(id));
      s.delete(def.title().id(), true);
      assertFalse(s.snapshot().titles().containsKey(def.title().id()));
      assertNull(s.snapshot().equipped().get(id));
    }
  }

  @Test
  void permissionLossKeepsPreferenceAndCanRestoreItAndStaticAlwaysWorks() throws Exception {
    var c = PresentationTest.config();
    var settings = Settings.read(c).animations();
    Player owner = mock(Player.class);
    var preferences = new PlayerPreferences("rainbow", true, true);
    assertEquals("static", settings.effective(owner, preferences, null));
    when(owner.hasPermission("seasonaltitles.animation.rainbow")).thenReturn(true);
    assertEquals("rainbow", settings.effective(owner, preferences, null));
    when(owner.hasPermission("seasonaltitles.animation.rainbow")).thenReturn(false);
    assertEquals("static", settings.effective(owner, preferences, null));
    // Simulate the provider resolving a wildcard grant to the concrete permission.
    when(owner.hasPermission("seasonaltitles.animation.*")).thenReturn(true);
    assertEquals(
        "static",
        settings.effective(owner, preferences, null),
        "an explicit false must beat a wildcard");
    when(owner.hasPermission("seasonaltitles.animation.rainbow")).thenReturn(true);
    assertEquals("rainbow", settings.effective(owner, preferences, null));
    var restricted =
        new CustomDefinition(Title.custom("test", "Text"), "static", Set.of("pulse"), "");
    assertFalse(settings.allowed(owner, "rainbow", restricted));
    assertTrue(settings.allowed(owner, "static", restricted));
    c.set("animations.static-only-titles", List.of("2026-winter-legend"));
    assertEquals(
        "static",
        Settings.read(c).animations().effective(owner, preferences, null, "2026-winter-legend"));
    assertEquals(
        "rainbow",
        Settings.read(c).animations().effective(owner, preferences, null, "2026-winter-bronze"));
    c.set("animations.enabled", false);
    assertTrue(Settings.read(c).animations().allowed(owner, "static", null));
    assertFalse(Settings.read(c).animations().allowed(owner, "rainbow", null));
    c.set("animations.interval-ticks", 0);
    assertThrows(IllegalArgumentException.class, () -> Settings.read(c));
    c.set("animations.interval-ticks", 10);
    c.set("animations.default", "typo");
    assertThrows(IllegalArgumentException.class, () -> Settings.read(c));
    assertEquals("rainbow", preferences.animation());
  }

  @Test
  void malformedYamlDoesNotReplaceFilesOrActiveConfig() throws Exception {
    Files.writeString(folder.resolve("config.yml"), "initial-season: {name: Winter, year: 2026}\n");
    var files = new ConfigurationFiles(folder);
    files.load();
    Files.writeString(folder.resolve("tiers.yml"), "tiers: [bad\n");
    assertThrows(Exception.class, files::load);
    assertEquals("tiers: [bad\n", Files.readString(folder.resolve("tiers.yml")));
    Files.writeString(folder.resolve("tiers.yml"), "tiers: {bronze: {groups: [speed]}}\n");
    Files.writeString(
        folder.resolve("titles.yml"), "custom-titles: a-string-is-not-a-definition-map\n");
    assertThrows(IllegalArgumentException.class, files::load);
    assertEquals(
        "custom-titles: a-string-is-not-a-definition-map\n",
        Files.readString(folder.resolve("titles.yml")));
  }
}
