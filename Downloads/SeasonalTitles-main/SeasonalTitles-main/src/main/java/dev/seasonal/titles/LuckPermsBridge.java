package dev.seasonal.titles;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.node.*;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;

public final class LuckPermsBridge {
  private final SeasonalTitlesPlugin plugin;
  private final LuckPerms api;
  private final List<net.luckperms.api.event.EventSubscription<?>> subscriptions =
      new ArrayList<>();
  private final Set<UUID> inFlight = new HashSet<>();
  private final Set<UUID> dirty = new LinkedHashSet<>();
  private final Map<UUID, String> enrollment = new HashMap<>();
  private final Map<UUID, Long> retryAfter = new HashMap<>();
  private volatile boolean closed;

  public LuckPermsBridge(SeasonalTitlesPlugin plugin, LuckPerms api) {
    this.plugin = plugin;
    this.api = api;
    subscribe(
        NodeAddEvent.class,
        e -> {
          if (e.getTarget() instanceof User user
              && e.getDataType() == DataType.NORMAL
              && e.getNode() instanceof InheritanceNode node
              && node.getValue()
              && !node.hasExpired()) {
            plugin.main(
                () -> {
                  // Only a newly added qualifying membership is evidence, not a load/recalculation.
                  QueryOptions options = options(user);
                  Group added = api.getGroupManager().getGroup(node.getGroupName());
                  Set<String> branch = new HashSet<>();
                  branch.add(node.getGroupName());
                  if (added != null)
                    added.getInheritedGroups(options).forEach(g -> branch.add(g.getName()));
                  if (groups(user).contains(node.getGroupName())
                      && Access.highest(branch, user.getPrimaryGroup(), plugin.settings().ranks())
                          .isPresent()) {
                    UUID id = user.getUniqueId();
                    String evidence = "LuckPerms membership added: " + node.getGroupName();
                    enrollment.put(id, evidence);
                    Tier tier =
                        Access.highest(
                                groups(user), user.getPrimaryGroup(), plugin.settings().ranks())
                            .orElse(null);
                    Season observed = plugin.state() == null ? null : plugin.state().season();
                    // Persist verified eligibility immediately, before any later asynchronous
                    // reload.
                    plugin
                        .database()
                        .submit(
                            s -> {
                              s.enroll(id, evidence);
                              s.reconcile(id, observed == null ? s.season() : observed, tier);
                            })
                        .whenComplete(
                            (v, error) ->
                                plugin.main(
                                    () -> {
                                      if (error == null) enrollment.remove(id, evidence);
                                      else retry(id, error);
                                    }));
                  }
                  dirty.add(user.getUniqueId());
                });
          } else if (e.isGroup()) plugin.main(this::queueEnrolled);
        });
    subscribe(
        NodeRemoveEvent.class,
        e ->
            plugin.main(
                () -> {
                  if (e.getTarget() instanceof User u) dirty.add(u.getUniqueId());
                  else queueEnrolled();
                }));
    subscribe(
        NodeClearEvent.class,
        e ->
            plugin.main(
                () -> {
                  if (e.getTarget() instanceof User u) dirty.add(u.getUniqueId());
                  else queueEnrolled();
                }));
    subscribe(
        UserDataRecalculateEvent.class,
        e ->
            plugin.main(
                () -> {
                  if (!inFlight.contains(e.getUser().getUniqueId()))
                    dirty.add(e.getUser().getUniqueId());
                }));
  }

  public QueryOptions options(User user) {
    return api.getContextManager()
        .getQueryOptions(user)
        .orElse(api.getContextManager().getStaticQueryOptions());
  }

  public Set<String> groups(User user) {
    return inherited(user, options(user));
  }

  static Set<String> inherited(User user, QueryOptions options) {
    return user.getInheritedGroups(options).stream()
        .map(Group::getName)
        .map(n -> n.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  public Set<String> groups(UUID id) {
    User u = api.getUserManager().getUser(id);
    return u == null ? Set.of() : groups(u);
  }

  public void queue(UUID id) {
    dirty.add(id);
  }

  public void queueEnrolled() {
    if (plugin.state() != null) {
      dirty.addAll(plugin.state().enrolled());
      dirty.addAll(plugin.state().names().keySet());
    }
  }

  public void tick() {
    if (plugin.state() == null) return;
    dirty.addAll(plugin.state().pending());
    int capacity = plugin.settings().batchSize() - inFlight.size();
    for (UUID id : new ArrayList<>(dirty)) {
      if (capacity <= 0) break;
      if (inFlight.contains(id) || plugin.tick() < retryAfter.getOrDefault(id, 0L)) continue;
      dirty.remove(id);
      inFlight.add(id);
      capacity--;
      Season observed = plugin.state().season();
      api.getUserManager()
          .loadUser(id)
          .whenComplete(
              (user, error) ->
                  plugin.main(
                      () -> {
                        if (closed) return;
                        if (error != null) {
                          inFlight.remove(id);
                          retry(id, error);
                          return;
                        }
                        Tier tier =
                            Access.highest(
                                    groups(user), user.getPrimaryGroup(), plugin.settings().ranks())
                                .orElse(null);
                        String evidence = enrollment.remove(id);
                        plugin
                            .database()
                            .submit(
                                s -> {
                                  s.remember(id, user.getUsername());
                                  if (evidence != null) s.enroll(id, evidence);
                                  s.reconcile(id, observed, tier);
                                })
                            .whenComplete(
                                (v, err) ->
                                    plugin.main(
                                        () -> {
                                          inFlight.remove(id);
                                          api.getUserManager().cleanupUser(user);
                                          if (err != null) {
                                            if (evidence != null) enrollment.put(id, evidence);
                                            retry(id, err);
                                          } else retryAfter.remove(id);
                                        }));
                      }));
    }
  }

  private void retry(UUID id, Throwable error) {
    dirty.add(id);
    retryAfter.put(id, plugin.tick() + 1200);
    plugin.failure(error);
  }

  public CompletableFuture<UUID> identify(String name) {
    try {
      return CompletableFuture.completedFuture(UUID.fromString(name));
    } catch (IllegalArgumentException ignored) {
      return api.getUserManager().lookupUniqueId(name);
    }
  }

  private <T extends net.luckperms.api.event.LuckPermsEvent> void subscribe(
      Class<T> type, java.util.function.Consumer<T> listener) {
    subscriptions.add(api.getEventBus().subscribe(plugin, type, listener));
  }

  public void close() {
    closed = true;
    subscriptions.forEach(net.luckperms.api.event.EventSubscription::close);
  }
}
