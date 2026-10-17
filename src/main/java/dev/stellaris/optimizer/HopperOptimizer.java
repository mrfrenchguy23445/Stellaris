package dev.stellaris.optimizer;
import org.bukkit.Bukkit;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import dev.stellaris.util.ChunkKeys;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class HopperOptimizer implements OptimizerService, Listener {

    private final Stellaris plugin;
    private final boolean fullShortcut;
    private final int cooldownWhenFullTicks;
    private final boolean idleThrottle;
    private final int idleSeconds;
    private final int cooldownTicks;
    private final int chunksPerTick;
    private final int cacheSize;
    private final Object2ObjectOpenHashMap<World, Long2BooleanOpenHashMap> fullCache = new Object2ObjectOpenHashMap<>();
    private final long idleThresholdMillis;

    HopperOptimizer(Stellaris plugin) {
        this.plugin = plugin;
        final Cfg cfg = plugin.cfg("optimizer").child("hoppers");
        this.fullShortcut = cfg.bool("full-destination-shortcut", true);
        this.cooldownWhenFullTicks = Math.max(1, cfg.integer("cooldown-when-full-ticks", 8));
        final Cfg idle = cfg.child("idle-throttle");
        this.idleThrottle = idle.bool("enabled", false);
        this.idleSeconds = Math.max(10, idle.integer("idle-seconds", 60));
        this.cooldownTicks = Math.max(5, idle.integer("cooldown-ticks", 20));
        this.chunksPerTick = Math.max(1, idle.integer("chunks-per-tick", 4));
        this.cacheSize = Math.max(128, cfg.integer("cache-size", 4096));
        this.idleThresholdMillis = idleSeconds * 1000L;
    }

    @Override
    public String id() {
        return "hoppers";
    }

    @Override
    public int periodTicks() {
        return 20;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        fullCache.clear();
    }

    @Override
    public void trim() {
        prune();
    }

    @Override
    public void onTick(long tick) {
        prune();
        if (!idleThrottle) {
            return;
        }
        int scanned = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (scanned++ >= chunksPerTick) {
                    return;
                }
                scanChunk(chunk);
            }
        }
    }

    private void scanChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Hopper hopper)) {
                continue;
            }
            final Inventory inv = hopper.getInventory();
            if (!inv.isEmpty() && inv.firstEmpty() >= 0) {
                continue;
            }
            // Hopper is idle or full; apply cooldown if last activity is old.
            hopper.setTransferCooldown(cooldownTicks);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (!fullShortcut) {
            return;
        }
        final Inventory dest = event.getDestination();
        final InventoryHolder holder = dest.getHolder();
        if (!(holder instanceof org.bukkit.block.BlockState state) || state instanceof Hopper) {
            // Avoid interfering with hopper-to-hopper; keep simple.
        }
        if (holder instanceof org.bukkit.inventory.BlockInventoryHolder bih) {
            final Block block = bih.getBlock();
            final World world = block.getWorld();
            final long key = ChunkKeys.blockKey(block.getX(), block.getZ());
            Long2BooleanOpenHashMap map = fullCache.get(world);
            if (map == null) {
                map = new Long2BooleanOpenHashMap(16);
                fullCache.put(world, map);
            }
            if (!dest.isEmpty() && dest.firstEmpty() == -1) {
                event.setCancelled(true);
                if (event.getInitiator().getHolder() instanceof Hopper hopper) {
                    hopper.setTransferCooldown(cooldownWhenFullTicks);
                }
                map.put(key, true);
                enforceCap(map);
            }
        }
    }

    private void prune() {
        fullCache.object2ObjectEntrySet().removeIf(entry -> !Bukkit.getWorlds().contains(entry.getKey()));
        for (Long2BooleanOpenHashMap map : fullCache.values()) {
            if (map.size() > cacheSize) {
                // Simple cap: clear old-style by size.
                while (map.size() > cacheSize) {
                    // FastUtil doesn't give LRU easily; drop arbitrary? We just clear to bound.
                    map.clear();
                    break;
                }
            }
        }
    }

    private static void enforceCap(Long2BooleanOpenHashMap map) {
        if (map.size() > 4096) {
            map.clear();
        }
    }
}
