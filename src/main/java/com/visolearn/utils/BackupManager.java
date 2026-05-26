package com.visolearn.utils;

import com.visolearn.data.DatabaseUtil;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.*;

/**
 * BackupManager handles full export and import of
 * all VisoLearn data — the SQLite database file and
 * all cached session images — as a single ZIP archive.
 *
 * Export ZIP structure:
 *   visolearn_backup_YYYY-MM-DD/
 *     visolearn.db          (the SQLite database)
 *     images/               (all cached scan images)
 *       patient_123_abc.png
 *       ...
 *
 * No external libraries required — uses only
 * java.util.zip from the Java standard library.
 */
public final class BackupManager {

    private BackupManager() {}

    /**
     * Exports the SQLite database and all cached
     * images into a ZIP file at the given path.
     *
     * @param destinationZip the ZIP file to create
     * @throws IOException if any file cannot be read
     *                     or the ZIP cannot be written
     */
    public static void exportBackup(
            File destinationZip) throws IOException {

        String timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd_HH-mm-ss"));
        String rootEntry = "visolearn_backup_"
            + timestamp + "/";

        Path dbPath = DatabaseUtil.getDbFilePathAsPath();
        Path imagesDir = DatabaseUtil.getImagesDir();

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(
                    new FileOutputStream(
                        destinationZip)))) {

            zos.setLevel(
                Deflater.BEST_COMPRESSION);

            // ── Write database file ───────────────
            if (Files.exists(dbPath)) {
                zos.putNextEntry(new ZipEntry(
                    rootEntry + "visolearn.db"));
                Files.copy(dbPath, zos);
                zos.closeEntry();
            }

            // ── Write all images ──────────────────
            if (Files.exists(imagesDir)
                    && Files.isDirectory(imagesDir)) {
                try (var stream =
                        Files.walk(imagesDir)) {
                    stream.filter(Files::isRegularFile)
                          .forEach(imgPath -> {
                        try {
                            String relative =
                                imagesDir.relativize(
                                    imgPath).toString()
                                .replace("\\", "/");
                            zos.putNextEntry(
                                new ZipEntry(
                                    rootEntry +
                                    "images/" +
                                    relative));
                            Files.copy(imgPath, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            System.err.println(
                                "Skipping image: "
                                + imgPath + " — "
                                + e.getMessage());
                        }
                    });
                }
            }
        }
    }

    /**
     * Imports a backup ZIP previously created by
     * exportBackup(). Extracts the database file
     * and all images into the correct app data
     * directories.
     *
     * WARNING: This overwrites the existing database.
     * The caller must warn the user before calling.
     *
     * @param sourceZip the ZIP file to import from
     * @throws IOException if the ZIP cannot be read
     *                     or files cannot be written
     */
    public static void importBackup(
            File sourceZip) throws IOException {

        Path dbPath   = DatabaseUtil.getDbFilePathAsPath();
        Path imagesDir = DatabaseUtil.getImagesDir();

        Files.createDirectories(
            dbPath.getParent());
        Files.createDirectories(imagesDir);

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(
                    new FileInputStream(sourceZip)))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry())
                    != null) {

                String name = entry.getName();

                // Strip the root folder prefix
                // e.g. "visolearn_backup_2026-.../
                //       visolearn.db"
                // becomes "visolearn.db"
                int slashIdx = name.indexOf('/');
                if (slashIdx >= 0) {
                    name = name.substring(
                        slashIdx + 1);
                }

                if (name.isBlank()
                        || entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                if (name.equals("visolearn.db")) {
                    // Overwrite the database file
                    try (OutputStream out =
                            new BufferedOutputStream(
                                new FileOutputStream(
                                    dbPath.toFile()))) {
                        zis.transferTo(out);
                    }

                } else if (name.startsWith(
                        "images/")) {
                    // Write image to images dir
                    String imgName =
                        name.substring(
                            "images/".length());
                    if (!imgName.isBlank()) {
                        Path target =
                            imagesDir.resolve(imgName);
                        Files.createDirectories(
                            target.getParent());
                        try (OutputStream out =
                                new BufferedOutputStream(
                                    new FileOutputStream(
                                        target.toFile()))) {
                            zis.transferTo(out);
                        }
                    }
                }

                zis.closeEntry();
            }
        }
    }

    /**
     * Returns a human-readable summary of what
     * will be included in the backup:
     * number of images and database size.
     *
     * @return summary string for display in UI
     */
    public static String getBackupSummary() {
        try {
            Path dbPath    = DatabaseUtil.getDbFilePathAsPath();
            Path imagesDir = DatabaseUtil.getImagesDir();

            long dbSize = Files.exists(dbPath)
                ? Files.size(dbPath) : 0;

            long imageCount = Files.exists(imagesDir)
                ? Files.walk(imagesDir)
                    .filter(Files::isRegularFile)
                    .count()
                : 0;

            return String.format(
                "Database: %.1f KB  |  " +
                "Images: %d files",
                dbSize / 1024.0, imageCount);

        } catch (Exception e) {
            return "Unable to calculate backup size";
        }
    }
}
