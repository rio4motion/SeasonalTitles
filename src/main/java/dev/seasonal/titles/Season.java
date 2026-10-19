package dev.seasonal.titles;

import java.util.Locale;

public record Season(Name name, int year) implements Comparable<Season> {
  public enum Name {
    WINTER,
    SPRING,
    SUMMER,
    AUTUMN
  }

  public Season {
    if (name == null || year < 1 || year > 9999)
      throw new IllegalArgumentException("Year must be 1–9999.");
  }

  public static Season parse(String name, String year) {
    try {
      return new Season(Name.valueOf(name.toUpperCase(Locale.ROOT)), Integer.parseInt(year));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Use Winter, Spring, Summer or Autumn and a year from 1–9999.");
    }
  }

  public int compareTo(Season other) {
    int years = Integer.compare(year, other.year);
    return years == 0 ? name.compareTo(other.name) : years;
  }

  public String label() {
    return name.name().charAt(0) + name.name().substring(1).toLowerCase(Locale.ROOT) + " " + year;
  }

  public String key() {
    return year + "-" + name.name().toLowerCase(Locale.ROOT);
  }

  public void requireAfter(Season previous) {
    if (compareTo(previous) <= 0)
      throw new IllegalArgumentException("Season must be later than " + previous.label() + ".");
  }
}
