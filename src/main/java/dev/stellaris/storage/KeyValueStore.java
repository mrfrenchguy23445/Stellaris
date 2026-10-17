package dev.stellaris.storage;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class KeyValueStore implements AutoCloseable {

    private static final char SEPARATOR = '\u0000';

    private final Stellaris plugin;
    private final Backend backend;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> cache = new ConcurrentHashMap<>();

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    private final Set<String> pendingDeletes = ConcurrentHashMap.newKeySet();

    private final Object flushLock = new Object();
    private final ExecutorService writer = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final ScheduledTask flushTask;
    private volatile boolean closed;

    private KeyValueStore(Stellaris plugin, Backend backend, long flushIntervalTicks) {
        this.plugin = plugin;
        this.backend = backend;
        final long periodMillis = Math.max(1000L, flushIntervalTicks * 50L);
        this.flushTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> flushAsync(), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public static KeyValueStore open(Stellaris plugin, Cfg cfg) {
        final String backend = cfg.str("backend", "SQLITE").toUpperCase(Locale.ROOT);
        Backend impl;
        try {
            switch (backend) {
                case "MYSQL" -> impl = SqlBackend.mysql(plugin, cfg.child("mysql"));
                case "YAML" -> impl = new YamlBackend(plugin);
                default -> impl = SqlBackend.sqlite(plugin, cfg.child("sqlite"));
            }
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.WARNING, "Could not initialise the '" + backend + "' storage backend - using YAML instead", failure);
            impl = new YamlBackend(plugin);
        }
        plugin.getLogger().info("Storage backend: " + impl.getClass().getSimpleName());
        return new KeyValueStore(plugin, impl, cfg.lng("flush-interval-ticks", 200L));
    }

    // ── reads (main thread, allocation-free apart from the map lookup) ────────

    public String get(String namespace, String key) {
        final ConcurrentHashMap<String, String> bucket = cache.get(namespace);
        return bucket == null ? null : bucket.get(key);
    }

    public String getOrDefault(String namespace, String key, String fallback) {
        final String value = get(namespace, key);
        return value == null ? fallback : value;
    }

    public long getLong(String namespace, String key, long fallback) {
        final String value = get(namespace, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public int getInt(String namespace, String key, int fallback) {
        final long value = getLong(namespace, key, fallback);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public boolean getBoolean(String namespace, String key, boolean fallback) {
        final String value = get(namespace, key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public Map<String, String> snapshot(String namespace) {
        final ConcurrentHashMap<String, String> bucket = cache.get(namespace);
        return bucket == null ? Map.of() : new HashMap<>(bucket);
    }

    public Set<String> keys(String namespace) {
        final ConcurrentHashMap<String, String> bucket = cache.get(namespace);
        return bucket == null ? Set.of() : new HashSet<>(bucket.keySet());
    }

    // ── writes (main thread: two map/set operations) ──────────────────────────

    public void put(String namespace, String key, String value) {
        if (closed || value == null) {
            return;
        }
        cache.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>(16)).put(key, value);
        final String composite = composite(namespace, key);
        pendingDeletes.remove(composite);
        dirty.add(composite);
    }

    public void putLong(String namespace, String key, long value) {
        put(namespace, key, Long.toString(value));
    }

    public void putInt(String namespace, String key, int value) {
        put(namespace, key, Integer.toString(value));
    }

    public void putBoolean(String namespace, String key, boolean value) {
        put(namespace, key, Boolean.toString(value));
    }

    public void remove(String namespace, String key) {
        if (closed) {
            return;
        }
        final ConcurrentHashMap<String, String> bucket = cache.get(namespace);
        if (bucket != null) {
            bucket.remove(key);
        }
        final String composite = composite(namespace, key);
        dirty.remove(composite);
        pendingDeletes.add(composite);
    }

    public CompletableFuture<Void> warmUp(String namespace) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            final Map<String, String> loaded = backend.loadAll(namespace);
            if (loaded.isEmpty()) {
                return;
            }
            final ConcurrentHashMap<String, String> bucket = cache.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>(Math.max(16, loaded.size())));
            loaded.forEach((key, value) -> {
                // Never let a stale row overwrite a value the server has already
                // changed since startup.
                if (!dirty.contains(composite(namespace, key))) {
                    bucket.put(key, value);
                }
            });
        }, writer);
    }

    // ── flushing ──────────────────────────────────────────────────────────────

    public void flushAsync() {
        if (closed || dirty.isEmpty() && pendingDeletes.isEmpty() || !flushing.compareAndSet(false, true)) {
            return;
        }
        writer.execute(() -> {
            try {
                flushNow();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Asynchronous storage flush failed", t);
            } finally {
                flushing.set(false);
            }
        });
    }

    public void flush() {
        if (closed) {
            return;
        }
        try {
            writer.submit(this::flushNow).get(15L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Blocking storage flush failed", ex);
        }
    }

    private void flushNow() {
        synchronized (flushLock) {
            final List<String> keys = drain(dirty);
            final List<String> deletes = drain(pendingDeletes);
            if (keys.isEmpty() && deletes.isEmpty()) {
                return;
            }

            final Map<String, Map<String, String>> writes = new HashMap<>();
            for (String composite : keys) {
                final int split = composite.indexOf(SEPARATOR);
                final String namespace = composite.substring(0, split);
                final String key = composite.substring(split + 1);
                final ConcurrentHashMap<String, String> bucket = cache.get(namespace);
                final String value = bucket == null ? null : bucket.get(key);
                if (value != null) {
                    writes.computeIfAbsent(namespace, ignored -> new HashMap<>()).put(key, value);
                }
            }

            final Map<String, Set<String>> removals = new HashMap<>();
            for (String composite : deletes) {
                final int split = composite.indexOf(SEPARATOR);
                removals.computeIfAbsent(composite.substring(0, split), ignored -> new HashSet<>()).add(composite.substring(split + 1));
            }

            final Set<String> namespaces = new HashSet<>(writes.keySet());
            namespaces.addAll(removals.keySet());
            for (String namespace : namespaces) {
                backend.write(namespace,
                        writes.getOrDefault(namespace, Map.of()),
                        removals.getOrDefault(namespace, Set.of()));
            }
        }
    }

    private static List<String> drain(Set<String> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        final List<String> copy = new ArrayList<>(source);
        source.removeAll(copy);
        return copy;
    }

    private static String composite(String namespace, String key) {
        return namespace + SEPARATOR + key;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        flushTask.cancel();
        flush();
        writer.shutdown();
        try {
            backend.close();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to close the storage backend cleanly", t);
        }
        cache.clear();
        dirty.clear();
        pendingDeletes.clear();
    }

    public boolean isClosed() {
        return closed;
    }
}
