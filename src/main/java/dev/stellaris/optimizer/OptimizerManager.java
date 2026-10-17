package dev.stellaris.optimizer;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import dev.stellaris.module.AbstractModule;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

public final class OptimizerManager extends AbstractModule {

    private final List<OptimizerService> services = new ArrayList<>(9);
    private final List<String> failed = new ArrayList<>(2);

    private ScheduledTask dispatcher;
    private long tick;

    private boolean lagDetectorEnabled;
    private double lagTpsThreshold;
    private int samplesToThrottle;
    private int samplesToRecover;
    private int badSamples;
    private int goodSamples;
    private volatile boolean throttling;

    public OptimizerManager(Stellaris plugin) {
        super(plugin, "optimizer");
    }

    @Override
    protected void onEnable() {
        final Cfg cfg = config();

        final Cfg lag = cfg.child("lag-detector");
        this.lagDetectorEnabled = lag.bool("enabled", true);
        this.lagTpsThreshold = Math.max(0.0D, lag.dbl("tps-threshold", 18.5D));
        this.samplesToThrottle = Math.max(1, lag.integer("samples-before-throttle", 3));
        this.samplesToRecover = Math.max(1, lag.integer("samples-before-recovery", 5));

        // Order matters for /stellaris optimize output and for shutdown, which
        // walks the list backwards.
        if (cfg.child("view-distance").bool("enabled", true)) {
            services.add(new ViewDistanceTuner(plugin));
        }
        if (cfg.child("entity-ai").bool("enabled", true)) {
            services.add(new EntityAiCuller(plugin));
        }
        if (cfg.child("redstone").bool("enabled", true)) {
            services.add(new RedstoneThrottler(plugin));
        }
        if (cfg.child("hoppers").bool("enabled", true)) {
            services.add(new HopperOptimizer(plugin));
        }
        if (cfg.child("explosions").bool("enabled", true)) {
            services.add(new ExplosionOptimizer(plugin));
        }
        if (cfg.child("item-stacking").bool("enabled", true)) {
            services.add(new ItemStacker(plugin));
        }
        if (cfg.child("mob-stacking").bool("enabled", false)) {
            services.add(new MobStacker(plugin));
        }
        if (cfg.child("packets").bool("enabled", true)) {
            services.add(new PacketCuller(plugin));
        }
        if (cfg.child("ram-sweeper").bool("enabled", true)) {
            services.add(new RamSweeper(plugin));
        }

        final Iterator<OptimizerService> iterator = services.iterator();
        while (iterator.hasNext()) {
            final OptimizerService service = iterator.next();
            try {
                service.start();
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.SEVERE, "Optimizer layer '" + service.id() + "' failed to start and was dropped", failure);
                failed.add(service.id());
                iterator.remove();
            }
        }

        if (services.isEmpty()) {
            plugin.getLogger().warning("Every optimizer layer is switched off - the optimizer module is idling.");
            return;
        }

        // One task to drive them all. 20-tick delay: let the server finish
        // starting up before the first evaluation.
        this.dispatcher = repeatTicks(this::dispatch, 20L, 1L);
        debug("Started %d optimizer layers: %s", services.size(), layerIds());
    }

    @Override
    protected void onDisable() {
        final ScheduledTask task = this.dispatcher;
        if (task != null) {
            task.cancel();
            this.dispatcher = null;
        }
        for (int i = services.size() - 1; i >= 0; i--) {
            try {
                services.get(i).stop();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Error stopping optimizer layer '" + services.get(i).id() + "'", t);
            }
        }
        services.clear();
        failed.clear();
        throttling = false;
    }

    @Override
    protected void onTrimCaches() {
        for (int i = 0, size = services.size(); i < size; i++) {
            services.get(i).trim();
        }
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    private void dispatch() {
        final long now = ++tick;

        if (lagDetectorEnabled && (now % 20L) == 0L) {
            evaluateLag();
        }

        for (int i = services.size() - 1; i >= 0; i--) {
            final OptimizerService service = services.get(i);
            if ((now % service.periodTicks()) != 0L) {
                continue;
            }
            try {
                service.onTick(now);
            } catch (Throwable failure) {
                // Fail fast and loud once, then get out of the way: a layer that
                // throws every tick would be worse than the lag it is fixing.
                plugin.getLogger().log(Level.SEVERE, "Optimizer layer '" + service.id() + "' threw during onTick and was disabled", failure);
                try {
                    service.stop();
                } catch (Throwable ignored) {
                    // Already broken; nothing else to do.
                }
                failed.add(service.id());
                services.remove(i);
            }
        }
    }

    private void evaluateLag() {
        final boolean lagging = plugin.metrics().tpsShort() < lagTpsThreshold;
        if (lagging) {
            goodSamples = 0;
            if (++badSamples >= samplesToThrottle) {
                setThrottling(true);
            }
        } else {
            badSamples = 0;
            if (++goodSamples >= samplesToRecover) {
                setThrottling(false);
            }
        }
    }

    private void setThrottling(boolean value) {
        if (this.throttling == value) {
            return;
        }
        this.throttling = value;
        for (int i = 0, size = services.size(); i < size; i++) {
            services.get(i).setThrottling(value);
        }
        plugin.getLogger().info(value
                ? String.format("[optimizer] TPS below %.1f for %d samples - engaging soft throttles.", lagTpsThreshold, samplesToThrottle)
                : String.format("[optimizer] TPS recovered for %d samples - releasing soft throttles.", samplesToRecover));
    }

    // ── introspection ─────────────────────────────────────────────────────────

    public boolean isThrottling() {
        return throttling;
    }

    public List<String> status() {
        final List<String> lines = new ArrayList<>(services.size() + 4);
        lines.add(String.format("TPS %.2f (1m %.2f) | MSPT %.2f ms | peak %.2f ms | throttling: %s",
                plugin.metrics().tpsShort(), plugin.metrics().tps1m(), plugin.metrics().mspt(), plugin.metrics().msptPeak(),
                throttling ? "yes" : "no"));
        if (failed.isEmpty()) {
            lines.add("Layers: " + layerIds());
        } else {
            lines.add("Layers: " + layerIds() + " (disabled after failure: " + String.join(", ", failed) + ")");
        }
        for (int i = 0, size = services.size(); i < size; i++) {
            lines.add("  " + services.get(i).status());
        }
        return lines;
    }

    List<OptimizerService> layers() {
        return services;
    }

    private String layerIds() {
        final StringBuilder builder = new StringBuilder(64);
        for (int i = 0, size = services.size(); i < size; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(services.get(i).id());
        }
        return builder.toString();
    }
}
