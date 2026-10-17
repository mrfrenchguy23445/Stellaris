package dev.stellaris.module;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public abstract class AbstractModule implements StellarisModule {

    protected final Stellaris plugin;
    private final String id;

    private final List<ScheduledTask> tasks = new ArrayList<>(4);
    private final List<Listener> listeners = new ArrayList<>(4);
    private final List<Runnable> cleanup = new ArrayList<>(2);

    private Cfg config = Cfg.empty(null);
    private boolean active;

    protected AbstractModule(Stellaris plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final boolean isActive() {
        return active;
    }

    @Override
    public final void enable() {
        this.config = Cfg.of(plugin.getConfig().getConfigurationSection(id), plugin.getLogger());
        this.active = true;
        try {
            onEnable();
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.SEVERE, "Stellaris module '" + id + "' failed to enable; rolling back", failure);
            disable();
        }
    }

    @Override
    public final void disable() {
        if (!active) {
            return;
        }
        active = false;

        // 1. Stop producing first. Once tasks are cancelled and listeners are
        //    gone, the teardown below cannot race with a live callback.
        for (int i = tasks.size() - 1; i >= 0; i--) {
            try {
                tasks.get(i).cancel();
            } catch (Throwable ignored) {
                // A task that has already finished is not an error.
            }
        }
        tasks.clear();

        for (int i = listeners.size() - 1; i >= 0; i--) {
            try {
                HandlerList.unregisterAll(listeners.get(i));
            } catch (Throwable ignored) {
                // Ignore double unregistration.
            }
        }
        listeners.clear();

        // 2. Let the module drop its own strong references.
        try {
            onDisable();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Error while disabling Stellaris module '" + id + "'", t);
        }

        // 3. Finally run the cleanup hooks (close inventories, clear maps...).
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Cleanup hook failed in Stellaris module '" + id + "'", t);
            }
        }
        cleanup.clear();

        this.config = Cfg.empty(null);
    }

    @Override
    public final void trimCaches() {
        if (!active) {
            return;
        }
        try {
            onTrimCaches();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "trimCaches() failed in Stellaris module '" + id + "'", t);
        }
    }

    protected abstract void onEnable();

    protected void onDisable() {
    }

    protected void onTrimCaches() {
    }

    // ── accessors ─────────────────────────────────────────────────────────────

    protected final Cfg config() {
        return config;
    }

    protected final Stellaris plugin() {
        return plugin;
    }

    protected final void debug(String format, Object... args) {
        if (plugin.isDebug()) {
            plugin.getLogger().info("[debug/" + id + "] " + String.format(format, args));
        }
    }

    // ── scheduling ────────────────────────────────────────────────────────────

    protected final ScheduledTask repeatTicks(Runnable body, long initialDelayTicks, long periodTicks) {
        return track(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> body.run(),
                Math.max(0L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    protected final ScheduledTask repeatAsync(Runnable body, long initialDelayMillis, long periodMillis) {
        return track(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> body.run(),
                Math.max(0L, initialDelayMillis), Math.max(1L, periodMillis), TimeUnit.MILLISECONDS));
    }

    protected final ScheduledTask delayTicks(Runnable body, long delayTicks) {
        return track(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> body.run(), Math.max(1L, delayTicks)));
    }

    protected final void sync(Runnable body) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> body.run());
    }

    protected final void async(Runnable body) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> body.run());
    }

    protected final void atLocation(Location where, Runnable body) {
        Bukkit.getRegionScheduler().run(plugin, where, task -> body.run());
    }

    // ── registration ──────────────────────────────────────────────────────────

    protected final void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    protected final void command(String name, CommandExecutor executor) {
        plugin.commands().bind(name, executor, executor instanceof TabCompleter completer ? completer : null);
    }

    protected final void onCleanup(Runnable hook) {
        cleanup.add(hook);
    }

    private ScheduledTask track(ScheduledTask task) {
        tasks.add(task);
        return task;
    }
}
