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
    boolean commandTrace,
    String adminPermission,
    Map<String, String> messages,
    AnimationSettings animations,
    Map<String, CustomDefinition> customTitles,
    Map<Tier, String> tierFormats) {
  public static Settings read(FileConfiguration c) {
    Map<String, Tier> ranks = new LinkedHashMap<>();
    List<Tier> order = new ArrayList<>();
    Map<Tier, String> tierFormats = new EnumMap<>(Tier.class);
    var tiers = c.getConfigurationSection("tiers");
    if (tiers == null && !c.isConfigurationSection("ranks")) {
      try (var reader =
          new java.io.InputStreamReader(
              Objects.requireNonNull(Settings.class.getResourceAsStream("/tiers.yml")),
              java.nio.charset.StandardCharsets.UTF_8)) {
        tiers =
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader)
                .getConfigurationSection("tiers");
      } catch (java.io.IOException e) {
        throw new IllegalArgumentException("Cannot read default tiers", e);
      }
    }
    if (tiers != null) {
      if (tiers.getKeys(false).isEmpty())
        throw new IllegalArgumentException("tiers must define at least one stable tier.");
      for (String key : tiers.getKeys(false)) {
        Tier tier = Tier.parse(key);
        if (order.contains(tier))
          throw new IllegalArgumentException("Duplicate tier identity: " + key);
        order.add(tier);
        if (!tiers.isConfigurationSection(key) || !tiers.isList(key + ".groups"))
          throw new IllegalArgumentException("tiers." + key + " must contain a groups list.");
        for (Object value : tiers.getList(key + ".groups", List.of()))
          if (!(value instanceof String))
            throw new IllegalArgumentException(
                "tiers." + key + ".groups must contain group names as strings.");
        for (String group : tiers.getStringList(key + ".groups")) {
          group = group.toLowerCase(Locale.ROOT);
          if (!group.matches("[a-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid LuckPerms group: " + group);
          Tier previous = ranks.putIfAbsent(group, tier);
          if (previous != null && previous != tier)
            throw new IllegalArgumentException(
                "Conflicting group mapping: " + group + " belongs to " + previous + " and " + tier);
        }
        if (tiers.isString(key + ".format"))
          tierFormats.put(tier, tiers.getString(key + ".format"));
      }
    } else {
      var section =
          Objects.requireNonNull(c.getConfigurationSection("ranks"), "Missing tiers configuration");
      for (String key : section.getKeys(false))
        ranks.put(key.toLowerCase(Locale.ROOT), Tier.parse(section.getString(key, "")));
      order.addAll(ranks.values().stream().distinct().toList());
    }
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
    Settings settings =
        new Settings(
            Season.parse(
                c.getString("initial-season.name", "Winter"),
                c.getString("initial-season.year", "2026")),
            Map.copyOf(ranks),
            List.copyOf(order),
            List.copyOf(c.getStringList("suggestions.title-order")),
            c.getString("format", "<color><symbol> <season> <year> • <tier></color>"),
            Map.copyOf(symbols),
            Map.copyOf(colors),
            (float)
                Math.max(
                    number(c, "render.offset", 0.9, -1, 6),
                    number(c, "render.nametag-top", 0.5, 0, 5)
                        + number(c, "render.gap", 0.4, 0.1, 1)),
            (float) number(c, "render.scale", 1, 0.1, 2),
            number(c, "render.distance", 32, 1, 64),
            (int) number(c, "render.interval-ticks", 1, 1, 1),
            (int) number(c, "reconcile.seconds", 60, 10, 3600),
            (int) number(c, "reconcile.batch-size", 16, 1, 100),
            c.getBoolean("visibility.line-of-sight", true),
            c.getBoolean("visibility.show-self", true),
            c.getBoolean("visibility.respect-team-nametag-visibility", false),
            c.getBoolean("diagnostics.command-trace", false),
            c.getString("permissions.admin", "seasonaltitles.admin"),
            Map.copyOf(messages),
            AnimationSettings.read(c),
            definitions(c),
            Map.copyOf(tierFormats));
    for (CustomDefinition definition : settings.customTitles().values())
      settings.text(definition.title());
    for (Tier tier : Tier.values()) settings.text(Title.seasonal(settings.initial(), tier));
    return settings;
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
        : net.kyori.adventure.text.format.NamedTextColor.NAMES.value(
            color.toLowerCase(Locale.ROOT));
  }

  private static Map<String, CustomDefinition> definitions(FileConfiguration c) {
    Map<String, CustomDefinition> definitions = new LinkedHashMap<>();
    var section = c.getConfigurationSection("custom-titles");
    if (section != null)
      for (String id : section.getKeys(false)) {
        if (!section.isString(id + ".display"))
          throw new IllegalArgumentException("custom-titles." + id + " needs display text.");
        Title title = Title.custom(id, section.getString(id + ".display"));
        String effect =
            section.getString(id + ".default-animation", "static").toLowerCase(Locale.ROOT);
        AnimationEffect.parse(effect);
        Set<String> allowed = new HashSet<>();
        for (String entry : section.getStringList(id + ".allowed-animations"))
          allowed.add(AnimationEffect.parse(entry).name().toLowerCase(Locale.ROOT));
        definitions.put(
            title.id(),
            new CustomDefinition(
                title, effect, allowed, section.getString(id + ".permission", "")));
      }
    return Map.copyOf(definitions);
  }

  private static String configuredColor(FileConfiguration c, Tier tier) {
    var tiers = c.getConfigurationSection("tiers");
    if (tiers != null)
      for (String key : tiers.getKeys(false))
        if (key.equalsIgnoreCase(tier.name()) && tiers.isString(key + ".color"))
          return tiers.getString(key + ".color");
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
            tierFormats
                .getOrDefault(title.tier(), format)
                .replace("<color>", "<color:" + colors.get(title.tier()) + ">"),
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
