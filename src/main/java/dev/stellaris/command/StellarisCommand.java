package dev.stellaris.command;

import dev.stellaris.Stellaris;
import dev.stellaris.optimizer.OptimizerManager;
import dev.stellaris.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public final class StellarisCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("info", "reload", "modules", "optimize");

    private final Stellaris plugin;

    public StellarisCommand(Stellaris plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("stellaris.reload") && !sender.hasPermission("stellaris.admin")) {
            Text.send(sender, "<red>You are not allowed to do that.</red>");
            return true;
        }
        final String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                Text.send(sender, "<green>Stellaris configuration reloaded.</green>");
            }
            case "modules" -> modules(sender);
            case "optimize" -> optimize(sender);
            default -> info(sender);
        }
        return true;
    }

    private void info(CommandSender sender) {
        final var metrics = plugin.metrics();
        Text.send(sender, "<gradient:#7dd3fc:#818cf8><bold>Stellaris</bold></gradient> <gray>v" + plugin.getPluginMeta().getVersion() + " <dark_gray>|</dark_gray> " + (plugin.isFolia() ? "Folia" : "Paper") + "</gray>");
        Text.send(sender, "<gray>TPS <white>" + format(metrics.tpsShort()) + "</white> <dark_gray>(1m " + format(metrics.tps1m()) + ")</dark_gray> <dark_gray>|</dark_gray> MSPT <white>" + format(metrics.mspt()) + "ms</white> <dark_gray>|</dark_gray> peak <white>" + format(metrics.msptPeak()) + "ms</white>");
        Text.send(sender, "<gray>RAM <white>" + (metrics.heapUsed() >> 20) + "MB</white> / <white>" + (metrics.heapMax() >> 20) + "MB</white> <dark_gray>(" + format(metrics.heapUsedPercent()) + "%)</dark_gray> <dark_gray>|</dark_gray> players <white>" + metrics.onlinePlayers() + "</white>");
        Text.send(sender, "<gray>Modules <white>" + plugin.modules().summary() + "</white>");
    }

    private void modules(CommandSender sender) {
        Text.send(sender, "<gray>Registered modules:");
        for (String id : plugin.modules().registeredIds()) {
            final boolean active = plugin.modules().isActive(id);
            Text.send(sender, (active ? " <green>\u2714 " : " <dark_gray>\u2716 ") + "<white>" + id + "</white>");
        }
    }

    private void optimize(CommandSender sender) {
        final OptimizerManager optimizer = plugin.optimizer();
        if (optimizer == null) {
            Text.send(sender, "<yellow>The optimizer module is disabled in config.yml.</yellow>");
            return;
        }
        Text.send(sender, "<gradient:#7dd3fc:#818cf8><bold>Optimizer</bold></gradient>");
        for (String line : optimizer.status()) {
            sender.sendMessage(Text.mm("<gray>" + line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(entry -> entry.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
