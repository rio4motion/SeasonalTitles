package dev.seasonal.titles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Loads the plugin's split configuration files while preserving settings in config.yml. */
public final class ConfigurationFiles {
  private static final List<String> FILES =
      List.of("tiers.yml", "titles.yml", "animations.yml", "messages.yml");

  private final Path folder;

  public ConfigurationFiles(Path folder) {
    this.folder = Objects.requireNonNull(folder, "folder");
  }

  public Path folder() {
    return folder;
  }

  public Path tierFile() {
    return folder.resolve("tiers.yml");
  }

  /** Loads config.yml and overlays the split files onto their matching root sections. */
  public YamlConfiguration load() throws Exception {
    Files.createDirectories(folder);
    Path configPath = folder.resolve("config.yml");
    if (!Files.isRegularFile(configPath)) throw new IOException("Missing config.yml in " + folder);
    YamlConfiguration raw = read(configPath);

    for (String file : FILES) {
      String root = rootFor(file);
      Path path = folder.resolve(file);
      YamlConfiguration split;
      if (Files.exists(path)) {
        split = read(path);
      } else {
        split = migratedOrDefault(file, raw);
        if (file.equals("tiers.yml") && raw.getConfigurationSection("ranks") != null)
          backupLegacyConfig(configPath);
        if (file.equals("messages.yml")) split = mergeMessages(defaults(file), split);
        if (file.equals("animations.yml")) {
          // Legacy inline settings have no split file to back up; config.yml stays untouched.
          if (!split.contains("animations.timing-version")
              && split.getInt("animations.interval-ticks", 1) == 10) {
            split.set("animations.interval-ticks", 1);
          }
          split = mergeRoot(defaults(file), split, "animations");
        }
        write(path, split);
      }

      if (file.equals("animations.yml")
          && !split.contains("animations.timing-version")
          && split.getInt("animations.interval-ticks", 1) == 10) {
        AnimationSettings.read(split); // Never rewrite an invalid animation configuration.
        Path backup = path.resolveSibling("animations.yml.pre-2.1.bak");
        if (!Files.exists(backup)) Files.copy(path, backup);
        split.set("animations.interval-ticks", 1);
        split.set("animations.timing-version", 2);
        write(path, split);
      }
      if (split.contains(root) && !split.isConfigurationSection(root))
        throw new IllegalArgumentException(file + ": " + root + " must be a YAML section.");
      // Message defaults are supplied in memory too, without changing an existing user's file.
      if (file.equals("messages.yml")) split = mergeMessages(defaults(file), split);
      if (file.equals("animations.yml")) split = mergeRoot(defaults(file), split, "animations");
      raw.set(root, null);
      raw.createSection(root, sectionMap(split.getConfigurationSection(root)));
    }
    return raw;
  }

  public static YamlConfiguration read(Path file) throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.load(file.toFile());
    return config;
  }

  /** Writes a YAML file through a sibling temporary file and an atomic rename when supported. */
  public static void write(Path file, YamlConfiguration configuration) throws IOException {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(configuration, "configuration");
    Path absolute = file.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
    boolean moved = false;
    try {
      Files.writeString(temporary, configuration.saveToString(), StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary,
            absolute,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    } finally {
      if (!moved) Files.deleteIfExists(temporary);
    }
  }

  private YamlConfiguration migratedOrDefault(String file, YamlConfiguration raw)
      throws IOException {
    String root = rootFor(file);
    if (raw.contains(root) && !raw.isConfigurationSection(root))
      throw new IllegalArgumentException("config.yml: " + root + " must be a YAML section.");
    if (file.equals("tiers.yml") && raw.contains("ranks") && !raw.isConfigurationSection("ranks"))
      throw new IllegalArgumentException("config.yml: legacy ranks must be a YAML section.");
    ConfigurationSection inline = raw.getConfigurationSection(root);
    if (inline != null) return fromSection(inline);
    if (file.equals("tiers.yml")) {
      ConfigurationSection ranks = raw.getConfigurationSection("ranks");
      if (ranks != null) return migrateRanks(raw, ranks);
    }
    return defaults(file);
  }

  private static YamlConfiguration migrateRanks(YamlConfiguration raw, ConfigurationSection ranks) {
    Map<String, List<String>> groupsByTier = new LinkedHashMap<>();
    for (String group : ranks.getKeys(false)) {
      String configuredTier = ranks.getString(group, "").trim();
      if (configuredTier.isEmpty()) continue;
      String id = configuredTier.toLowerCase(Locale.ROOT);
      groupsByTier.computeIfAbsent(id, ignored -> new ArrayList<>()).add(group);
    }

    YamlConfiguration migrated = new YamlConfiguration();
    ConfigurationSection oldTiers = raw.getConfigurationSection("tiers");
    if (oldTiers != null) return fromSection(oldTiers);
    ConfigurationSection colors = raw.getConfigurationSection("colors");
    for (Map.Entry<String, List<String>> entry : groupsByTier.entrySet()) {
      String base = "tiers." + entry.getKey();
      migrated.set(base + ".groups", entry.getValue());
      String color = findColor(colors, entry.getKey());
      if (color != null) migrated.set(base + ".color", color);
    }
    return migrated;
  }

  private static String findColor(ConfigurationSection colors, String tierId) {
    if (colors == null) return null;
    for (String key : colors.getKeys(false))
      if (key.equalsIgnoreCase(tierId)) return colors.getString(key);
    return null;
  }

  private static void backupLegacyConfig(Path config) throws IOException {
    Path backup = config.resolveSibling("config.yml.pre-2.0.bak");
    if (Files.exists(backup)) return;
    Path temporary = Files.createTempFile(config.getParent(), "config.yml.pre-2.0.", ".tmp");
    boolean moved = false;
    try {
      Files.copy(config, temporary, StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, backup);
      }
      moved = true;
    } catch (java.nio.file.FileAlreadyExistsException concurrentBackup) {
      moved = true;
    } finally {
      if (!moved) Files.deleteIfExists(temporary);
    }
  }

  private static YamlConfiguration defaults(String file) throws IOException {
    String resource = "/" + file;
    try (InputStream input = ConfigurationFiles.class.getResourceAsStream(resource)) {
      if (input == null)
        throw new IOException("Missing bundled configuration resource " + resource);
      return YamlConfiguration.loadConfiguration(
          new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
    }
  }

  private static YamlConfiguration fromSection(ConfigurationSection section) {
    YamlConfiguration result = new YamlConfiguration();
    result.createSection(section.getName(), sectionMap(section));
    return result;
  }

  private static Map<String, Object> sectionMap(ConfigurationSection section) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (section == null) return values;
    for (String key : section.getKeys(false)) {
      Object value = section.get(key);
      if (value instanceof ConfigurationSection child) value = sectionMap(child);
      values.put(key, value);
    }
    return values;
  }

  private static YamlConfiguration mergeMessages(
      YamlConfiguration defaults, YamlConfiguration custom) {
    Map<String, Object> merged = sectionMap(defaults.getConfigurationSection("messages"));
    merged.putAll(sectionMap(custom.getConfigurationSection("messages")));
    YamlConfiguration result = new YamlConfiguration();
    result.createSection("messages", merged);
    return result;
  }

  private static YamlConfiguration mergeRoot(
      YamlConfiguration defaults, YamlConfiguration custom, String root) {
    Map<String, Object> merged = sectionMap(defaults.getConfigurationSection(root));
    deepOverlay(merged, sectionMap(custom.getConfigurationSection(root)));
    YamlConfiguration result = new YamlConfiguration();
    result.createSection(root, merged);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static void deepOverlay(Map<String, Object> target, Map<String, Object> custom) {
    for (Map.Entry<String, Object> entry : custom.entrySet()) {
      Object current = target.get(entry.getKey());
      Object replacement = entry.getValue();
      if (current instanceof Map<?, ?> currentMap
          && replacement instanceof Map<?, ?> replacementMap) {
        Map<String, Object> nested = new LinkedHashMap<>((Map<String, Object>) currentMap);
        deepOverlay(nested, (Map<String, Object>) replacementMap);
        target.put(entry.getKey(), nested);
      } else {
        target.put(entry.getKey(), replacement);
      }
    }
  }

  private static String rootFor(String file) {
    return switch (file) {
      case "tiers.yml" -> "tiers";
      case "titles.yml" -> "custom-titles";
      case "animations.yml" -> "animations";
      case "messages.yml" -> "messages";
      default -> throw new IllegalArgumentException("Unknown configuration file: " + file);
    };
  }
}
