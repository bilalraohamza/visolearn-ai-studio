package com.visolearn.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Central database utility for VisoLearn AI Studio.
 *
 * <p>Manages a singleton {@link HikariDataSource} that pools up to 2 physical
 * connections to the SQLite database file. All DAOs borrow a connection via
 * {@link #getConnection()} and return it automatically when their
 * {@code try-with-resources} block exits — the connection is <em>not</em>
 * physically closed; it is returned to the pool.</p>
 *
 * <h3>Design choices</h3>
 * <ul>
 *   <li><b>Pool size 2</b> — allows one read and one write to overlap (e.g.
 *       a batch import in the Classify tab while the History tab is loading).
 *       SQLite serialises writes internally, so no corruption can occur.</li>
 *   <li><b>WAL journal mode</b> — enables concurrent readers alongside a
 *       single writer without "database is locked" errors. Activated once in
 *       {@link #initSchema(Connection)} and persisted for the lifetime of the
 *       database file.</li>
 *   <li><b>Foreign keys</b> — enabled on every new physical connection via
 *       {@code connectionInitSql} so the ON DELETE CASCADE constraint on
 *       Predictions is always enforced regardless of which pool slot is used.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>The pool is initialised lazily on the first call to
 *       {@link #getConnection()} (static initialiser in {@code DATA_SOURCE}).</li>
 *   <li>Call {@link #shutdown()} from {@code MainApp}'s close-request handler
 *       to drain the pool and release the SQLite file handle cleanly.</li>
 * </ol>
 */
public final class DatabaseUtil {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    private static final String DB_FILE_NAME = "visolearn.db";
    private static final String APP_DATA_DIR = "app_data/sessions";
    private static final String JDBC_PREFIX  = "jdbc:sqlite:";

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton HikariDataSource — initialised exactly once
    // ─────────────────────────────────────────────────────────────────────────

    private static final HikariDataSource DATA_SOURCE = buildDataSource();

    private static HikariDataSource buildDataSource() {
        HikariConfig cfg = new HikariConfig();

        cfg.setJdbcUrl(JDBC_PREFIX + getDbFilePath());
        cfg.setDriverClassName("org.sqlite.JDBC");

        // Pool size: 2 allows one read + one write to overlap while
        // SQLite's write-serialisation keeps the file consistent.
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);

        // Fail fast if a connection cannot be obtained
        cfg.setConnectionTimeout(10_000);

        // Release idle connections after 5 minutes of inactivity
        cfg.setIdleTimeout(300_000);

        // Recycle connections every 10 minutes to avoid stale state
        cfg.setMaxLifetime(600_000);

        // Lightweight liveness check
        cfg.setConnectionTestQuery("SELECT 1");

        // Enable WAL mode and foreign-key enforcement on every new physical
        // connection so pool slots are always in the correct state.
        cfg.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;"
        );

        cfg.setPoolName("VisoLearn-DB");

        HikariDataSource ds = new HikariDataSource(cfg);

        // Initialise the schema using the first connection from the pool.
        // We do this synchronously here so any schema errors surface at
        // startup rather than during the first user action.
        try (Connection conn = ds.getConnection()) {
            initSchema(conn);
        } catch (SQLException e) {
            ds.close();
            throw new ExceptionInInitializerError(
                    "DatabaseUtil: schema initialisation failed — " + e.getMessage());
        }

        return ds;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Borrows a connection from the pool.
     *
     * <p>Always use inside a {@code try-with-resources} block — the connection
     * is returned to the pool (not physically closed) when the block exits.</p>
     *
     * @return A live {@link Connection} backed by the HikariCP pool.
     * @throws SQLException if no connection can be obtained within the timeout.
     */
    public static Connection getConnection() throws SQLException {
        return DATA_SOURCE.getConnection();
    }

    /**
     * Gracefully shuts down the connection pool and releases the SQLite file
     * handle. Call this once from {@code MainApp}'s close-request handler.
     *
     * <p>After this method returns, any further call to {@link #getConnection()}
     * will throw an {@link SQLException}.</p>
     */
    public static void shutdown() {
        if (!DATA_SOURCE.isClosed()) {
            DATA_SOURCE.close();
            System.out.println("DatabaseUtil: connection pool shut down.");
        }
    }

    /**
     * Returns the absolute path to the session images cache directory,
     * creating it if it does not already exist.
     */
    public static Path getImagesDir() {
        String baseDir   = System.getProperty("user.home");
        Path   imagesDir = Paths.get(baseDir, APP_DATA_DIR, "images");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException ignored) {}
        return imagesDir;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates the schema tables (if absent) and applies incremental migrations
     * for columns added after the initial release.
     *
     * <p>Called exactly once, during pool construction, using the first physical
     * connection borrowed from the pool.</p>
     */
    private static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {

            // WAL mode: persisted at the file level; setting it once is enough,
            // but it is harmless to repeat on a fresh database.
            stmt.execute("PRAGMA journal_mode=WAL");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Patients (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT    NOT NULL,
                    dob          TEXT,
                    gender       TEXT,
                    phone        TEXT,
                    skin_type    TEXT,
                    doctor_notes TEXT
                )
                """);

            // ── Schema migration for existing databases ──────────────────────
            // SQLite doesn't support ALTER TABLE … IF NOT EXISTS, so we
            // attempt each ALTER and silently catch "duplicate column" errors.
            String[] newCols = {"gender TEXT", "phone TEXT", "skin_type TEXT", "doctor_notes TEXT"};
            for (String col : newCols) {
                try {
                    stmt.execute("ALTER TABLE Patients ADD COLUMN " + col);
                } catch (SQLException ignored) {
                    // Column already exists — safe to ignore
                }
            }

            try {
                stmt.execute(
                    "ALTER TABLE Patients ADD COLUMN " +
                    "follow_up_date TEXT");
            } catch (SQLException ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Predictions (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    patient_id      INTEGER NOT NULL,
                    image_path      TEXT    NOT NULL,
                    predicted_class TEXT    NOT NULL,
                    confidence      REAL,
                    inference_time  INTEGER,
                    notes           TEXT,
                    timestamp       TEXT    NOT NULL DEFAULT (CURRENT_TIMESTAMP),
                    FOREIGN KEY (patient_id) REFERENCES Patients(id) ON DELETE CASCADE
                )
                """);

            // Migration: add notes column to existing Predictions table
            try {
                stmt.execute("ALTER TABLE Predictions ADD COLUMN notes TEXT");
            } catch (SQLException ignored) {
                // Column already exists
            }

            stmt.execute("PRAGMA foreign_keys = ON");
        }
    }

    /**
     * Resolves (and creates) the directory that contains the SQLite database
     * file, then returns the absolute path to the file itself.
     */
    private static String getDbFilePath() {
        String baseDir = System.getProperty("user.home");
        Path   dbDir   = Paths.get(baseDir, APP_DATA_DIR);
        Path   dbFile  = dbDir.resolve(DB_FILE_NAME);
        try {
            Files.createDirectories(dbDir);
        } catch (IOException ignored) {}
        return dbFile.toAbsolutePath().toString();
    }

    // Prevent instantiation
    private DatabaseUtil() {}
}