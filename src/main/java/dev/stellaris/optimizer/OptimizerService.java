package dev.stellaris.optimizer;

public interface OptimizerService {

    String id();

    int periodTicks();

    void start();

    void stop();

    default void onTick(long tick) {
    }

    default void setThrottling(boolean throttling) {
    }

    default void trim() {
    }

    default int trackedEntries() {
        return 0;
    }

    default String status() {
        return id() + ": active";
    }
}
