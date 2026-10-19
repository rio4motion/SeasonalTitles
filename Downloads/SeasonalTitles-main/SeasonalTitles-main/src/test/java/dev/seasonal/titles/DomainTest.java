package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.junit.jupiter.api.Test;

class DomainTest {
  private final Season winter = new Season(Season.Name.WINTER, 2026);

  @Test
  void orderingIsServerDefinedAndFutureYearsAreAllowed() {
    Season spring = Season.parse("Spring", "2026"), next = Season.parse("Winter", "2027");
    spring.requireAfter(winter);
    next.requireAfter(Season.parse("Autumn", "2026"));
    Season.parse("Winter", "2040").requireAfter(next);
    assertThrows(IllegalArgumentException.class, () -> winter.requireAfter(winter));
    assertThrows(IllegalArgumentException.class, () -> winter.requireAfter(spring));
    assertThrows(IllegalArgumentException.class, () -> Season.parse("Fall", "2027"));
    assertThrows(IllegalArgumentException.class, () -> Season.parse("Winter", "10000"));
  }

  @Test
  void inheritedHighestRankWinsOverPrimaryAndStalePrimaryIsIgnored() {
    User user = mock(User.class);
    Group speed = mock(Group.class), santa = mock(Group.class);
    QueryOptions query = mock(QueryOptions.class);
    when(speed.getName()).thenReturn("speed");
    when(santa.getName()).thenReturn("santa");
    when(user.getInheritedGroups(query)).thenReturn(List.of(speed, santa));
    Set<String> inherited = LuckPermsBridge.inherited(user, query);
    Map<String, Tier> ranks = Map.of("speed", Tier.BRONZE, "santa", Tier.LEGEND);
    assertEquals(Tier.LEGEND, Access.highest(inherited, "speed", ranks).orElseThrow());
    assertTrue(Access.highest(Set.of("default"), "santa", ranks).isEmpty());
    verify(user).getInheritedGroups(query);
  }

  @Test
  void stackedRolesAreExactAndOwnershipSurvivesRoleRemoval() {
    Title diamond = Title.seasonal(winter, Tier.DIAMOND),
        bronze = Title.seasonal(winter, Tier.BRONZE),
        gold = Title.seasonal(winter, Tier.GOLD);
    Map<String, Set<Tier>> roles =
        Map.of("servermanager", Set.of(Tier.DIAMOND, Tier.LEGEND, Tier.BRONZE));
    Set<String> groups = Set.of("staff", "servermanager");
    assertTrue(Access.canEquip(false, Set.of(), groups, roles, diamond));
    assertTrue(Access.canEquip(false, Set.of(), groups, roles, bronze));
    assertFalse(Access.canEquip(false, Set.of(), groups, roles, gold));
    assertFalse(Access.canEquip(false, Set.of(), groups, Map.of(), diamond));
    assertTrue(Access.canEquip(false, Set.of(diamond.id()), groups, Map.of(), diamond));
    assertFalse(Access.canEquip(false, Set.of(), Set.of(), roles, diamond));
  }

  @Test
  void operatorsAccessExistingSeasonalAndCustomTitlesWithoutOwnership() {
    assertTrue(
        Access.canEquip(true, Set.of(), Set.of(), Map.of(), Title.seasonal(winter, Tier.LEGEND)));
    assertTrue(
        Access.canEquip(true, Set.of(), Set.of(), Map.of(), Title.custom("founder", "Founder")));
    assertFalse(Access.canEquip(true, Set.of(), Set.of(), Map.of(), null));
    assertFalse(
        Access.canEquip(
            false,
            Set.of(),
            Set.of("manager"),
            Map.of("manager", Set.of(Tier.LEGEND)),
            Title.custom("founder", "Founder")));
  }

  @Test
  void previewExpiresWithoutChangingSelectionAndRejectsOverlap() {
    Preview previews = new Preview();
    UUID player = UUID.randomUUID();
    previews.start(player, "preview", 100);
    assertEquals("preview", previews.displayed(player, 299, "owned"));
    assertThrows(IllegalArgumentException.class, () -> previews.start(player, "other", 299));
    assertEquals("owned", previews.displayed(player, 300, "owned"));
    assertNull(previews.displayed(player, 301, null));
    previews.start(player, "other", 301);
    previews.clear(player);
    assertEquals("owned", previews.displayed(player, 302, "owned"));
  }

  @Test
  void confirmationRequiresMatchingSenderActionAndDeadline() {
    Confirmations c = new Confirmations();
    assertFalse(c.confirm("admin", "winter", false, 0));
    assertFalse(c.confirm("other", "winter", true, 1));
    assertFalse(c.confirm("admin", "spring", true, 2));
    assertTrue(c.confirm("admin", "spring", true, 3));
    assertFalse(c.confirm("admin", "spring", true, 4));
    assertFalse(c.confirm("admin", "spring", true, 40_000));
  }

  @Test
  void permissionsAndTitleInputAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> Access.requirePermission(false));
    assertDoesNotThrow(() -> Access.requirePermission(true));
    assertThrows(IllegalArgumentException.class, () -> Title.custom("bad id", "ok"));
    assertThrows(IllegalArgumentException.class, () -> Title.custom("ok", "two\nlines"));
    assertEquals("custom:founder", Title.custom("founder", "<gold>★ Founder").id());
  }
}
