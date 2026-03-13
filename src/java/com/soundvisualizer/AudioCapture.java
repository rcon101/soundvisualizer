package com.soundvisualizer;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages system audio capture via {@link TargetDataLine}.
 *
 * <p>On WSL2, Java's ALSA backend only exposes a single "default" PulseAudio
 * mixer.  This class supplements that with a direct PulseAudio source query
 * (via {@code pactl}) so every PA source (including the system-output monitor)
 * appears individually in the source list.
 *
 * <p>Captured audio is delivered to registered listeners as stereo
 * {@code float[][]} arrays: {@code channels[0]} = left, {@code channels[1]} = right.
 *
 * <p>Usage:
 * <pre>
 *   AudioCapture cap = new AudioCapture(source);   // SourceInfo wraps either a
 *   cap.setBufferFrames(2048);                      // Mixer.Info or a PA name
 *   cap.addListener(channels -> processor.process(channels));
 *   cap.start();
 *   ...
 *   cap.stop();
 * </pre>
 */
public class AudioCapture {

    // -----------------------------------------------------------------------
    // Preferred format – 44.1 kHz, 16-bit, stereo, signed, little-endian
    // -----------------------------------------------------------------------
    public static final float   SAMPLE_RATE   = 44100f;
    public static final int     SAMPLE_BITS   = 16;
    public static final int     CHANNELS      = 2;
    public static final boolean SIGNED        = true;
    public static final boolean BIG_ENDIAN    = false;

    public static final AudioFormat FORMAT = new AudioFormat(
            SAMPLE_RATE, SAMPLE_BITS, CHANNELS, SIGNED, BIG_ENDIAN);

    // -----------------------------------------------------------------------
    // SourceInfo – wraps a Java Mixer.Info OR a PulseAudio source name
    // -----------------------------------------------------------------------

    /** Describes one capturable audio source, regardless of how it is accessed. */
    public static final class SourceInfo {
        /** Not-null for ALSA/Java-mixer sources. */
        public final Mixer.Info mixerInfo;
        /** Not-null for PulseAudio sources accessed directly via pactl. */
        public final String     pulseName;
        /**
         * Not-null for Windows WASAPI loopback sources.  Contains the Windows
         * device ID passed to ffmpeg, or the empty string to use the default
         * render endpoint.
         */
        public final String     wasapiDeviceId;
        /** Human-readable display label. */
        public final String     label;

        /** Java-mixer source. */
        public SourceInfo(Mixer.Info mi) {
            this.mixerInfo     = mi;
            this.pulseName     = null;
            this.wasapiDeviceId = null;
            this.label         = labelForMixer(mi);
        }

        /** PulseAudio direct source. */
        public SourceInfo(String pulseName, String description) {
            this.mixerInfo     = null;
            this.pulseName     = pulseName;
            this.wasapiDeviceId = null;
            this.label         = description;
        }

        /**
         * Windows WASAPI loopback source.
         *
         * @param deviceId  Windows device ID for ffmpeg, or {@code ""} for the
         *                  system default render endpoint.
         * @param label     Human-readable name shown in the UI.
         */
        public static SourceInfo wasapi(String deviceId, String label) {
            return new SourceInfo(deviceId, label, true);
        }

        /** Private constructor for WASAPI entries. */
        private SourceInfo(String wasapiDeviceId, String label, boolean wasapi) {
            this.mixerInfo      = null;
            this.pulseName      = null;
            this.wasapiDeviceId = wasapiDeviceId;
            this.label          = label;
        }

        public boolean isPulse()  { return pulseName      != null; }
        public boolean isWasapi() { return wasapiDeviceId != null; }

        @Override public String toString() { return label; }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final SourceInfo    source;
    private TargetDataLine      line;
    private Thread              captureThread;
    private volatile boolean    running;
    private int                 bufferFrames = 2048;

    /**
     * Optional callback invoked on the capture thread when WASAPI loopback
     * fails (e.g. ffmpeg exits with 0 frames decoded).  The argument is a
     * human-readable error message suitable for a dialog.
     */
    private Consumer<String> wasapiErrorCallback;
    public void setWasapiErrorCallback(Consumer<String> cb) { wasapiErrorCallback = cb; }

    private final CopyOnWriteArrayList<Consumer<float[][]>> listeners = new CopyOnWriteArrayList<>();

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public AudioCapture(SourceInfo source) {
        this.source = source;
    }

    /** Convenience: wrap a raw Mixer.Info. */
    public AudioCapture(Mixer.Info mixerInfo) {
        this(new SourceInfo(mixerInfo));
    }

    // -----------------------------------------------------------------------
    // Source enumeration
    // -----------------------------------------------------------------------

    /**
     * Returns all capturable audio sources.
     *
     * <p>On Windows (detected via {@link WindowsAudioSessions#isWindows()}),
     * WASAPI loopback sources are prepended so the user can capture system
     * audio output.  Java mixer and PulseAudio sources follow.
     *
     * <p>On Linux/WSL2, only Java mixer and PulseAudio sources are returned.
     */
    public static List<SourceInfo> getAvailableSources() {
        List<SourceInfo> result = new ArrayList<>();
        boolean isWindows = WindowsAudioSessions.isWindows();
        CaptureLogger.section("getAvailableSources");
        CaptureLogger.info("os.name=" + System.getProperty("os.name") + "  isWindows=" + isWindows);

        // ── Windows audio sources ─────────────────────────────────────────
        if (isWindows) {
            // ── 1. Render endpoints (output devices: speakers, headphones) ─
            // We capture these via a native C# WASAPI loopback captured at
            // runtime via PowerShell's Add-Type.  No ffmpeg format required.
            try {
                List<WindowsAudioSessions.RenderEndpoint> endpoints =
                        WindowsAudioSessions.queryRenderEndpoints();
                CaptureLogger.info("Render endpoints found: " + endpoints.size());
                for (var ep : endpoints) {
                    // Put default device first via label distinction
                    String lbl = ep.isDefault()
                        ? ep.friendlyName() + " (loopback)"
                        : ep.friendlyName() + " (loopback)";
                    result.add(SourceInfo.wasapi("native-loopback:" + ep.deviceId(), lbl));
                    CaptureLogger.info("  render endpoint: " + ep.friendlyName()
                        + (ep.isDefault() ? " [default]" : ""));
                }
                if (endpoints.isEmpty()) {
                    // Fallback: capture default render endpoint
                    CaptureLogger.warn("No render endpoints found; adding default loopback");
                    result.add(SourceInfo.wasapi("native-loopback:",
                        "System Audio Output (loopback)"));
                }
            } catch (Exception e) {
                CaptureLogger.warn("Render endpoint enumeration failed: " + e.getMessage());
                result.add(SourceInfo.wasapi("native-loopback:",
                    "System Audio Output (loopback)"));
            }

            // ── 2. DirectShow recording devices (microphones, line-in) ─────
            // gyan.dev ffmpeg 8.x does not include the 'wasapi' input device,
            // but 'dshow' is always present.  Devices whose names suggest a
            // loopback/mix are tagged as "system audio".
            String ffmpegExe = resolveFfmpegExe();
            List<String> dshowDevices = queryDshowAudioDevices(ffmpegExe);
            for (String dev : dshowDevices) {
                boolean isLoopback = isLikelyLoopbackDevice(dev);
                String lbl = isLoopback ? dev + " (system audio)" : dev;
                result.add(SourceInfo.wasapi("dshow:" + dev, lbl));
                CaptureLogger.info("  dshow audio: " + lbl);
            }
            if (dshowDevices.isEmpty()) {
                CaptureLogger.warn("No dshow audio devices found; adding placeholder");
                result.add(SourceInfo.wasapi("dshow:", "System Audio (enable Stereo Mix first)"));
            }
        }

        // ── Java / ALSA mixer sources ──────────────────────────────────────
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mi);
                if (mixer.isLineSupported(targetInfo)) {
                    result.add(new SourceInfo(mi));
                    CaptureLogger.info("  Java mixer: " + mi.getName());
                    continue;
                }
                for (Line.Info li : mixer.getTargetLineInfo()) {
                    if (li instanceof DataLine.Info dli &&
                            dli.getLineClass() == TargetDataLine.class) {
                        result.add(new SourceInfo(mi));
                        CaptureLogger.info("  Java mixer (via TargetLineInfo): " + mi.getName());
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── PulseAudio direct sources (Linux / WSL2) ──────────────────────
        if (!isWindows && (System.getenv("PULSE_SERVER") != null || isPulseAvailable())) {
            for (PulseSourceEntry e : queryPulseSources()) {
                String label = buildPulseLabel(e);
                result.add(new SourceInfo(e.name, label));
                CaptureLogger.info("  PulseAudio: " + label);
            }
        }

        CaptureLogger.info("Total sources: " + result.size());
        return result;
    }

    private static String labelForMixer(Mixer.Info info) {
        if (info == null) return "System Default";
        String name = info.getName();
        String desc = info.getDescription();
        boolean isMonitor = name.toLowerCase().contains("monitor")
                || desc.toLowerCase().contains("monitor")
                || name.toLowerCase().contains("loopback");
        return name + (isMonitor ? " [all programs]" : "");
    }

    private static String buildPulseLabel(PulseSourceEntry e) {
        boolean isMonitor = e.name.endsWith(".monitor") || e.name.contains("monitor");
        String tag = isMonitor ? " [all programs]" : "";
        // Use the human description if available and different from the name
        String base = (e.description != null && !e.description.isBlank()
                        && !e.description.equals(e.name))
                      ? e.description + " (" + e.name + ")"
                      : e.name;
        return base + tag;
    }

    // -----------------------------------------------------------------------
    // PulseAudio source enumeration via pactl subprocess
    // -----------------------------------------------------------------------

    private record PulseSourceEntry(String name, String description, String format) {}

    private static boolean isPulseAvailable() {
        try {
            File f = findExecutable("pactl");
            return f != null;
        } catch (Exception e) { return false; }
    }

    private static File findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(":")) {
            File f = new File(dir, name);
            if (f.canExecute()) return f;
        }
        return null;
    }

    /**
     * Runs {@code pactl list sources} and parses each source into a
     * {@link PulseSourceEntry}.  Returns an empty list on any error.
     */
    static List<PulseSourceEntry> queryPulseSources() {
        List<PulseSourceEntry> sources = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("pactl", "list", "sources");
            String server = System.getenv("PULSE_SERVER");
            if (server != null) pb.environment().put("PULSE_SERVER", server);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, TimeUnit.SECONDS);

            // Parse blocks separated by blank lines
            String name = null, desc = null, fmt = null;
            for (String line : out.split("\n")) {
                String t = line.strip();
                if (t.startsWith("Name:")) {
                    name = t.substring(5).strip();
                } else if (t.startsWith("Description:")) {
                    desc = t.substring(12).strip();
                } else if (t.startsWith("Sample Specification:")) {
                    fmt = t.substring(21).strip();
                } else if (t.isBlank() && name != null) {
                    sources.add(new PulseSourceEntry(name, desc, fmt));
                    name = desc = fmt = null;
                }
            }
            if (name != null) sources.add(new PulseSourceEntry(name, desc, fmt));

        } catch (Exception ignored) {}
        return sources;
    }

    /**
     * Switches the PulseAudio default source to {@code pulseName} so that
     * subsequent ALSA-default captures read from it.
     */
    private static void setPulseDefaultSource(String pulseName) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("pactl", "set-default-source", pulseName);
            String server = System.getenv("PULSE_SERVER");
            if (server != null) pb.environment().put("PULSE_SERVER", server);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes(); // drain
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while setting PA source", e);
        }
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    public void setBufferFrames(int frames) {
        this.bufferFrames = frames;
    }

    public int getBufferFrames() { return bufferFrames; }

    public void addListener(Consumer<float[][]> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<float[][]> listener) {
        listeners.remove(listener);
    }

    public SourceInfo getSourceInfo() { return source; }

    public boolean isRunning()       { return running; }

    // -----------------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------------

    /**
     * Opens the audio line and starts the capture thread.
     *
     * <p>If the source is a PulseAudio source, the PA default source is
     * switched to it first so that the ALSA "default" device captures from it.
     *
     * @throws LineUnavailableException if the audio source cannot be opened.
     */
    public void start() throws LineUnavailableException {
        CaptureLogger.section("AudioCapture.start");
        CaptureLogger.info("source=" + (source != null ? source.label : "null")
            + "  isWasapi=" + (source != null && source.isWasapi())
            + "  isPulse=" + (source != null && source.isPulse()));

        if (source != null && source.isWasapi()) {
            // ── Windows WASAPI loopback via ffmpeg ───────────────────────
            startWasapiLoopback();
            return;
        }

        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);

        if (source != null && source.isPulse()) {
            // ── PulseAudio direct source ──────────────────────────────────
            // Switch the PA default source then capture from ALSA "default".
            try {
                setPulseDefaultSource(source.pulseName);
            } catch (IOException e) {
                throw new LineUnavailableException(
                        "Failed to switch PA source to '" + source.pulseName
                        + "': " + e.getMessage());
            }
            line = (TargetDataLine) AudioSystem.getLine(info);

        } else if (source != null && source.mixerInfo != null) {
            // ── Java Mixer.Info source ────────────────────────────────────
            Mixer mixer = AudioSystem.getMixer(source.mixerInfo);
            if (mixer.isLineSupported(info)) {
                line = (TargetDataLine) mixer.getLine(info);
            } else {
                // Try without format constraint
                for (Line.Info li : mixer.getTargetLineInfo()) {
                    if (li instanceof DataLine.Info dli &&
                            dli.getLineClass() == TargetDataLine.class) {
                        line = (TargetDataLine) mixer.getLine(li);
                        break;
                    }
                }
            }
            if (line == null) throw new LineUnavailableException(
                    "No TargetDataLine found on mixer: " + source.mixerInfo.getName());

        } else {
            // ── System default ────────────────────────────────────────────
            line = (TargetDataLine) AudioSystem.getLine(info);
        }

        // Try to open with our preferred format; fall back to the line's default
        try {
            line.open(FORMAT, bufferFrames * FORMAT.getFrameSize() * 4);
            CaptureLogger.info("Line opened with FORMAT: " + FORMAT);
        } catch (LineUnavailableException e) {
            CaptureLogger.warn("Could not open with preferred FORMAT (" + e.getMessage()
                + "), trying line default");
            line.open();
            CaptureLogger.info("Line opened with default format: " + line.getFormat());
        }

        line.start();
        running = true;
        captureThread = new Thread(this::captureLoop, "AudioCapture");
        captureThread.setDaemon(true);
        captureThread.start();
        CaptureLogger.info("AudioCapture started via Java mixer/PA");
    }

    private volatile Process wasapiProcess;

    /**
     * Resolves the real {@code ffmpeg.exe} path on Windows, bypassing WinGet
     * app-execution-alias stubs which cannot be started by Java's
     * {@link ProcessBuilder} (they require a desktop window station).
     *
     * <p>Search order:
     * <ol>
     *   <li>Scan {@code %LOCALAPPDATA%\Microsoft\WinGet\Packages} for the
     *       first {@code bin\ffmpeg.exe} — this is where WinGet portable       *       installs land (gyan.dev, BtbN, etc.).</li>
     *   <li>Common manual install paths ({@code C:\ffmpeg\bin\ffmpeg.exe},
     *       {@code C:\Program Files\ffmpeg\bin\ffmpeg.exe}).</li>
     *   <li>Every directory on the Windows {@code PATH} that contains
     *       {@code ffmpeg.exe}; entries under {@code WinGet\Links} are
     *       skipped because those are the problematic stubs.</li>
     *   <li>Fall back to plain {@code "ffmpeg"} and hope the OS resolves it.</li>
     * </ol>
     */
    private static String resolveFfmpegExe() {
        // 1 ── WinGet portable packages (most reliable – real binary, no shim)
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) {
            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null) localAppData = userProfile + "\\AppData\\Local";
        }
        if (localAppData != null) {
            File pkgRoot = new File(localAppData, "Microsoft\\WinGet\\Packages");
            if (pkgRoot.isDirectory()) {
                File found = searchForBinary(pkgRoot, "ffmpeg.exe", 4);
                if (found != null) {
                    CaptureLogger.info("WinGet package binary: " + found.getAbsolutePath());
                    return found.getAbsolutePath();
                }
            }
        }

        // 2 ── common manual install paths
        for (String candidate : new String[]{
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe"}) {
            if (new File(candidate).isFile()) {
                CaptureLogger.info("Found ffmpeg at: " + candidate);
                return candidate;
            }
        }

        // 3 ── Windows PATH (skip WinGet\Links stubs)
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (dir.toLowerCase().contains("winget\\links")) continue; // skip stubs
                File f = new File(dir.trim(), "ffmpeg.exe");
                if (f.isFile()) {
                    CaptureLogger.info("PATH ffmpeg: " + f.getAbsolutePath());
                    return f.getAbsolutePath();
                }
            }
        }

        CaptureLogger.warn("Could not find ffmpeg.exe; falling back to bare name");
        return "ffmpeg";
    }

    /**
     * Enumerates all DirectShow audio input devices by running
     * {@code ffmpeg -f dshow -list_devices true -i dummy}.
     * Output goes to stderr, collected via redirectErrorStream.
     */
    private static List<String> queryDshowAudioDevices(String ffmpegExe) {
        List<String> devices = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(
                    ffmpegExe, "-hide_banner",
                    "-f", "dshow", "-list_devices", "true", "-i", "dummy")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            CaptureLogger.raw("ffmpeg dshow -list_devices",
                    out.length() > 3000 ? out.substring(0, 3000) : out);

            // Lines look like:  [dshow @ 0x...] "Device Name" (audio)
            for (String line : out.split("[\\r\\n]+")) {
                if (!line.contains("(audio)")) continue;
                int q1 = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                if (q1 >= 0 && q2 > q1) {
                    String name = line.substring(q1 + 1, q2);
                    if (!name.isBlank()) devices.add(name);
                }
            }
        } catch (Exception e) {
            CaptureLogger.warn("dshow device enumeration failed: " + e.getMessage());
        }
        return devices;
    }

    /** Returns true if the device name looks like a system-audio loopback source. */
    private static boolean isLikelyLoopbackDevice(String name) {
        String lower = name.toLowerCase();
        return lower.contains("stereo mix")
            || lower.contains("loopback")
            || lower.contains("cable output")   // VB-Cable
            || lower.contains("wave out mix")
            || lower.contains("what u hear")
            || lower.contains("monitor of ");
    }

    private void startWasapiLoopback() throws LineUnavailableException {
        String ffmpegExe = resolveFfmpegExe();
        String deviceId  = source.wasapiDeviceId != null ? source.wasapiDeviceId : "";

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExe);
        cmd.add("-loglevel"); cmd.add("warning");

        if (deviceId.startsWith("native-loopback:")) {
            // ── Native WASAPI loopback via C#/PowerShell (output devices) ─
            startWasapiNativeLoopback(deviceId.substring("native-loopback:".length()));
            return;
        }

        if (deviceId.startsWith("dshow:")) {
            // ── DirectShow capture (works with all gyan.dev 8.x builds) ──
            String dshowDevice = deviceId.substring(6); // strip "dshow:" prefix
            if (dshowDevice.isBlank()) {
                throw new LineUnavailableException(
                    "No system-audio loopback device was found on this PC.\n\n"
                    + "To capture system audio on Windows:\n"
                    + "  1. Right-click the speaker icon in the taskbar\n"
                    + "  2. Open Sound settings \u2192 More sound settings\n"
                    + "  3. Click the Recording tab\n"
                    + "  4. Right-click in the empty area \u2192 Show Disabled Devices\n"
                    + "  5. Right-click \u2018Stereo Mix\u2019 \u2192 Enable\n"
                    + "  6. Restart the Sound Visualizer\n\n"
                    + "Alternatively, install a virtual audio cable:\n"
                    + "  https://vb-audio.com/Cable/");
            }
            cmd.add("-f"); cmd.add("dshow");
            cmd.add("-i"); cmd.add("audio=" + dshowDevice);
        } else {
            // ── WASAPI path (kept for older ffmpeg builds that include it) ──
            cmd.add("-f"); cmd.add("wasapi");
            cmd.add("-loopback"); cmd.add("1");
            cmd.add("-i"); cmd.add(deviceId);
        }

        cmd.add("-f");       cmd.add("s16le");
        cmd.add("-acodec");  cmd.add("pcm_s16le");
        cmd.add("-ar");      cmd.add(String.valueOf((int) SAMPLE_RATE));
        cmd.add("-ac");      cmd.add(String.valueOf(CHANNELS));
        cmd.add("pipe:1");

        CaptureLogger.info("capture command: " + String.join(" ", cmd));


        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        try {
            wasapiProcess = pb.start();
        } catch (IOException e) {
            CaptureLogger.error("Failed to launch ffmpeg", e);
            throw new LineUnavailableException("Failed to launch ffmpeg: " + e.getMessage());
        }

        // Read stderr and PCM in separate sub-threads; fire error callback on failure.
        final InputStream pcmStream = wasapiProcess.getInputStream();
        final InputStream errStream = wasapiProcess.getErrorStream();
        running = true;
        captureThread = new Thread(() -> {
            final String[] stderrHolder = {""};
            Thread stderrT = new Thread(() -> {
                try { stderrHolder[0] = new String(errStream.readAllBytes()).trim(); }
                catch (IOException ignored) {}
            }, "AudioCapture-wasapi-stderr");
            stderrT.setDaemon(true);
            stderrT.start();

            long frames = wasapiCaptureLoop(pcmStream);

            try { stderrT.join(2000); } catch (InterruptedException ignored) {}
            String stderr = stderrHolder[0];
            if (!stderr.isBlank()) CaptureLogger.raw("wasapi ffmpeg stderr", stderr);

            if (frames == 0 && wasapiErrorCallback != null) {
                wasapiErrorCallback.accept(buildWasapiErrorMessage(stderr));
            }
        }, "AudioCapture-wasapi");
        captureThread.setDaemon(true);
        captureThread.start();
        CaptureLogger.info("WASAPI loopback capture started");
    }

    /**
     * Captures audio from a Windows render endpoint (speakers/headphones) by
     * compiling and running a tiny C# WASAPI loopback helper via PowerShell's
     * {@code Add-Type}.  No external executables or drivers are required.
     *
     * <p>The helper outputs raw s16le stereo PCM at 44,100 Hz to stdout.
     *
     * @param windowsDeviceId  WASAPI device ID (e.g.
     *   {@code {0.0.0.00000000}.{guid}}) or empty string for the current
     *   default render endpoint.
     */
    private void startWasapiNativeLoopback(String windowsDeviceId)
            throws LineUnavailableException {
        Path ps1File = null;
        try {
            // Embed the C# directly into the .ps1 as a PowerShell here-string
            // and compile via Add-Type -TypeDefinition.  This avoids the
            // conflicting-parameter-set error that occurs when combining
            // Add-Type -Path with -Language CSharp.
            String devId = (windowsDeviceId == null ? "" : windowsDeviceId)
                               .replace("'", "''");
            String ps1Content =
                "$ErrorActionPreference = 'Stop'\r\n"
                + "$code = @\"\r\n"
                + buildWasapiLoopbackCsharp()
                + "\r\n\"@\r\n"
                + "Add-Type -TypeDefinition $code -Language CSharp\r\n"
                + "[WasapiLoopback]::Main([string[]]@('" + devId + "'))\r\n";

            Path ps1 = Files.createTempFile("wasapi_loopback_", ".ps1");
            ps1File = ps1;
            Files.writeString(ps1, ps1Content);

            CaptureLogger.info("native loopback devId='" + devId + "'  ps1=" + ps1);

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-ExecutionPolicy", "Bypass",
                    "-NonInteractive", "-File", ps1.toString())
                    .redirectErrorStream(false);
            wasapiProcess = pb.start();
        } catch (IOException e) {
            CaptureLogger.error("Failed to launch native WASAPI loopback", e);
            throw new LineUnavailableException(
                    "Failed to start WASAPI loopback: " + e.getMessage());
        }

        final InputStream pcmStream = wasapiProcess.getInputStream();
        final InputStream errStream = wasapiProcess.getErrorStream();
        final Path ps1FileRef = ps1File;

        running = true;
        captureThread = new Thread(() -> {
            final String[] stderrHolder = { "" };
            Thread stderrT = new Thread(() -> {
                try {
                    stderrHolder[0] = new String(errStream.readAllBytes()).trim();
                } catch (IOException ignored) {}
            }, "AudioCapture-native-stderr");
            stderrT.setDaemon(true);
            stderrT.start();

            long frames = wasapiCaptureLoop(pcmStream);

            try { stderrT.join(2000); } catch (InterruptedException ignored) {}
            String stderr = stderrHolder[0];
            if (!stderr.isBlank()) CaptureLogger.raw("native loopback stderr", stderr);

            // Clean up temp file
            try { if (ps1FileRef != null) Files.deleteIfExists(ps1FileRef); } catch (IOException ignored) {}

            if (frames == 0 && wasapiErrorCallback != null) {
                String msg = stderr.isBlank()
                    ? "Windows WASAPI loopback started but delivered no audio.\n\n"
                      + "Make sure something is playing through \""
                      + source.label.replace(" (loopback)", "") + "\".\n\n"
                      + "Check capture.log for details."
                    : "Windows WASAPI loopback failed:\n\n" + stderr;
                wasapiErrorCallback.accept(msg);
            }
        }, "AudioCapture-native-loopback");
        captureThread.setDaemon(true);
        captureThread.start();
        CaptureLogger.info("Native WASAPI loopback capture started");
    }

    /**
     * Returns the C# source for the WASAPI loopback helper.
     * Compiled at runtime via PowerShell {@code Add-Type -Path}.
     * Outputs raw s16le stereo PCM at 44,100 Hz to stdout.
     */
    private static String buildWasapiLoopbackCsharp() {
        // Plain double-quotes are safe inside a Java text block (only """ ends it).
        // This string is embedded into a PowerShell @" ... "@ here-string, so
        // no additional escaping is needed there either (PS does not interpret
        // the body of a double-quoted here-string for backslash sequences).
        return """
            using System;
            using System.IO;
            using System.Runtime.InteropServices;
            using System.Threading;

            [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
            [ClassInterface(ClassInterfaceType.None)]
            class MMDeviceEnumeratorCls {}

            [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
             InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDeviceEnumerator {
                void EnumAudioEndpoints(int flow, uint mask,
                    [MarshalAs(UnmanagedType.Interface)] out object devs);
                void GetDefaultAudioEndpoint(int flow, int role,
                    [MarshalAs(UnmanagedType.Interface)] out IMMDevice ppDev);
                void GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id,
                    [MarshalAs(UnmanagedType.Interface)] out IMMDevice ppDev);
                void Reg(IntPtr c); void Unreg(IntPtr c);
            }

            [Guid("D666063F-1587-4E43-81F1-B948E807363F"),
             InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDevice {
                void Activate([MarshalAs(UnmanagedType.LPStruct)] Guid iid,
                    uint ctx, IntPtr p,
                    [MarshalAs(UnmanagedType.Interface)] out object ppv);
                void OpenProp(uint acc,
                    [MarshalAs(UnmanagedType.Interface)] out object prop);
                void GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
                void GetState(out uint s);
            }

            [Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),
             InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioClient {
                void Initialize(int mode, uint flags, long bufDur,
                    long period, IntPtr fmt, IntPtr guid);
                void GetBufferSize(out uint size);
                void GetStreamLatency(out long lat);
                void GetCurrentPadding(out uint pad);
                void IsFormatSupported(int mode, IntPtr fmt, out IntPtr closest);
                void GetMixFormat(out IntPtr fmt);
                void GetDevicePeriod(out long def, out long minP);
                void Start(); void Stop(); void Reset();
                void SetEventHandle(IntPtr h);
                void GetService([MarshalAs(UnmanagedType.LPStruct)] Guid riid,
                    out IntPtr ppv);
            }

            [Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
             InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioCaptureClient {
                void GetBuffer(out IntPtr data, out uint frames,
                    out uint flags, out ulong pos, out ulong qpc);
                void ReleaseBuffer(uint frames);
                void GetNextPacketSize(out uint size);
            }

            [StructLayout(LayoutKind.Sequential)]
            struct WAVEFORMATEX {
                public ushort wFormatTag, nChannels;
                public uint   nSamplesPerSec, nAvgBytesPerSec;
                public ushort nBlockAlign, wBitsPerSample, cbSize;
            }

            public static class WasapiLoopback {
                const int LOOPBACK = 0x20000;
                const int SHARED   = 0;
                const int eRender  = 0, eConsole = 0;
                const int TARGET   = 44100;

                static readonly Guid IID_AudioClient =
                    new Guid(\"1CB9AD4C-DBFA-4C32-B178-C2F568A703B2\");
                static readonly Guid IID_CaptureClient =
                    new Guid(\"C8ADBD64-E71E-48A0-A4DE-185C395CD317\");
                static readonly Guid SUBFORMAT_FLOAT =
                    new Guid(\"00000003-0000-0010-8000-00aa00389b71\");

                public static void Main(string[] args) {
                    var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCls();
                    IMMDevice dev;
                    string id = args.Length > 0 ? args[0].Trim() : \"\";
                    if (string.IsNullOrEmpty(id))
                        en.GetDefaultAudioEndpoint(eRender, eConsole, out dev);
                    else
                        en.GetDevice(id, out dev);

                    object acObj;
                    dev.Activate(IID_AudioClient, 23, IntPtr.Zero, out acObj);
                    var ac = (IAudioClient)acObj;

                    IntPtr pFmt; ac.GetMixFormat(out pFmt);
                    var wfx = Marshal.PtrToStructure<WAVEFORMATEX>(pFmt);

                    bool isFloat = wfx.wFormatTag == 3;
                    if (wfx.wFormatTag == 0xFFFE && wfx.cbSize >= 22) {
                        // WAVEFORMATEXTENSIBLE: SubFormat GUID at byte offset 24
                        var sfmt = Marshal.PtrToStructure<Guid>(
                            new IntPtr(pFmt.ToInt64() + 24));
                        isFloat = sfmt == SUBFORMAT_FLOAT;
                    }
                    int srcRate = (int)wfx.nSamplesPerSec;
                    Console.Error.WriteLine(\"NATIVE_FORMAT:rate=\" + srcRate
                        + \" ch=\" + wfx.nChannels
                        + \" bits=\" + wfx.wBitsPerSample
                        + \" float=\" + isFloat);
                    Console.Error.Flush();

                    ac.Initialize(SHARED, LOOPBACK, 10000000L, 0, pFmt, IntPtr.Zero);

                    IntPtr pCap; ac.GetService(IID_CaptureClient, out pCap);
                    var cc = (IAudioCaptureClient)
                        Marshal.GetObjectForIUnknown(pCap);

                    ac.Start();
                    Console.Error.WriteLine(\"WASAPI_LOOPBACK_STARTED\");
                    Console.Error.Flush();

                    var stdout = Console.OpenStandardOutput();
                    while (true) {
                        Thread.Sleep(5);
                        uint next; cc.GetNextPacketSize(out next);
                        while (next > 0) {
                            IntPtr pData; uint nFrames, flags;
                            ulong pos, qpc;
                            cc.GetBuffer(out pData, out nFrames, out flags,
                                out pos, out qpc);
                            byte[] raw = new byte[nFrames * wfx.nBlockAlign];
                            Marshal.Copy(pData, raw, 0, raw.Length);
                            cc.ReleaseBuffer(nFrames);
                            byte[] s16 = ToS16Stereo44100(raw, (int)nFrames,
                                wfx, isFloat, srcRate);
                            stdout.Write(s16, 0, s16.Length);
                            stdout.Flush();
                            cc.GetNextPacketSize(out next);
                        }
                    }
                }

                static byte[] ToS16Stereo44100(byte[] raw, int frames,
                        WAVEFORMATEX wfx, bool isFloat, int srcRate) {
                    int srcCh  = wfx.nChannels;
                    int stride = srcCh > 0 ? (wfx.nBlockAlign / srcCh) : 4;

                    // Step 1: convert to float stereo at srcRate
                    float[] fs = new float[frames * 2];
                    for (int f = 0; f < frames; f++) {
                        for (int c = 0; c < 2; c++) {
                            int sc  = c < srcCh ? c : 0;
                            int off = f * wfx.nBlockAlign + sc * stride;
                            float v;
                            if (isFloat && stride == 4)
                                v = BitConverter.ToSingle(raw, off);
                            else if (stride == 2)
                                v = BitConverter.ToInt16(raw, off) / 32768f;
                            else if (stride == 4)
                                v = BitConverter.ToInt32(raw, off) / 2147483648f;
                            else if (stride == 3) {
                                int i = raw[off] | (raw[off+1]<<8)
                                    | (raw[off+2]<<16);
                                if ((i & 0x800000) != 0)
                                    i |= unchecked((int)0xFF000000);
                                v = i / 8388608f;
                            } else v = 0f;
                            fs[f * 2 + c] = v;
                        }
                    }

                    // Step 2: resample srcRate → 44100 if needed
                    int dstFrames = (srcRate == TARGET)
                        ? frames
                        : (int)Math.Round(frames * (double)TARGET / srcRate);
                    byte[] out16 = new byte[dstFrames * 4];
                    for (int i = 0; i < dstFrames; i++) {
                        double srcPos = (srcRate == TARGET)
                            ? i : i * (double)srcRate / TARGET;
                        int i0 = (int)srcPos;
                        int i1 = Math.Min(i0 + 1, frames - 1);
                        float t  = (float)(srcPos - i0);
                        for (int c = 0; c < 2; c++) {
                            float v = fs[i0*2+c] + (fs[i1*2+c] - fs[i0*2+c]) * t;
                            short s = (short)Math.Max(-32768,
                                Math.Min(32767, (int)(v * 32767f)));
                            int dst = (i * 2 + c) * 2;
                            out16[dst]   = (byte)(s & 0xFF);
                            out16[dst+1] = (byte)((s >> 8) & 0xFF);
                        }
                    }
                    return out16;
                }
            }
            """;
    }

    private static String buildWasapiErrorMessage(String stderr) {
        if (stderr.contains("dshow") && stderr.contains("not find")) {
            return "System audio capture failed: the selected audio device was not found.\n\n"
                + "ffmpeg error: " + stderr + "\n\n"
                + "Select a different device from the Source dropdown.";
        }
        if (!stderr.isBlank()) {
            return "Windows system audio capture failed.\n\nffmpeg reported:\n" + stderr + "\n\n"
                + "To fix:\n"
                + "  \u2022 Make sure 'Stereo Mix' is enabled in Windows Sound settings\n"
                + "    (Recording tab \u2192 right-click blank area \u2192 Show Disabled Devices)\n"
                + "  \u2022 Or install VB-Cable from https://vb-audio.com/Cable/\n"
                + "  \u2022 See capture.log for details";
        }
        return "Windows system audio capture started but delivered no audio samples.\n\n"
            + "Is something playing on your system?\n\n"
            + "If the source dropdown shows no loopback device:\n"
            + "  1. Open Windows Sound settings\n"
            + "  2. Go to More sound settings \u2192 Recording tab\n"
            + "  3. Right-click blank area \u2192 Show Disabled Devices\n"
            + "  4. Enable 'Stereo Mix'\n"
            + "  5. Restart the Sound Visualizer\n\n"
            + "Alternatively, install VB-Cable (free virtual audio cable):\n"
            + "  https://vb-audio.com/Cable/";
    }

    /**
     * Recursively searches {@code dir} up to {@code maxDepth} levels for a
     * file named {@code name}.  Only descends into directories whose names
     * contain "bin" OR whose depth < 2, to avoid scanning everything.
     */
    private static File searchForBinary(File dir, String name, int maxDepth) {
        if (maxDepth < 0 || dir == null) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        // Check direct children first
        for (File f : children) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        }
        // Recurse into subdirectories
        for (File f : children) {
            if (!f.isDirectory()) continue;
            File found = searchForBinary(f, name, maxDepth - 1);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Reads raw s16le stereo PCM from the ffmpeg stdout pipe and delivers
     * decoded frames to listeners, exactly like {@link #captureLoop()} but
     * driven by the pipe rather than a TargetDataLine.
     */
    /** Reads raw s16le stereo PCM from the ffmpeg stdout pipe and returns the frame count. */
    private long wasapiCaptureLoop(InputStream in) {
        final int frameSize    = (SAMPLE_BITS / 8) * CHANNELS; // 4
        final int bytesPerRead = bufferFrames * frameSize;
        byte[] buf = new byte[bytesPerRead];
        long framesDelivered = 0;

        try {
            while (running) {
                int totalRead = 0;
                while (totalRead < bytesPerRead && running) {
                    int n = in.read(buf, totalRead, bytesPerRead - totalRead);
                    if (n < 0) { running = false; break; } // EOF
                    totalRead += n;
                }
                if (totalRead <= 0) break;

                int alignedBytes = (totalRead / frameSize) * frameSize;
                int frames       = alignedBytes / frameSize;
                if (frames == 0) continue;

                float[] left  = new float[frames];
                float[] right = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int off = i * frameSize;
                    short l = (short) ((buf[off]     & 0xFF) | ((buf[off+1] & 0xFF) << 8));
                    short r = (short) ((buf[off + 2] & 0xFF) | ((buf[off+3] & 0xFF) << 8));
                    left[i]  = l / 32768f;
                    right[i] = r / 32768f;
                }
                framesDelivered += frames;
                float[][] channels = { left, right };
                for (Consumer<float[][]> cb : listeners) {
                    try { cb.accept(channels); } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            if (running) CaptureLogger.error("WASAPI capture loop IOException", e);
        } finally {
            CaptureLogger.info("WASAPI capture loop ended. framesDelivered=" + framesDelivered);
            running = false;
        }
        return framesDelivered;
    }


    public void stop() {
        CaptureLogger.info("AudioCapture.stop() wasapi=" + wasapiProcess);
        running = false;
        if (wasapiProcess != null) {
            wasapiProcess.destroyForcibly();
            wasapiProcess = null;
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }

    // -----------------------------------------------------------------------
    // Capture loop
    // -----------------------------------------------------------------------

    private void captureLoop() {
        AudioFormat fmt = (line != null) ? line.getFormat() : FORMAT;
        int framesPerChunk = bufferFrames;
        int frameSize      = fmt.getFrameSize();
        int bytesPerChunk  = framesPerChunk * frameSize;
        byte[] buf         = new byte[bytesPerChunk];

        while (running) {
            int read = line.read(buf, 0, buf.length);
            if (read <= 0) continue;

            int frames   = read / frameSize;
            int numChan  = fmt.getChannels();
            int bitDepth = fmt.getSampleSizeInBits();
            boolean le   = !fmt.isBigEndian();

            float[] left  = new float[frames];
            float[] right = new float[frames];

            for (int i = 0; i < frames; i++) {
                int offset = i * frameSize;
                left[i]  = sampleToFloat(buf, offset, bitDepth, le);
                if (numChan >= 2) {
                    right[i] = sampleToFloat(buf, offset + (bitDepth / 8), bitDepth, le);
                } else {
                    right[i] = left[i]; // mono → duplicate
                }
            }

            float[][] channels = {left, right};
            for (Consumer<float[][]> cb : listeners) {
                try { cb.accept(channels); } catch (Exception ignored) {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static float sampleToFloat(byte[] buf, int offset, int bits, boolean le) {
        if (bits == 16) {
            int lo  = buf[offset]     & 0xFF;
            int hi  = buf[offset + 1] & 0xFF;
            short s = le ? (short) (lo | (hi << 8)) : (short) ((lo << 8) | hi);
            return s / 32768f;
        } else if (bits == 8) {
            return (buf[offset] - 128) / 128f;
        } else if (bits == 24) {
            int b0 = buf[offset]     & 0xFF;
            int b1 = buf[offset + 1] & 0xFF;
            int b2 = buf[offset + 2] & 0xFF;
            int v  = le ? (b0 | (b1 << 8) | (b2 << 16)) : ((b0 << 16) | (b1 << 8) | b2);
            if ((v & 0x800000) != 0) v |= 0xFF000000; // sign-extend
            return v / 8388608f;
        } else if (bits == 32) {
            int b0 = buf[offset]     & 0xFF;
            int b1 = buf[offset + 1] & 0xFF;
            int b2 = buf[offset + 2] & 0xFF;
            int b3 = buf[offset + 3] & 0xFF;
            int v  = le ? (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
                        : ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3);
            return v / 2147483648f;
        }
        return 0f;
    }
}
