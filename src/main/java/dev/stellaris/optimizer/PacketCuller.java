package dev.stellaris.optimizer;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PacketCuller implements OptimizerService, Listener {

    private final Stellaris plugin;
    private final int particleBudgetPerSecond;
    private final double maxParticleDistance;
    private final boolean skipMetadataForAiDisabled;
    private final Map<Player, Long> lastCheck = new ConcurrentHashMap<>();
    private final Map<Player, Integer> budget = new ConcurrentHashMap<>();

    PacketCuller(Stellaris plugin) {
        this.plugin = plugin;
        final Cfg cfg = plugin.cfg("optimizer").child("packets");
        this.particleBudgetPerSecond = Math.max(10, cfg.integer("particle-budget-per-second", 400));
        this.maxParticleDistance = Math.max(4.0D, cfg.dbl("max-particle-distance", 32.0D));
        this.skipMetadataForAiDisabled = cfg.bool("skip-metadata-for-ai-disabled", true);
    }

    @Override
    public String id() {
        return "packets";
    }

    @Override
    public int periodTicks() {
        return 20;
    }

    @Override
    public void start() {
        // PacketEvents integration is optional; keep lightweight stub.
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        org.bukkit.event.HandlerList.unregisterAll(this);
        lastCheck.clear();
        budget.clear();
    }

    @Override
    public void trim() {
        lastCheck.keySet().removeIf(p -> !p.isOnline());
        budget.keySet().removeIf(p -> !p.isOnline());
    }
}
