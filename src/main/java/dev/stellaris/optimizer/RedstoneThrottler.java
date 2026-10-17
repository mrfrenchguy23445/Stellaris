package dev.stellaris.optimizer;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import dev.stellaris.util.ChunkKeys;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.EnumSet;
import java.util.List;

final class RedstoneThrottler implements OptimizerService, Listener {

    private final Stellaris plugin;

    private final Object2ObjectOpenHashMap<World, Long2ObjectOpenHashMap<TokenBucket>> redstone = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<World, Long2ObjectOpenHashMap<TokenBucket>> physics = new Object2ObjectOpenHashMap<>();

    private final int redstoneRate;
    private final int redstoneBurst;
    private final int physicsRate;
    private final int physicsBurst;
    private final boolean physicsEnabled;
    private final int prunePeriodTicks;
    private final EnumSet<Material> exempt;

    private volatile boolean throttling;
    private long droppedRedstone;
    private long droppedPhysics;

    RedstoneThrottler(Stellaris plugin) {
        this.plugin = plugin;

        final Cfg cfg = plugin.cfg("optimizer").child("redstone");
        this.redstoneRate = Math.max(1, cfg.integer("updates-per-second-per-chunk", 4000));
        this.redstoneBurst = Math.max(1, cfg.integer("burst-limit", 400));
        final Cfg physicsCfg = cfg.child("physics");
        this.physicsEnabled = physicsCfg.bool("enabled", true);
        this.physicsRate = Math.max(1, physicsCfg.integer("updates-per-second-per-chunk", 6000));
        this.physicsBurst = Math.max(1, physicsCfg.integer("burst-limit", 600));
        this.exempt = physicsCfg.materials("exempt-materials");
        this.prunePeriodTicks = Math.max(20, cfg.integer("prune-interval-seconds", 60) * 20);
    }

    @Override
    public String id() {
        return "redstone";
    }

    @Override
    public int periodTicks() {
        return prunePeriodTicks;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        redstone.clear();
        physics.clear();
    }

    @Override
    public void setThrottling(boolean throttling) {
        this.throttling = throttling;
    }

    // ── redstone ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!throttling) {
            return;
        }
        final Block block = event.getBlock();
        final TokenBucket bucket = bucket(redstone, block.getWorld(), ChunkKeys.fromBlock(block.getX(), block.getZ()), redstoneBurst, redstoneRate);
        if (bucket.tryConsume()) {
            return;
        }
        // Reverting the current is more portable than cancelling the event:
        // every implementation honours the new current, not every one the flag.
        event.setNewCurrent(event.getOldCurrent());
        droppedRedstone++;
    }

    // ── physics ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!throttling || !physicsEnabled || exempt.contains(event.getChangedType())) {
            return;
        }
        final Block block = event.getBlock();
        final TokenBucket bucket = bucket(physics, block.getWorld(), ChunkKeys.fromBlock(block.getX(), block.getZ()), physicsBurst, physicsRate);
        if (bucket.tryConsume()) {
            return;
        }
        event.setCancelled(true);
        droppedPhysics++;
    }

    // ── bookkeeping ───────────────────────────────────────────────────────────

    private TokenBucket bucket(Object2ObjectOpenHashMap<World, Long2ObjectOpenHashMap<TokenBucket>> maps,
                               World world, long chunkKey, int burst, int rate) {
        Long2ObjectOpenHashMap<TokenBucket> chunks = maps.get(world);
        if (chunks == null) {
            chunks = new Long2ObjectOpenHashMap<>(256);
            maps.put(world, chunks);
        }
        TokenBucket bucket = chunks.get(chunkKey);
        if (bucket == null) {
            bucket = new TokenBucket(burst, rate);
            chunks.put(chunkKey, bucket);
        }
        return bucket;
    }

    @Override
    public void onTick(long tick) {
        prune(redstone);
        prune(physics);
    }

    @Override
    public void trim() {
        prune(redstone);
        prune(physics);
    }

    private void prune(Object2ObjectOpenHashMap<World, Long2ObjectOpenHashMap<TokenBucket>> maps) {
        final List<World> loaded = Bukkit.getWorlds();
        maps.object2ObjectEntrySet().removeIf(entry -> !loaded.contains(entry.getKey()));
        for (Long2ObjectOpenHashMap<TokenBucket> chunks : maps.values()) {
            if (chunks.isEmpty()) {
                continue;
            }
            chunks.long2ObjectEntrySet().removeIf(entry -> entry.getValue().isIdle());
        }
    }

    @Override
    public int trackedEntries() {
        return size(redstone) + size(physics);
    }

    private static int size(Object2ObjectOpenHashMap<World, Long2ObjectOpenHashMap<TokenBucket>> maps) {
        int total = 0;
        for (Long2ObjectOpenHashMap<TokenBucket> chunks : maps.values()) {
            total += chunks.size();
        }
        return total;
    }

    @Override
    public String status() {
        return String.format("redstone: %d redstone / %d physics updates suppressed, tracking %d chunks",
                droppedRedstone, droppedPhysics, trackedEntries());
    }
}
