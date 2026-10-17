package dev.stellaris.metrics;

import dev.stellaris.Stellaris;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ServerMetrics {

    private final Stellaris plugin;
    private final OnlineCounter onlineCounter = new OnlineCounter();
    private ScheduledTask sampler;

    private volatile double mspt;
    private volatile double msptPeak;
    private volatile double tpsShort;
    private volatile double tps1m;
    private volatile double tps5m;
    private volatile double tps15m;
    private volatile long heapUsed;
    private volatile long heapCommitted;
    private volatile long heapMax;

    private long tickCounter;

    public ServerMetrics(Stellaris plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(onlineCounter, plugin);
        onlineCounter.seed();
        sampler = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> sample(), 1L, 1L);
    }

    public void stop() {
        final ScheduledTask task = this.sampler;
        if (task != null) {
            task.cancel();
            this.sampler = null;
        }
        HandlerList.unregisterAll(onlineCounter);
    }

    private void sample() {
        final double average = Bukkit.getAverageTickTime();
        this.mspt = average;
        this.tpsShort = average > 0.0D ? Math.min(20.0D, 1000.0D / average) : 20.0D;

        // The expensive-ish reads (TPS arrays, tick history scan, Runtime) are
        // amortised to once per second.
        if ((tickCounter++ % 20L) == 0L) {
            final double[] tps = Bukkit.getTPS();
            this.tps1m = tps[0];
            this.tps5m = tps[1];
            this.tps15m = tps[2];

            final long[] tickTimes = Bukkit.getTickTimes();
            double peak = 0.0D;
            for (int i = 0; i < tickTimes.length; i++) {
                final double millis = tickTimes[i] / 1_000_000.0D;
                if (millis > peak) {
                    peak = millis;
                }
            }
            this.msptPeak = peak;

            final Runtime runtime = Runtime.getRuntime();
            this.heapUsed = runtime.totalMemory() - runtime.freeMemory();
            this.heapCommitted = runtime.totalMemory();
            this.heapMax = runtime.maxMemory();
        }
    }

    // ── read side ─────────────────────────────────────────────────────────────

    public double mspt() {
        return mspt;
    }

    public double msptPeak() {
        return msptPeak;
    }

    public double tpsShort() {
        return tpsShort;
    }

    public double tps1m() {
        return tps1m;
    }

    public double tps5m() {
        return tps5m;
    }

    public double tps15m() {
        return tps15m;
    }

    public int onlinePlayers() {
        return onlineCounter.online;
    }

    public long heapUsed() {
        return heapUsed;
    }

    public long heapCommitted() {
        return heapCommitted;
    }

    public long heapMax() {
        return heapMax;
    }

    public double heapUsedPercent() {
        final long max = heapMax;
        return max <= 0L ? 0.0D : (heapUsed * 100.0D) / max;
    }

    public boolean isLagging(double tpsThreshold) {
        return tpsShort < tpsThreshold;
    }

    private final class OnlineCounter implements Listener {

        private volatile int online;

        void seed() {
            this.online = Bukkit.getOnlinePlayers().size();
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            online++;
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            final int current = online;
            online = current > 0 ? current - 1 : 0;
        }
    }
}
