package com.soundvisualizer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Saves and loads {@link VisualizerProfile} instances as Java {@link Properties}
 * files inside {@code ~/.soundvisualizer/profiles/}.
 *
 * Profile names are sanitized before being used as file names to prevent path
 * traversal or invalid-character issues.
 */
public final class ProfileManager {

    private static final Path PROFILE_DIR = Path.of(
            System.getProperty("user.home"), ".soundvisualizer", "profiles");

    private ProfileManager() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a sorted list of saved profile names (without the {@code .properties}
     * extension). Returns an empty list if the directory does not exist or cannot
     * be read.
     */
    public static List<String> listProfiles() {
        try {
            Files.createDirectories(PROFILE_DIR);
            try (var stream = Files.list(PROFILE_DIR)) {
                return stream
                        .filter(p -> p.getFileName().toString().endsWith(".properties"))
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return name.substring(0, name.length() - ".properties".length());
                        })
                        .sorted()
                        .toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Saves {@code profile} under {@code name}, overwriting any existing file
     * with the same name.
     *
     * @throws IOException if the file cannot be written
     */
    public static void save(String name, VisualizerProfile profile) throws IOException {
        Files.createDirectories(PROFILE_DIR);
        Path file = PROFILE_DIR.resolve(sanitize(name) + ".properties");
        Properties p = profile.toProperties();
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "SoundVisualizer profile: " + name);
        }
    }

    /**
     * Loads and returns the profile stored under {@code name}.
     *
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if no profile with that name exists
     */
    public static VisualizerProfile load(String name) throws IOException {
        Path file = PROFILE_DIR.resolve(sanitize(name) + ".properties");
        if (!Files.exists(file))
            throw new IllegalArgumentException("Profile not found: " + name);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return VisualizerProfile.fromProperties(p);
    }

    /**
     * Deletes the profile with the given name. Does nothing if it does not exist.
     *
     * @throws IOException if the file exists but cannot be deleted
     */
    public static void delete(String name) throws IOException {
        Path file = PROFILE_DIR.resolve(sanitize(name) + ".properties");
        Files.deleteIfExists(file);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /** Replaces characters unsafe for use in file names with underscores. */
    private static String sanitize(String name) {
        return name.trim().replaceAll("[^a-zA-Z0-9_\\-.() ]", "_");
    }
}
