package dev.stellaris.core;

import dev.stellaris.Stellaris;
import dev.stellaris.module.AbstractModule;

public final class CoreManager extends AbstractModule {

    public CoreManager(Stellaris plugin) {
        super(plugin, "core");
    }

    @Override
    protected void onEnable() {
        // Implemented by the core package (teleports, warps, spawn, tpa, back).
    }
}
