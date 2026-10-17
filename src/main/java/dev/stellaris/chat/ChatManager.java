package dev.stellaris.chat;

import dev.stellaris.Stellaris;
import dev.stellaris.module.AbstractModule;

public final class ChatManager extends AbstractModule {

    public ChatManager(Stellaris plugin) {
        super(plugin, "chat");
    }

    @Override
    protected void onEnable() {
        // Implemented by the chat package.
    }
}
