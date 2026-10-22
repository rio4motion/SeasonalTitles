package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.util.*;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.util.Tristate;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

class CommandRegistrationTest {
  private List<Command> parsed(SeasonalTitlesPlugin plugin) throws Exception {
    try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
      var description = new PluginDescriptionFile(stream);
      when(plugin.getDescription()).thenReturn(description);
      when(plugin.isEnabled()).thenReturn(true);
      return PluginCommandYamlParser.parse(plugin);
    }
  }

  private Player inheritedPlayer(Set<String> permissions) {
    // Simulate the Bukkit provider reading LuckPerms' cached, context-resolved effective
    // permissions.
    // Parent-group inheritance is intentionally not reconstructed from direct User nodes.
    var inherited = mock(CachedPermissionData.class);
    when(inherited.checkPermission(anyString()))
        .thenAnswer(i -> permissions.contains(i.getArgument(0)) ? Tristate.TRUE : Tristate.FALSE);
    Player player = mock(Player.class);
    when(player.hasPermission(anyString()))
        .thenAnswer(i -> inherited.checkPermission(i.getArgument(0)).asBoolean());
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    return player;
  }

  @Test
  void parsedRootAndAliasesAdmitNonOpInheritedUseButNotAdminCommands() throws Exception {
    var plugin = mock(SeasonalTitlesPlugin.class);
    var settings = mock(Settings.class);
    when(plugin.settings()).thenReturn(settings);
    when(settings.adminPermission()).thenReturn("seasonaltitles.admin");
    when(settings.message(anyString(), anyString()))
        .thenAnswer(i -> net.kyori.adventure.text.Component.text(i.getArgument(1, String.class)));
    var season = Season.parse("Winter", "2026");
    when(plugin.state())
        .thenReturn(
            new Snapshot(
                season, Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of()));
    var commands = parsed(plugin);
    var titles =
        (PluginCommand)
            commands.stream().filter(c -> c.getName().equals("titles")).findFirst().orElseThrow();
    var sti = commands.stream().filter(c -> c.getName().equals("sti")).findFirst().orElseThrow();
    assertEquals(List.of("title"), titles.getAliases());
    Player player = inheritedPlayer(Set.of("seasonaltitles.use"));
    assertFalse(player.isOp());
    assertTrue(titles.testPermissionSilent(player));
    assertFalse(sti.testPermissionSilent(player));
    var handler = new TitleCommands(plugin);
    titles.setExecutor(handler);
    titles.setTabCompleter(handler);
    for (String alias :
        List.of("titles", "title", "seasonaltitles:titles", "seasonaltitles:title")) {
      assertTrue(titles.execute(player, alias, new String[] {"list"}));
      assertEquals(
          List.of(
              "animation",
              "animations",
              "equip",
              "help",
              "list",
              "off",
              "on",
              "unequip",
              "vouchers"),
          titles.tabComplete(player, alias, new String[] {""}));
    }
    verify(player, times(4)).sendMessage(net.kyori.adventure.text.Component.text("Titles 1/1 (0)"));
    for (String[] args :
        List.of(
            new String[] {"reload"},
            new String[] {"preview", "Winter", "2027"},
            new String[] {"update", "Spring", "2026"},
            new String[] {"equip", "missing"})) titles.execute(player, "title", args);
    verify(plugin, never()).database();
    verify(plugin, never()).reloadSafe();
  }

  @Test
  void previewOnlyPermissionPassesRootWithoutGrantingBasicOrAdminAccess() throws Exception {
    var plugin = mock(SeasonalTitlesPlugin.class);
    Command titles = parsed(plugin).getFirst();
    Player previewOnly = inheritedPlayer(Set.of("seasonaltitles.preview"));
    assertTrue(titles.testPermissionSilent(previewOnly));
    assertTrue(CommandPermissions.allowed(previewOnly, "seasonaltitles.preview"));
    assertFalse(CommandPermissions.allowed(previewOnly, "seasonaltitles.use"));
    assertFalse(CommandPermissions.allowed(previewOnly, "seasonaltitles.admin"));
    assertFalse(titles.testPermissionSilent(inheritedPlayer(Set.of())));
  }

  @Test
  void customAdminPermissionIsAppliedToBothRootGates() throws Exception {
    var plugin = mock(SeasonalTitlesPlugin.class);
    var settings = mock(Settings.class);
    when(plugin.settings()).thenReturn(settings);
    when(settings.adminPermission()).thenReturn("staff.titles");
    var commands = parsed(plugin);
    for (Command command : commands)
      when(plugin.getCommand(command.getName())).thenReturn((PluginCommand) command);
    CommandPermissions.configure(plugin);
    var admin = inheritedPlayer(Set.of("staff.titles"));
    for (Command command : commands) assertTrue(command.testPermissionSilent(admin));
    when(settings.adminPermission()).thenReturn("staff.newtitles");
    CommandPermissions.configure(plugin);
    for (Command command : commands) assertFalse(command.testPermissionSilent(admin));
  }

  @Test
  void traceRecognizesOnlyPluginRootsAndDoesNotAlterCancelledEvents() {
    var plugin = mock(SeasonalTitlesPlugin.class);
    var settings = mock(Settings.class);
    when(plugin.settings()).thenReturn(settings);
    var event = mock(org.bukkit.event.player.PlayerCommandPreprocessEvent.class);
    when(event.getMessage()).thenReturn("/seasonaltitles:title list");
    when(event.isCancelled()).thenReturn(true);
    new CommandPermissions(plugin).before(event); // tracing defaults off
    new CommandPermissions(plugin).after(event);
    verify(event, never()).setCancelled(anyBoolean());
    verify(event, never()).setMessage(anyString());
    assertTrue(CommandPermissions.relevant(CommandPermissions.root("/SeAsOnAlTiTlEs:TiTlE list")));
    assertFalse(CommandPermissions.relevant(CommandPermissions.root("/minecraft:title")));
  }
}
