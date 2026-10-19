package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
  @TempDir Path temp;

  @Test
  void publicationPrecedesCompletionAndShutdownDrainsWrites() throws Exception {
    AtomicReference<Snapshot> latest = new AtomicReference<>();
    UUID id = UUID.randomUUID();
    Database database = new Database(latest::set);
    Season season = Season.parse("Winter", "2026");
    database.open(temp.resolve("titles.db"), season).get(5, TimeUnit.SECONDS);
    List<CompletableFuture<Void>> writes = new ArrayList<>();
    writes.add(database.submit(s -> s.enroll(id, "test")));
    for (int i = 0; i < 20; i++)
      writes.add(database.submit(s -> s.reconcile(id, season, Tier.LEGEND)));
    writes.add(
        database
            .submit(s -> s.equip(id, Title.seasonal(season, Tier.LEGEND).id()))
            .thenRun(() -> assertNotNull(latest.get().equipped().get(id))));
    database.close();
    for (var write : writes) assertTrue(write.isDone() && !write.isCompletedExceptionally());
    try (Store store = new Store(temp.resolve("titles.db"), season)) {
      assertEquals(1, store.snapshot().owned(id).size());
    }
    assertTrue(database.submit(s -> {}).isCompletedExceptionally());
  }
}
