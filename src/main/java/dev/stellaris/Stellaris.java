package dev.stellaris;

import dev.stellaris.command.CommandBinder;
import dev.stellaris.command.StellarisCommand;
import dev.stellaris.config.Cfg;
import dev.stellaris.metrics.ServerMetrics;
import dev.stellaris.module.ModuleRegistry;
import dev.stellaris.optimizer.OptimizerManager;
import dev.stellaris.storage.KeyValueStore;
import dev.stellaris.util.Text;
import org.bukkit.plugin.java.JavaPlugin;

public final class Stellaris extends JavaPlugin {

    private static final boolean FOLIA = detectFolia();
    private static Stellaris instance;

    private ServerMetrics metrics;
    private ModuleRegistry modules;
    private CommandBinder commands;
    private volatile KeyValueStore storage;
    private volatile boolean debug;

    @Override
    public void onLoad() {
        // Publish the instance as early as possible so that other plugins
        // loading before us can already resolve Stellaris.get().
        instance = this;
    }

    @Override
    public void onEnable() {
        final long startedAt = System.nanoTime();
        saveDefaultConfig();
        this.debug = getConfig().getBoolean("debug", false);

        this.commands = new CommandBinder(this);
        this.metrics = new ServerMetrics(this);
        this.modules = new ModuleRegistry(this);

        // Registration order == enable order. Each entry is a lazy factory: when
        // `modules.<id>` is false the supplier is never invoked, so the module
        // class is not even loaded.
        modules.register("optimizer", () -> new OptimizerManager(this));
        modules.register("economy", () -> new dev.stellaris.economy.EconomyManager(this));
        modules.register("core", () -> new dev.stellaris.core.CoreManager(this));
        modules.register("chat", () -> new dev.stellaris.chat.ChatManager(this));
        modules.register("moderation", () -> new dev.stellaris.moderation.ModerationManager(this));
        modules.register("hub", () -> new dev.stellaris.hub.HubManager(this));
        modules.register("holograms", () -> new dev.stellaris.hologram.HologramManager(this));
        modules.register("placeholders", () -> new dev.stellaris.placeholder.PlaceholdersModule(this));

        metrics.start();
        modules.enableAll();

        commands.bind("stellaris", new StellarisCommand(this));
        commands.installFallbacks();

        getLogger().info(String.format("Stellaris %s enabled in %d ms | platform: %s | modules: %s",
                getPluginMeta().getVersion(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                FOLIA ? "Folia" : "Paper",
                modules.summary()));
    }

    @Override
    public void onDisable() {
        // Reverse order: modules first (they may still need the storage/metrics
        // services), then the shared services, then drop the static instance so
        // the plugin class loader can actually be garbage collected.
        if (modules != null) {
            modules.disableAll();
        }
        final KeyValueStore store = this.storage;
        if (store != null) {
            store.close();
            this.storage = null;
        }
        if (metrics != null) {
            metrics.stop();
        }
        this.modules = null;
        this.commands = null;
        this.metrics = null;
        instance = null;
    }

    public void reload() {
        final long startedAt = System.nanoTime();
        modules.disableAll();
        commands.reset();
        reloadConfig();
        this.debug = getConfig().getBoolean("debug", false);
        Text.invalidateCache();
        modules.enableAll();
        commands.installFallbacks();
        getLogger().info(String.format("Reloaded in %d ms | modules: %s", (System.nanoTime() - startedAt) / 1_000_000L, modules.summary()));
    }

    // ── services ──────────────────────────────────────────────────────────────

    public ServerMetrics metrics() {
        return metrics;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public CommandBinder commands() {
        return commands;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isFolia() {
        return FOLIA;
    }

    public boolean moduleEnabled(String id) {
        return getConfig().getBoolean("modules." + id, true);
    }

    public Cfg cfg(String path) {
        return Cfg.of(getConfig().getConfigurationSection(path), getLogger());
    }

    public OptimizerManager optimizer() {
        return modules == null ? null : modules.get("optimizer");
    }

    public KeyValueStore storage() {
        KeyValueStore local = this.storage;
        if (local == null) {
            synchronized (this) {
                local = this.storage;
                if (local == null) {
                    local = KeyValueStore.open(this, cfg("storage"));
                    this.storage = local;
                }
            }
        }
        return local;
    }

    public static Stellaris get() {
        return instance;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException notFolia) {
            return false;
        }
    }
}
