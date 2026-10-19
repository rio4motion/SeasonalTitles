package dev.seasonal.titles;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Single writer; coalesces snapshot refreshes and skips publication for no-op reconciliation. */
public final class Database implements AutoCloseable {
  @FunctionalInterface
  public interface Mutation {
    void apply(Store store) throws Exception;
  }

  private record Request(Mutation mutation, CompletableFuture<Void> done) {}

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "SeasonalTitles-SQLite"));
  private final ArrayDeque<Request> queue = new ArrayDeque<>();
  private final Consumer<Snapshot> publish;
  private boolean draining, closed;
  private Store store;

  public Database(Consumer<Snapshot> publish) {
    this.publish = publish;
  }

  public CompletableFuture<Void> open(Path path, Season season) {
    return submit(
        ignored -> {
          store = new Store(path, season);
          store.queueAll();
        });
  }

  public synchronized CompletableFuture<Void> submit(Mutation mutation) {
    if (closed)
      return CompletableFuture.failedFuture(new IllegalStateException("Database is closing"));
    CompletableFuture<Void> done = new CompletableFuture<>();
    queue.add(new Request(mutation, done));
    if (!draining) {
      draining = true;
      worker.execute(this::drain);
    }
    return done;
  }

  private void drain() {
    while (true) {
      List<Request> batch;
      synchronized (this) {
        if (queue.isEmpty()) {
          draining = false;
          return;
        }
        batch = new ArrayList<>(queue);
        queue.clear();
      }
      List<Request> successful = new ArrayList<>();
      try {
        long before = store == null ? -1 : store.changes();
        for (Request request : batch) {
          try {
            request.mutation.apply(store);
            successful.add(request);
          } catch (Exception e) {
            request.done.completeExceptionally(e);
          }
        }
        if (store != null && before != store.changes()) publish.accept(store.snapshot());
        successful.forEach(r -> r.done.complete(null));
      } catch (Exception e) {
        batch.forEach(r -> r.done.completeExceptionally(e));
      }
    }
  }

  public void close() throws Exception {
    Future<?> close;
    synchronized (this) {
      closed = true;
      close =
          worker.submit(
              () -> {
                if (store != null)
                  try {
                    store.close();
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
              });
      worker.shutdown();
    }
    if (!worker.awaitTermination(15, TimeUnit.SECONDS))
      throw new IllegalStateException("Database shutdown still pending; do not force-kill JVM.");
    close.get();
  }
}
