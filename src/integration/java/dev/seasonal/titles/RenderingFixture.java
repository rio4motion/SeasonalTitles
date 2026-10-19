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
public final class RenderingFixture extends JavaPlugin {
  private final Map<UUID, ArmorStand> stands = new HashMap<>();

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.isOp() || args.length != 2) return false;
    Player player = Bukkit.getPlayerExact(args[1]);
    if (player == null) throw new IllegalArgumentException("Fixture player is offline");
    SeasonalTitlesPlugin titles =
        (SeasonalTitlesPlugin) Bukkit.getPluginManager().getPlugin("SeasonalTitles");
    switch (args[0]) {
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
