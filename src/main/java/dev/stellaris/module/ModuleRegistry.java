package dev.stellaris.module;

import dev.stellaris.Stellaris;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class ModuleRegistry {

    private final Stellaris plugin;
    private final Map<String, Supplier<? extends StellarisModule>> factories = new LinkedHashMap<>();
    private final Map<String, StellarisModule> active = new LinkedHashMap<>();

    public ModuleRegistry(Stellaris plugin) {
        this.plugin = plugin;
    }

    public void register(String id, Supplier<? extends StellarisModule> factory) {
        if (factories.putIfAbsent(id, factory) != null) {
            throw new IllegalStateException("Duplicate Stellaris module id: " + id);
        }
    }

    public void enableAll() {
        for (Map.Entry<String, Supplier<? extends StellarisModule>> entry : factories.entrySet()) {
            final String id = entry.getKey();
            if (!plugin.moduleEnabled(id)) {
                plugin.getLogger().info("Module '" + id + "' disabled in config.yml - not loaded (0% CPU, 0 RAM).");
                continue;
            }

            final long start = System.nanoTime();
            final StellarisModule module;
            try {
                module = entry.getValue().get();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Could not construct Stellaris module '" + id + "'", t);
                continue;
            }
            if (module == null) {
                continue;
            }

            module.enable();
            if (module.isActive()) {
                active.put(id, module);
                plugin.getLogger().info(String.format("  \u2713 %-13s %.1f ms", id, (System.nanoTime() - start) / 1_000_000.0D));
            }
        }
    }

    public void disableAll() {
        final List<StellarisModule> snapshot = new ArrayList<>(active.values());
        Collections.reverse(snapshot);
        for (StellarisModule module : snapshot) {
            try {
                module.disable();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Error disabling Stellaris module '" + module.id() + "'", t);
            }
        }
        active.clear();
    }

    public void trimAll() {
        for (StellarisModule module : active.values()) {
            module.trimCaches();
        }
    }

    public boolean isActive(String id) {
        return active.containsKey(id);
    }

    @SuppressWarnings("unchecked")
    public <T extends StellarisModule> T get(String id) {
        return (T) active.get(id);
    }

    public Collection<StellarisModule> active() {
        return Collections.unmodifiableCollection(active.values());
    }

    public java.util.Set<String> registeredIds() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    public String summary() {
        if (active.isEmpty()) {
            return "none";
        }
        return String.join(", ", active.keySet());
    }
}
