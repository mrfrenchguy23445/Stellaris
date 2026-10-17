package dev.stellaris.storage;

import dev.stellaris.Stellaris;
import dev.stellaris.config.Cfg;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

public final class SqlBackend implements Backend {

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS stellaris_kv ("
            + "namespace VARCHAR(64) NOT NULL,"
            + " k VARCHAR(191) NOT NULL,"
            + " v TEXT,"
            + " PRIMARY KEY (namespace, k))";
    private static final String UPSERT = "REPLACE INTO stellaris_kv (namespace, k, v) VALUES (?, ?, ?)";
    private static final String DELETE = "DELETE FROM stellaris_kv WHERE namespace = ? AND k = ?";

    private final String url;
    private final String user;
    private final String password;

    private final Semaphore permits;
    private final ArrayBlockingQueue<Connection> idle;

    private SqlBackend(String url, String user, String password, int poolSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.permits = new Semaphore(poolSize);
        this.idle = new ArrayBlockingQueue<>(poolSize);
    }

    static SqlBackend sqlite(Stellaris plugin, Cfg cfg) {
        loadDriver("org.sqlite.JDBC");
        final File file = new File(plugin.getDataFolder(), cfg.str("file", "stellaris.db"));
        final SqlBackend backend = new SqlBackend("jdbc:sqlite:" + file.getAbsolutePath(), null, null, 1);
        backend.initialise(true);
        return backend;
    }

    static SqlBackend mysql(Stellaris plugin, Cfg cfg) {
        loadDriver("com.mysql.cj.jdbc.Driver");
        final String host = cfg.str("host", "127.0.0.1");
        final int port = cfg.integer("port", 3306);
        final String database = cfg.str("database", "stellaris");
        final String properties = cfg.str("properties", "");
        final String url = "jdbc:mysql://" + host + ":" + port + "/" + database + (properties.isEmpty() ? "" : "?" + properties);
        final SqlBackend backend = new SqlBackend(url, cfg.str("username", "root"), cfg.str("password", ""), Math.max(1, cfg.integer("pool-size", 4)));
        backend.initialise(false);
        return backend;
    }

    private static void loadDriver(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("JDBC driver not available: " + className, ex);
        }
    }

    private void initialise(boolean sqlite) {
        Connection connection = acquireQuietly();
        try (Statement statement = connection.createStatement()) {
            if (sqlite) {
                // WAL + a busy timeout turn SQLite from "one writer at a time,
                // readers blocked" into a concurrent-read database. Big win for
                // a plugin that reads balances while it writes them.
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            statement.execute(CREATE_TABLE);
        } catch (SQLException ex) {
            discard(connection);
            connection = null;
            throw new IllegalStateException("Could not prepare the Stellaris storage table", ex);
        } finally {
            release(connection);
        }
    }

    @Override
    public Map<String, String> loadAll(String namespace) {
        final Map<String, String> out = new HashMap<>();
        Connection connection = acquireQuietly();
        try (PreparedStatement statement = connection.prepareStatement("SELECT k, v FROM stellaris_kv WHERE namespace = ?")) {
            statement.setString(1, namespace);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    out.put(result.getString(1), result.getString(2));
                }
            }
        } catch (SQLException ex) {
            discard(connection);
            connection = null;
            throw new IllegalStateException("Failed to load storage namespace '" + namespace + "'", ex);
        } finally {
            release(connection);
        }
        return out;
    }

    @Override
    public void write(String namespace, Map<String, String> values, Set<String> deletes) {
        if (values.isEmpty() && deletes.isEmpty()) {
            return;
        }
        Connection connection = acquireQuietly();
        try {
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!values.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                        for (Map.Entry<String, String> entry : values.entrySet()) {
                            statement.setString(1, namespace);
                            statement.setString(2, entry.getKey());
                            statement.setString(3, entry.getValue());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                if (!deletes.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                        for (String key : deletes) {
                            statement.setString(1, namespace);
                            statement.setString(2, key);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            discard(connection);
            connection = null;
            throw new IllegalStateException("Failed to write storage namespace '" + namespace + "'", ex);
        } finally {
            release(connection);
        }
    }

    // ── tiny pool ─────────────────────────────────────────────────────────────

    private Connection acquireQuietly() {
        try {
            permits.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a database connection", ex);
        }
        try {
            final Connection pooled = idle.poll();
            if (pooled != null) {
                if (!pooled.isClosed()) {
                    return pooled;
                }
                closeQuietly(pooled);
            }
            return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        } catch (SQLException ex) {
            permits.release();
            throw new IllegalStateException("Could not open a database connection", ex);
        }
    }

    private void release(Connection connection) {
        if (connection == null) {
            return;
        }
        if (idle.offer(connection)) {
            permits.release();
        } else {
            closeQuietly(connection);
            permits.release();
        }
    }

    private void discard(Connection connection) {
        closeQuietly(connection);
        permits.release();
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful to do with a failed close.
        }
    }

    @Override
    public void close() {
        Connection connection;
        while ((connection = idle.poll()) != null) {
            closeQuietly(connection);
        }
    }
}
