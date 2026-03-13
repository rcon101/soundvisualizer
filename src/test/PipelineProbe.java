package test;

import com.soundvisualizer.*;
import javax.sound.sampled.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Headless end-to-end pipeline probe.
 *
 * Runs the exact same capture→processor→visualizer update path as the GUI app,
 * but prints metrics to stdout every second so each stage can be independently
 * verified without a display.
 *
 * Usage (with audio playing in Windows):
 *   PULSE_SERVER=unix:/mnt/wslg/PulseServer java -cp bin test.PipelineProbe
 *
 * Or to test a specific source by name:
 *   PULSE_SERVER=unix:/mnt/wslg/PulseServer java -cp bin test.PipelineProbe RDPSink.monitor
 */
public class PipelineProbe {

    // ── Counters for each stage ──────────────────────────────────────────
    private static final AtomicLong captureChunks   = new AtomicLong();
    private static final AtomicLong captureBytes    = new AtomicLong();
    private static final AtomicDouble captureRmsSum = new AtomicDouble();

    private static final AtomicLong processCalls    = new AtomicLong();
    private static final AtomicDouble spectrumMax   = new AtomicDouble();

    private static final AtomicLong vizUpdateCalls  = new AtomicLong();
    private static final AtomicDouble vizSpecMax    = new AtomicDouble();

    // ── Simple AtomicDouble since Java doesn't have one ─────────────────
    static class AtomicDouble {
        private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0.0));
        double get() { return Double.longBitsToDouble(bits.get()); }
        void set(double v) { bits.set(Double.doubleToLongBits(v)); }
        void updateMax(double v) {
            double cur;
            do { cur = get(); } while (v > cur && !bits.compareAndSet(
                    Double.doubleToLongBits(cur), Double.doubleToLongBits(v)));
        }
    }

    // ────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("Pipeline Probe — " + new java.util.Date());
        System.out.println("JVM: " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version"));
        System.out.println("PULSE_SERVER: " + System.getenv("PULSE_SERVER"));
        System.out.println();

        // ── Stage 0: enumerate sources ───────────────────────────────────
        List<AudioCapture.SourceInfo> sources = AudioCapture.getAvailableSources();
        System.out.println("Stage 0 – Source Enumeration");
        System.out.println("  Available sources: " + sources.size());
        for (AudioCapture.SourceInfo s : sources) {
            System.out.println("  [" + (s.isPulse() ? "PULSE" : "ALSA ") + "] "
                    + s.label + (s.pulseName != null ? "  (PA: " + s.pulseName + ")" : ""));
        }

        if (sources.isEmpty()) {
            System.out.println("  ✗ No sources found. Aborting.");
            return;
        }

        // Pick requested source or default to first monitor/PA source
        AudioCapture.SourceInfo chosen = chooseSource(sources, args.length > 0 ? args[0] : null);
        System.out.println();
        System.out.println("  Selected: " + chosen.label);
        System.out.println("  Type: " + (chosen.isPulse() ? "PulseAudio direct" : "Java ALSA mixer"));
        System.out.println();

        // ── Stage 1: open capture ────────────────────────────────────────
        System.out.println("Stage 1 – AudioCapture.start()");
        AudioCapture capture = new AudioCapture(chosen);
        capture.setBufferFrames(2048);

        // Logging listener on capture output
        capture.addListener(channels -> {
            captureChunks.incrementAndGet();
            float[] L = channels[0];
            double rms = 0;
            for (float v : L) { rms += v * v; captureBytes.addAndGet(4); }
            captureRmsSum.updateMax(Math.sqrt(rms / L.length));
        });

        // ── Stage 2: AudioProcessor ──────────────────────────────────────
        AudioProcessor processor = new AudioProcessor();
        processor.setFftSize(2048);
        processor.setSmoothing(0.75f);
        processor.setBarCount(120);
        processor.setNoiseFloor(-90f);

        // Logging shim around processor.process()
        capture.addListener(channels -> {
            processor.process(channels);
            processCalls.incrementAndGet();
            float[] spec = processor.getSpectrum();
            double max = 0;
            for (float v : spec) if (v > max) max = v;
            spectrumMax.updateMax(max);
        });

        // ── Stage 3: simulated viz update ───────────────────────────────
        capture.addListener(channels -> {
            // Simulate what SoundVisualizer.pushFrame() does
            float[] spec     = processor.getSpectrum();
            float[] peaks    = processor.getPeaks();
            float[] barFreqs = processor.getBarFrequencies();
            float[] waveL    = processor.getWaveformL();

            // Metrics
            vizUpdateCalls.incrementAndGet();
            double max = 0;
            for (float v : spec) if (v > max) max = v;
            vizSpecMax.updateMax(max);
        });

        try {
            capture.start();
        } catch (LineUnavailableException e) {
            System.out.println("  ✗ capture.start() threw: " + e.getMessage());
            System.out.println("    Source: " + chosen.label);
            System.out.println("    Hint: 'System Default' maps to PulseAudio default source.");
            System.out.println("    Try: pactl set-default-source RDPSink.monitor first.");
            tryRawCapture();
            return;
        }
        System.out.println("  ✓ capture.start() succeeded");
        System.out.println();

        // ── Print metrics every second for 10 seconds ───────────────────
        System.out.printf("%-5s  %-12s  %-12s  %-14s  %-12s  %-12s  %-12s%n",
                "Sec", "CaptChunks", "CaptBytes", "CaptRmsMax",
                "ProcCalls", "SpecMax", "VizCalls");
        System.out.println("-".repeat(82));

        for (int sec = 1; sec <= 10; sec++) {
            Thread.sleep(1000);
            long   cc = captureChunks.get();
            long   cb = captureBytes.get();
            double cr = captureRmsSum.get();
            long   pc = processCalls.get();
            double sm = spectrumMax.get();
            long   vc = vizUpdateCalls.get();

            System.out.printf("%-5d  %-12d  %-12d  %-14.6f  %-12d  %-12.4f  %-12d%n",
                    sec, cc, cb, cr, pc, sm, vc);

            // Diagnose stalled stages
            if (sec == 3) {
                if (cc == 0) {
                    System.out.println("  ⚠ STAGE 1 STALLED: no capture chunks after 3s");
                    System.out.println("    → The TargetDataLine opened but is not delivering data.");
                    System.out.println("    → Is anything playing audio on Windows?");
                    System.out.println("    → Try: PULSE_SERVER=unix:/mnt/wslg/PulseServer pactl "
                            + "set-default-source RDPSink.monitor");
                } else if (pc == 0) {
                    System.out.println("  ⚠ STAGE 2 STALLED: capture has data but processor never called");
                    System.out.println("    → Check that the processor listener is registered.");
                } else if (vc == 0) {
                    System.out.println("  ⚠ STAGE 3 STALLED: processor runs but viz update never called");
                } else if (sm < 0.001) {
                    System.out.println("  ⚠ STAGE 2: processor is called but spectrum is all ~zero");
                    System.out.println("    → Captured audio is silence.  Play audio on Windows and retry.");
                    System.out.println("    → CaptRmsMax=" + cr + "  (needs > 0.0001 to visualize)");
                }
            }
        }

        capture.stop();
        System.out.println();
        diagnosticSummary();
    }

    // ── Choose source ────────────────────────────────────────────────────

    private static AudioCapture.SourceInfo chooseSource(
            List<AudioCapture.SourceInfo> sources, String nameHint) {

        if (nameHint != null) {
            for (AudioCapture.SourceInfo s : sources) {
                if (s.label.contains(nameHint)
                        || (s.pulseName != null && s.pulseName.contains(nameHint))) {
                    return s;
                }
            }
            System.out.println("  (hint '" + nameHint + "' not found, using first source)");
        }

        // Prefer a monitor/loopback source
        for (AudioCapture.SourceInfo s : sources) {
            if (s.label.toLowerCase().contains("monitor")
                    || s.label.toLowerCase().contains("all programs")) return s;
        }
        return sources.get(0);
    }

    // ── Raw TargetDataLine test (fallback when capture.start() fails) ───

    private static void tryRawCapture() throws Exception {
        System.out.println();
        System.out.println("Fallback: trying AudioSystem default TargetDataLine directly...");
        AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
        try {
            DataLine.Info dli = new DataLine.Info(TargetDataLine.class, fmt);
            TargetDataLine tdl = (TargetDataLine) AudioSystem.getLine(dli);
            tdl.open(fmt, 8192);
            tdl.start();

            byte[] buf = new byte[4096];
            int totalRead = 0;
            double maxRms = 0;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                int n = tdl.read(buf, 0, buf.length);
                if (n > 0) {
                    totalRead += n;
                    double rms = 0;
                    for (int i = 0; i < n - 1; i += 2) {
                        short s = (short)((buf[i+1] << 8) | (buf[i] & 0xFF));
                        rms += (double)s * s;
                    }
                    maxRms = Math.max(maxRms, Math.sqrt(rms / (n/2)) / 32768.0);
                }
            }
            tdl.close();
            System.out.printf("  Raw fallback: read %d bytes  maxRMS=%.6f%n", totalRead, maxRms);
            if (maxRms > 0.0001) {
                System.out.println("  ✓ Raw capture has signal — issue is in source routing, not hardware");
            } else {
                System.out.println("  ✗ Raw capture is silent — no audio playing or wrong PA source");
            }
        } catch (Exception e) {
            System.out.println("  ✗ Raw fallback also failed: " + e.getMessage());
        }
    }

    // ── Summary ──────────────────────────────────────────────────────────

    private static void diagnosticSummary() {
        System.out.println("Pipeline Summary");
        System.out.println("─".repeat(50));

        boolean s1 = captureChunks.get() > 0;
        boolean s2 = processCalls.get() > 0;
        boolean s3 = vizUpdateCalls.get() > 0;
        boolean hasSignal = spectrumMax.get() > 0.001;

        System.out.printf("  Stage 1 – Capture:    %s  (%d chunks, %.2f KB)%n",
                s1 ? "✓ ACTIVE" : "✗ DEAD",
                captureChunks.get(), captureBytes.get() / 1024.0);
        System.out.printf("  Stage 2 – Processor:  %s  (specMax=%.4f)%n",
                s2 ? "✓ ACTIVE" : "✗ DEAD", spectrumMax.get());
        System.out.printf("  Stage 3 – Viz update: %s  (%d calls)%n",
                s3 ? "✓ ACTIVE" : "✗ DEAD", vizUpdateCalls.get());
        System.out.printf("  Signal present:       %s%n",
                hasSignal ? "✓ YES" : "✗ NO (all silence)");
        System.out.println();

        if (!s1) {
            System.out.println("  FIX: Capture not delivering data.");
            System.out.println("    Run: pactl set-default-source RDPSink.monitor");
            System.out.println("    Then rerun this probe.");
        } else if (!hasSignal) {
            System.out.println("  FIX: Capture runs but captured audio is silent.");
            System.out.println("    → Play something on Windows (YouTube, music app, etc.)");
            System.out.println("    → Then rerun this probe.");
        } else if (!s2) {
            System.out.println("  FIX: Processor listener was never called despite active capture.");
            System.out.println("    → This is a code bug — check AudioCapture.addListener() wiring.");
        } else if (!s3) {
            System.out.println("  FIX: Viz update never fired despite active processor.");
            System.out.println("    → In the app, check that the Swing Timer is running.");
        } else {
            System.out.println("  All stages active with signal present.");
            System.out.println("  → If the GUI still shows nothing, run the app with:");
            System.out.println("    PULSE_SERVER=unix:/mnt/wslg/PulseServer make run");
            System.out.println("    and check that the Swing timer is calling pushFrame().");
        }
    }
}
