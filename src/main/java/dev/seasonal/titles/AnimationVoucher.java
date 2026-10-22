package dev.seasonal.titles;

import java.util.Objects;
import java.util.UUID;

/** The database, not an item's name/lore, is authoritative for this single-use token. */
public record AnimationVoucher(UUID token, String effect) {
  public AnimationVoucher {
    Objects.requireNonNull(token);
    effect = AnimationEffect.parse(Objects.requireNonNull(effect)).id();
    if (effect.equals("static"))
      throw new IllegalArgumentException("Static is already available to everyone.");
  }
}
