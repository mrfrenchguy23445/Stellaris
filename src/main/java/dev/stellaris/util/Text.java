package dev.stellaris.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final java.util.concurrent.ConcurrentHashMap<String, Component> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(64);
    private static final int CACHE_LIMIT = 512;

    private Text() {
    }

    public static MiniMessage miniMessage() {
        return MM;
    }

    public static Component mm(String raw) {
        return raw == null || raw.isEmpty() ? Component.empty() : MM.deserialize(raw);
    }

    public static Component mm(String raw, TagResolver... resolvers) {
        return raw == null || raw.isEmpty() ? Component.empty() : MM.deserialize(raw, resolvers);
    }

    public static Component cached(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        Component hit = CACHE.get(raw);
        if (hit != null) {
            return hit;
        }
        Component parsed = MM.deserialize(raw);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(raw, parsed);
        }
        return parsed;
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static void send(CommandSender to, String miniMessage) {
        to.sendMessage(mm(miniMessage));
    }

    public static void actionBar(org.bukkit.entity.Player player, String miniMessage) {
        player.sendActionBar(mm(miniMessage));
    }

    public static void invalidateCache() {
        CACHE.clear();
    }
}
