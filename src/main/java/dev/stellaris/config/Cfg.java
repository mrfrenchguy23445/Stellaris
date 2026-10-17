package dev.stellaris.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.logging.Logger;

public final class Cfg {

    private final ConfigurationSection section;
    private final Logger logger;

    private Cfg(ConfigurationSection section, Logger logger) {
        this.section = section;
        this.logger = logger;
    }

    public static Cfg of(ConfigurationSection section, Logger logger) {
        return new Cfg(section, logger);
    }

    public static Cfg empty(Logger logger) {
        return new Cfg(null, logger);
    }

    public boolean present() {
        return section != null;
    }

    public boolean bool(String path, boolean def) {
        return section == null ? def : section.getBoolean(path, def);
    }

    public int integer(String path, int def) {
        return section == null ? def : section.getInt(path, def);
    }

    public long lng(String path, long def) {
        return section == null ? def : section.getLong(path, def);
    }

    public double dbl(String path, double def) {
        return section == null ? def : section.getDouble(path, def);
    }

    public String str(String path, String def) {
        return section == null ? def : section.getString(path, def);
    }

    public List<String> strList(String path) {
        return section == null ? List.of() : section.getStringList(path);
    }

    public Cfg child(String path) {
        return new Cfg(section == null ? null : section.getConfigurationSection(path), logger);
    }

    public java.util.EnumSet<Material> materials(String path) {
        java.util.EnumSet<Material> out = java.util.EnumSet.noneOf(Material.class);
        for (String raw : strList(path)) {
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                warn("Unknown material '%s' at '%s' - skipped.", raw, path);
            } else {
                out.add(material);
            }
        }
        return out;
    }

    public java.util.EnumSet<EntityType> entityTypes(String path) {
        java.util.EnumSet<EntityType> out = java.util.EnumSet.noneOf(EntityType.class);
        for (String raw : strList(path)) {
            try {
                out.add(EntityType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                warn("Unknown entity type '%s' at '%s' - skipped.", raw, path);
            }
        }
        return out;
    }

    private void warn(String format, Object... args) {
        if (logger != null) {
            logger.warning(String.format(format, args));
        }
    }
}
