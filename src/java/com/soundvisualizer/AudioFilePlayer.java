package com.soundvisualizer;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Plays an audio file by decoding it through ffmpeg and streaming raw PCM
 * frames to registered listeners in the same stereo {@code float[][]} format
 * used by {@link AudioCapture}.
 *
 * <p>Supports any container/codec that ffmpeg can decode, including:
 * <ul>
 *   <li>.m4a – Apple AAC (DRM-free iTunes Plus) or ALAC lossless</li>
 *   <li>.flac – Free Lossless Audio Codec</li>
 *   <li>.mp3, .wav, .aac, .ogg, .opus, .aiff, …</li>
 *   <li>.m4p – DRM-protected Apple files will NOT decode</li>
 * </ul>
 *
 * <p>ffmpeg must be available on {@code PATH}.  Playback is paced to
 * real-time using nanosecond timing so the visualizer receives frames at the
 * same rate as live capture.
 */
public class AudioFilePlayer {

    /** Log file written next to the JAR / working directory. */
    public static final Path LOG_FILE = Path.of("audioplayer.log");

    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Appends a timestamped line (or block) to LOG_FILE. */
    private static void writeLog(String text) {
        try {
            String entry = "[" + LocalDateTime.now().format(TS) + "] " + text + "\n";
            Files.writeString(LOG_FILE, entry,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
    private static final float SAMPLE_RATE = AudioCapture.SAMPLE_RATE;
    private static final int   CHANNELS    = AudioCapture.CHANNELS;
    private static final int   BITS        = AudioCapture.SAMPLE_BITS;
    /** Bytes per PCM frame: 2 channels × 2 bytes (16-bit). */
    private static final int   FRAME_SIZE  = (BITS / 8) * CHANNELS;

    private final File   file;
    private int          bufferFrames = 2048;
    private volatile boolean running  = false;
    private Process        ffmpegProcess;
    private Thread         playbackThread;
    private SourceDataLine outputLine;

    /** Non-null after playback ends if ffmpeg decoded 0 frames (error condition). */
    private volatile String  errorMessage = null;
    /** Raw stderr from the ffmpeg process; populated before errorMessage is set. */
    private volatile String  lastStderr   = "";

    private final CopyOnWriteArrayList<Consumer<float[][]>> listeners =
            new CopyOnWriteArrayList<>();

    public AudioFilePlayer(File file) {
        this.file = file;
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    public void setBufferFrames(int frames) { this.bufferFrames = frames; }
    public File    getFile()   { return file; }
    public boolean isRunning() { return running; }

    public void addListener(Consumer<float[][]> listener)    { listeners.add(listener); }
    public void removeListener(Consumer<float[][]> listener) { listeners.remove(listener); }
    /** Returns an error description if playback ended with 0 decoded frames, else {@code null}. */
    public String getErrorMessage() { return errorMessage; }

    // -----------------------------------------------------------------------
    // ffmpeg availability check
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code ffmpeg} / {@code ffmpeg.exe} is found on
     * the system PATH.  Works on both Windows (uses the Windows JDK via
     * {@code make native}) and Linux ({@code make run}).
     */
    public static boolean isFfmpegAvailable() {
        String path = System.getenv("PATH");
        if (path == null) return false;
        boolean isWindows = File.separatorChar == '\\';
        String sep = File.pathSeparator; // ';' on Windows, ':' on Linux/Mac
        for (String dir : path.split(java.util.regex.Pattern.quote(sep))) {
            if (isWindows) {
                // Windows: check both ffmpeg.exe and ffmpeg (batch wrapper)
                if (new File(dir, "ffmpeg.exe").isFile()) return true;
                if (new File(dir, "ffmpeg.bat").isFile()) return true;
            } else {
                if (new File(dir, "ffmpeg").canExecute()) return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Playback lifecycle
    // -----------------------------------------------------------------------

    /**
     * Launches ffmpeg to decode the file into raw 16-bit signed LE stereo
     * PCM at {@value AudioCapture#SAMPLE_RATE} Hz and starts delivering
     * frames to listeners at real-time pace.
     *
     * @throws IOException if ffmpeg cannot be started
     */
    public void start() throws IOException {
        if (running) return;
        writeLog("=== AudioFilePlayer.start() file=" + file.getAbsolutePath() + " ===");
        // Try to open audio output for playback through speakers.
        // Falls back to visualization-only if no output line is available.
        // Catch Exception (not just LineUnavailableException) because
        // AudioSystem.getLine() can also throw IllegalArgumentException on
        // some platforms/drivers.
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, AudioCapture.FORMAT);
            outputLine = (SourceDataLine) AudioSystem.getLine(info);
            outputLine.open(AudioCapture.FORMAT, bufferFrames * AudioCapture.FORMAT.getFrameSize() * 4);
            outputLine.start();
            writeLog("SourceDataLine opened OK: " + AudioCapture.FORMAT);
        } catch (Exception e) {
            writeLog("SourceDataLine unavailable (visualization-only): " + e);
            outputLine = null;
        }
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-loglevel", "error",
            "-i",        file.getAbsolutePath(),
            "-f",        "s16le",
            "-acodec",   "pcm_s16le",
            "-ar",       String.valueOf((int) SAMPLE_RATE),
            "-ac",       String.valueOf(CHANNELS),
            "pipe:1"
        );
        pb.redirectErrorStream(false);
        writeLog("Launching: " + String.join(" ", pb.command()));
        ffmpegProcess = pb.start();

        // Capture stderr so we can show meaningful errors when decoding fails.
        InputStream stderrStream = ffmpegProcess.getErrorStream();
        Thread stderrDrain = new Thread(() -> {
            try {
                lastStderr = new String(stderrStream.readAllBytes()).trim();
                if (!lastStderr.isEmpty()) writeLog("ffmpeg stderr:\n" + lastStderr);
            }
            catch (IOException ignored) {}
        }, "AudioFilePlayer-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        running = true;
        playbackThread = new Thread(this::playbackLoop, "AudioFilePlayer");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    /** Stops playback immediately and releases audio resources. */
    public void stop() {
        running = false;
        if (ffmpegProcess != null) {
            ffmpegProcess.destroyForcibly();
            ffmpegProcess = null;
        }
        // Null the field first so playbackLoop's finally block skips the close,
        // then call stop() which interrupts any in-progress drain() or write().
        SourceDataLine line = outputLine;
        outputLine = null;
        if (line != null) {
            line.stop();
            line.close();
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    // -----------------------------------------------------------------------
    // Playback loop
    // -----------------------------------------------------------------------

    private void playbackLoop() {
        final int  bytesPerChunk = bufferFrames * FRAME_SIZE;
        final long nanosPerChunk = (long) (bufferFrames / SAMPLE_RATE * 1_000_000_000L);

        byte[] buf           = new byte[bytesPerChunk];
        long   nextTick      = System.nanoTime();
        long   framesDecoded = 0;
        // True only when we reached EOF naturally (not stopped by the user).
        boolean completedNormally = false;

        try (InputStream in = ffmpegProcess.getInputStream()) {
            outer:
            while (running) {
                // Fill the buffer – may arrive in several OS reads
                int totalRead = 0;
                while (totalRead < bytesPerChunk && running) {
                    int n = in.read(buf, totalRead, bytesPerChunk - totalRead);
                    if (n < 0) { completedNormally = true; break outer; } // EOF
                    totalRead += n;
                }
                if (totalRead <= 0) break;

                int alignedBytes = (totalRead / FRAME_SIZE) * FRAME_SIZE;
                if (alignedBytes <= 0) continue;

                // Capture to local ref to avoid NPE race if stop() nulls the field
                SourceDataLine line = outputLine;
                if (line != null) {
                    try {
                        // write() blocks when hardware buffer is full – natural real-time pacing
                        line.write(buf, 0, alignedBytes);
                    } catch (Exception e) {
                        // Line was stopped/closed by stop() – exit gracefully
                        break;
                    }
                }

                // Decode s16le → float[][] for the visualizer
                int     frames = alignedBytes / FRAME_SIZE;
                float[] left   = new float[frames];
                float[] right  = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int   off = i * FRAME_SIZE;
                    short l   = (short) ((buf[off]     & 0xFF) | ((buf[off + 1] & 0xFF) << 8));
                    short r   = (short) ((buf[off + 2] & 0xFF) | ((buf[off + 3] & 0xFF) << 8));
                    left[i]   = l / 32768f;
                    right[i]  = r / 32768f;
                }
                framesDecoded += frames;
                float[][] channels = { left, right };
                for (Consumer<float[][]> cb : listeners) {
                    try { cb.accept(channels); } catch (Exception ignored) {}
                }

                // Sleep-based pacing in visualization-only fallback mode
                if (outputLine == null) {
                    nextTick += nanosPerChunk;
                    long wait = (nextTick - System.nanoTime()) / 1_000_000L;
                    if (wait > 1) {
                        try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
                    }
                }
            }

            // Drain the hardware buffer so audio plays to the end before we
            // signal completion.  Use a local ref in case stop() races us here.
            SourceDataLine drainLine = outputLine;
            if (drainLine != null) {
                try { drainLine.drain(); } catch (Exception ignored) {}
            }

        } catch (IOException ignored) {
            // Stream closed: ffmpeg exited or stop() was called
        } finally {
            writeLog("Playback ended. completedNormally=" + completedNormally
                + " framesDecoded=" + framesDecoded);
            // If we reached EOF with no decoded audio, surface the ffmpeg error.
            // Wait briefly for the stderr thread to finish writing lastStderr.
            if (completedNormally && framesDecoded == 0) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                String err = lastStderr;
                String logPath = LOG_FILE.toAbsolutePath().toString();
                writeLog("errorMessage set (framesDecoded=0)");
                // Detect DRM-protected .m4p files: ffmpeg produces a flood of
                // AAC decode errors because the audio payload is encrypted.
                boolean likelyDrm = file.getName().toLowerCase().endsWith(".m4p")
                    && err.contains("Invalid data found when processing input");
                if (likelyDrm) {
                    errorMessage =
                        "This file appears to be DRM-protected (Apple FairPlay).\n\n"
                        + "DRM-protected .m4p files cannot be decoded by ffmpeg.\n\n"
                        + "To use this track with the visualizer, you have a few options:\n"
                        + "  \u2022 In iTunes / Apple Music, check if an iTunes Plus (DRM-free)\n"
                        + "    upgrade is available for this purchase.\n"
                        + "  \u2022 Import the CD to get a DRM-free .m4a or .mp3 copy.\n"
                        + "  \u2022 Use a DRM-free audio file (.m4a, .flac, .mp3, .wav, etc.).\n\n"
                        + "Full log: " + logPath;
                } else {
                    errorMessage = (err.isEmpty()
                        ? "No audio was decoded from the file.\n"
                        + "The file may be corrupt or in an unsupported format."
                        : "ffmpeg reported an error:\n\n" + err)
                        + "\n\nFull log: " + logPath;
                }
            }
            // Set running = false AFTER drain so the watcher fires at the right time.
            running = false;
            SourceDataLine line = outputLine;
            outputLine = null;
            if (line != null) {
                try { line.close(); } catch (Exception ignored) {}
            }
        }
    }
}
