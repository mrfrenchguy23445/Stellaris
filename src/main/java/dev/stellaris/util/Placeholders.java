package dev.stellaris.util;

import org.bukkit.entity.Player;

import java.util.function.BiFunction;

public final class Placeholders {

    private static volatile BiFunction<Player, String, String> applier;

    private Placeholders() {
    }

    public static void install(BiFunction<Player, String, String> function) {
        applier = function;
    }

    public static void uninstall() {
        applier = null;
    }

    public static boolean available() {
        return applier != null;
    }

    public static String apply(Player player, String text) {
        final BiFunction<Player, String, String> local = applier;
        if (local == null || text == null || text.indexOf('%') < 0) {
            return text;
        }
        return local.apply(player, text);
    }
}
