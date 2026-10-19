package dev.seasonal.titles;

import java.util.*;
import java.util.logging.Level;
import net.luckperms.api.LuckPerms;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SeasonalTitlesPlugin extends JavaPlugin implements Listener {
  private Settings settings;
  private Snapshot state;
  private Database database;
  private LuckPermsBridge ranks;
  private TitleRenderer renderer;
  private final Preview previews = new Preview();
  private final Set<UUID> hiddenTitles = new HashSet<>();
  private long tick;
  private volatile boolean stopping;

  public void onEnable() {
    stopping = false;
    state = null;
    tick = 0;
    previews.clearAll();
    saveDefaultConfig();
    try {
      settings = Settings.read(getConfig());
    } catch (Exception e) {
      getLogger().log(Level.SEVERE, "Invalid configuration", e);
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    renderer = new TitleRenderer(this);
    Bukkit.getWorlds().forEach(renderer::cleanup);
    var registration = getServer().getServicesManager().getRegistration(LuckPerms.class);
    if (registration == null) {
      getLogger().severe("LuckPerms API unavailable");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    ranks = new LuckPermsBridge(this, registration.getProvider());
    database = new Database(s -> main(() -> state = s));
    var commands = new TitleCommands(this);
    for (String name : List.of("titles", "sti")) {
      var command = Objects.requireNonNull(getCommand(name));
      command.setExecutor(commands);
      command.setTabCompleter(commands);
    }
    getServer().getPluginManager().registerEvents(this, this);
    database
        .open(getDataFolder().toPath().resolve("titles.db"), settings.initial())
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      if (error != null) {
                        failure(error);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                      }
                      Bukkit.getOnlinePlayers().forEach(p -> ranks.queue(p.getUniqueId()));
                      getLogger().info("Ready: " + state.season().label());
                    }));
    getServer()
        .getScheduler()
        .runTaskTimer(
            this,
            () -> {
              tick++;
              if (state == null) return;
              renderer.tick(tick);
              if (tick % 20 == 0) ranks.tick();
              if (tick % (settings.reconcileSeconds() * 20L) == 0) ranks.queueEnrolled();
            },
            1,
            1);
  }

  public void onDisable() {
    stopping = true;
    if (ranks != null) ranks.close();
    if (renderer != null) renderer.clear();
    if (database != null)
      try {
        database.close();
      } catch (Exception e) {
        getLogger().log(Level.SEVERE, "Database shutdown failed", e);
      }
  }

  public void main(Runnable task) {
    if (stopping) return;
    // Always queue, preserving publication order between SQLite snapshots and completions.
    try {
      Bukkit.getScheduler()
          .runTask(
              this,
              () -> {
                if (!stopping) task.run();
              });
    } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
      /* Server disabled plugin during callback. */
    }
  }

  public void failure(Throwable error) {
    getLogger().log(Level.SEVERE, "SeasonalTitles operation failed", error);
  }

  public List<String> renderingDiagnostics(Player owner, Player viewer) {
    return renderer.diagnostics(owner, viewer, tick);
  }

  public Settings settings() {
    return settings;
  }

  public Snapshot state() {
    return state;
  }

  public Database database() {
    return database;
  }

  public LuckPermsBridge ranks() {
    return ranks;
  }

  public Preview previews() {
    return previews;
  }

  public long tick() {
    return tick;
  }

  public boolean canEquip(Player player, Title title) {
    Set<String> owned = state.owned(player.getUniqueId());
    Set<String> groups =
        player.isOp() || (title != null && owned.contains(title.id()))
            ? Set.of()
            : ranks.groups(player.getUniqueId());
    return Access.canEquip(player.isOp(), owned, groups, state.roles(), title);
  }

  /** Main-thread integration hook for vanish plugins that only manipulate packets. */
  public void setTitleHidden(UUID owner, boolean hidden) {
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Call on the server thread");
    if (hidden) {
      hiddenTitles.add(owner);
      renderer.remove(owner);
    } else hiddenTitles.remove(owner);
  }

  public boolean titleHidden(UUID owner) {
    return hiddenTitles.contains(owner);
  }

  public void reloadSafe() {
    reloadConfig();
    Settings candidate = Settings.read(getConfig());
    settings = candidate;
    renderer.clear();
    ranks.queueEnrolled();
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    ranks.queue(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    renderer.disconnect(e.getPlayer().getUniqueId());
    previews.clear(e.getPlayer().getUniqueId());
  }

  // Remove passengers before teleport processing: passengers can otherwise make teleport fail.
  @EventHandler(priority = EventPriority.LOWEST)
  public void teleport(PlayerTeleportEvent e) {
    renderer.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    renderer.remove(e.getPlayer().getUniqueId());
    ranks.queue(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    renderer.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    renderer.remove(e.getEntity().getUniqueId());
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void sneak(PlayerToggleSneakEvent e) {
    if (e.isSneaking()) renderer.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void mode(PlayerGameModeChangeEvent e) {
    renderer.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void potion(EntityPotionEffectEvent e) {
    if (e.getEntity() instanceof Player p) renderer.remove(p.getUniqueId());
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void pose(EntityPoseChangeEvent e) {
    if (e.getEntity() instanceof Player p) renderer.remove(p.getUniqueId());
  }

  @EventHandler
  public void hidden(PlayerHideEntityEvent e) {
    if (e.getEntity() instanceof Player p) renderer.hideFor(e.getPlayer(), p.getUniqueId());
  }

  @EventHandler
  public void worldLoad(WorldLoadEvent e) {
    renderer.cleanup(e.getWorld());
  }

  @EventHandler
  public void chunkLoad(ChunkLoadEvent e) {
    var key = new NamespacedKey(this, "display");
    for (var entity : e.getChunk().getEntities())
      if (entity.getPersistentDataContainer().has(key)) entity.remove();
  }
}
