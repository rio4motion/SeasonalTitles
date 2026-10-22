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
  private volatile Settings settings;
  private volatile Snapshot state;
  private Database database;
  private LuckPermsBridge ranks;
  private TitleRenderer renderer;
  private ConfigurationFiles files;
  private AnimationVouchers vouchers;
  private org.bukkit.scheduler.BukkitTask animationTask;
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
      files = new ConfigurationFiles(getDataFolder().toPath());
      settings = Settings.read(files.load());
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
    vouchers = new AnimationVouchers(this);
    var commands = new TitleCommands(this);
    for (String name : List.of("titles", "sti")) {
      var command = Objects.requireNonNull(getCommand(name));
      command.setExecutor(commands);
      command.setTabCompleter(commands);
    }
    CommandPermissions.configure(this);
    getServer().getPluginManager().registerEvents(new CommandPermissions(this), this);
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getPluginManager().registerEvents(new TitleSuggestions(this), this);
    getServer().getPluginManager().registerEvents(vouchers, this);
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
                      try {
                        exportCustoms();
                        Settings imported = Settings.read(files.load());
                        database
                            .submit(s -> s.definitions(imported.customTitles().values()))
                            .whenComplete(
                                (ignored, problem) ->
                                    main(
                                        () -> {
                                          if (problem != null) {
                                            failure(problem);
                                            getServer().getPluginManager().disablePlugin(this);
                                            return;
                                          }
                                          settings = imported;
                                          restartAnimations();
                                          ranks.warnMappings(imported);
                                          Bukkit.getOnlinePlayers()
                                              .forEach(
                                                  p -> {
                                                    ranks.queue(p.getUniqueId());
                                                    vouchers.claim(p.getUniqueId(), false);
                                                  });
                                          getLogger().info("Ready: " + state.season().label());
                                        }));
                      } catch (Exception problem) {
                        failure(problem);
                        getServer().getPluginManager().disablePlugin(this);
                      }
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
    if (animationTask != null) animationTask.cancel();
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
    List<String> lines = new ArrayList<>(renderer.diagnostics(owner, viewer, tick));
    lines.addAll(new CommandPermissions(this).diagnostics(owner));
    return lines;
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

  public AnimationVouchers vouchers() {
    return vouchers;
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
    var definition = title == null ? null : settings.customTitles().get(title.id());
    if (definition != null
        && !definition.permission().isBlank()
        && !player.isOp()
        && !player.hasPermission(definition.permission())) return false;
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

  private void restartAnimations() {
    if (animationTask != null) animationTask.cancel();
    int interval = settings.animations().interval();
    animationTask = Bukkit.getScheduler().runTaskTimer(this, renderer::animate, interval, interval);
  }

  public java.util.concurrent.CompletableFuture<Void> reloadSafe() {
    try {
      Settings candidate = Settings.read(files.load());
      var done = new java.util.concurrent.CompletableFuture<Void>();
      database
          .submit(s -> s.definitions(candidate.customTitles().values()))
          .whenComplete(
              (v, error) ->
                  main(
                      () -> {
                        if (error != null) {
                          done.completeExceptionally(error);
                          return;
                        }
                        settings = candidate;
                        CommandPermissions.configure(this);
                        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
                        renderer.clear();
                        restartAnimations();
                        ranks.queueEnrolled();
                        ranks.warnMappings(candidate);
                        done.complete(null);
                      }));
      return done;
    } catch (Exception e) {
      throw new IllegalArgumentException("Configuration reload failed: " + e.getMessage(), e);
    }
  }

  private void exportCustoms() throws Exception {
    var file = files.folder().resolve("titles.yml");
    var yaml = ConfigurationFiles.read(file);
    boolean changed = false;
    for (Title title : state.titles().values())
      if (title.customText() != null
          && !yaml.contains("custom-titles." + title.id().substring(7))) {
        yaml.set("custom-titles." + title.id().substring(7) + ".display", title.customText());
        yaml.set("custom-titles." + title.id().substring(7) + ".default-animation", "static");
        changed = true;
      }
    if (changed) ConfigurationFiles.write(file, yaml);
  }

  public java.util.concurrent.CompletableFuture<Void> definition(
      String id, String display, boolean delete) {
    try {
      id = id.startsWith("custom:") ? id.substring(7) : id;
      Title validated = Title.custom(id, delete ? "deleted" : display);
      var path = files.folder().resolve("titles.yml");
      var yaml = ConfigurationFiles.read(path);
      yaml.set("custom-titles." + id + (delete ? "" : ".display"), delete ? null : display);
      if (!delete && !yaml.contains("custom-titles." + id + ".default-animation"))
        yaml.set("custom-titles." + id + ".default-animation", "static");
      var candidate = files.load();
      candidate.set("custom-titles", yaml.getConfigurationSection("custom-titles"));
      Settings.read(candidate);
      ConfigurationFiles.write(path, yaml);
      if (!delete) return reloadSafe();
      return database
          .submit(s -> s.delete(validated.id(), true))
          .thenCompose(
              v -> {
                var done = new java.util.concurrent.CompletableFuture<Void>();
                main(
                    () -> {
                      try {
                        reloadSafe()
                            .whenComplete(
                                (ignored, error) -> {
                                  if (error == null) done.complete(null);
                                  else done.completeExceptionally(error);
                                });
                      } catch (Exception e) {
                        done.completeExceptionally(e);
                      }
                    });
                return done;
              });
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot save title definition: " + e.getMessage(), e);
    }
  }

  public java.util.concurrent.CompletableFuture<Void> rankMapping(
      Tier tier, String action, String group) {
    try {
      group = group.toLowerCase(Locale.ROOT);
      if (!group.matches("[a-z0-9_-]{1,64}"))
        throw new IllegalArgumentException("Invalid LuckPerms group.");
      var yaml = ConfigurationFiles.read(files.tierFile());
      String tierKey =
          yaml.getConfigurationSection("tiers").getKeys(false).stream()
              .filter(name -> name.equalsIgnoreCase(tier.name()))
              .findFirst()
              .orElse(tier.name().toLowerCase(Locale.ROOT));
      String key = "tiers." + tierKey + ".groups";
      var groups = new ArrayList<>(yaml.getStringList(key));
      if (action.equals("set")) groups.clear();
      if (action.equals("remove")) groups.removeIf(group::equalsIgnoreCase);
      else if (groups.stream().noneMatch(group::equalsIgnoreCase)) groups.add(group);
      yaml.set(key, groups);
      var candidate = files.load();
      candidate.set("tiers", yaml.getConfigurationSection("tiers"));
      Settings.read(candidate);
      ConfigurationFiles.write(files.tierFile(), yaml);
      return reloadSafe();
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot update rank mapping: " + e.getMessage(), e);
    }
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
