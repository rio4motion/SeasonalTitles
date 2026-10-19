package dev.seasonal.titles;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** Root registration gates complement, never replace, subcommand and title-access checks. */
final class CommandPermissions implements Listener {
  private final SeasonalTitlesPlugin plugin;
  private final Map<PlayerCommandPreprocessEvent, String> attempts = new IdentityHashMap<>();

  CommandPermissions(SeasonalTitlesPlugin plugin) {
    this.plugin = plugin;
  }

  static void configure(SeasonalTitlesPlugin plugin) {
    Objects.requireNonNull(plugin.getCommand("titles"))
        .setPermission(
            "seasonaltitles.use;seasonaltitles.preview;" + plugin.settings().adminPermission());
    Objects.requireNonNull(plugin.getCommand("sti"))
        .setPermission(plugin.settings().adminPermission());
  }

  static boolean allowed(CommandSender sender, String permission) {
    // Bukkit's permission provider resolves LuckPerms inheritance and active contexts.
    return sender.hasPermission(permission)
        || (sender.isOp()
            && (permission.equals("seasonaltitles.use")
                || permission.equals("seasonaltitles.preview")));
  }

  static String root(String input) {
    return input.replaceFirst("^/", "").split(" ", 2)[0].toLowerCase(Locale.ROOT);
  }

  static boolean relevant(String root) {
    if (root.startsWith("seasonaltitles:")) root = root.substring(15);
    return Set.of("title", "titles", "sti").contains(root);
  }

  List<String> diagnostics(Player player) {
    List<String> lines = new ArrayList<>();
    lines.add(
        "Commands for "
            + player.getName()
            + ": op="
            + player.isOp()
            + ", use="
            + player.hasPermission("seasonaltitles.use")
            + ", preview="
            + player.hasPermission("seasonaltitles.preview")
            + ", admin("
            + plugin.settings().adminPermission()
            + ")="
            + player.hasPermission(plugin.settings().adminPermission()));
    for (String label :
        List.of(
            "title",
            "titles",
            "seasonaltitles:title",
            "seasonaltitles:titles",
            "sti",
            "seasonaltitles:sti")) {
      Command command = Bukkit.getCommandMap().getCommand(label);
      lines.add(
          "Command /"
              + label
              + ": "
              + describe(command)
              + ", root-allowed="
              + (command != null && command.testPermissionSilent(player)));
    }
    lines.add(
        "Command preprocess listeners: "
            + Arrays.stream(PlayerCommandPreprocessEvent.getHandlerList().getRegisteredListeners())
                .map(l -> l.getPlugin().getName())
                .distinct()
                .sorted()
                .toList()
            + "; trace="
            + plugin.settings().commandTrace());
    return lines;
  }

  static String describe(Command command) {
    if (command == null) return "missing";
    return "owner="
        + (command instanceof PluginIdentifiableCommand c
            ? c.getPlugin().getName()
            : command.getClass().getSimpleName())
        + ", name="
        + command.getName()
        + ", root-permission="
        + command.getPermission();
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void before(PlayerCommandPreprocessEvent event) {
    String root = root(event.getMessage());
    if (plugin.settings().commandTrace() && relevant(root)) attempts.put(event, root);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void after(PlayerCommandPreprocessEvent event) {
    String original = attempts.remove(event);
    if (original == null) return;
    String current = root(event.getMessage());
    Player player = event.getPlayer();
    Command command = Bukkit.getCommandMap().getCommand(current);
    plugin
        .getLogger()
        .info(
            "Command trace: player="
                + player.getName()
                + ", requested=/"
                + original
                + ", final=/"
                + current
                + ", cancelled="
                + event.isCancelled()
                + ", "
                + describe(command)
                + ", root-allowed="
                + (command != null && command.testPermissionSilent(player))
                + ", use="
                + player.hasPermission("seasonaltitles.use")
                + ", preview="
                + player.hasPermission("seasonaltitles.preview"));
  }
}
