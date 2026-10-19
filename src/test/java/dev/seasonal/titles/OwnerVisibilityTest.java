package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnerVisibilityTest {
  SeasonalTitlesPlugin plugin;
  Settings settings;
  TitleRenderer renderer;
  Player owner, viewer;
  World world;
  Scoreboard scoreboard;

  @BeforeEach
  void setup() {
    plugin = mock(SeasonalTitlesPlugin.class);
    settings = mock(Settings.class);
    when(plugin.getName()).thenReturn("SeasonalTitles");
    when(plugin.namespace()).thenReturn("seasonaltitles");
    when(plugin.settings()).thenReturn(settings);
    when(settings.showSelf()).thenReturn(true);
    when(settings.lineOfSight()).thenReturn(true);
    when(settings.distance()).thenReturn(32.0);
    renderer = new TitleRenderer(plugin);
    owner = mock(Player.class);
    viewer = mock(Player.class);
    when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
    when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
    when(owner.getName()).thenReturn("Owner");
    when(viewer.getName()).thenReturn("Viewer");
    world = mock(World.class);
    scoreboard = mock(Scoreboard.class);
    for (Player p : new Player[] {owner, viewer}) {
      when(p.getWorld()).thenReturn(world);
      when(p.getLocation()).thenAnswer(i -> new Location(world, 0, 80, 0));
      when(p.getScoreboard()).thenReturn(scoreboard);
      when(p.canSee(owner)).thenReturn(true);
    }
    when(owner.isVisibleByDefault()).thenReturn(true);
  }

  @Test
  void normalEquippedTitleIsVisibleToItsOwnerWithoutAPreview() {
    assertEquals("visible", renderer.visibilityReason(owner, owner, false));
    // Asking the server to ray trace a player's eyes to their own body is not an F5 camera test.
    verify(owner, never()).hasLineOfSight(any(org.bukkit.entity.Entity.class));
    verify(owner, never()).getEyeLocation();
  }

  @Test
  void optOutOnlySuppressesNormalSelfViewNotPreviewOrOtherViewers() {
    when(settings.showSelf()).thenReturn(false);
    assertEquals("self-disabled", renderer.visibilityReason(owner, owner, false));
    assertEquals("visible", renderer.visibilityReason(owner, owner, true));
    when(settings.lineOfSight()).thenReturn(false);
    assertEquals("visible", renderer.visibilityReason(viewer, owner, false));
  }

  @Test
  void selfPreviewAndEquipKeepConcealmentGates() {
    when(plugin.titleHidden(owner.getUniqueId())).thenReturn(true);
    for (boolean preview : new boolean[] {false, true})
      assertEquals("hidden-by-integration", renderer.visibilityReason(owner, owner, preview));
    when(plugin.titleHidden(owner.getUniqueId())).thenReturn(false);
    when(owner.canSee(owner)).thenReturn(false);
    assertEquals("player-hidden", renderer.visibilityReason(owner, owner, false));
    when(owner.canSee(owner)).thenReturn(true);
    when(owner.isVisibleByDefault()).thenReturn(false);
    assertEquals("player-hidden", renderer.visibilityReason(owner, owner, true));
  }

  @Test
  void scoreboardRestrictionsStillApplyToOwner() {
    Team team = mock(Team.class);
    when(scoreboard.getEntryTeam("Owner")).thenReturn(team);
    when(team.getOption(Team.Option.NAME_TAG_VISIBILITY)).thenReturn(Team.OptionStatus.NEVER);
    assertEquals("team-name-hidden", renderer.visibilityReason(owner, owner, false));
    assertEquals("team-name-hidden", renderer.visibilityReason(owner, owner, true));
    verify(team, never()).setOption(any(), any());
  }

  @Test
  void observerDoesNotGetSelfLineOfSightExemption() {
    when(viewer.hasLineOfSight(owner)).thenReturn(false);
    assertEquals("body-occluded", renderer.visibilityReason(viewer, owner, false));
    when(viewer.getWorld()).thenReturn(mock(World.class));
    assertEquals("different-world", renderer.visibilityReason(viewer, owner, false));
  }

  @Test
  void existingConfigWithoutNewKeyEnablesSelfView() throws Exception {
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
      YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
      config.set("visibility.show-self", null);
      assertTrue(Settings.read(config).showSelf());
      config.set("visibility.show-self", false);
      assertFalse(Settings.read(config).showSelf());
    }
  }
}
