package test;

import com.soundvisualizer.*;
import javax.sound.sampled.*;
import java.util.List;
import java.util.concurrent.atomic.*;

/**
 * Quick 3-second capture test: opens the monitor source, reads audio,
 * and reports RMS + peak amplitude.  Runs in ~4 seconds.
 *
 * Usage:
 *   PULSE_SERVER=unix:/mnt/wslg/PulseServer java -cp bin test.QuickCaptureTest
 */
public class QuickCaptureTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Quick Capture Test — " + new java.util.Date());
        System.out.println("PULSE_SERVER: " + System.getenv("PULSE_SERVER"));

        // Start a 1 kHz test tone through PulseAudio before capturing
        Process toneProcess = null;
        try {
            System.out.println("\nStarting 1 kHz test tone via speaker-test...");
            ProcessBuilder pb = new ProcessBuilder(
                    "speaker-test", "-t", "sine", "-f", "1000", "-l", "999", "-p", "50");
            pb.environment().put("PULSE_SERVER",
                    System.getenv("PULSE_SERVER") != null
                    ? System.getenv("PULSE_SERVER") : "unix:/mnt/wslg/PulseServer");
            pb.redirectErrorStream(true);
            toneProcess = pb.start();
            toneProcess.getInputStream(); // don't block on stdout
            Thread.sleep(300); // let tone start
            System.out.println("  Tone started (PID tracked)");
        } catch (Exception e) {
            System.out.println("  Could not start speaker-test: " + e.getMessage());
            System.out.println("  Continuing without test tone...");
        }

        // Enumerate sources
        List<AudioCapture.SourceInfo> sources = AudioCapture.getAvailableSources();
        System.out.println("\nAvailable sources:");
        for (int i = 0; i < sources.size(); i++) {
            AudioCapture.SourceInfo s = sources.get(i);
            System.out.printf("  [%d] %s%s%n", i, s.label,
                    s.pulseName != null ? "  PA=" + s.pulseName : "");
        }

        if (sources.isEmpty()) {
            System.out.println("No sources. Exiting.");
            if (toneProcess != null) toneProcess.destroy();
            return;
        }

        // Test each source for 1.5 seconds each
        for (AudioCapture.SourceInfo src : sources) {
            System.out.printf("%n--- Testing: %s ---%n", src.label);
            testSource(src);
        }

        if (toneProcess != null) toneProcess.destroy();
        System.out.println("\nDone.");
    }

    private static void testSource(AudioCapture.SourceInfo src) throws Exception {
        AtomicLong chunksIn     = new AtomicLong();
        AtomicLong samplesIn    = new AtomicLong();
        double[]   maxRms       = {0.0};
        double[]   maxAmplitude = {0.0};
        double[]   sumRmsSq     = {0.0};

        AudioCapture cap = new AudioCapture(src);
        cap.setBufferFrames(2048);
        cap.addListener(channels -> {
            chunksIn.incrementAndGet();
            float[] L = channels[0];
            samplesIn.addAndGet(L.length);
            double rms = 0;
            for (float v : L) {
                rms += v * v;
                double abs = Math.abs(v);
                if (abs > maxAmplitude[0]) maxAmplitude[0] = abs;
            }
            rms = Math.sqrt(rms / L.length);
            if (rms > maxRms[0]) maxRms[0] = rms;
            sumRmsSq[0] += rms * rms;
        });

        try {
            cap.start();
        } catch (LineUnavailableException e) {
            System.out.println("  ✗ start() failed: " + e.getMessage());
            return;
        }

        // Capture for 2 seconds
        Thread.sleep(2000);
        cap.stop();

        long   chunks = chunksIn.get();
        long   samples = samplesIn.get();
        double peakRms = maxRms[0];
        double peak    = maxAmplitude[0];
        double avgRms  = chunks > 0 ? Math.sqrt(sumRmsSq[0] / chunks) : 0;

        System.out.printf("  Chunks received: %d  Samples: %d%n", chunks, samples);
        System.out.printf("  Peak amplitude:  %.6f  (0=silence, 1=max)%n", peak);
        System.out.printf("  Peak RMS:        %.6f%n", peakRms);
        System.out.printf("  Avg RMS:         %.6f%n", avgRms);
        System.out.printf("  dBFS peak:       %.1f dB%n",
                peak > 0 ? 20 * Math.log10(peak) : Double.NEGATIVE_INFINITY);

        if (peak < 0.0001) {
            System.out.println("  RESULT: SILENCE — no audio signal detected");
        } else if (peak < 0.01) {
            System.out.println("  RESULT: VERY QUIET — signal present but very low");
        } else {
            System.out.println("  RESULT: ACTIVE — clear audio signal ✓");
        }
    }
}
