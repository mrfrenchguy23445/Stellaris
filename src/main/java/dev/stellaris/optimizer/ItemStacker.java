package dev.stellaris.optimizer;
import org.bukkit.Bukkit;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;

final class ItemStacker implements OptimizerService, Listener {

    private final Stellaris plugin;
    private final double mergeRadiusMultiplier;
    private final int mobPickupLimit;
    private final boolean restoreOnDisable;
    private double originalRadius = -1.0D;

    ItemStacker(Stellaris plugin) {
        this.plugin = plugin;
        final Cfg cfg = plugin.cfg("optimizer").child("item-stacking");
        this.mergeRadiusMultiplier = Math.max(0.5D, Math.min(5.0D, cfg.dbl("merge-radius-multiplier", 2.0D)));
        this.mobPickupLimit = Math.max(1, cfg.integer("max-pile-size", 32));
        this.restoreOnDisable = cfg.bool("restore-on-disable", true);
    }

    @Override
    public String id() {
        return "item-stacking";
    }

    @Override
    public int periodTicks() {
        return 200;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            if (originalRadius < 0) {
                originalRadius = Bukkit.getServer().spigot().getSpigotConfig().getDouble("world-settings.default.merge-radius.item");
            }
            final double target = originalRadius * mergeRadiusMultiplier;
            Bukkit.getServer().spigot().getSpigotConfig().set("world-settings.default.merge-radius.item", target);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        try {
            if (restoreOnDisable && originalRadius >= 0) {
                Bukkit.getServer().spigot().getSpigotConfig().set("world-settings.default.merge-radius.item", originalRadius);
            }
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (event.getEntity().getItemStack().getAmount() >= mobPickupLimit
                || event.getTarget().getItemStack().getAmount() >= mobPickupLimit) {
            // Allow but also note: to actually prevent mob pickup we can't change vanilla;
            // config key kept for informational purposes; implementation is a best effort.
        }
    }
}
