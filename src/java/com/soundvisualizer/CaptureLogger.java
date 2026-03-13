package com.soundvisualizer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared structured logger for all audio-capture diagnostics.
 *
 * <p>Writes timestamped entries to {@code capture.log} in the working
 * directory.  Safe to call from any thread.  Each run appends to the
 * existing file so the full history is preserved across sessions.
 *
 * <p>Log sections are delimited with {@link #section(String)} headers so
 * they are easy to grep.  Stack traces are inlined with {@link #error}.
 */
public final class CaptureLogger {

    public static final Path LOG_FILE = Path.of("capture.log");
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private CaptureLogger() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Writes a prominent section header (separator + title). */
    public static void section(String title) {
        write("===== " + title + " =====");
    }

    /** Writes an informational line. */
    public static void info(String msg) {
        write("INFO  " + msg);
    }

    /** Writes a warning line. */
    public static void warn(String msg) {
        write("WARN  " + msg);
    }

    /** Writes an error line. */
    public static void error(String msg) {
        write("ERROR " + msg);
    }

    /** Writes an error line with a full stack trace inline. */
    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        write("ERROR " + msg + "\n" + sw);
    }

    /** Writes a raw (unformatted) block, e.g. subprocess output. */
    public static void raw(String label, String content) {
        write("RAW   [" + label + "]\n" + content.stripTrailing());
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private static void write(String text) {
        try {
            String entry = "[" + LocalDateTime.now().format(TS) + "] " + text + "\n";
            Files.writeString(LOG_FILE, entry,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
