package dev.seasonal.titles;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated immutable settings. Seasonal state is read from SQLite after first boot. */
public record Settings(
    Season initial,
    Map<String, Tier> ranks,
    List<Tier> tierOrder,
    List<String> titleOrder,
    String format,
    Map<String, String> symbols,
    Map<Tier, String> colors,
    float offset,
    float scale,
    double distance,
    int interval,
    int reconcileSeconds,
    int batchSize,
    boolean lineOfSight,
    boolean showSelf,
    boolean respectTeamNameVisibility,
    String adminPermission,
    Map<String, String> messages) {
  public static Settings read(FileConfiguration c) {
    Map<String, Tier> ranks = new LinkedHashMap<>();
    var section =
        Objects.requireNonNull(c.getConfigurationSection("ranks"), "Missing ranks configuration");
    for (String key : section.getKeys(false))
      ranks.put(key.toLowerCase(Locale.ROOT), Tier.parse(section.getString(key, "")));
    Map<String, String> symbols = new HashMap<>(), messages = new HashMap<>();
    Map<Tier, String> colors = new EnumMap<>(Tier.class);
    for (Season.Name season : Season.Name.values())
      symbols.put(
          season.name(), c.getString("symbols." + season.name().toLowerCase(Locale.ROOT), ""));
    for (Tier t : Tier.values()) {
      String color = configuredColor(c, t);
      if (parseColor(color) == null)
        throw new IllegalArgumentException("Invalid tier color: " + color);
      colors.put(t, color);
    }
    var msg = c.getConfigurationSection("messages");
    if (msg != null)
      for (String key : msg.getKeys(false)) messages.put(key, msg.getString(key, ""));
    return new Settings(
        Season.parse(
            c.getString("initial-season.name", "Winter"),
            c.getString("initial-season.year", "2026")),
        Map.copyOf(ranks),
        ranks.values().stream().distinct().toList(),
        List.copyOf(c.getStringList("suggestions.title-order")),
        c.getString("format", "<color><symbol> <season> <year> • <tier></color>"),
        Map.copyOf(symbols),
        Map.copyOf(colors),
        (float)
            Math.max(
                number(c, "render.offset", 0.9, -1, 6),
                number(c, "render.nametag-top", 0.5, 0, 5) + number(c, "render.gap", 0.4, 0.1, 1)),
        (float) number(c, "render.scale", 1, 0.1, 2),
        number(c, "render.distance", 32, 1, 64),
        (int) number(c, "render.interval-ticks", 1, 1, 1),
        (int) number(c, "reconcile.seconds", 60, 10, 3600),
        (int) number(c, "reconcile.batch-size", 16, 1, 100),
        c.getBoolean("visibility.line-of-sight", true),
        c.getBoolean("visibility.show-self", true),
        c.getBoolean("visibility.respect-team-nametag-visibility", false),
        c.getString("permissions.admin", "seasonaltitles.admin"),
        Map.copyOf(messages));
  }

  /** Color names are case-insensitive; unknown independent labels use a readable neutral color. */
  static String automaticColor(String tier) {
    return switch (tier.toLowerCase(Locale.ROOT)) {
      case "bronze" -> "#CD7F32";
      case "silver" -> "#C0C0C0";
      case "gold" -> "#FFD700";
      case "platinum" -> "#80E5E5";
      case "diamond" -> "#55FFFF";
      case "master" -> "#AA55FF";
      case "legend" -> "#FFAA00";
      default -> "#FFFFFF";
    };
  }

  private static net.kyori.adventure.text.format.TextColor parseColor(String color) {
    return color.startsWith("#")
        ? net.kyori.adventure.text.format.TextColor.fromHexString(color)
        : net.kyori.adventure.text.format.NamedTextColor.NAMES.value(color);
  }

  private static String configuredColor(FileConfiguration c, Tier tier) {
    var colors = c.getConfigurationSection("colors");
    if (colors != null)
      for (String key : colors.getKeys(false))
        if (key.equalsIgnoreCase(tier.name()))
          return colors.getString(key, automaticColor(tier.name()));
    return automaticColor(tier.name());
  }

  private static double number(
      FileConfiguration c, String path, double fallback, double min, double max) {
    double n = c.getDouble(path, fallback);
    if (!Double.isFinite(n) || n < min || n > max)
      throw new IllegalArgumentException(path + " must be " + min + "–" + max);
    return n;
  }

  public Component text(Title title) {
    MiniMessage mm = MiniMessage.miniMessage();
    if (title.customText() != null) {
      Component component = mm.deserialize(title.customText());
      String plain =
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(component);
      if (plain.isBlank() || plain.contains("\n") || plain.contains("\r"))
        throw new IllegalArgumentException("Custom titles must render as one nonempty line.");
      return component.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE);
    }
    return mm.deserialize(
            format.replace("<color>", "<color:" + colors.get(title.tier()) + ">"),
            Placeholder.unparsed("symbol", symbols.get(title.season().name().name())),
            Placeholder.unparsed("season", title.season().label().split(" ")[0]),
            Placeholder.unparsed("year", Integer.toString(title.season().year())),
            Placeholder.unparsed("tier", title.tier().label()))
        .colorIfAbsent(parseColor(colors.get(title.tier())));
  }

  public Component message(String key, String fallback) {
    return MiniMessage.miniMessage().deserialize(messages.getOrDefault(key, fallback));
  }
}
