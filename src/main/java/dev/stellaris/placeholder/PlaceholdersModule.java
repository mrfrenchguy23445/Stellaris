package dev.stellaris.placeholder;

import dev.stellaris.Stellaris;
import dev.stellaris.module.AbstractModule;
import dev.stellaris.util.Placeholders;
import org.bukkit.Bukkit;

public final class PlaceholdersModule extends AbstractModule {

    private StellarisExpansion expansion;

    public PlaceholdersModule(Stellaris plugin) {
        super(plugin, "placeholders");
    }

    @Override
    protected void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().info("PlaceholderAPI not found - the %stellaris_*% expansion will not be registered.");
            return;
        }
        this.expansion = new StellarisExpansion(plugin);
        this.expansion.register();
        Placeholders.install(this.expansion::apply);
        debug("Registered the %stellaris_*% expansion.");
    }

    @Override
    protected void onDisable() {
        Placeholders.uninstall();
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
    }
}
