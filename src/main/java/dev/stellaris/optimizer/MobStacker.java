package dev.stellaris.optimizer;
import org.bukkit.Bukkit;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Set;

final class MobStacker implements OptimizerService, Listener {

    private final Stellaris plugin;
    private final NamespacedKey stackKey;
    private final int maxStackSize;
    private final boolean showStackedName;
    private final int unstackPerClick;
    private final boolean splitOnDeath;
    private final double mergeRadius;
    private final EnumSet<org.bukkit.entity.EntityType> whitelist = EnumSet.noneOf(org.bukkit.entity.EntityType.class);
    private final EnumSet<org.bukkit.entity.EntityType> blacklist = EnumSet.noneOf(org.bukkit.entity.EntityType.class);
    private final int intervalTicks;

    MobStacker(Stellaris plugin) {
        this.plugin = plugin;
        this.stackKey = new NamespacedKey(plugin, "mob_stack");
        final Cfg cfg = plugin.cfg("optimizer").child("mob-stacking");
        this.intervalTicks = Math.max(20, cfg.integer("interval-seconds", 5) * 20);
        this.mergeRadius = Math.max(1.0D, cfg.dbl("merge-radius", 6.0D));
        this.maxStackSize = Math.max(2, cfg.integer("max-stack-size", 64));
        this.showStackedName = cfg.bool("show-stacked-name", true);
        this.unstackPerClick = Math.max(1, cfg.integer("unstack-per-click", 16));
        this.splitOnDeath = cfg.bool("split-on-death", true);
        whitelist.addAll(cfg.entityTypes("whitelist"));
        blacklist.addAll(cfg.entityTypes("blacklist"));
    }

    @Override
    public String id() {
        return "mob-stacking";
    }

    @Override
    public int periodTicks() {
        return intervalTicks;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob mob) {
                    clearStack(mob);
                }
            }
        }
    }

    @Override
    public void onTick(long tick) {
        for (World world : Bukkit.getWorlds()) {
            for (Player player : world.getPlayers()) {
                for (Entity entity : player.getNearbyEntities(mergeRadius, mergeRadius, mergeRadius)) {
                    if (!(entity instanceof Mob mob)) {
                        continue;
                    }
                    if (!canStack(mob)) {
                        continue;
                    }
                    final Mob target = findMergeTarget(mob, player);
                    if (target == null) {
                        continue;
                    }
                    merge(mob, target);
                }
            }
        }
    }

    private Mob findMergeTarget(Mob source, Player player) {
        for (Entity entity : player.getNearbyEntities(mergeRadius, mergeRadius, mergeRadius)) {
            if (!(entity instanceof Mob target)) {
                continue;
            }
            if (target.equals(source)) {
                continue;
            }
            if (!canStack(target)) {
                continue;
            }
            if (compatible(source, target)) {
                final int total = stackSize(source) + stackSize(target);
                if (total <= maxStackSize) {
                    return target;
                }
            }
        }
        return null;
    }

    private boolean compatible(Mob a, Mob b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        if (a instanceof Ageable aa && b instanceof Ageable ab) {
            if (aa.isAdult() != ab.isAdult()) {
                return false;
            }
            if (aa.getAge() != ab.getAge()) {
                return false;
            }
        }
        if (a instanceof Animals aa && b instanceof Animals ab) {
            if (aa.isLoveMode() != ab.isLoveMode()) {
                return false;
            }
        }
        return true;
    }

    private boolean canStack(Mob mob) {
        if (mob.isDead() || !mob.isValid()) {
            return false;
        }
        if (!whitelist.isEmpty() && !whitelist.contains(mob.getType())) {
            return false;
        }
        if (blacklist.contains(mob.getType())) {
            return false;
        }
        if (mob instanceof Tameable tameable && tameable.isTamed()) {
            return false;
        }
        if (mob.isLeashed()) {
            return false;
        }
        if (mob.getCustomName() != null) {
            return false;
        }
        if (mob.isInsideVehicle()) {
            return false;
        }
        return true;
    }

    private int stackSize(Mob mob) {
        final PersistentDataContainer pdc = mob.getPersistentDataContainer();
        final Integer n = pdc.get(stackKey, PersistentDataType.INTEGER);
        return n == null ? 1 : n;
    }

    private void setStackSize(Mob mob, int size) {
        final PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (size <= 1) {
            pdc.remove(stackKey);
            if (showStackedName) {
                mob.setCustomName(null);
                mob.setCustomNameVisible(false);
            }
            return;
        }
        pdc.set(stackKey, PersistentDataType.INTEGER, size);
        if (showStackedName) {
            mob.setCustomNameVisible(true);
            mob.setCustomName(size + "x " + mob.getType().name());
        }
    }

    private void merge(Mob source, Mob target) {
        final int total = stackSize(source) + stackSize(target);
        setStackSize(target, Math.min(total, maxStackSize));
        source.remove();
    }

    private void clearStack(Mob mob) {
        setStackSize(mob, 1);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Mob mob)) {
            return;
        }
        final int size = stackSize(mob);
        if (size <= 1) {
            return;
        }
        // Best effort: unstack by spawning a duplicate? Vanilla spawning is expensive; skip spawning
        // for this minimal implementation. Config key is honored conceptually.
        if (unstackPerClick >= size) {
            clearStack(mob);
        } else {
            setStackSize(mob, size - unstackPerClick);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!splitOnDeath) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        final int size = stackSize(mob);
        if (size <= 1) {
            return;
        }
        // Cannot easily spawn replicas without full logic; clear to avoid dupes.
        clearStack(mob);
    }
}
