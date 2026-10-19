package dev.seasonal.titles;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class TitleCommands implements CommandExecutor, TabCompleter {
  private final SeasonalTitlesPlugin plugin;
  private final Confirmations confirmations = new Confirmations();

  public TitleCommands(SeasonalTitlesPlugin plugin) {
    this.plugin = plugin;
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (plugin.state() == null) {
      sender.sendMessage(plugin.settings().message("loading", "Loading…"));
      return true;
    }
    try {
      if (args.length == 0) throw new IllegalArgumentException(command.getUsage());
      if (command.getName().equals("sti")) {
        permission(sender, plugin.settings().adminPermission());
        admin(sender, args);
      } else titles(sender, args);
    } catch (IllegalArgumentException e) {
      error(sender, e.getMessage());
    }
    return true;
  }

  private void permission(CommandSender sender, String permission) {
    Access.requirePermission(commandAllowed(sender, permission));
  }

  private boolean commandAllowed(CommandSender sender, String permission) {
    return sender.hasPermission(permission)
        || (sender.isOp()
            && (permission.equals("seasonaltitles.use")
                || permission.equals("seasonaltitles.preview")));
  }

  private Player player(CommandSender s) {
    if (s instanceof Player p) return p;
    throw new IllegalArgumentException("This command requires a player.");
  }

  private void count(String[] a, int n, String usage) {
    if (a.length != n) throw new IllegalArgumentException("Usage: " + usage);
  }

  private void titles(CommandSender sender, String[] a) {
    String sub = a[0].toLowerCase(Locale.ROOT);
    if (sub.equals("update") || (sub.equals("preview") && a.length >= 3)) {
      permission(sender, plugin.settings().adminPermission());
      if (a.length < 3
          || a.length > 4
          || (a.length == 4 && !a[3].equalsIgnoreCase("confirm"))
          || (sub.equals("preview") && a.length != 3))
        throw new IllegalArgumentException(
            "Usage: /titles "
                + sub
                + " <season> <year>"
                + (sub.equals("update") ? " [confirm]" : ""));
      Season next = Season.parse(a[1], a[2]);
      Season current = plugin.state().season();
      next.requireAfter(current);
      if (sub.equals("preview")) {
        sender.sendMessage(
            Component.text(
                current.label()
                    + " → "
                    + next.label()
                    + "; "
                    + plugin.state().enrolled().size()
                    + " enrolled players will be checked. Ranks are verified at processing time; no"
                    + " historical backfill."));
        return;
      }
      if (!confirmations.confirm(
          senderKey(sender),
          "update:" + current.key() + ":" + next.key(),
          a.length == 4,
          System.currentTimeMillis())) {
        sender.sendMessage(
            Component.text("Confirm within 30s: /titles update " + a[1] + " " + a[2] + " confirm"));
        return;
      }
      String actor = sender.getName();
      save(sender, plugin.database().submit(s -> s.transition(current, next, actor)));
      return;
    }
    if (sub.equals("debug")) {
      permission(sender, plugin.settings().adminPermission());
      if (a.length > 2) throw new IllegalArgumentException("Usage: /titles debug [online-player]");
      Player owner = a.length == 1 ? player(sender) : org.bukkit.Bukkit.getPlayerExact(a[1]);
      if (owner == null) throw new IllegalArgumentException("Player must be online.");
      Player viewer = sender instanceof Player p ? p : owner;
      plugin
          .renderingDiagnostics(owner, viewer)
          .forEach(line -> sender.sendMessage(Component.text(line)));
      return;
    }
    if (sub.equals("reload")) {
      permission(sender, plugin.settings().adminPermission());
      count(a, 1, "/titles reload");
      plugin.reloadSafe();
      saved(sender);
      return;
    }
    if (sub.equals("preview")) {
      permission(sender, "seasonaltitles.preview");
      count(a, 2, "/title preview <title-id>");
      Player p = player(sender);
      Title title = title(a[1]);
      plugin.previews().start(p.getUniqueId(), title.id(), plugin.tick());
      sender.sendMessage(plugin.settings().message("preview", "Preview started for 10 seconds."));
      return;
    }
    permission(sender, "seasonaltitles.use");
    Player p = player(sender);
    switch (sub) {
      case "list" -> {
        if (a.length > 2) throw new IllegalArgumentException("Usage: /titles list [page]");
        List<String> available =
            plugin.state().titles().values().stream()
                .filter(t -> plugin.canEquip(p, t))
                .map(
                    t ->
                        t.id()
                            + (plugin.state().owned(p.getUniqueId()).contains(t.id())
                                ? " [owned]"
                                : " [access]"))
                .sorted()
                .toList();
        int page = a.length == 2 ? positiveInt(a[1]) : 1;
        int pages = Math.max(1, (available.size() + 9) / 10);
        if (page > pages) throw new IllegalArgumentException("Page must be 1–" + pages);
        sender.sendMessage(
            Component.text("Titles " + page + "/" + pages + " (" + available.size() + ")"));
        available.stream()
            .skip((page - 1) * 10L)
            .limit(10)
            .forEach(id -> sender.sendMessage(Component.text(id)));
      }
      case "equip" -> {
        count(a, 2, "/titles equip <title-id>");
        Title title = title(a[1]);
        if (!plugin.canEquip(p, title))
          throw new IllegalArgumentException("You do not have access to this title.");
        UUID id = p.getUniqueId();
        save(sender, plugin.database().submit(s -> s.equip(id, title.id())));
      }
      case "unequip" -> {
        count(a, 1, "/titles unequip");
        UUID id = p.getUniqueId();
        plugin.previews().clear(id);
        save(
            sender,
            plugin.database().submit(s -> s.equip(id, null)),
            "unequipped",
            "Title hidden.");
      }
      default ->
          throw new IllegalArgumentException(
              "Use /titles list, equip, unequip, preview, update or reload.");
    }
  }

  private void admin(CommandSender sender, String[] a) {
    switch (a[0].toLowerCase(Locale.ROOT)) {
      case "addrole", "removerole" -> {
        count(a, 3, "/sti " + a[0] + " <group> <tier>");
        Tier tier = Tier.parse(a[2]);
        save(
            sender,
            plugin
                .database()
                .submit(
                    s ->
                        s.role(
                            a[1].toLowerCase(Locale.ROOT),
                            tier,
                            a[0].equalsIgnoreCase("addrole"))));
      }
      case "roles" -> {
        count(a, 1, "/sti roles");
        sender.sendMessage(Component.text(plugin.state().roles().toString()));
      }
      case "custom" -> {
        count(a, 1, "/sti custom");
        plugin.state().titles().values().stream()
            .filter(t -> t.customText() != null)
            .sorted(Comparator.comparing(Title::id))
            .forEach(
                t ->
                    sender.sendMessage(
                        Component.text(t.id() + " = ").append(plugin.settings().text(t))));
      }
      case "create" -> {
        if (a.length < 3)
          throw new IllegalArgumentException("Usage: /sti create <id> <MiniMessage text>");
        Title title = Title.custom(a[1], String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
        plugin.settings().text(title); // Parse before persisting.
        if (plugin.state().titles().containsKey(title.id()))
          throw new IllegalArgumentException("Title already exists.");
        save(sender, plugin.database().submit(s -> s.create(title)));
      }
      case "delete" -> {
        if (a.length < 2 || a.length > 3 || (a.length == 3 && !a[2].equalsIgnoreCase("confirm")))
          throw new IllegalArgumentException("Usage: /sti delete <id> [confirm]");
        Title title = custom(a[1]);
        if (!confirmations.confirm(
            senderKey(sender), "delete:" + title.id(), a.length == 3, System.currentTimeMillis())) {
          long owners =
              plugin.state().owned().values().stream().filter(v -> v.contains(title.id())).count();
          long equipped =
              plugin.state().equipped().values().stream().filter(title.id()::equals).count();
          sender.sendMessage(
              Component.text(
                  "Deletes title, "
                      + owners
                      + " grants and "
                      + equipped
                      + " selections. Confirm within 30s: /sti delete "
                      + a[1]
                      + " confirm"));
          return;
        }
        save(sender, plugin.database().submit(s -> s.delete(title.id(), true)));
      }
      case "give", "revoke", "enroll" -> {
        boolean enroll = a[0].equalsIgnoreCase("enroll");
        count(
            a,
            enroll ? 2 : 3,
            "/sti " + a[0] + " <player-or-uuid>" + (enroll ? "" : " <custom-id>"));
        Title title = enroll ? null : custom(a[2]);
        plugin
            .ranks()
            .identify(a[1])
            .whenComplete(
                (id, err) ->
                    plugin.main(
                        () -> {
                          if (err != null) {
                            report(sender, err);
                            return;
                          }
                          if (id == null) {
                            error(
                                sender,
                                "Unknown player; use a UUID for players not cached by LuckPerms.");
                            return;
                          }
                          String actor = sender.getName();
                          save(
                              sender,
                              plugin
                                  .database()
                                  .submit(
                                      s -> {
                                        if (enroll) s.enroll(id, "Administrator: " + actor);
                                        else s.grant(id, title.id(), a[0].equalsIgnoreCase("give"));
                                      }));
                          plugin.ranks().queue(id);
                        }));
      }
      default ->
          throw new IllegalArgumentException(
              "Use /sti enroll, addrole, removerole, roles, create, delete, give, revoke or"
                  + " custom.");
    }
  }

  private int positiveInt(String text) {
    try {
      int n = Integer.parseInt(text);
      if (n > 0) return n;
    } catch (NumberFormatException ignored) {
    }
    throw new IllegalArgumentException("Invalid page number.");
  }

  private String senderKey(CommandSender sender) {
    return sender instanceof Player p ? p.getUniqueId().toString() : "console:" + sender.getName();
  }

  private Title title(String id) {
    Title t = plugin.state().titles().get(id);
    if (t == null) throw new IllegalArgumentException("Unknown title ID. Use /titles list.");
    return t;
  }

  private Title custom(String id) {
    Title t = title(id.startsWith("custom:") ? id : "custom:" + id);
    if (t.customText() == null)
      throw new IllegalArgumentException("A custom title ID is required.");
    return t;
  }

  private void save(CommandSender sender, CompletableFuture<Void> future) {
    save(sender, future, "saved", "Saved.");
  }

  private void save(
      CommandSender sender, CompletableFuture<Void> future, String messageKey, String fallback) {
    future.whenComplete(
        (v, e) ->
            plugin.main(
                () -> {
                  if (e == null)
                    sender.sendMessage(plugin.settings().message(messageKey, fallback));
                  else report(sender, e);
                }));
  }

  private void saved(CommandSender sender) {
    sender.sendMessage(plugin.settings().message("saved", "Saved."));
  }

  private void error(CommandSender sender, String message) {
    if ("You do not have permission.".equals(message))
      sender.sendMessage(
          plugin.settings().message("no-permission", "<red>You do not have permission.</red>"));
    else sender.sendMessage(Component.text(message, NamedTextColor.RED));
  }

  private void report(CommandSender sender, Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null) cause = cause.getCause();
    if (cause instanceof IllegalArgumentException) error(sender, cause.getMessage());
    else {
      plugin.failure(error);
      error(
          sender,
          "Operation failed; check the server log before retrying. Data may already be saved.");
    }
  }

  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] a) {
    if (plugin.state() == null || a.length == 0) return List.of();
    List<String> options = new ArrayList<>();
    boolean admin = sender.hasPermission(plugin.settings().adminPermission());
    boolean sti = command.getName().equals("sti");
    String sub = a[0].toLowerCase(Locale.ROOT);
    if (sti && !admin) return List.of();
    if (a.length == 1) {
      if (sti)
        options.addAll(
            List.of(
                "enroll",
                "addrole",
                "removerole",
                "roles",
                "create",
                "delete",
                "give",
                "revoke",
                "custom"));
      else {
        if (commandAllowed(sender, "seasonaltitles.use"))
          options.addAll(List.of("list", "equip", "unequip"));
        if (admin) options.addAll(List.of("update", "reload", "preview", "debug"));
        if (commandAllowed(sender, "seasonaltitles.preview")) options.add("preview");
      }
    } else if (!sti) {
      if (admin && sub.equals("debug") && a.length == 2)
        org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
      if (a.length == 2
          && (sub.equals("equip")
              || (sub.equals("preview") && commandAllowed(sender, "seasonaltitles.preview")))) {
        plugin.state().titles().values().stream()
            .filter(
                t -> sub.equals("preview") || (sender instanceof Player p && plugin.canEquip(p, t)))
            .map(Title::id)
            .forEach(options::add);
      }
      if (admin && (sub.equals("update") || sub.equals("preview"))) {
        if (a.length == 2)
          for (Season.Name season : Season.Name.values())
            if (season != plugin.state().season().name())
              options.add(season.name().toLowerCase(Locale.ROOT));
        if (a.length == 3)
          for (int y = plugin.state().season().year();
              y <= Math.min(9999, plugin.state().season().year() + 10);
              y++) options.add(Integer.toString(y));
        if (a.length == 4 && sub.equals("update")) options.add("confirm");
      }
    } else {
      if (a.length == 3 && (sub.equals("addrole") || sub.equals("removerole")))
        for (Tier tier : Tier.values()) options.add(tier.name().toLowerCase(Locale.ROOT));
      if (a.length == 2 && sub.equals("removerole"))
        options.addAll(plugin.state().roles().keySet());
      if ((a.length == 2 && sub.equals("delete"))
          || (a.length == 3 && (sub.equals("give") || sub.equals("revoke"))))
        plugin.state().titles().values().stream()
            .filter(t -> t.customText() != null)
            .map(t -> t.id().substring(7))
            .forEach(options::add);
      if (a.length == 2 && List.of("give", "revoke", "enroll").contains(sub))
        options.addAll(plugin.state().names().values());
      if (a.length == 3 && sub.equals("delete")) options.add("confirm");
    }
    String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
    return options.stream()
        .distinct()
        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
        .sorted()
        .limit(100)
        .toList();
  }
}
