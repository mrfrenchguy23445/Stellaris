package dev.stellaris.storage;

import dev.stellaris.Stellaris;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class YamlBackend implements Backend {

    private final Stellaris plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Object lock = new Object();

    public YamlBackend(Stellaris plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "storage.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public Map<String, String> loadAll(String namespace) {
        synchronized (lock) {
            final ConfigurationSection section = data.getConfigurationSection(namespace);
            if (section == null) {
                return Map.of();
            }
            final Map<String, String> out = new HashMap<>(Math.max(16, section.getKeys(false).size()));
            for (String key : section.getKeys(false)) {
                final String value = section.getString(key);
                if (value != null) {
                    out.put(key, value);
                }
            }
            return out;
        }
    }

    @Override
    public void write(String namespace, Map<String, String> values, Set<String> deletes) {
        synchronized (lock) {
            ConfigurationSection section = data.getConfigurationSection(namespace);
            if (section == null && !values.isEmpty()) {
                section = data.createSection(namespace);
            }
            if (section != null) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    section.set(entry.getKey(), entry.getValue());
                }
                for (String key : deletes) {
                    section.set(key, null);
                }
            }
            save();
        }
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save storage.yml", ex);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            save();
        }
    }
}
