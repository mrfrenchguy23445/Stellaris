package dev.stellaris.module;

public interface StellarisModule {

    String id();

    boolean isActive();

    void enable();

    void disable();

    default void trimCaches() {
    }
}
