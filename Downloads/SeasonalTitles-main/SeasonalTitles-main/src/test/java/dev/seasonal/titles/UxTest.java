package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class UxTest {
  @Test
  void discoverableCommandsAndModernAdminRouteRespectPermissions() throws Exception {
    var plugin = mock(SeasonalTitlesPlugin.class);
    Settings settings = Settings.read(PresentationTest.config());
    when(plugin.settings()).thenReturn(settings);
    var winter = Season.parse("Winter", "2026");
    when(plugin.state())
        .thenReturn(
            new Snapshot(
                winter, Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of()));
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.hasPermission("seasonaltitles.use")).thenReturn(true);
    Command command = mock(Command.class);
    when(command.getName()).thenReturn("titles");
    var handler = new TitleCommands(plugin);
    handler.onCommand(player, command, "titles", new String[] {});
    verify(player).sendMessage(Component.text("Titles 1/1 (0)"));
    handler.onCommand(
        player, command, "titles", new String[] {"admin", "rank", "set", "legend", "new_name"});
    verify(plugin, never()).rankMapping(any(), any(), any());
    when(player.hasPermission(settings.adminPermission())).thenReturn(true);
    when(plugin.rankMapping(Tier.LEGEND, "set", "new_name"))
        .thenReturn(CompletableFuture.completedFuture(null));
    handler.onCommand(
        player, command, "titles", new String[] {"admin", "rank", "set", "legend", "new_name"});
    verify(plugin).rankMapping(Tier.LEGEND, "set", "new_name");
    assertTrue(
        handler
            .onTabComplete(player, command, "titles", new String[] {"admin", ""})
            .containsAll(List.of("create", "rank", "season", "edit")));
    assertEquals(
        List.of("default", "static"),
        handler.onTabComplete(player, command, "titles", new String[] {"animation", ""}));
    when(player.hasPermission("seasonaltitles.animation.rainbow")).thenReturn(true);
    assertTrue(
        handler
            .onTabComplete(player, command, "titles", new String[] {"animation", "r"})
            .contains("rainbow"));
  }

  @Test
  void unprivilegedCustomTextNeverReachesDefinitionMutationAndRevokeRequiresConfirmation()
      throws Exception {
    var plugin = mock(SeasonalTitlesPlugin.class);
    Settings settings = Settings.read(PresentationTest.config());
    when(plugin.settings()).thenReturn(settings);
    var season = Season.parse("Winter", "2026");
    Title title = Title.custom("test", "<gold>Test");
    when(plugin.state())
        .thenReturn(
            new Snapshot(
                season,
                Map.of(title.id(), title),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Map.of()));
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    Command command = mock(Command.class);
    when(command.getName()).thenReturn("titles");
    var handler = new TitleCommands(plugin);
    handler.onCommand(
        player,
        command,
        "titles",
        new String[] {"admin", "create", "inject", "<click:run_command:/op>Oops"});
    verify(plugin, never()).definition(anyString(), anyString(), anyBoolean());
    when(player.hasPermission(settings.adminPermission())).thenReturn(true);
    handler.onCommand(
        player, command, "titles", new String[] {"admin", "revoke", "Player", "test"});
    verify(plugin, never()).ranks();
    verify(plugin, never()).database();
  }
}
