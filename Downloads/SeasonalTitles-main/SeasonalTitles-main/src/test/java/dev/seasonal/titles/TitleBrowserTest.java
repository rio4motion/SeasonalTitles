package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class TitleBrowserTest {
  @Test
  void labelsHideInternalIdsButKeepOwnershipAccessEquipmentAndStableClickTargets()
      throws Exception {
    var settings = Settings.read(PresentationTest.config());
    for (Title title :
        java.util.List.of(
            Title.seasonal(Season.parse("Winter", "2026"), Tier.BRONZE),
            Title.custom("secret_id", "<gradient:#FF5555:#55FFFF>Beta Supporter</gradient>"))) {
      var owned = TitleBrowser.line(settings, title, true, true, true, false);
      String plain = PlainTextComponentSerializer.plainText().serialize(owned);
      assertFalse(plain.contains(title.id()));
      assertFalse(plain.contains("custom:"));
      assertTrue(plain.contains("[owned] [equipped]"));
      assertEquals(ClickEvent.suggestCommand("/titles equip " + title.id()), owned.clickEvent());
      var locked = TitleBrowser.line(settings, title, false, false, false, true);
      assertTrue(
          PlainTextComponentSerializer.plainText()
              .serialize(locked)
              .endsWith("[not owned · locked]"));
      assertEquals(ClickEvent.suggestCommand("/titles preview " + title.id()), locked.clickEvent());
      var access = TitleBrowser.line(settings, title, false, true, false, false);
      assertTrue(
          PlainTextComponentSerializer.plainText()
              .serialize(access)
              .endsWith("[not owned · available]"));
    }
  }
}
