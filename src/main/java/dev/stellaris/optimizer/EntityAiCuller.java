package dev.stellaris.optimizer;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

import java.util.EnumSet;
import java.util.Set;

final class EntityAiCuller implements OptimizerService {

    private final Stellaris plugin;
    private final Set<EntityType> ignoreTypes = EnumSet.noneOf(EntityType.class);
    private final boolean ignoreTamed;
    private final boolean ignoreLeashed;
    private final boolean ignoreNamed;
    private final boolean ignoreInVehicle;
    private final boolean enclosedSpaceEnabled;
    private final double disableDistance;
    private final double reenableDistance;
    private final double enclosedDistance;
    private final int intervalTicks;
    private final ObjectOpenHashSet<Mob> disabled = new ObjectOpenHashSet<>(256);

    // Immutable snapshot of the culled entity ids, read lock-free from the
    // packet threads (see PacketCuller). Rebuilt once per scan interval.
    private volatile IntOpenHashSet disabledIds = new IntOpenHashSet();

    EntityAiCuller(Stellaris plugin) {
        this.plugin = plugin;
        final Cfg cfg = plugin.cfg("optimizer").child("entity-ai");
        this.intervalTicks = Math.max(20, cfg.integer("interval-seconds", 3) * 20);
        this.disableDistance = Math.max(1.0D, cfg.dbl("disable-distance", 48.0D));
        this.reenableDistance = Math.max(1.0D, cfg.dbl("reenable-distance", 40.0D));
        this.enclosedSpaceEnabled = cfg.bool("enclosed-space.enabled", true);
        this.enclosedDistance = Math.max(1.0D, cfg.dbl("enclosed-space.distance", 24.0D));
        this.ignoreTamed = cfg.bool("ignore-tamed", true);
        this.ignoreLeashed = cfg.bool("ignore-leashed", true);
        this.ignoreNamed = cfg.bool("ignore-named", true);
        this.ignoreInVehicle = cfg.bool("ignore-in-vehicle", true);
        this.ignoreTypes.addAll(cfg.entityTypes("ignore-types"));
    }

    @Override
    public String id() {
        return "entity-ai";
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
        for (Mob mob : disabled) {
            if (mob.isValid() && !mob.isDead()) {
                mob.setAware(true);
            }
        }
        disabled.clear();
        disabledIds = new IntOpenHashSet();
    }

    @Override
    public void trim() {
        disabled.removeIf(mob -> !mob.isValid() || mob.isDead());
        disabledIds = snapshotIds();
    }

    boolean isAiDisabled(int entityId) {
        return disabledIds.contains(entityId);
    }

    private IntOpenHashSet snapshotIds() {
        final IntOpenHashSet ids = new IntOpenHashSet(disabled.size());
        for (Mob mob : disabled) {
            ids.add(mob.getEntityId());
        }
        return ids;
    }

    @Override
    public void onTick(long tick) {
        // Paper path: scan worlds on the global scheduler. (Folia-aware logic could run per-player;
        // this implementation is safe on Paper and compiles on Folia.)
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob mob)) {
                    continue;
                }
                if (mob.isDead() || !mob.isValid()) {
                    disabled.remove(mob);
                    continue;
                }
                if (shouldIgnore(mob)) {
                    if (disabled.remove(mob)) {
                        mob.setAware(true);
                    }
                    continue;
                }
                final double dist = distanceToNearestPlayer(mob);
                if (dist < 0) {
                    // No players nearby; treat as far.
                    if (mob.isAware() && distNearestIsLarge(world, mob, disableDistance)) {
                        mob.setAware(false);
                        disabled.add(mob);
                    }
                    continue;
                }
                if (mob.isAware()) {
                    if (dist >= disableDistance) {
                        mob.setAware(false);
                        disabled.add(mob);
                    }
                } else {
                    if (dist <= reenableDistance) {
                        mob.setAware(true);
                        disabled.remove(mob);
                    } else if (enclosedSpaceEnabled && dist <= enclosedDistance && isEnclosedSpace(world, mob)) {
                        mob.setAware(true);
                        disabled.remove(mob);
                    }
                }
            }
        }
        disabledIds = snapshotIds();
    }

    private boolean shouldIgnore(Mob mob) {
        if (ignoreTypes.contains(mob.getType())) {
            return true;
        }
        if (ignoreTamed && mob instanceof Tameable tameable && tameable.isTamed()) {
            return true;
        }
        if (ignoreLeashed && mob.isLeashed()) {
            return true;
        }
        if (ignoreNamed && mob.getCustomName() != null) {
            return true;
        }
        if (ignoreInVehicle && mob.isInsideVehicle()) {
            return true;
        }
        return false;
    }

    private double distanceToNearestPlayer(Mob mob) {
        final Location loc = mob.getLocation();
        double closest = Double.MAX_VALUE;
        boolean hasPlayer = false;
        for (Player player : mob.getWorld().getPlayers()) {
            hasPlayer = true;
            final double d = player.getLocation().distanceSquared(loc);
            if (d < closest) {
                closest = d;
            }
        }
        if (!hasPlayer) {
            return -1.0D;
        }
        return Math.sqrt(closest);
    }

    private boolean distNearestIsLarge(World world, Mob mob, double threshold) {
        final Location loc = mob.getLocation();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(loc) < threshold) {
                return false;
            }
        }
        return true;
    }

    private boolean isEnclosedSpace(World world, Mob mob) {
        final Location loc = mob.getLocation();
        final int x = loc.getBlockX();
        final int y = loc.getBlockY();
        final int z = loc.getBlockZ();
        final int skyLight = world.getHighestBlockYAt(x, z);
        return y > skyLight;
    }

    @Override
    public int trackedEntries() {
        return disabled.size();
    }

    @Override
    public String status() {
        return String.format("entity-ai: %d mobs AI-disabled, disable<=%.1f reenable<=%.1f",
                disabled.size(), disableDistance, reenableDistance);
    }
}
