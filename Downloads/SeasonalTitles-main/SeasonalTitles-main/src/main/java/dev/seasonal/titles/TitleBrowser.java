package dev.seasonal.titles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Human-facing labels; IDs remain stable and are used only by the click action. */
final class TitleBrowser {
  static Component line(
      Settings settings,
      Title title,
      boolean owned,
      boolean access,
      boolean equipped,
      boolean preview) {
    String status =
        owned ? " [owned]" : access ? " [not owned · available]" : " [not owned · locked]";
    Component line =
        settings
            .text(title)
            .append(
                Component.text(
                    status,
                    owned
                        ? NamedTextColor.GREEN
                        : access ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
    if (equipped) line = line.append(Component.text(" [equipped]", NamedTextColor.AQUA));
    if (access)
      return line.clickEvent(ClickEvent.suggestCommand("/titles equip " + title.id()))
          .hoverEvent(Component.text("Click to equip."));
    if (preview)
      return line.clickEvent(ClickEvent.suggestCommand("/titles preview " + title.id()))
          .hoverEvent(Component.text("Click to preview for 10 seconds."));
    return line;
  }
}
