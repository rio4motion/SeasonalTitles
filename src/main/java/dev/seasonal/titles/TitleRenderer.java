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
        // addPassenger appends our display; do not eject or replace another plugin's passengers.
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
        boolean visible = visibilityReason(viewer, owner, preview).equals("visible");
        if (visible && shown.add(viewer.getUniqueId())) viewer.showEntity(plugin, display);
        else if (!visible && shown.remove(viewer.getUniqueId())) viewer.hideEntity(plugin, display);
      }
    }
  }

  static boolean safeOwner(Player p) {
    return ownerBlockReason(p) == null;
  }

  static String ownerBlockReason(Player p) {
    if (!p.isOnline()) return "offline";
    if (p.isDead()) return "dead";
    if (p.isInvisible() || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY))
      return "invisible";
    if (p.getMetadata("vanished").stream().anyMatch(org.bukkit.metadata.MetadataValue::asBoolean))
      return "vanished-metadata";
    if (p.getGameMode() == GameMode.SPECTATOR) return "spectator";
    if (p.isSneaking()) return "sneaking";
    if (p.getPose() != Pose.STANDING) return "pose:" + p.getPose();
    if (p.isGliding()) return "gliding";
    if (p.isSwimming()) return "swimming";
    if (p.isInsideVehicle()) return "riding";
    // Creative / permission-based flight keeps STANDING pose and is supported.
    return null;
  }

  /**
   * Viewer policy shared by actual rendering and on-demand diagnostics. Owner state is checked
   * first.
   */
  String visibilityReason(Player viewer, Player owner, boolean preview) {
    boolean self = viewer.getUniqueId().equals(owner.getUniqueId());
    if (self && !plugin.settings().showSelf() && !preview) return "self-disabled";
    if (plugin.titleHidden(owner.getUniqueId())) return "hidden-by-integration";
    if (viewer.getWorld() != owner.getWorld()) return "different-world";
    if (!viewer.canSee(owner) || !owner.isVisibleByDefault()) return "player-hidden";
    if (viewer.getLocation().distanceSquared(owner.getLocation())
        > plugin.settings().distance() * plugin.settings().distance()) return "out-of-range";
    if (!teamVisible(viewer, owner)) return "team-name-hidden";
    // There is no server-side F5 camera state. A ray from your eyes to your own body is not
    // a meaningful visibility test. Keep all concealment gates above; the client depth-tests text.
    if (self || !plugin.settings().lineOfSight()) return "visible";
    if (!viewer.hasLineOfSight(owner)) return "body-occluded";
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
                == null
        ? "visible"
        : "title-occluded";
  }

  /** Read-only snapshot: no entity changes and no per-tick log spam. Call on the server thread. */
  public List<String> diagnostics(Player owner, Player viewer, long tick) {
    UUID id = owner.getUniqueId();
    String equipped = plugin.state().equipped().get(id);
    String preview = plugin.previews().active(id, tick);
    String selected = preview == null ? equipped : preview;
    Title title = selected == null ? null : plugin.state().titles().get(selected);
    String blocked = ownerBlockReason(owner);
    TextDisplay display = displays.get(id);
    List<String> lines = new ArrayList<>();
    lines.add(
        "Selection: equipped="
            + equipped
            + ", preview="
            + preview
            + ", catalog="
            + (title != null)
            + ", access="
            + (title != null && plugin.canEquip(owner, title)));
    lines.add(
        "Owner: "
            + owner.getName()
            + ", state="
            + (blocked == null ? "supported" : blocked)
            + ", pose="
            + owner.getPose()
            + ", flying="
            + owner.isFlying());
    lines.add(
        "Passengers: "
            + owner.getPassengers().stream()
                .map(e -> e.getType() + "#" + e.getEntityId())
                .toList());
    if (display == null)
      lines.add("Display: absent (no selection/access, suppressed state, or attachment failed)");
    else {
      lines.add(
          "Display: #"
              + display.getEntityId()
              + ", valid="
              + display.isValid()
              + ", attached="
              + owner.equals(display.getVehicle())
              + ", tracked-by="
              + display.getTrackedBy().size());
      lines.add(
          "Layout: translation="
              + display.getTransformation().getTranslation()
              + ", scale="
              + display.getTransformation().getScale()
              + ", billboard="
              + display.getBillboard()
              + ", opacity="
              + Byte.toUnsignedInt(display.getTextOpacity())
              + ", see-through="
              + display.isSeeThrough());
    }
    if (viewer != null)
      lines.add(
          "Viewer: "
              + viewer.getName()
              + ", policy="
              + (blocked == null ? visibilityReason(viewer, owner, preview != null) : blocked)
              + ", show-requested="
              + viewers.getOrDefault(id, Set.of()).contains(viewer.getUniqueId())
              + ", can-see-display="
              + (display != null && viewer.canSee(display))
              + ", tracked="
              + (display != null && display.getTrackedBy().contains(viewer)));
    return lines;
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
