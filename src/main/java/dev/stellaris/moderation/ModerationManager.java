package dev.stellaris.moderation;

import dev.stellaris.Stellaris;
import dev.stellaris.module.AbstractModule;

public final class ModerationManager extends AbstractModule {

    public ModerationManager(Stellaris plugin) {
        super(plugin, "moderation");
    }

    @Override
    protected void onEnable() {
        // Implemented by the moderation package.
    }
}
