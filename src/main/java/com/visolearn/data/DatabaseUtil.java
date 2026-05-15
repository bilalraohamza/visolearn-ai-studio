package com.visolearn.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseUtil {

    /** Tracks if the tables have been created during this app session. */
    private static volatile boolean schemaInitialized = false;

    private static final String DB_FILE_NAME = "visolearn.db";
    private static final String APP_DATA_DIR = "app_data/sessions";
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    /**
     * Returns a NEW connection to the SQLite database.
     * Safe to use in try-with-resources blocks.
     */
    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(JDBC_PREFIX + getDbFilePath());

        // Ensure schema is built on the very first connection
        if (!schemaInitialized) {
            synchronized (DatabaseUtil.class) {
                if (!schemaInitialized) {
                    initSchema(conn);
                    schemaInitialized = true;
                }
            }
        }
        return conn;
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
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

            // ── Schema migration for existing databases ──────────────────
            // SQLite doesn't support ALTER TABLE ... IF NOT EXISTS, so we
            // attempt each ALTER and silently catch "duplicate column" errors.
            String[] newCols = {"gender TEXT", "phone TEXT", "skin_type TEXT", "doctor_notes TEXT"};
            for (String col : newCols) {
                try {
                    stmt.execute("ALTER TABLE Patients ADD COLUMN " + col);
                } catch (SQLException ignored) {
                    // Column already exists — safe to ignore
                }
            }

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

    private static String getDbFilePath() {
        String baseDir = System.getProperty("user.home");
        Path dbDir = Paths.get(baseDir, APP_DATA_DIR);
        Path dbFile = dbDir.resolve(DB_FILE_NAME);
        try {
            Files.createDirectories(dbDir);
        } catch (IOException ignored) {}
        return dbFile.toAbsolutePath().toString();
    }

    public static Path getImagesDir() {
        String baseDir = System.getProperty("user.home");
        Path imagesDir = Paths.get(baseDir, APP_DATA_DIR, "images");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException ignored) {}
        return imagesDir;
    }
}