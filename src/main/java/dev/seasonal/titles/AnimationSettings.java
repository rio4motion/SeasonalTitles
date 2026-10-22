package dev.seasonal.titles;

import java.util.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public record AnimationSettings(
    boolean enabled,
    String defaultEffect,
    int interval,
    double distance,
    Map<String, Effect> effects,
    Set<String> staticOnlyTitles) {
  public record Effect(boolean enabled, double speed, List<String> colors) {
    public Effect {
      colors = List.copyOf(colors);
    }
  }

  public static AnimationSettings read(FileConfiguration c) {
    int interval = c.getInt("animations.interval-ticks", 1);
    double distance = c.getDouble("animations.visibility-distance", 32);
    if (interval < 1
        || interval > 200
        || !Double.isFinite(distance)
        || distance < 1
        || distance > 64)
      throw new IllegalArgumentException(
          "Animation interval must be 1–200 ticks and distance 1–64.");
    String selected = c.getString("animations.default", "static").toLowerCase(Locale.ROOT);
    AnimationEffect.parse(selected);
    Map<String, Effect> effects = new HashMap<>();
    for (AnimationEffect effect : AnimationEffect.values()) {
      String id = effect.name().toLowerCase(Locale.ROOT), path = "animations.effects." + id;
      double speed = c.getDouble(path + ".speed", 1);
      if (!Double.isFinite(speed) || speed <= 0 || speed > 10)
        throw new IllegalArgumentException(path + ".speed must be >0 and <=10");
      List<String> colors = c.getStringList(path + ".colors");
      if (colors.size() > 16)
        throw new IllegalArgumentException(path + ".colors: maximum 16 colors");
      for (String color : colors)
        if (net.kyori.adventure.text.format.TextColor.fromHexString(color) == null
            && net.kyori.adventure.text.format.NamedTextColor.NAMES.value(
                    color.toLowerCase(Locale.ROOT))
                == null) throw new IllegalArgumentException("Invalid animation color: " + color);
      effects.put(id, new Effect(c.getBoolean(path + ".enabled", true), speed, colors));
    }
    var section = c.getConfigurationSection("animations.effects");
    if (section != null) for (String key : section.getKeys(false)) AnimationEffect.parse(key);
    return new AnimationSettings(
        c.getBoolean("animations.enabled", true),
        selected,
        interval,
        distance,
        Map.copyOf(effects),
        Set.copyOf(c.getStringList("animations.static-only-titles")));
  }

  public boolean allowed(Player player, String id, CustomDefinition definition) {
    return allowed(player, id, definition, null);
  }

  public boolean allowed(Player player, String id, CustomDefinition definition, String titleId) {
    return allowed(player, id, definition, titleId, Set.of());
  }

  public boolean allowed(
      Player player, String id, CustomDefinition definition, String titleId, Set<String> unlocks) {
    if (id.equals("static")) return true;
    Effect effect = effects.get(id);
    return !staticOnlyTitles.contains(titleId == null ? "" : titleId)
        && enabled
        && effect != null
        && effect.enabled()
        && (definition == null
            || definition.allowedAnimations().isEmpty()
            || definition.allowedAnimations().contains(id))
        // LuckPerms/Bukkit resolves wildcard inheritance here; a separate wildcard OR
        // would incorrectly override a specific false permission.
        && (player.isOp()
            || unlocks.contains(id)
            || player.hasPermission("seasonaltitles.animation." + id));
  }

  public String effective(
      Player owner, PlayerPreferences preferences, CustomDefinition definition) {
    return effective(owner, preferences, definition, null);
  }

  public String effective(
      Player owner, PlayerPreferences preferences, CustomDefinition definition, String titleId) {
    return effective(owner, preferences, definition, titleId, Set.of());
  }

  public String effective(
      Player owner,
      PlayerPreferences preferences,
      CustomDefinition definition,
      String titleId,
      Set<String> unlocks) {
    String requested =
        preferences.animation().equals("default")
            ? (definition == null ? defaultEffect : definition.defaultAnimation())
            : preferences.animation();
    return allowed(owner, requested, definition, titleId, unlocks) ? requested : "static";
  }
}
