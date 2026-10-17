package dev.stellaris.hub;

import dev.stellaris.Stellaris;
import dev.stellaris.module.AbstractModule;

public final class HubManager extends AbstractModule {

    public HubManager(Stellaris plugin) {
        super(plugin, "hub");
    }

    @Override
    protected void onEnable() {
        // Implemented by the hub package.
    }
}
