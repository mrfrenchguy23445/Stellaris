package dev.stellaris.optimizer;
import org.bukkit.Bukkit;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

final class ExplosionOptimizer implements OptimizerService, Listener {

    private final Stellaris plugin;
    private final boolean onlyWhenLagging;
    private final int maxPerTick;
    private final int maxBlocksPerExplosion;
    private final double yieldMultiplier;
    private volatile boolean throttling;
    private int explosionsThisTick;
    private long lastTick;

    ExplosionOptimizer(Stellaris plugin) {
        this.plugin = plugin;
        final Cfg cfg = plugin.cfg("optimizer").child("explosions");
        this.onlyWhenLagging = cfg.bool("only-when-lagging", true);
        this.maxPerTick = Math.max(1, cfg.integer("max-explosions-per-tick", 40));
        this.maxBlocksPerExplosion = Math.max(0, cfg.integer("max-blocks-per-explosion", 0));
        this.yieldMultiplier = Math.max(0.0D, Math.min(5.0D, cfg.dbl("yield-multiplier", 1.0D)));
    }

    @Override
    public String id() {
        return "explosions";
    }

    @Override
    public int periodTicks() {
        return 1;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        explosionsThisTick = 0;
    }

    @Override
    public void setThrottling(boolean throttling) {
        this.throttling = throttling;
    }

    @Override
    public void onTick(long tick) {
        if (lastTick != tick) {
            lastTick = tick;
            explosionsThisTick = 0;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!apply(event)) {
            return;
        }
        if (maxBlocksPerExplosion > 0 && event.blockList().size() > maxBlocksPerExplosion) {
            event.blockList().subList(maxBlocksPerExplosion, event.blockList().size()).clear();
        }
        if (yieldMultiplier != 1.0D) {
            event.setYield((float) (event.getYield() * yieldMultiplier));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!apply(event)) {
            return;
        }
        if (maxBlocksPerExplosion > 0 && event.blockList().size() > maxBlocksPerExplosion) {
            event.blockList().subList(maxBlocksPerExplosion, event.blockList().size()).clear();
        }
        if (yieldMultiplier != 1.0D) {
            event.setYield((float) (event.getYield() * yieldMultiplier));
        }
    }

    private boolean apply(org.bukkit.event.Cancellable event) {
        if (onlyWhenLagging && !throttling) {
            return true;
        }
        explosionsThisTick++;
        if (explosionsThisTick > maxPerTick) {
            event.setCancelled(true);
            return false;
        }
        return true;
    }
}
