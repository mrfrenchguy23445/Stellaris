package dev.stellaris.optimizer;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import dev.stellaris.metrics.ServerMetrics;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ViewDistanceTuner implements OptimizerService {

    private final Stellaris plugin;
    private final ServerMetrics metrics;

    private final int minView;
    private final int maxView;
    private final int minSim;
    private final int maxSim;
    private final int step;
    private final int lagReduction;
    private final double playerPenalty;
    private final int manualBaseline;
    private final long intervalTicks;
    private final long cooldownMillis;
    private final boolean perPlayer;
    private final boolean restoreOnDisable;
    private final Set<String> worlds;

    private final Object2IntOpenHashMap<String> originalView = new Object2IntOpenHashMap<>();
    private final Object2IntOpenHashMap<String> originalSim = new Object2IntOpenHashMap<>();

    private boolean throttling;
    private long lastChangeMillis;

    ViewDistanceTuner(Stellaris plugin) {
        this.plugin = plugin;
        this.metrics = plugin.metrics();

        final Cfg cfg = plugin.cfg("optimizer").child("view-distance");
        this.minView = clampView(cfg.integer("min-view-distance", 4));
        this.maxView = Math.max(minView, clampView(cfg.integer("max-view-distance", 12)));
        this.minSim = clampView(cfg.integer("min-simulation-distance", 4));
        this.maxSim = Math.max(minSim, clampView(cfg.integer("max-simulation-distance", 10)));
        this.step = Math.max(1, cfg.integer("step", 1));
        this.lagReduction = Math.max(0, cfg.integer("lag-reduction", 2));
        this.playerPenalty = Math.max(0.0D, cfg.dbl("player-penalty", 0.05D));
        this.manualBaseline = cfg.integer("manual-baseline", -1);
        this.intervalTicks = Math.max(20L, cfg.lng("interval-seconds", 10L) * 20L);
        this.cooldownMillis = Math.max(1000L, cfg.lng("cooldown-seconds", 30L) * 1000L);
        this.perPlayer = cfg.bool("per-player", false);
        this.restoreOnDisable = cfg.bool("restore-on-disable", true);
        this.worlds = new HashSet<>(cfg.strList("worlds"));

        // Snapshot the admin's values once, up front.
        final List<World> tuned = tuneWorlds();
        for (int i = 0, size = tuned.size(); i < size; i++) {
            final World world = tuned.get(i);
            if (!originalView.containsKey(world.getName())) {
                originalView.put(world.getName(), world.getViewDistance());
                originalSim.put(world.getName(), world.getSimulationDistance());
            }
        }
    }

    @Override
    public String id() {
        return "view-distance";
    }

    @Override
    public int periodTicks() {
        return (int) Math.min(Integer.MAX_VALUE, intervalTicks);
    }

    @Override
    public void start() {
        // Nothing to schedule: the manager's dispatcher drives onTick().
    }

    @Override
    public void stop() {
        if (!restoreOnDisable) {
            return;
        }
        for (World world : tuneWorlds()) {
            final int view = originalView.getOrDefault(world.getName(), Integer.MIN_VALUE);
            final int sim = originalSim.getOrDefault(world.getName(), Integer.MIN_VALUE);
            if (view != Integer.MIN_VALUE) {
                world.setViewDistance(view);
            }
            if (sim != Integer.MIN_VALUE) {
                world.setSimulationDistance(sim);
            }
        }
        originalView.clear();
        originalSim.clear();
    }

    @Override
    public void setThrottling(boolean throttling) {
        this.throttling = throttling;
    }

    @Override
    public void onTick(long tick) {
        if (System.currentTimeMillis() - lastChangeMillis < cooldownMillis) {
            return;
        }

        final int players = metrics.onlinePlayers();
        final int baselineSim = baseline(maxSim, minSim, players);
        final int baselineView = baseline(maxView, minView, players);
        final int reduction = throttling ? lagReduction : 0;
        final int desiredSim = clamp(baselineSim - reduction, minSim, maxSim);
        final int desiredView = clamp(baselineView - reduction, minView, maxView);

        boolean changed;
        if (perPlayer) {
            changed = applyPerPlayer(desiredView, desiredSim);
        } else {
            changed = false;
            final List<World> tuned = tuneWorlds();
            for (int i = 0, size = tuned.size(); i < size; i++) {
                changed |= apply(tuned.get(i), desiredView, desiredSim);
            }
        }

        if (changed) {
            lastChangeMillis = System.currentTimeMillis();
            debug("Applied view=%d sim=%d (players=%d, throttling=%s)", desiredView, desiredSim, players, throttling);
        }
    }

    private boolean apply(World world, int desiredView, int desiredSim) {
        final int currentView = world.getViewDistance();
        final int currentSim = world.getSimulationDistance();
        final int nextView = approach(currentView, desiredView, step);
        final int nextSim = approach(currentSim, desiredSim, step);
        if (nextView == currentView && nextSim == currentSim) {
            return false;
        }
        world.setViewDistance(nextView);
        world.setSimulationDistance(nextSim);
        return true;
    }

    private boolean applyPerPlayer(int desiredView, int desiredSim) {
        boolean changed = false;
        for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
            if (!worlds.isEmpty() && !worlds.contains(player.getWorld().getName())) {
                continue;
            }
            if (player.getViewDistance() != desiredView) {
                player.setViewDistance(desiredView);
                changed = true;
            }
            if (player.getSimulationDistance() != desiredSim) {
                player.setSimulationDistance(desiredSim);
                changed = true;
            }
        }
        return changed;
    }

    private int baseline(int max, int min, int players) {
        if (manualBaseline > 0) {
            return clamp(Math.min(manualBaseline, max), min, max);
        }
        return clamp((int) Math.round(max - players * playerPenalty), min, max);
    }

    private List<World> tuneWorlds() {
        if (worlds.isEmpty()) {
            return plugin.getServer().getWorlds();
        }
        final List<World> out = new ArrayList<>(worlds.size());
        for (World world : plugin.getServer().getWorlds()) {
            if (worlds.contains(world.getName())) {
                out.add(world);
            }
        }
        return out;
    }

    private static int approach(int current, int desired, int step) {
        if (current < desired) {
            return Math.min(current + step, desired);
        }
        if (current > desired) {
            return Math.max(current - step, desired);
        }
        return current;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static int clampView(int value) {
        return Math.max(2, Math.min(32, value));
    }

    @Override
    public String status() {
        return String.format("view-distance: view %d-%d, sim %d-%d, step %d, cooldown %ds, per-player %s",
                minView, maxView, minSim, maxSim, step, cooldownMillis / 1000L, perPlayer);
    }

    private void debug(String format, Object... args) {
        if (plugin.isDebug()) {
            plugin.getLogger().info("[debug/view-distance] " + String.format(format, args));
        }
    }
}
