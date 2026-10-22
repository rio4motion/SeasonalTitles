package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnimationVoucherTest {
  @TempDir Path folder;
  final Season winter = Season.parse("Winter", "2026");

  @Test
  void tradableSingleUseTokensPersistAndCannotBeReplayedForgedOrWastedOnDuplicateUnlocks()
      throws Exception {
    Path file = folder.resolve("vouchers.db");
    UUID admin = UUID.randomUUID(), buyer = UUID.randomUUID(), other = UUID.randomUUID();
    var voucher = new AnimationVoucher(UUID.randomUUID(), "RAINBOW");
    var spare = new AnimationVoucher(UUID.randomUUID(), "rainbow");
    try (Store store = new Store(file, winter)) {
      store.issueVoucher(voucher, admin);
      store.issueVoucher(spare, admin);
      assertEquals(2, store.pendingVouchers(admin).size());
      store.deliveredVouchers(other, List.of(voucher.token()));
      assertEquals(
          2, store.pendingVouchers(admin).size(), "only the recipient can acknowledge delivery");
      store.deliveredVouchers(admin, List.of(voucher.token(), spare.token()));
      assertTrue(store.pendingVouchers(admin).isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> store.redeemVoucher(buyer, new AnimationVoucher(voucher.token(), "fire")));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.redeemVoucher(buyer, new AnimationVoucher(UUID.randomUUID(), "fire")));
      assertTrue(store.snapshot().animationUnlocks(buyer).isEmpty());
      store.redeemVoucher(buyer, voucher);
      assertEquals(Set.of("rainbow"), store.snapshot().animationUnlocks(buyer));
      assertThrows(IllegalArgumentException.class, () -> store.redeemVoucher(other, voucher));
      assertThrows(IllegalArgumentException.class, () -> store.redeemVoucher(buyer, spare));
      store.redeemVoucher(
          other, spare); // Duplicate-unlock rejection did not spend the spare token.
      assertTrue(
          store.snapshot().owned(buyer).isEmpty(), "animation unlocks aren't title ownership");
      assertEquals(PlayerPreferences.DEFAULT, store.snapshot().preferences(buyer));
    }
    try (Store store = new Store(file, Season.parse("Summer", "2099"))) {
      assertEquals(winter, store.season());
      assertEquals(Set.of("rainbow"), store.snapshot().animationUnlocks(buyer));
      assertEquals(Set.of("rainbow"), store.snapshot().animationUnlocks(other));
      assertThrows(IllegalArgumentException.class, () -> store.redeemVoucher(admin, voucher));
    }
    assertThrows(
        IllegalArgumentException.class, () -> new AnimationVoucher(UUID.randomUUID(), "static"));
    assertThrows(
        IllegalArgumentException.class, () -> new AnimationVoucher(UUID.randomUUID(), "typo"));
  }

  @Test
  void oldDatabaseKeepsAwardsEquipmentPreferencesAndPendingDeliverySurvivesRestart()
      throws Exception {
    Path file = folder.resolve("old.db");
    UUID player = UUID.randomUUID();
    String title = Title.seasonal(winter, Tier.DIAMOND).id();
    var voucher = new AnimationVoucher(UUID.randomUUID(), "gradient");
    try (Store store = new Store(file, winter)) {
      store.enroll(player, "verified");
      store.reconcile(player, winter, Tier.DIAMOND);
      store.equip(player, title);
      store.preference(player, "animation", "fire");
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        var sql = connection.createStatement()) {
      sql.execute("DROP TABLE animation_vouchers");
      sql.execute("DROP TABLE animation_unlocks");
      sql.execute("PRAGMA user_version=2");
    }
    try (Store store = new Store(file, winter)) {
      store.issueVoucher(voucher, player);
    }
    try (Store store = new Store(file, winter)) {
      assertEquals(List.of(voucher), store.pendingVouchers(player));
      assertEquals(Set.of(title), store.snapshot().owned(player));
      assertEquals(title, store.snapshot().equipped().get(player));
      assertEquals("fire", store.snapshot().preferences(player).animation());
      store.redeemVoucher(player, voucher);
      assertTrue(store.pendingVouchers(player).isEmpty());
    }
  }

  @Test
  void permanentUnlocksWorkWithoutOpOrLuckPermsGrantButStillRespectTitleAndGlobalRestrictions()
      throws Exception {
    var config = PresentationTest.config();
    var settings = Settings.read(config).animations();
    Player player = mock(Player.class);
    var preference = new PlayerPreferences("fire", true, true);
    assertEquals("static", settings.effective(player, preference, null));
    assertEquals(
        "fire", settings.effective(player, preference, null, "custom:test", Set.of("fire")));
    var restricted =
        new CustomDefinition(Title.custom("test", "Test"), "static", Set.of("rainbow"), "");
    assertFalse(settings.allowed(player, "fire", restricted, "custom:test", Set.of("fire")));
    config.set("animations.static-only-titles", List.of("custom:test"));
    assertEquals(
        "static",
        Settings.read(config)
            .animations()
            .effective(player, preference, null, "custom:test", Set.of("fire")));
    config.set("animations.enabled", false);
    assertFalse(
        Settings.read(config).animations().allowed(player, "fire", null, "other", Set.of("fire")));
    assertTrue(
        Settings.read(config).animations().allowed(player, "static", null, "other", Set.of()));
  }
}
