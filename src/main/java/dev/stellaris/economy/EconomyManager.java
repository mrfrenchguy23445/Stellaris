package dev.stellaris.economy;

import dev.stellaris.Stellaris;
import dev.stellaris.module.AbstractModule;

public final class EconomyManager extends AbstractModule {

    public EconomyManager(Stellaris plugin) {
        super(plugin, "economy");
    }

    @Override
    protected void onEnable() {
        // Implemented by the economy package.
    }

    public long balance(java.util.UUID player) {
        return 0L;
    }

    public String format(long amount) {
        return Long.toString(amount);
    }
}
