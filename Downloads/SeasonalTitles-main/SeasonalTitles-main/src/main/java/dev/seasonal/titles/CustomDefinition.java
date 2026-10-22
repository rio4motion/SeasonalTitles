package dev.seasonal.titles;

import java.util.Set;

public record CustomDefinition(
    Title title, String defaultAnimation, Set<String> allowedAnimations, String permission) {
  public CustomDefinition {
    allowedAnimations = Set.copyOf(allowedAnimations);
  }
}
