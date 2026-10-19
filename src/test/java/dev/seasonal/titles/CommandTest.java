package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

class CommandTest {
  SeasonalTitlesPlugin plugin;
  TitleCommands handler;
  Command titles, sti;
  Player player;
  Snapshot snapshot;

  @BeforeEach
  void setup() {
    plugin = mock(SeasonalTitlesPlugin.class);
    Settings settings = mock(Settings.class);
    when(plugin.settings()).thenReturn(settings);
    when(settings.adminPermission()).thenReturn("seasonaltitles.admin");
    when(settings.message(anyString(), anyString()))
        .thenAnswer(i -> Component.text(i.getArgument(1, String.class)));
    Season winter = Season.parse("Winter", "2026");
    Title title = Title.seasonal(winter, Tier.LEGEND);
    snapshot =
        new Snapshot(
            winter,
            Map.of(title.id(), title),
            Map.of(),
            Map.of(),
            Map.of(),
            Set.of(),
            Set.of(),
            Map.of());
    when(plugin.state()).thenReturn(snapshot);
    when(plugin.previews()).thenReturn(new Preview());
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn("Tester");
    titles = mock(Command.class);
    when(titles.getName()).thenReturn("titles");
    sti = mock(Command.class);
    when(sti.getName()).thenReturn("sti");
    handler = new TitleCommands(plugin);
  }

  @Test
  void adminCommandsAreDeniedBeforeAnyMutation() {
    handler.onCommand(player, titles, "titles", new String[] {"update", "Spring", "2026"});
    handler.onCommand(player, sti, "sti", new String[] {"create", "founder", "Founder"});
    handler.onCommand(player, titles, "titles", new String[] {"reload"});
    verify(plugin, never()).database();
    verify(plugin, never()).reloadSafe();
    verify(player, times(3)).sendMessage(any(Component.class));
  }

  @Test
  void invalidOrUnconfirmedTransitionNeverWrites() {
    when(player.hasPermission("seasonaltitles.admin")).thenReturn(true);
    for (String[] a :
        List.of(
            new String[] {"update", "Winter", "2026"},
            new String[] {"update", "Summer", "2025"},
            new String[] {"update", "Spring", "2026", "oops"},
            new String[] {"update", "Spring", "2026", "confirm"}))
      handler.onCommand(player, titles, "titles", a);
    verify(plugin, never()).database();
  }

  @Test
  void previewUsesPermissionAndNeverWritesOwnershipOrEquipment() {
    handler.onCommand(player, titles, "title", new String[] {"preview", "2026-winter-legend"});
    assertNull(plugin.previews().active(player.getUniqueId(), 0));
    when(player.hasPermission("seasonaltitles.preview")).thenReturn(true);
    handler.onCommand(player, titles, "title", new String[] {"preview", "2026-winter-legend"});
    assertEquals("2026-winter-legend", plugin.previews().active(player.getUniqueId(), 0));
    assertNull(plugin.previews().active(player.getUniqueId(), 200));
    verify(plugin, never()).database();
  }

  @Test
  void completionsExcludeCurrentSeasonAndStartWithActiveYear() {
    when(player.hasPermission("seasonaltitles.admin")).thenReturn(true);
    assertEquals(
        List.of("autumn", "spring", "summer"),
        handler.onTabComplete(player, titles, "titles", new String[] {"update", ""}));
    List<String> years =
        handler.onTabComplete(player, titles, "titles", new String[] {"update", "spring", ""});
    assertEquals("2026", years.getFirst());
    assertTrue(years.contains("2027"));
    when(player.hasPermission("seasonaltitles.admin")).thenReturn(false);
    assertTrue(handler.onTabComplete(player, sti, "sti", new String[] {""}).isEmpty());
  }

  @Test
  void diagnosticsAreAdminOnlyAndReadOnly() {
    handler.onCommand(player, titles, "titles", new String[] {"debug"});
    verify(plugin, never()).renderingDiagnostics(any(), any());
    when(player.hasPermission("seasonaltitles.admin")).thenReturn(true);
    when(plugin.renderingDiagnostics(player, player)).thenReturn(List.of("Display: attached"));
    handler.onCommand(player, titles, "titles", new String[] {"debug"});
    verify(plugin).renderingDiagnostics(player, player);
    verify(plugin, never()).database();
  }

  @Test
  void operatorCanPreviewWithoutExplicitPermission() {
    when(player.isOp()).thenReturn(true);
    handler.onCommand(player, titles, "title", new String[] {"preview", "2026-winter-legend"});
    assertEquals("2026-winter-legend", plugin.previews().active(player.getUniqueId(), 0));
    verify(plugin, never()).database();
  }

  @Test
  void equipChecksAccessAndUnknownIdsBeforeWriting() {
    when(player.hasPermission("seasonaltitles.use")).thenReturn(true);
    handler.onCommand(player, titles, "titles", new String[] {"equip", "missing"});
    handler.onCommand(player, titles, "titles", new String[] {"equip", "2026-winter-legend"});
    verify(plugin, never()).database();
  }
}
