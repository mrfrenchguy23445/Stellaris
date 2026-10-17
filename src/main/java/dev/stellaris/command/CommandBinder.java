package dev.stellaris.command;

import dev.stellaris.Stellaris;
import dev.stellaris.util.Text;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("deprecation") // PluginMeta exposes no command map; getDescription() is the only source.
public final class CommandBinder {

    private final Stellaris plugin;
    private final Set<String> bound = new HashSet<>();

    public CommandBinder(Stellaris plugin) {
        this.plugin = plugin;
    }

    public void bind(String name, CommandExecutor executor) {
        bind(name, executor, executor instanceof TabCompleter completer ? completer : null);
    }

    public void bind(String name, CommandExecutor executor, TabCompleter completer) {
        final PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            plugin.getLogger().warning("Command '" + name + "' is not declared in plugin.yml - cannot bind.");
            return;
        }
        command.setExecutor(executor);
        if (completer != null) {
            command.setTabCompleter(completer);
        }
        bound.add(name);
    }

    public void installFallbacks() {
        for (String name : plugin.getDescription().getCommands().keySet()) {
            if (bound.contains(name)) {
                continue;
            }
            final PluginCommand command = plugin.getCommand(name);
            if (command != null) {
                command.setExecutor(DISABLED);
            }
        }
    }

    public void reset() {
        for (String name : plugin.getDescription().getCommands().keySet()) {
            final PluginCommand command = plugin.getCommand(name);
            if (command != null) {
                command.setExecutor(DISABLED);
                command.setTabCompleter(null);
            }
        }
        bound.clear();
    }

    private static final CommandExecutor DISABLED = (sender, command, label, args) -> {
        sender.sendMessage(Text.mm("<gray>That feature is disabled on this server.</gray>"));
        return true;
    };
}
