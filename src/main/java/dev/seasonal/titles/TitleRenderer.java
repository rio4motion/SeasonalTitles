package dev.seasonal.titles;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Main-thread only. Movement uses the client passenger graph; never per-tick teleportation. */
public final class TitleRenderer {
  private final SeasonalTitlesPlugin plugin;
  private final NamespacedKey marker;
  private final Map<Title, Component> textCache = new HashMap<>();
  private final Map<UUID, TextDisplay> displays = new HashMap<>();
  private final Map<UUID, Set<UUID>> viewers = new HashMap<>();

  public TitleRenderer(SeasonalTitlesPlugin plugin) {
    this.plugin = plugin;
    marker = new NamespacedKey(plugin, "display");
  }

  public void cleanup(World world) {
    for (TextDisplay e : world.getEntitiesByClass(TextDisplay.class))
      if (e.getPersistentDataContainer().has(marker)) e.remove();
  }

  public void tick(long tick) {
    for (Player owner : Bukkit.getOnlinePlayers()) {
      UUID id = owner.getUniqueId();
      String selected = plugin.previews().displayed(id, tick, plugin.state().equipped().get(id));
      Title title = selected == null ? null : plugin.state().titles().get(selected);
      boolean preview = plugin.previews().active(id, tick) != null;
      if (title == null || (!preview && !plugin.canEquip(owner, title)) || !safeOwner(owner)) {
        remove(id);
        continue;
      }
      TextDisplay display = displays.get(id);
      if (display != null && (!display.isValid() || display.getVehicle() != owner)) {
        remove(id);
        display = null;
      }
      if (display == null) {
        // Never alter existing passengers, which may belong to other plugins.
        if (!owner.getPassengers().isEmpty()) continue;
        display =
            owner
                .getWorld()
                .spawn(
                    owner.getLocation(),
                    TextDisplay.class,
                    e -> {
                      e.setVisibleByDefault(false);
                      e.setPersistent(false);
                      e.setInvulnerable(true);
                      e.setGravity(false);
                      e.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
                      e.setBillboard(Display.Billboard.CENTER);
                      e.setSeeThrough(false);
                      e.setShadowed(true);
                      e.setDefaultBackground(false);
                      e.setBackgroundColor(Color.fromARGB(0));
                      e.setLineWidth(1000);
                      e.setViewRange((float) (plugin.settings().distance() / 64.0));
                      e.setInterpolationDuration(0);
                      e.setTeleportDuration(0);
                      e.setTransformation(
                          new Transformation(
                              new Vector3f(0, plugin.settings().offset(), 0),
                              new Quaternionf(),
                              new Vector3f(plugin.settings().scale()),
                              new Quaternionf()));
                      e.text(text(title));
                    });
        if (!owner.addPassenger(display)) {
          display.remove();
          continue;
        }
        displays.put(id, display);
        viewers.put(id, new HashSet<>());
      }
      Component text = text(title);
      if (!display.text().equals(text)) display.text(text);
      Set<UUID> shown = viewers.get(id);
      for (Player viewer : Bukkit.getOnlinePlayers()) {
        boolean visible = (viewer != owner || preview) && allowed(viewer, owner, display);
        if (visible && shown.add(viewer.getUniqueId())) viewer.showEntity(plugin, display);
        else if (!visible && shown.remove(viewer.getUniqueId())) viewer.hideEntity(plugin, display);
      }
    }
  }

  static boolean safeOwner(Player p) {
    return p.isOnline()
        && !p.isDead()
        && !p.isInvisible()
        && !p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)
        && p.getMetadata("vanished").stream()
            .noneMatch(org.bukkit.metadata.MetadataValue::asBoolean)
        && p.getGameMode() != GameMode.SPECTATOR
        && !p.isSneaking()
        && p.getPose() == Pose.STANDING
        && !p.isGliding()
        && !p.isSwimming()
        && !p.isInsideVehicle();
  }

  private boolean allowed(Player viewer, Player owner, TextDisplay display) {
    if (plugin.titleHidden(owner.getUniqueId())
        || viewer.getWorld() != owner.getWorld()
        || !viewer.canSee(owner)
        || !owner.isVisibleByDefault()) return false;
    if (viewer.getLocation().distanceSquared(owner.getLocation())
        > plugin.settings().distance() * plugin.settings().distance()) return false;
    if (!teamVisible(viewer, owner)) return false;
    if (!plugin.settings().lineOfSight()) return true;
    if (!viewer.hasLineOfSight(owner)) return false;
    // Also check the title's height: a wall hiding the body must not leave a floating label
    // visible.
    var start = viewer.getEyeLocation();
    var target =
        owner.getLocation().add(0, owner.getHeight() + plugin.settings().offset() + 0.5, 0);
    var direction = target.toVector().subtract(start.toVector());
    double distance = direction.length();
    return distance < 0.01
        || viewer
                .getWorld()
                .rayTraceBlocks(
                    start, direction.normalize(), distance, FluidCollisionMode.NEVER, true)
            == null;
  }

  static boolean teamVisible(Player viewer, Player owner) {
    Team team = viewer.getScoreboard().getEntryTeam(owner.getName());
    if (team == null) return true;
    Team viewerTeam = viewer.getScoreboard().getEntryTeam(viewer.getName());
    boolean same = team.equals(viewerTeam);
    return switch (team.getOption(Team.Option.NAME_TAG_VISIBILITY)) {
      case ALWAYS -> true;
      case NEVER -> false;
      case FOR_OTHER_TEAMS -> !same;
      case FOR_OWN_TEAM -> same;
    };
  }

  public void remove(UUID owner) {
    TextDisplay e = displays.remove(owner);
    if (e != null) e.remove();
    viewers.remove(owner);
  }

  public void disconnect(UUID player) {
    remove(player);
    viewers.values().forEach(s -> s.remove(player));
  }

  public void hideFor(Player viewer, UUID owner) {
    TextDisplay display = displays.get(owner);
    if (display != null) {
      viewer.hideEntity(plugin, display);
      viewers.get(owner).remove(viewer.getUniqueId());
    }
  }

  private Component text(Title title) {
    if (textCache.size() > 1024) textCache.clear();
    return textCache.computeIfAbsent(title, plugin.settings()::text);
  }

  public void clear() {
    new ArrayList<>(displays.keySet()).forEach(this::remove);
    textCache.clear();
  }
}
