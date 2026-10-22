package dev.seasonal.titles;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Main-thread item handling; all issuance/redemption records use the existing SQLite worker. */
public final class AnimationVouchers implements Listener {
  private final SeasonalTitlesPlugin plugin;
  private final NamespacedKey tokenKey, effectKey;
  private final Set<UUID> delivering = new HashSet<>(), redeeming = new HashSet<>();
  private final Set<UUID> deliveryRequested = new HashSet<>();

  public AnimationVouchers(SeasonalTitlesPlugin plugin) {
    this.plugin = plugin;
    tokenKey = new NamespacedKey(plugin, "animation_voucher");
    effectKey = new NamespacedKey(plugin, "voucher_effect");
  }

  public CompletableFuture<Void> issue(Player recipient, String effect) {
    var voucher = new AnimationVoucher(UUID.randomUUID(), effect);
    var settings = plugin.settings().animations();
    if (!settings.enabled() || !settings.effects().get(voucher.effect()).enabled())
      throw new IllegalArgumentException("That animation is disabled.");
    UUID id = recipient.getUniqueId();
    return plugin
        .database()
        .submit(s -> s.issueVoucher(voucher, id))
        .thenRun(() -> plugin.main(() -> claim(id, false)));
  }

  public void claim(UUID id, boolean notifyEmpty) {
    if (!delivering.add(id)) {
      deliveryRequested.add(id);
      return;
    }
    List<AnimationVoucher> pending = new ArrayList<>();
    plugin
        .database()
        .submit(s -> pending.addAll(s.pendingVouchers(id)))
        .whenComplete(
            (v, error) ->
                plugin.main(
                    () -> {
                      Player player = Bukkit.getPlayer(id);
                      if (error != null || player == null || !player.isOnline()) {
                        finishDelivery(id);
                        if (error != null) plugin.failure(error);
                        return;
                      }
                      List<UUID> delivered = new ArrayList<>();
                      for (AnimationVoucher voucher : pending) {
                        if (player.getInventory().firstEmpty() < 0) break;
                        player.getInventory().addItem(item(voucher));
                        delivered.add(voucher.token());
                      }
                      if (!delivered.isEmpty())
                        player.sendMessage(
                            Component.text(
                                "Received "
                                    + delivered.size()
                                    + " animation voucher(s). Right-click to redeem.",
                                NamedTextColor.GREEN));
                      if (delivered.size() < pending.size())
                        player.sendMessage(
                            Component.text(
                                "Inventory full. Free a slot, then /titles vouchers to collect"
                                    + " pending vouchers.",
                                NamedTextColor.YELLOW));
                      else if (pending.isEmpty() && notifyEmpty)
                        player.sendMessage(Component.text("No pending vouchers."));
                      plugin
                          .database()
                          .submit(s -> s.deliveredVouchers(id, delivered))
                          .whenComplete(
                              (ignored, failure) ->
                                  plugin.main(
                                      () -> {
                                        finishDelivery(id);
                                        if (failure != null) plugin.failure(failure);
                                      }));
                    }));
  }

  private void finishDelivery(UUID id) {
    delivering.remove(id);
    if (deliveryRequested.remove(id)) claim(id, false);
  }

  ItemStack item(AnimationVoucher voucher) {
    ItemStack item = new ItemStack(Material.PAPER);
    var meta = item.getItemMeta();
    String label =
        Character.toUpperCase(voucher.effect().charAt(0)) + voucher.effect().substring(1);
    meta.displayName(
        Component.text(label + " Animation Voucher", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
            Component.text(
                    "Right-click to permanently unlock " + voucher.effect() + ".",
                    NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Single use • Tradable", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
    meta.getPersistentDataContainer()
        .set(tokenKey, PersistentDataType.STRING, voucher.token().toString());
    meta.getPersistentDataContainer().set(effectKey, PersistentDataType.STRING, voucher.effect());
    item.setItemMeta(meta);
    return item;
  }

  AnimationVoucher voucher(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return null;
    var data = item.getItemMeta().getPersistentDataContainer();
    String token = data.get(tokenKey, PersistentDataType.STRING);
    if (token == null) return null;
    try {
      return new AnimationVoucher(
          UUID.fromString(token), data.get(effectKey, PersistentDataType.STRING));
    } catch (IllegalArgumentException | NullPointerException invalid) {
      return null;
    }
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    if (plugin.state() != null) claim(event.getPlayer().getUniqueId(), false);
  }

  // Paper marks air interactions with non-usable items cancelled by default. Do not ignore those;
  // respect a protection plugin's explicit DENY on a block/item interaction instead.
  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND
        || (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
    AnimationVoucher voucher = voucher(event.getItem());
    if (voucher == null) return;
    if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
    event.setCancelled(true);
    Player player = event.getPlayer();
    if (plugin.state() == null) return;
    UUID id = player.getUniqueId();
    if (!redeeming.add(id)) return;
    plugin
        .database()
        .submit(s -> s.redeemVoucher(id, voucher))
        .whenComplete(
            (v, error) ->
                plugin.main(
                    () -> {
                      redeeming.remove(id);
                      if (error != null) {
                        Throwable cause = error;
                        while (cause.getCause() != null) cause = cause.getCause();
                        if (!(cause instanceof IllegalArgumentException)) plugin.failure(error);
                        if (player.isOnline())
                          player.sendMessage(
                              Component.text(
                                  cause instanceof IllegalArgumentException
                                      ? cause.getMessage()
                                      : "Redemption failed. Voucher kept; check the server log.",
                                  NamedTextColor.RED));
                        return;
                      }
                      // Never alter a stale Player instance after reconnect. The durable unlock
                      // already exists.
                      Player online = Bukkit.getPlayer(id);
                      if (online == null) return;
                      for (int slot = 0; slot < online.getInventory().getSize(); slot++) {
                        ItemStack current = online.getInventory().getItem(slot);
                        if (voucher.equals(voucher(current))) {
                          current.subtract(1);
                          online.getInventory().setItem(slot, current);
                          break;
                        }
                      }
                      online.sendMessage(
                          Component.text(
                              "Permanently unlocked "
                                  + voucher.effect()
                                  + "! Select it with /titles animation "
                                  + voucher.effect()
                                  + ".",
                              NamedTextColor.GREEN));
                    }));
  }
}
