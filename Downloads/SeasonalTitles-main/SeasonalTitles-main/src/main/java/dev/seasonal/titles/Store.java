package dev.seasonal.titles;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Only called on the database executor. Every multi-statement mutation is atomic. */
public final class Store implements AutoCloseable {
  private final Connection db;

  @FunctionalInterface
  public interface Work {
    void run() throws Exception;
  }

  public Store(Path file, Season initial) throws Exception {
    Class.forName("org.sqlite.JDBC");
    db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (Statement s = db.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute("PRAGMA busy_timeout=5000");
      s.execute(
          "CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), season TEXT NOT"
              + " NULL, year INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS titles (id TEXT PRIMARY KEY, season TEXT, year INTEGER, tier"
              + " TEXT, text TEXT)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT, enrolled_at"
              + " INTEGER, evidence TEXT, rankless_at INTEGER)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS awards (uuid TEXT NOT NULL REFERENCES players(uuid), title"
              + " TEXT NOT NULL REFERENCES titles(id) ON DELETE CASCADE, awarded_at INTEGER NOT"
              + " NULL, PRIMARY KEY(uuid,title))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS equipped (uuid TEXT PRIMARY KEY REFERENCES players(uuid),"
              + " title TEXT NOT NULL REFERENCES titles(id) ON DELETE CASCADE)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS roles (role TEXT NOT NULL, tier TEXT NOT NULL, PRIMARY"
              + " KEY(role,tier))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS pending (uuid TEXT PRIMARY KEY REFERENCES players(uuid))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS transitions (id INTEGER PRIMARY KEY AUTOINCREMENT, season"
              + " TEXT, year INTEGER, activated_at INTEGER, actor TEXT)");
      try (ResultSet version = s.executeQuery("PRAGMA user_version")) {
        if (version.next() && version.getInt(1) > 3)
          throw new SQLException("Database is newer than this plugin; refusing to downgrade.");
      }
      s.execute(
          "CREATE TABLE IF NOT EXISTS preferences (uuid TEXT PRIMARY KEY REFERENCES players(uuid),"
              + " animation TEXT NOT NULL DEFAULT 'default', show_animations INTEGER NOT NULL"
              + " DEFAULT 1, show_titles INTEGER NOT NULL DEFAULT 1)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS animation_unlocks (uuid TEXT NOT NULL REFERENCES"
              + " players(uuid), effect TEXT NOT NULL, unlocked_at INTEGER NOT NULL, PRIMARY"
              + " KEY(uuid,effect))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS animation_vouchers (token TEXT PRIMARY KEY, effect TEXT NOT"
              + " NULL, delivery_to TEXT REFERENCES players(uuid), issued_at INTEGER NOT NULL,"
              + " redeemed_by TEXT REFERENCES players(uuid), redeemed_at INTEGER)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS animation_voucher_delivery ON animation_vouchers(delivery_to)"
              + " WHERE redeemed_by IS NULL AND delivery_to IS NOT NULL");
      s.execute("PRAGMA user_version=3");
    }
    transaction(
        () -> {
          update(
              "INSERT OR IGNORE INTO state VALUES(1,?,?)", initial.name().name(), initial.year());
          catalog(season());
        });
  }

  public void transaction(Work action) throws Exception {
    db.setAutoCommit(false);
    try {
      action.run();
      db.commit();
    } catch (Exception e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  private int update(String sql, Object... args) throws SQLException {
    try (PreparedStatement s = db.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      return s.executeUpdate();
    }
  }

  private boolean exists(String sql, Object... args) throws SQLException {
    try (PreparedStatement s = db.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (ResultSet r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  public Season season() throws SQLException {
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT season,year FROM state WHERE id=1")) {
      if (!r.next()) throw new SQLException("Missing season state");
      return new Season(Season.Name.valueOf(r.getString(1)), r.getInt(2));
    }
  }

  private void catalog(Season season) throws SQLException {
    for (Tier tier : Tier.values())
      update(
          "INSERT OR IGNORE INTO titles(id,season,year,tier) VALUES(?,?,?,?)",
          Title.seasonal(season, tier).id(),
          season.name().name(),
          season.year(),
          tier.name());
  }

  public void remember(UUID id, String name) throws SQLException {
    update(
        "INSERT INTO players(uuid,name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET"
            + " name=COALESCE(excluded.name,players.name) WHERE players.name IS NOT"
            + " COALESCE(excluded.name,players.name)",
        id.toString(),
        name);
  }

  public void enroll(UUID id, String evidence) throws Exception {
    transaction(
        () -> {
          remember(id, null);
          update(
              "UPDATE players SET enrolled_at=COALESCE(enrolled_at,?),"
                  + " evidence=COALESCE(evidence,?) WHERE uuid=?",
              Instant.now().toEpochMilli(),
              evidence,
              id.toString());
          update("INSERT OR IGNORE INTO pending VALUES(?)", id.toString());
        });
  }

  public void queueAll() throws SQLException {
    update("INSERT OR IGNORE INTO pending SELECT uuid FROM players WHERE enrolled_at IS NOT NULL");
  }

  public void transition(Season expected, Season next, String actor) throws Exception {
    transaction(
        () -> {
          if (!season().equals(expected))
            throw new IllegalArgumentException("Season changed; preview and confirm again.");
          next.requireAfter(expected);
          if (exists("SELECT 1 FROM pending LIMIT 1"))
            throw new IllegalArgumentException(
                "Current season reconciliation is still pending. Retry after it finishes.");
          catalog(next);
          update("UPDATE state SET season=?,year=? WHERE id=1", next.name().name(), next.year());
          update(
              "INSERT INTO transitions(season,year,activated_at,actor) VALUES(?,?,?,?)",
              next.name().name(),
              next.year(),
              Instant.now().toEpochMilli(),
              actor);
          queueAll();
        });
  }

  /** Verified now, never backdated. A stale rank read cannot award a different active season. */
  public void reconcile(UUID id, Season observedSeason, Tier tier) throws Exception {
    transaction(
        () -> {
          if (!season().equals(observedSeason)) return;
          if (!exists(
              "SELECT 1 FROM players WHERE uuid=? AND enrolled_at IS NOT NULL", id.toString())) {
            remember(id, null);
            if (tier == null) {
              update(
                  "UPDATE players SET rankless_at=? WHERE uuid=? AND rankless_at IS NULL",
                  Instant.now().toEpochMilli(),
                  id.toString());
              return;
            }
            if (!exists(
                "SELECT 1 FROM players WHERE uuid=? AND rankless_at IS NOT NULL", id.toString()))
              return;
            update(
                "UPDATE players SET enrolled_at=?,evidence=? WHERE uuid=?",
                Instant.now().toEpochMilli(),
                "Verified eligible after recorded rankless observation",
                id.toString());
          }
          if (tier != null)
            update(
                "INSERT OR IGNORE INTO awards VALUES(?,?,?)",
                id.toString(),
                Title.seasonal(observedSeason, tier).id(),
                Instant.now().toEpochMilli());
          update("DELETE FROM pending WHERE uuid=?", id.toString());
        });
  }

  public void equip(UUID id, String title) throws Exception {
    transaction(
        () -> {
          remember(id, null);
          if (title == null) update("DELETE FROM equipped WHERE uuid=?", id.toString());
          else
            update(
                "INSERT INTO equipped VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET"
                    + " title=excluded.title",
                id.toString(),
                title);
        });
  }

  public void role(String group, Tier tier, boolean add) throws SQLException {
    if (!group.matches("[a-z0-9_\\-]{1,64}"))
      throw new IllegalArgumentException("Invalid LuckPerms group name.");
    update(
        add
            ? "INSERT OR IGNORE INTO roles VALUES(?,?)"
            : "DELETE FROM roles WHERE role=? AND tier=?",
        group,
        tier.name());
  }

  public void create(Title title) throws SQLException {
    update("INSERT INTO titles(id,text) VALUES(?,?)", title.id(), title.customText());
  }

  public void definitions(Collection<CustomDefinition> definitions) throws Exception {
    transaction(
        () -> {
          for (CustomDefinition definition : definitions) {
            Title title = definition.title();
            update(
                "INSERT INTO titles(id,text) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET"
                    + " text=excluded.text WHERE titles.text IS NOT excluded.text AND titles.season"
                    + " IS NULL",
                title.id(),
                title.customText());
          }
        });
  }

  public void preference(UUID player, String key, String value) throws Exception {
    String column =
        switch (key) {
          case "animation" -> "animation";
          case "animations" -> "show_animations";
          case "titles" -> "show_titles";
          default -> throw new IllegalArgumentException("Unknown preference");
        };
    if (key.equals("animation")) {
      if (!value.equals("default")) AnimationEffect.parse(value);
    } else if (!value.equals("on") && !value.equals("off"))
      throw new IllegalArgumentException("Use on or off.");
    transaction(
        () -> {
          remember(player, null);
          update("INSERT OR IGNORE INTO preferences(uuid) VALUES(?)", player.toString());
          update(
              "UPDATE preferences SET " + column + "=? WHERE uuid=?",
              key.equals("animation") ? value : (value.equals("on") ? 1 : 0),
              player.toString());
        });
  }

  public void issueVoucher(AnimationVoucher voucher, UUID recipient) throws Exception {
    transaction(
        () -> {
          remember(recipient, null);
          update(
              "INSERT INTO animation_vouchers(token,effect,delivery_to,issued_at) VALUES(?,?,?,?)",
              voucher.token().toString(),
              voucher.effect(),
              recipient.toString(),
              Instant.now().toEpochMilli());
        });
  }

  public List<AnimationVoucher> pendingVouchers(UUID player) throws SQLException {
    List<AnimationVoucher> result = new ArrayList<>();
    try (PreparedStatement query =
        db.prepareStatement(
            "SELECT token,effect FROM animation_vouchers WHERE delivery_to=? AND redeemed_by IS"
                + " NULL ORDER BY issued_at,token LIMIT 128")) {
      query.setString(1, player.toString());
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next())
          result.add(new AnimationVoucher(UUID.fromString(rows.getString(1)), rows.getString(2)));
      }
    }
    return result;
  }

  public void deliveredVouchers(UUID player, Collection<UUID> tokens) throws Exception {
    transaction(
        () -> {
          for (UUID token : tokens)
            update(
                "UPDATE animation_vouchers SET delivery_to=NULL WHERE token=? AND delivery_to=?",
                token.toString(),
                player.toString());
        });
  }

  /** The token and unlock commit together before the physical item is consumed. */
  public void redeemVoucher(UUID player, AnimationVoucher voucher) throws Exception {
    transaction(
        () -> {
          if (!exists(
              "SELECT 1 FROM animation_vouchers WHERE token=? AND effect=? AND redeemed_by IS NULL",
              voucher.token().toString(),
              voucher.effect()))
            throw new IllegalArgumentException(
                "This voucher is invalid or has already been redeemed.");
          if (exists(
              "SELECT 1 FROM animation_unlocks WHERE uuid=? AND effect=?",
              player.toString(),
              voucher.effect()))
            throw new IllegalArgumentException(
                "You already permanently unlocked " + voucher.effect() + ". Voucher kept.");
          remember(player, null);
          long now = Instant.now().toEpochMilli();
          update(
              "INSERT INTO animation_unlocks VALUES(?,?,?)",
              player.toString(),
              voucher.effect(),
              now);
          update(
              "UPDATE animation_vouchers SET redeemed_by=?,redeemed_at=?,delivery_to=NULL WHERE"
                  + " token=?",
              player.toString(),
              now,
              voucher.token().toString());
        });
  }

  public void delete(String id, boolean confirmed) throws Exception {
    transaction(
        () -> {
          requireCustom(id);
          if (!confirmed
              && (exists("SELECT 1 FROM awards WHERE title=?", id)
                  || exists("SELECT 1 FROM equipped WHERE title=?", id)))
            throw new IllegalArgumentException(
                "Title is owned or equipped; repeat with confirmation.");
          update("DELETE FROM titles WHERE id=?", id);
        });
  }

  private void requireCustom(String id) throws SQLException {
    if (!exists("SELECT 1 FROM titles WHERE id=? AND text IS NOT NULL", id))
      throw new IllegalArgumentException("Unknown custom title.");
  }

  public void grant(UUID player, String id, boolean give) throws Exception {
    transaction(
        () -> {
          requireCustom(id);
          remember(player, null);
          if (give)
            update(
                "INSERT OR IGNORE INTO awards VALUES(?,?,?)",
                player.toString(),
                id,
                Instant.now().toEpochMilli());
          else update("DELETE FROM awards WHERE uuid=? AND title=?", player.toString(), id);
          // A revoked selection may still be available through operator access; render access is
          // always rechecked.
        });
  }

  public Snapshot snapshot() throws SQLException {
    Map<String, Title> titles = new TreeMap<>();
    Map<UUID, Set<String>> owned = new HashMap<>();
    Map<UUID, String> equipped = new HashMap<>(), names = new HashMap<>();
    Map<String, Set<Tier>> roles = new TreeMap<>();
    Set<UUID> enrolled = new HashSet<>(), pending = new HashSet<>();
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM titles")) {
      while (r.next()) {
        String id = r.getString("id"), name = r.getString("season");
        titles.put(
            id,
            new Title(
                id,
                name == null ? null : new Season(Season.Name.valueOf(name), r.getInt("year")),
                name == null ? null : Tier.valueOf(r.getString("tier")),
                r.getString("text")));
      }
    }
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT uuid,title FROM awards")) {
      while (r.next())
        owned
            .computeIfAbsent(UUID.fromString(r.getString(1)), k -> new HashSet<>())
            .add(r.getString(2));
    }
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT uuid,title FROM equipped")) {
      while (r.next()) equipped.put(UUID.fromString(r.getString(1)), r.getString(2));
    }
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT role,tier FROM roles")) {
      while (r.next())
        roles
            .computeIfAbsent(r.getString(1), k -> new HashSet<>())
            .add(Tier.valueOf(r.getString(2)));
    }
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT uuid,name,enrolled_at FROM players")) {
      while (r.next()) {
        UUID id = UUID.fromString(r.getString(1));
        if (r.getString(2) != null) names.put(id, r.getString(2));
        if (r.getObject(3) != null) enrolled.add(id);
      }
    }
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT uuid FROM pending")) {
      while (r.next()) pending.add(UUID.fromString(r.getString(1)));
    }
    Map<UUID, PlayerPreferences> preferences = new HashMap<>();
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM preferences")) {
      while (r.next())
        preferences.put(
            UUID.fromString(r.getString("uuid")),
            new PlayerPreferences(
                r.getString("animation"),
                r.getBoolean("show_animations"),
                r.getBoolean("show_titles")));
    }
    Map<UUID, Set<String>> unlocks = new HashMap<>();
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT uuid,effect FROM animation_unlocks")) {
      while (r.next())
        unlocks
            .computeIfAbsent(UUID.fromString(r.getString(1)), ignored -> new HashSet<>())
            .add(r.getString(2));
    }
    return new Snapshot(
        season(), titles, owned, equipped, roles, enrolled, pending, names, preferences, unlocks);
  }

  public long changes() throws SQLException {
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT total_changes()")) {
      r.next();
      return r.getLong(1);
    }
  }

  public void close() throws SQLException {
    db.close();
  }
}
