package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path temp;
  final Season winter = Season.parse("Winter", "2026"), spring = Season.parse("Spring", "2026");
  final UUID player = UUID.randomUUID();

  Store open() throws Exception {
    return new Store(temp.resolve("titles.db"), winter);
  }

  String id(Season season, Tier tier) {
    return Title.seasonal(season, tier).id();
  }

  @Test
  void preexistingRanksRequireEnrollmentAndNeverBackfill() throws Exception {
    try (Store store = open()) {
      store.reconcile(player, winter, Tier.LEGEND);
      assertTrue(store.snapshot().owned(player).isEmpty());
      store.transition(winter, spring, "admin");
      store.enroll(player, "admin verified");
      store.reconcile(player, spring, Tier.LEGEND);
      assertEquals(Set.of(id(spring, Tier.LEGEND)), store.snapshot().owned(player));
    }
  }

  @Test
  void firstPurchaseAfterVerifiedRanklessObservationEnrollsInCurrentSeasonOnly() throws Exception {
    try (Store store = open()) {
      store.reconcile(player, winter, null);
      store.transition(winter, spring, "admin");
      store.reconcile(player, spring, Tier.DIAMOND);
      assertTrue(store.snapshot().enrolled().contains(player));
      assertEquals(Set.of(id(spring, Tier.DIAMOND)), store.snapshot().owned(player));
    }
  }

  @Test
  void upgradesDowngradesExpiryAndDuplicatesPreservePermanentOwnership() throws Exception {
    try (Store store = open()) {
      store.enroll(player, "new purchase");
      store.reconcile(player, winter, Tier.BRONZE);
      store.reconcile(player, winter, Tier.LEGEND);
      store.reconcile(player, winter, Tier.BRONZE);
      store.reconcile(player, winter, null);
      store.reconcile(player, winter, Tier.LEGEND);
      assertEquals(
          Set.of(id(winter, Tier.BRONZE), id(winter, Tier.LEGEND)), store.snapshot().owned(player));
      store.equip(player, id(winter, Tier.LEGEND));
    }
    try (Store store = open()) {
      assertEquals(2, store.snapshot().owned(player).size());
      assertEquals(id(winter, Tier.LEGEND), store.snapshot().equipped().get(player));
    }
  }

  @Test
  void transitionQueueSurvivesRestartAndRejectsStaleReconciliation() throws Exception {
    try (Store store = open()) {
      store.enroll(player, "offline purchase");
      store.reconcile(player, winter, Tier.LEGEND);
      store.transition(winter, spring, "admin");
      assertTrue(store.snapshot().pending().contains(player));
    }
    try (Store store = open()) {
      assertEquals(spring, store.season());
      store.reconcile(player, winter, Tier.DIAMOND);
      assertTrue(store.snapshot().pending().contains(player));
      assertFalse(store.snapshot().owned(player).contains(id(spring, Tier.DIAMOND)));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.transition(spring, Season.parse("Summer", "2026"), "admin"));
      store.reconcile(player, spring, Tier.LEGEND);
      store.reconcile(player, spring, Tier.LEGEND);
      assertTrue(store.snapshot().pending().isEmpty());
      assertEquals(2, store.snapshot().owned(player).size());
    }
  }

  @Test
  void transitionFailureRollsBackCatalogStateAndAuditTogether() throws Exception {
    try (Store store = open()) {
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("titles.db"));
          var s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER simulate_failure BEFORE INSERT ON transitions BEGIN SELECT"
                + " RAISE(ABORT,'injected failure'); END");
      }
      assertThrows(Exception.class, () -> store.transition(winter, spring, "admin"));
      assertEquals(winter, store.season());
      assertEquals(7, store.snapshot().titles().size());
    }
    try (Store store = open()) {
      assertEquals(winter, store.season());
    }
  }

  @Test
  void awardFailureKeepsDurableJobForRetry() throws Exception {
    try (Store store = open()) {
      store.enroll(player, "test");
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("titles.db"));
          var s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER simulate_failure BEFORE INSERT ON awards BEGIN SELECT"
                + " RAISE(ABORT,'injected failure'); END");
      }
      assertThrows(Exception.class, () -> store.reconcile(player, winter, Tier.LEGEND));
      assertTrue(store.snapshot().pending().contains(player));
      assertTrue(store.snapshot().owned(player).isEmpty());
    }
  }

  @Test
  void roleAssignmentsAndCustomGrantsPersistAndDeletionIsSafe() throws Exception {
    Title custom = Title.custom("founder", "<gold>★ Founder");
    try (Store store = open()) {
      store.role("servermanager", Tier.DIAMOND, true);
      store.role("servermanager", Tier.LEGEND, true);
      store.role("servermanager", Tier.DIAMOND, true);
      store.role("servermanager", Tier.BRONZE, true);
      store.role("servermanager", Tier.LEGEND, false);
      store.create(custom);
      store.grant(player, custom.id(), true);
      store.grant(player, custom.id(), true);
      store.equip(player, custom.id());
      assertThrows(IllegalArgumentException.class, () -> store.delete(custom.id(), false));
    }
    try (Store store = open()) {
      assertEquals(
          Set.of(Tier.DIAMOND, Tier.BRONZE), store.snapshot().roles().get("servermanager"));
      assertEquals(Set.of(custom.id()), store.snapshot().owned(player));
      store.grant(player, custom.id(), false);
      assertTrue(store.snapshot().owned(player).isEmpty());
      store.grant(player, custom.id(), true);
      store.delete(custom.id(), true);
      assertTrue(store.snapshot().owned(player).isEmpty());
      assertFalse(store.snapshot().equipped().containsKey(player));
      assertFalse(store.snapshot().titles().containsKey(custom.id()));
    }
  }

  @Test
  void reloadInitialConfigCannotRollBackPersistedSeason() throws Exception {
    try (Store store = open()) {
      store.transition(winter, spring, "admin");
    }
    try (Store store = new Store(temp.resolve("titles.db"), Season.parse("Winter", "2000"))) {
      assertEquals(spring, store.season());
    }
  }
}
