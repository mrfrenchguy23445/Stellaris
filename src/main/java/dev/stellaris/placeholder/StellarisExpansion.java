package dev.stellaris.placeholder;

import dev.stellaris.Stellaris;
import dev.stellaris.metrics.ServerMetrics;
import dev.stellaris.optimizer.OptimizerManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class StellarisExpansion extends PlaceholderExpansion {

    private final Stellaris plugin;
    private final ServerMetrics metrics;

    public StellarisExpansion(Stellaris plugin) {
        this.plugin = plugin;
        this.metrics = plugin.metrics();
    }

    @Override
    public String getIdentifier() {
        return "stellaris";
    }

    @Override
    public String getAuthor() {
        return "Stellaris";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    public String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        final String key = params.toLowerCase(Locale.ROOT);
        switch (key) {
            // ── server health ────────────────────────────────────────────────
            case "tps":
                return format(metrics.tpsShort());
            case "tps_short":
                return format(metrics.tpsShort());
            case "tps_1m":
                return format(metrics.tps1m());
            case "tps_5m":
                return format(metrics.tps5m());
            case "tps_15m":
                return format(metrics.tps15m());
            case "mspt":
                return format(metrics.mspt());
            case "mspt_peak":
                return format(metrics.msptPeak());
            case "players":
                return Integer.toString(metrics.onlinePlayers());
            case "ram_used":
                return Long.toString(metrics.heapUsed() >> 20);
            case "ram_max":
                return Long.toString(metrics.heapMax() >> 20);
            case "ram_committed":
                return Long.toString(metrics.heapCommitted() >> 20);
            case "ram_used_percent":
                return format(metrics.heapUsedPercent());
            case "throttling": {
                final OptimizerManager optimizer = plugin.optimizer();
                return optimizer != null && optimizer.isThrottling() ? "true" : "false";
            }
            case "modules":
                return plugin.modules().summary();

            // ── per-player ───────────────────────────────────────────────────
            case "ping":
                return player != null && player.isOnline() ? Integer.toString(((Player) player).getPing()) : "-1";
            case "world":
                return player != null && player.isOnline() ? ((Player) player).getWorld().getName() : "";
            case "view_distance":
                return player != null && player.isOnline() ? Integer.toString(((Player) player).getSendViewDistance()) : "";
            case "simulation_distance":
                return player != null && player.isOnline() ? Integer.toString(((Player) player).getSimulationDistance()) : "";
            default:
                return plugin.cfg("placeholders").bool("empty-on-unknown", true) ? "" : null;
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
