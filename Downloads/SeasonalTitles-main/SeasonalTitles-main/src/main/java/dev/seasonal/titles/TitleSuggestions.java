package dev.seasonal.titles;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.*;
import org.bukkit.event.*;

/** Shared presentation order. Never changes IDs, access decisions, or rank eligibility. */
final class TitleSuggestions implements Listener {
  private final SeasonalTitlesPlugin plugin;

  TitleSuggestions(SeasonalTitlesPlugin plugin) {
    this.plugin = plugin;
  }

  static Comparator<Title> titles(Settings settings) {
    List<Tier> tiers = settings.tierOrder();
    List<String> explicit = settings.titleOrder();
    return Comparator.comparingInt((Title t) -> t.customText() == null ? 0 : 1)
        .thenComparingInt(t -> t.tier() == null ? Integer.MAX_VALUE : index(tiers, t.tier()))
        // Historical tiers removed from configuration still have deterministic grouping.
        .thenComparing(t -> t.tier() == null ? "" : t.tier().name())
        .thenComparingInt(t -> t.customText() == null ? index(explicit, t.id()) : 0)
        .thenComparing(Title::season, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(Title::id);
  }

  private static <T> int index(List<T> values, T value) {
    int index = values.indexOf(value);
    return index < 0 ? Integer.MAX_VALUE : index;
  }

  static Comparator<String> ids(Settings settings, Map<String, Title> catalog) {
    Comparator<Title> order = Comparator.nullsLast(titles(settings));
    return Comparator.comparing(
            (String id) -> catalog.getOrDefault(id, catalog.get("custom:" + id)), order)
        .thenComparing(Comparator.naturalOrder());
  }

  static boolean titleArgument(String buffer) {
    String[] args = buffer.replaceFirst("^/", "").split(" ", -1);
    String command = args[0].toLowerCase(Locale.ROOT);
    if (command.startsWith("seasonaltitles:")) command = command.substring(15);
    if ((command.equals("title") || command.equals("titles")) && args.length == 3)
      return args[1].equalsIgnoreCase("equip") || args[1].equalsIgnoreCase("preview");
    return command.equals("sti")
        && ((args.length == 3 && args[1].equalsIgnoreCase("delete"))
            || (args.length == 4
                && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("revoke"))));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void send(AsyncPlayerSendSuggestionsEvent event) {
    if (!titleArgument(event.getBuffer())) return;
    // Paper can fire this asynchronously. Only read immutable, volatile snapshots here.
    Snapshot snapshot = plugin.state();
    Settings settings = plugin.settings();
    if (snapshot == null || settings == null) return;
    var suggestions = event.getSuggestions();
    var order = ids(settings, snapshot.titles());
    var sorted =
        suggestions.getList().stream()
            .sorted(
                Comparator.comparing(com.mojang.brigadier.suggestion.Suggestion::getText, order))
            .toList();
    // Suggestions.create/merge sorts alphabetically. The constructor preserves our order,
    // replacement ranges and tooltips; we neither add entries nor broaden access.
    event.setSuggestions(new Suggestions(suggestions.getRange(), sorted));
  }
}
