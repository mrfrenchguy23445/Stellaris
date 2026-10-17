package dev.stellaris.storage;

import java.util.Map;
import java.util.Set;

public interface Backend extends AutoCloseable {

    Map<String, String> loadAll(String namespace);

    void write(String namespace, Map<String, String> values, Set<String> deletes);

    @Override
    default void close() {
    }
}
