package dev.stellaris.optimizer;
import org.bukkit.Bukkit;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import dev.stellaris.module.StellarisModule;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

final class RamSweeper implements OptimizerService {

    private final Stellaris plugin;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final int intervalTicks;
    private final int gcHintPercent;
    private final boolean trimCaches;
    private final boolean chunkSweep;
    private final int keepRadius;
    private final int chunksPerWorldPerRun;
    private final boolean leakWatchdog;
    private final int warnAboveEntries;
    private final int samplesBeforeWarning;
    private int leakSamples;

    RamSweeper(Stellaris plugin) {
        this.plugin = plugin;
        final Cfg cfg = plugin.cfg("optimizer").child("ram-sweeper");
        this.intervalTicks = Math.max(20, cfg.integer("interval-seconds", 60) * 20);
        this.gcHintPercent = cfg.integer("gc-hint-above-percent", -1);
        this.trimCaches = cfg.bool("trim-caches", true);
        final Cfg chunk = cfg.child("chunk-sweep");
        this.chunkSweep = chunk.bool("enabled", false);
        this.keepRadius = Math.max(2, chunk.integer("keep-radius", 6));
        this.chunksPerWorldPerRun = Math.max(1, chunk.integer("chunks-per-world-per-run", 64));
        final Cfg leak = cfg.child("leak-watchdog");
        this.leakWatchdog = leak.bool("enabled", true);
        this.warnAboveEntries = Math.max(1000, leak.integer("warn-above-entries", 20000));
        this.samplesBeforeWarning = Math.max(1, leak.integer("samples-before-warning", 3));
    }

    @Override
    public String id() {
        return "ram-sweeper";
    }

    @Override
    public int periodTicks() {
        return intervalTicks;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void onTick(long tick) {
        final MemoryUsage heap = memory.getHeapMemoryUsage();
        final long max = heap.getMax() <= 0 ? Runtime.getRuntime().maxMemory() : heap.getMax();
        final double percent = max > 0 ? (heap.getUsed() * 100.0D) / max : 0.0D;
        if (trimCaches) {
            try {
                for (StellarisModule module : plugin.modules().active()) {
                    module.trimCaches();
                }
            } catch (Throwable ignored) {
            }
        }
        if (gcHintPercent > 0 && percent >= gcHintPercent) {
            try {
                System.gc();
            } catch (Throwable ignored) {
            }
        }
        if (chunkSweep) {
            sweepChunks();
        }
        if (leakWatchdog) {
            checkLeaks();
        }
    }

    private void sweepChunks() {
        int swept = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (swept++ >= chunksPerWorldPerRun) {
                    break;
                }
                if (shouldUnload(chunk)) {
                    chunk.unload(false);
                }
            }
            if (swept >= chunksPerWorldPerRun) {
                break;
            }
        }
    }

    private boolean shouldUnload(Chunk chunk) {
        if (chunk.isForceLoaded()) {
            return false;
        }
        final Entity[] entities = chunk.getEntities();
        if (entities.length > 0) {
            return false;
        }
        // No players near: simple heuristic.
        if (chunk.getPlayersSeeingChunk().size() > 0) {
            return false;
        }
        // Keep chunks near any player within keepRadius? Coarse check.
        for (org.bukkit.entity.Player player : chunk.getWorld().getPlayers()) {
            final org.bukkit.Location pLoc = player.getLocation();
            final int cx = chunk.getX();
            final int cz = chunk.getZ();
            final int px = pLoc.getBlockX() >> 4;
            final int pz = pLoc.getBlockZ() >> 4;
            if (Math.abs(cx - px) <= keepRadius && Math.abs(cz - pz) <= keepRadius) {
                return false;
            }
        }
        return true;
    }

    private void checkLeaks() {
        int total = 0;
        final OptimizerManager opt = plugin.optimizer();
        if (opt != null) {
            for (OptimizerService svc : opt.layers()) {
                total += svc.trackedEntries();
            }
        }
        if (total > warnAboveEntries) {
            if (++leakSamples >= samplesBeforeWarning) {
                plugin.getLogger().warning("[ram-sweeper] Tracked optimizer entries grew to " + total + " (watchdog threshold " + warnAboveEntries + ")");
                leakSamples = 0;
            }
        } else {
            leakSamples = Math.max(0, leakSamples - 1);
        }
    }
}
