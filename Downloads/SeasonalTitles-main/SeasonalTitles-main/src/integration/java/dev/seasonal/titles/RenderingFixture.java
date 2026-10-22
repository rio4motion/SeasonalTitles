package dev.seasonal.titles;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;

/** Disposable test-server plugin. Never included in SeasonalTitles.jar. */
public final class RenderingFixture extends JavaPlugin implements org.bukkit.event.Listener {
  private final Map<UUID, ArmorStand> stands = new HashMap<>();
  private final java.util.Set<UUID> blocked = new java.util.HashSet<>();
  private final Map<UUID, org.bukkit.inventory.ItemStack> vouchers = new HashMap<>();

  @Override
  public void onEnable() {
    Bukkit.getPluginManager().registerEvents(this, this);
  }

  @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
  public void commandBlock(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
    if (blocked.contains(event.getPlayer().getUniqueId())
        && java.util.Set.of(
                "title",
                "titles",
                "seasonaltitles:title",
                "seasonaltitles:titles",
                "sti",
                "seasonaltitles:sti")
            .contains(
                event
                    .getMessage()
                    .substring(1)
                    .split(" ", 2)[0]
                    .toLowerCase(java.util.Locale.ROOT))) {
      event.setCancelled(true);
      event.getPlayer().sendMessage(Component.text("Fixture command blocked."));
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.isOp() || args.length != 2) return false;
    Player player = Bukkit.getPlayerExact(args[1]);
    if (player == null) throw new IllegalArgumentException("Fixture player is offline");
    SeasonalTitlesPlugin titles =
        (SeasonalTitlesPlugin) Bukkit.getPluginManager().getPlugin("SeasonalTitles");
    switch (args[0]) {
      case "checkvoucherburst" -> {
        var key = new org.bukkit.NamespacedKey(titles, "animation_voucher");
        long count =
            java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(item -> item != null && item.getPersistentDataContainer().has(key))
                .count();
        if (count != 3)
          throw new IllegalStateException("Expected three physical vouchers, got " + count);
      }
      case "voucherhand" -> {
        var inventory = player.getInventory();
        var key = new org.bukkit.NamespacedKey(titles, "animation_voucher");
        int slot = -1;
        for (int i = 0; i < inventory.getStorageContents().length; i++) {
          var item = inventory.getItem(i);
          if (item != null && item.getPersistentDataContainer().has(key)) {
            slot = i;
            break;
          }
        }
        if (slot < 0) throw new IllegalStateException("No voucher found");
        var item = inventory.getItem(slot);
        inventory.setItem(slot, inventory.getItem(0));
        inventory.setItem(0, item);
        inventory.setHeldItemSlot(0);
        vouchers.put(player.getUniqueId(), item.clone());
      }
      case "vouchercopy" ->
          player.getInventory().setItemInMainHand(vouchers.get(player.getUniqueId()).clone());
      case "vouchertrade" -> {
        var recipient = java.util.Objects.requireNonNull(Bukkit.getPlayerExact("TitleObserver"));
        var item = player.getInventory().getItemInMainHand().clone();
        player.getInventory().setItemInMainHand(null);
        recipient.getInventory().setHeldItemSlot(0);
        recipient.getInventory().setItem(0, item);
        vouchers.put(recipient.getUniqueId(), item.clone());
      }
      case "voucherfake" -> {
        var item = vouchers.get(player.getUniqueId()).clone();
        item.editMeta(
            meta ->
                meta.getPersistentDataContainer()
                    .set(
                        new org.bukkit.NamespacedKey(titles, "voucher_effect"),
                        org.bukkit.persistence.PersistentDataType.STRING,
                        "gradient"));
        player.getInventory().setItemInMainHand(item);
      }
      case "voucherfull" -> {
        player.getInventory().clear();
        for (int i = 0; i < player.getInventory().getStorageContents().length; i++)
          player
              .getInventory()
              .setItem(i, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE, 64));
      }
      case "emptyinventory" -> player.getInventory().clear();
      case "checkpreview" -> {
        var result = titles.getCommand("titles").tabComplete(player, "title", new String[] {""});
        if (!result.equals(java.util.List.of("preview")))
          throw new IllegalStateException("Unexpected preview-only suggestions: " + result);
      }
      case "checkpermissions" -> {
        var api = Bukkit.getServicesManager().load(net.luckperms.api.LuckPerms.class);
        var user =
            java.util.Objects.requireNonNull(api.getUserManager().getUser(player.getUniqueId()));
        var options = api.getContextManager().getQueryOptions(player);
        var group = java.util.Objects.requireNonNull(api.getGroupManager().getGroup("default"));
        if (player.isOp()
            || !player.hasPermission("seasonaltitles.use")
            || !user.getCachedData()
                .getPermissionData(options)
                .checkPermission("seasonaltitles.use")
                .asBoolean()
            || user.getNodes().stream().anyMatch(n -> n.getKey().equals("seasonaltitles.use"))
            || user.getInheritedGroups(options).stream()
                .noneMatch(g -> g.getName().equals("default"))
            || group.getNodes().stream()
                .noneMatch(
                    n ->
                        n.getKey().equals("seasonaltitles.use")
                            && n.getValue()
                            && n.getContexts().isEmpty()))
          throw new IllegalStateException(
              "Expected global inherited default-group use permission without OP or user override");
      }
      case "blockcommand" -> blocked.add(player.getUniqueId());
      case "allowcommand" -> blocked.remove(player.getUniqueId());
      case "stats" -> {
        ArmorStand previous = stands.remove(player.getUniqueId());
        if (previous != null) previous.remove();
        ArmorStand stand =
            player
                .getWorld()
                .spawn(
                    player.getLocation(),
                    ArmorStand.class,
                    e -> {
                      e.setPersistent(false);
                      e.setMarker(true);
                      e.setInvisible(true);
                      e.setGravity(false);
                    });
        if (!player.addPassenger(stand))
          throw new IllegalStateException("Could not attach fixture");
        stands.put(player.getUniqueId(), stand);
        var board = Bukkit.getScoreboardManager().getNewScoreboard();
        var team = board.registerNewTeam("st_fixture");
        team.prefix(Component.text("[Santa] "));
        // Model a custom nametag plugin suppressing only the vanilla label.
        team.setOption(
            org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
            org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        team.addEntry(player.getName());
        var objective =
            board.registerNewObjective("st_stats", Criteria.DUMMY, Component.text("Stats"));
        objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        objective.getScore(player.getName()).setScore(7);
        player.setScoreboard(board);
      }
      case "check" -> {
        var stand = stands.get(player.getUniqueId());
        var team = player.getScoreboard().getTeam("st_fixture");
        var stats = player.getScoreboard().getObjective("st_stats");
        if (stand == null
            || !stand.isValid()
            || stand.getVehicle() != player
            || team == null
            || !team.prefix().equals(Component.text("[Santa] "))
            || !team.hasEntry(player.getName())
            || team.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY)
                != org.bukkit.scoreboard.Team.OptionStatus.NEVER
            || stats == null
            || stats.getDisplaySlot() != DisplaySlot.BELOW_NAME
            || stats.getScore(player.getName()).getScore() != 7)
          throw new IllegalStateException("Stats/prefix fixture was modified");
      }
      case "clear" -> {
        var stand = stands.remove(player.getUniqueId());
        if (stand != null) stand.remove();
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
      }
      case "hide" -> titles.setTitleHidden(player.getUniqueId(), true);
      case "reveal" -> titles.setTitleHidden(player.getUniqueId(), false);
      default -> throw new IllegalArgumentException("Unknown fixture action");
    }
    sender.sendMessage(Component.text("fixture-ok:" + args[0]));
    return true;
  }

  @Override
  public void onDisable() {
    stands.values().forEach(ArmorStand::remove);
  }
}
