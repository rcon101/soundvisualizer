package test;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Comprehensive audio diagnostics for WSL2 / PulseAudio / ALSA environments.
 *
 * Runs a sequence of tests and prints a clear PASS/FAIL summary so you can
 * pinpoint exactly what is needed to get live system-audio capture working.
 *
 * Run:
 *   javac -d bin src/test/AudioDiagnostics.java
 *   PULSE_SERVER=unix:/mnt/wslg/PulseServer java -cp bin test.AudioDiagnostics
 */
public class AudioDiagnostics {

    // -----------------------------------------------------------------------
    // Test registry
    // -----------------------------------------------------------------------

    private record TestResult(String name, boolean passed, String detail) {}
    private static final List<TestResult> results = new ArrayList<>();

    private static void pass(String name, String detail) {
        results.add(new TestResult(name, true, detail));
        System.out.printf("  [ PASS ] %s%n        %s%n", name, detail);
    }

    private static void fail(String name, String detail) {
        results.add(new TestResult(name, false, detail));
        System.out.printf("  [FAIL ] %s%n        %s%n", name, detail);
    }

    private static void info(String msg) {
        System.out.println("  [INFO ] " + msg);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════");
        System.out.println("  " + title);
        System.out.println("══════════════════════════════════════════════════════");
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("Audio Diagnostics — " + new java.util.Date());
        System.out.println("JVM: " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version")
                + "  OS: " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch"));

        testEnvironment();
        testAlsaConfig();
        testMixerEnumeration();
        testFormatNegotiation();
        testCapture();
        testPulseAudioSocket();

        printSummary();
    }

    // -----------------------------------------------------------------------
    // Test 1 – Environment
    // -----------------------------------------------------------------------

    private static void testEnvironment() {
        section("1. Environment Variables");

        String pulseServer = System.getenv("PULSE_SERVER");
        info("PULSE_SERVER = " + pulseServer);
        if (pulseServer != null && !pulseServer.isBlank()) {
            pass("PULSE_SERVER set", pulseServer);
        } else {
            fail("PULSE_SERVER not set",
                    "Run with: PULSE_SERVER=unix:/mnt/wslg/PulseServer java ...");
        }

        String wslDistro = System.getenv("WSL_DISTRO_NAME");
        info("WSL_DISTRO_NAME = " + wslDistro);

        String wslInterop = System.getenv("WSL_INTEROP");
        info("WSL_INTEROP = " + wslInterop);

        if (wslDistro != null || wslInterop != null) {
            pass("WSL2 detected", "wslDistro=" + wslDistro);
        } else {
            info("Not running under WSL2 (or variables not set)");
        }

        // Check for WSLg PulseAudio socket
        File pulseSocket = new File("/mnt/wslg/PulseServer");
        if (pulseSocket.exists()) {
            pass("WSLg PulseAudio socket exists", pulseSocket.getPath());
        } else {
            fail("WSLg PulseAudio socket missing",
                    "/mnt/wslg/PulseServer not found — is WSLg running?");
        }

        // Check for ALSA config files
        checkFile("~/.asoundrc", System.getProperty("user.home") + "/.asoundrc");
        checkFile("/etc/asound.conf", "/etc/asound.conf");

        // Java audio properties
        String javasoundMixer = System.getProperty("javax.sound.sampled.Mixer");
        info("javax.sound.sampled.Mixer property = " + javasoundMixer);
    }

    private static void checkFile(String label, String path) {
        File f = new File(path);
        if (f.exists()) {
            pass(label + " exists", path);
            try {
                String content = Files.readString(f.toPath());
                info("  Contents of " + label + ":");
                for (String line : content.split("\n")) {
                    info("    " + line.stripTrailing());
                }
            } catch (IOException e) {
                info("  (could not read: " + e.getMessage() + ")");
            }
        } else {
            info(label + " not found (will use defaults)");
        }
    }

    // -----------------------------------------------------------------------
    // Test 2 – ALSA config recommendation
    // -----------------------------------------------------------------------

    private static void testAlsaConfig() {
        section("2. ALSA / PulseAudio Config Recommendation");

        String asoundrc = System.getProperty("user.home") + "/.asoundrc";
        File f = new File(asoundrc);

        // Accept either "type pulse" (our config) or "pcm.pulse" (alternate style)
        if (f.exists()) {
            try {
                String content = Files.readString(f.toPath());
                if (content.contains("type pulse") || content.contains("pcm.pulse")) {
                    pass("~/.asoundrc has PulseAudio PCM plugin", "'type pulse' found");
                } else {
                    fail("~/.asoundrc lacks PulseAudio PCM plugin",
                            "Add the config shown below");
                    printAsoundrcSuggestion();
                }
            } catch (IOException e) {
                fail("Cannot read ~/.asoundrc", e.getMessage());
            }
        } else {
            fail("~/.asoundrc not present",
                    "Create it with the config shown below to route ALSA → PulseAudio");
            printAsoundrcSuggestion();
        }
    }

    private static void printAsoundrcSuggestion() {
        System.out.println();
        System.out.println("  ┌── Suggested ~/.asoundrc ──────────────────────────────┐");
        System.out.println("  │  pcm.!default {                                        │");
        System.out.println("  │      type pulse                                        │");
        System.out.println("  │      hint.description \"Default Audio\"                 │");
        System.out.println("  │  }                                                     │");
        System.out.println("  │  ctl.!default {                                        │");
        System.out.println("  │      type pulse                                        │");
        System.out.println("  │  }                                                     │");
        System.out.println("  └────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  Then rerun with: PULSE_SERVER=unix:/mnt/wslg/PulseServer java ...");
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Test 3 – Mixer enumeration
    // -----------------------------------------------------------------------

    private static void testMixerEnumeration() {
        section("3. Java Mixer / Device Enumeration");

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        info("AudioSystem.getMixerInfo() returned " + mixers.length + " mixer(s)");

        if (mixers.length == 0) {
            fail("No mixers found",
                    "Java cannot see any audio devices. "
                    + "PULSE_SERVER env var must be set AND ~/.asoundrc must route to PulseAudio.");
            return;
        }

        pass("Mixers found", mixers.length + " mixer(s) visible");

        // Formats to probe
        AudioFormat[] probeFormats = {
            new AudioFormat(44100, 16, 2, true, false),
            new AudioFormat(44100, 16, 1, true, false),
            new AudioFormat(48000, 16, 2, true, false),
            new AudioFormat(48000, 16, 1, true, false),
            new AudioFormat(44100,  8, 1, true, false),
        };

        for (Mixer.Info minfo : mixers) {
            System.out.println();
            info("Mixer: [" + minfo.getName() + "]  vendor=" + minfo.getVendor());
            info("  description: " + minfo.getDescription());

            Mixer mixer = AudioSystem.getMixer(minfo);

            // Target lines (capture)
            Line.Info[] targets = mixer.getTargetLineInfo();
            info("  Target lines (capture): " + targets.length);
            for (Line.Info li : targets) {
                info("    " + li.toString()
                        + (li instanceof DataLine.Info dli
                            ? "  formats=" + dli.getFormats().length : ""));
            }

            // Source lines (playback — just count)
            Line.Info[] sources = mixer.getSourceLineInfo();
            info("  Source lines (playback): " + sources.length);

            // Format support check
            for (AudioFormat fmt : probeFormats) {
                DataLine.Info dli = new DataLine.Info(TargetDataLine.class, fmt);
                boolean supported = mixer.isLineSupported(dli);
                String tag = supported ? " ► CAPTURE SUPPORTED" : "";
                info("  isLineSupported(" + fmtStr(fmt) + ") = " + supported + tag);
            }
        }
    }

    private static String fmtStr(AudioFormat f) {
        return String.format("%dHz/%dbit/%dch", (int)f.getSampleRate(),
                f.getSampleSizeInBits(), f.getChannels());
    }

    // -----------------------------------------------------------------------
    // Test 4 – Format negotiation
    // -----------------------------------------------------------------------

    private static void testFormatNegotiation() {
        section("4. Format Negotiation — Open TargetDataLine");

        AudioFormat[] candidates = {
            new AudioFormat(44100, 16, 2, true, false),  // preferred
            new AudioFormat(44100, 16, 1, true, false),
            new AudioFormat(48000, 16, 2, true, false),
            new AudioFormat(48000, 16, 1, true, false),
            new AudioFormat(44100,  8, 2, true, false),
            new AudioFormat(44100,  8, 1, true, false),
            // Big-endian variants
            new AudioFormat(44100, 16, 2, true, true),
            new AudioFormat(44100, 16, 1, true, true),
        };

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        if (mixers.length == 0) {
            fail("Skipped — no mixers available", "Fix enumeration first");
            return;
        }

        boolean anySuccess = false;
        for (Mixer.Info minfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(minfo);
            // Skip mixers with no target lines
            if (mixer.getTargetLineInfo().length == 0) continue;

            info("Trying mixer: " + minfo.getName());
            for (AudioFormat fmt : candidates) {
                try {
                    DataLine.Info dli = new DataLine.Info(TargetDataLine.class, fmt);
                    TargetDataLine tdl = (TargetDataLine) mixer.getLine(dli);
                    tdl.open(fmt, 4096);
                    tdl.close();
                    pass("Opened TargetDataLine on [" + minfo.getName() + "]",
                            fmtStr(fmt));
                    anySuccess = true;
                    break;
                } catch (Exception e) {
                    info("  " + fmtStr(fmt) + " → " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        }

        // Also try AudioSystem default line
        info("Trying AudioSystem default line...");
        for (AudioFormat fmt : candidates) {
            try {
                DataLine.Info dli = new DataLine.Info(TargetDataLine.class, fmt);
                TargetDataLine tdl = (TargetDataLine) AudioSystem.getLine(dli);
                tdl.open(fmt, 4096);
                tdl.close();
                pass("Opened default TargetDataLine", fmtStr(fmt));
                anySuccess = true;
                break;
            } catch (Exception e) {
                info("  " + fmtStr(fmt) + " → " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }

        if (!anySuccess) {
            fail("Could not open any TargetDataLine",
                    "PulseAudio ALSA plugin likely not configured – see ~/.asoundrc suggestion above");
        }
    }

    // -----------------------------------------------------------------------
    // Test 5 – Live capture (reads 1 second of audio, checks for non-silence)
    // -----------------------------------------------------------------------

    private static void testCapture() {
        section("5. Live Capture — Read 1 Second of Audio");

        AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
        TargetDataLine tdl = null;

        // Try to open
        try {
            DataLine.Info dli = new DataLine.Info(TargetDataLine.class, fmt);
            tdl = (TargetDataLine) AudioSystem.getLine(dli);
            tdl.open(fmt, 8192);
        } catch (Exception e) {
            fail("Cannot open capture line", e.getMessage());
            return;
        }

        // Read 1 second
        int sampleRate = (int) fmt.getSampleRate();
        int channels   = fmt.getChannels();
        int bytesPerSample = fmt.getSampleSizeInBits() / 8;
        int totalBytes = sampleRate * channels * bytesPerSample; // 1s
        byte[] buf = new byte[totalBytes];

        tdl.start();
        int read = 0;
        long deadline = System.currentTimeMillis() + 3000; // 3s timeout
        while (read < totalBytes && System.currentTimeMillis() < deadline) {
            int chunk = tdl.read(buf, read, totalBytes - read);
            if (chunk > 0) read += chunk;
        }
        tdl.stop();
        tdl.close();

        if (read < totalBytes / 2) {
            fail("Capture timeout", "Only read " + read + "/" + totalBytes + " bytes in 3s");
            return;
        }
        pass("Capture read", read + " bytes from live line");

        // Analyse samples
        double rms = 0;
        int zeros = 0;
        int samples = read / (channels * bytesPerSample);
        for (int i = 0; i < samples; i++) {
            int off = i * channels * bytesPerSample;
            short s = (short) ((buf[off + 1] << 8) | (buf[off] & 0xFF));
            rms += (double) s * s;
            if (s == 0) zeros++;
        }
        rms = Math.sqrt(rms / samples) / 32768.0;
        double silentFraction = (double) zeros / samples;

        info(String.format("RMS level: %.6f  zero-samples: %.1f%%",
                rms, silentFraction * 100.0));

        if (rms > 0.0005) {
            pass("Non-silent audio captured", String.format("RMS=%.4f", rms));
        } else if (silentFraction < 0.99) {
            pass("Non-zero samples present (low level)", String.format("RMS=%.6f", rms));
        } else {
            fail("All-silence captured",
                    "Line opened but data is silent. "
                    + "Is anything playing? Is PULSE_SERVER pointing at the right server? "
                    + "Try selecting the monitor source (RDPSink.monitor).");
        }
    }

    // -----------------------------------------------------------------------
    // Test 6 – PulseAudio socket connectivity (via pactl subprocess)
    // -----------------------------------------------------------------------

    private static void testPulseAudioSocket() {
        section("6. PulseAudio Socket — pactl Probe");

        // Check if pactl is available
        String pactlPath = findExecutable("pactl");
        if (pactlPath == null) {
            fail("pactl not found on PATH",
                    "Install: sudo apt-get install pulseaudio-utils");
            return;
        }
        pass("pactl found", pactlPath);

        String pulseServer = System.getenv("PULSE_SERVER");
        if (pulseServer == null) pulseServer = "unix:/mnt/wslg/PulseServer";

        info("Using PULSE_SERVER=" + pulseServer);

        // Query sources
        runPactl(pulseServer, "info");
        runPactl(pulseServer, "list", "sources", "short");
        runPactl(pulseServer, "list", "sinks", "short");

        // Check which source maps to system output monitor
        String[] result = runPactlCapture(pulseServer, "list", "sources", "short");
        boolean hasMonitor = false;
        String monitorName = null;
        if (result != null) {
            for (String line : result) {
                if (line.contains(".monitor") || line.contains("monitor")) {
                    hasMonitor = true;
                    monitorName = line.split("\\s+").length > 1
                            ? line.split("\\s+")[1] : line.trim();
                }
            }
        }

        if (hasMonitor) {
            pass("System audio monitor source found", monitorName);
            System.out.println();
            System.out.println("  ┌── How to capture system audio ────────────────────────┐");
            System.out.println("  │  The monitor source '" + monitorName + "'");
            System.out.println("  │  captures all audio playing through Windows.           │");
            System.out.println("  │                                                         │");
            System.out.println("  │  Launch the app with:                                  │");
            System.out.println("  │    PULSE_SERVER=unix:/mnt/wslg/PulseServer make run    │");
            System.out.println("  │                                                         │");
            System.out.println("  │  Or add to run.sh:                                     │");
            System.out.println("  │    export PULSE_SERVER=unix:/mnt/wslg/PulseServer      │");
            System.out.println("  └─────────────────────────────────────────────────────────┘");
            System.out.println();
        } else {
            fail("No monitor source found",
                    "PulseAudio has no loopback / monitor source. "
                    + "Audio output capture may not be available.");
        }
    }

    private static void runPactl(String server, String... cmdArgs) {
        String[] lines = runPactlCapture(server, cmdArgs);
        if (lines != null) {
            for (String l : lines) info("  pactl: " + l);
        }
    }

    private static String[] runPactlCapture(String server, String... cmdArgs) {
        try {
            String[] cmd = new String[cmdArgs.length + 1];
            cmd[0] = "pactl";
            System.arraycopy(cmdArgs, 0, cmd, 1, cmdArgs.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PULSE_SERVER", server);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return out.isEmpty() ? new String[0] : out.split("\n");
        } catch (Exception e) {
            fail("pactl subprocess error", e.getMessage());
            return null;
        }
    }

    private static String findExecutable(String name) {
        for (String dir : System.getenv("PATH").split(":")) {
            File f = new File(dir, name);
            if (f.canExecute()) return f.getPath();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Summary
    // -----------------------------------------------------------------------

    private static void printSummary() {
        section("SUMMARY");
        long passed = results.stream().filter(r -> r.passed()).count();
        long failed = results.stream().filter(r -> !r.passed()).count();

        System.out.printf("  %d passed, %d failed%n%n", passed, failed);

        if (failed > 0) {
            System.out.println("  FAILED TESTS:");
            results.stream()
                   .filter(r -> !r.passed())
                   .forEach(r -> System.out.printf("    ✗  %s%n       %s%n", r.name(), r.detail()));
            System.out.println();
            System.out.println("  ACTION PLAN:");
            System.out.println("  1. If PULSE_SERVER not set → set it in run.sh");
            System.out.println("  2. If no mixers found → create ~/.asoundrc (see Test 2 output)");
            System.out.println("  3. If line opens but all-silence → play audio and retry,");
            System.out.println("     or switch to RDPSink.monitor source explicitly");
            System.out.println("  4. WSL2: 'make native' uses Windows JDK for direct Windows audio");
        } else {
            System.out.println("  All tests passed — live audio capture should work.");
            System.out.println("  Make sure to launch with: PULSE_SERVER=unix:/mnt/wslg/PulseServer");
        }
        System.out.println();
    }
}
