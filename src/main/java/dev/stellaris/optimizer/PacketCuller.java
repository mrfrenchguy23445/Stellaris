package dev.stellaris.optimizer;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network-side culling. Runs on the PacketEvents threads, so every hot path
 * here is allocation-free: one primitive bucket lookup, one squared-distance
 * compare, no wrappers are read unless the budget already passed.
 */
final class PacketCuller implements OptimizerService, PacketListener, Listener {

    private final Stellaris plugin;
    private final int particleBudgetPerSecond;
    private final double maxParticleDistanceSquared;
    private final boolean skipMetadataForAiDisabled;
    private final EntityAiCuller aiCuller;

    private final Map<Player, TokenBucket> particleBudgets = new ConcurrentHashMap<>();

    private volatile boolean registered;
    private volatile PacketListenerCommon handle;
    private long droppedParticles;
    private long droppedMetadata;

    PacketCuller(Stellaris plugin, EntityAiCuller aiCuller) {
        this.plugin = plugin;
        this.aiCuller = aiCuller;
        final Cfg cfg = plugin.cfg("optimizer").child("packets");
        this.particleBudgetPerSecond = Math.max(10, cfg.integer("particle-budget-per-second", 400));
        final double maxDistance = Math.max(4.0D, cfg.dbl("max-particle-distance", 32.0D));
        this.maxParticleDistanceSquared = maxDistance * maxDistance;
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
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            plugin.getLogger().info("PacketEvents not found - the packet layer stays off.");
            return;
        }
        try {
            this.handle = PacketEvents.getAPI().getEventManager().registerListener(this.asAbstract(PacketListenerPriority.NORMAL));
            registered = true;
            Bukkit.getPluginManager().registerEvents(this, plugin);
            debug("Packet layer attached to PacketEvents %s", PacketEvents.getAPI().getVersion());
        } catch (Throwable failure) {
            plugin.getLogger().warning("Could not attach to PacketEvents - packet layer stays off: " + failure.getMessage());
            registered = false;
        }
    }

    @Override
    public void stop() {
        if (registered) {
            try {
                final PacketListenerCommon listener = this.handle;
                if (listener != null) {
                    PacketEvents.getAPI().getEventManager().unregisterListener(listener);
                }
            } catch (Throwable ignored) {
                // PacketEvents may already be shutting down.
            }
            registered = false;
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        particleBudgets.clear();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        final PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.PARTICLE) {
            cullParticle(event);
        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            cullMetadata(event);
        }
    }

    private void cullParticle(PacketSendEvent event) {
        final Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        // 1. Budget: refill lazily, one primitive map hit. This is what stops
        //    particle farms; it never touches the packet body.
        TokenBucket bucket = particleBudgets.get(player);
        if (bucket == null) {
            bucket = new TokenBucket(particleBudgetPerSecond, particleBudgetPerSecond);
            final TokenBucket raced = particleBudgets.putIfAbsent(player, bucket);
            if (raced != null) {
                bucket = raced;
            }
        }
        if (!bucket.tryConsume()) {
            event.setCancelled(true);
            droppedParticles++;
            return;
        }
        // 2. Distance: only now read the packet, once per surviving particle.
        if (maxParticleDistanceSquared <= 0.0D) {
            return;
        }
        final WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(event);
        final Vector3d pos = wrapper.getPosition();
        final Location at = player.getLocation();
        final double dx = pos.getX() - at.getX();
        final double dy = pos.getY() - at.getY();
        final double dz = pos.getZ() - at.getZ();
        if (dx * dx + dy * dy + dz * dz > maxParticleDistanceSquared) {
            event.setCancelled(true);
            droppedParticles++;
        }
    }

    private void cullMetadata(PacketSendEvent event) {
        if (!skipMetadataForAiDisabled || aiCuller == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        final WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
        if (aiCuller.isAiDisabled(wrapper.getEntityId())) {
            event.setCancelled(true);
            droppedMetadata++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        particleBudgets.remove(event.getPlayer());
    }

    @Override
    public void trim() {
        particleBudgets.keySet().removeIf(player -> !player.isOnline());
    }

    @Override
    public int trackedEntries() {
        return particleBudgets.size();
    }

    @Override
    public String status() {
        return String.format("packets: %d particle / %d metadata packets dropped, %d players tracked, attached=%s",
                droppedParticles, droppedMetadata, particleBudgets.size(), registered);
    }

    private void debug(String format, Object... args) {
        if (plugin.isDebug()) {
            plugin.getLogger().info("[debug/packets] " + String.format(format, args));
        }
    }
}
