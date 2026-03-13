package com.soundvisualizer;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Generates synthetic test audio signals and delivers them to listeners
 * in exactly the same format as {@link AudioCapture} (stereo float[][]).
 *
 * Available signals:
 *   SINE_SWEEP  – slow logarithmic frequency sweep 20 Hz → 20 kHz
 *   MULTI_TONE  – a chord of musically-spaced sine tones
 *   PINK_NOISE  – random pink-ish noise (equal energy per octave)
 *   BASS_KICK   – periodic low-frequency pulse simulating a kick drum beat
 *   FULL_MUSIC  – mixture of harmonics + drums + hi-hats simulating music
 */
public class TestSignalSource {

    // Public constants so ControlPanel can list them
    public enum Signal {
        SINE_SWEEP ("Test: Sine Sweep", "Logarithmic sweep 20 Hz – 20 kHz"),
        MULTI_TONE ("Test: Multi-Tone", "Chord of harmonically-related tones"),
        PINK_NOISE ("Test: Pink Noise", "Pink noise (equal energy per octave)"),
        BASS_KICK  ("Test: Bass Kick",  "Low-frequency kick drum simulation"),
        FULL_MUSIC ("Test: Simulated Music", "Mixture of bass, mids, highs + rhythm");

        public final String displayName;
        public final String description;
        Signal(String d, String desc) { this.displayName = d; this.description = desc; }
        @Override public String toString() { return displayName; }
    }

    // -----------------------------------------------------------------------
    private static final float SAMPLE_RATE = AudioCapture.SAMPLE_RATE;
    private static final int   CHUNK       = 2048;

    private final Signal   signal;
    private volatile boolean running;
    private Thread          thread;

    private final CopyOnWriteArrayList<Consumer<float[][]>> listeners = new CopyOnWriteArrayList<>();

    // Per-signal oscillator state
    private double phase       = 0;
    private double sweepPhase  = 0;       // 0..1 through the sweep range
    private long   sampleCount = 0;

    // Pink noise state (Paul Kellet's filter)
    private double b0=0, b1=0, b2=0, b3=0, b4=0, b5=0, b6=0;

    public TestSignalSource(Signal signal) {
        this.signal = signal;
    }

    public void addListener(Consumer<float[][]> listener) { listeners.add(listener); }
    public void removeListener(Consumer<float[][]> listener) { listeners.remove(listener); }

    public Signal getSignal() { return signal; }
    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        thread  = new Thread(this::loop, "TestSignal-" + signal.name());
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    // -----------------------------------------------------------------------
    // Generation loop  – runs at real-time pace
    // -----------------------------------------------------------------------

    private void loop() {
        long nanosPerChunk = (long) (CHUNK / SAMPLE_RATE * 1_000_000_000L);
        long nextTick      = System.nanoTime();

        while (running) {
            float[] left  = new float[CHUNK];
            float[] right = new float[CHUNK];
            generate(left, right);
            float[][] channels = {left, right};
            for (Consumer<float[][]> cb : listeners) {
                try { cb.accept(channels); } catch (Exception ignored) {}
            }
            sampleCount += CHUNK;

            // Pace to real time
            nextTick += nanosPerChunk;
            long now  = System.nanoTime();
            long wait = (nextTick - now) / 1_000_000L; // ms
            if (wait > 1) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Per-signal synthesis
    // -----------------------------------------------------------------------

    private void generate(float[] L, float[] R) {
        switch (signal) {
            case SINE_SWEEP  -> genSineSweep(L, R);
            case MULTI_TONE  -> genMultiTone(L, R);
            case PINK_NOISE  -> genPinkNoise(L, R);
            case BASS_KICK   -> genBassKick(L, R);
            case FULL_MUSIC  -> genFullMusic(L, R);
        }
    }

    /** Logarithmic sine sweep 20 Hz → 20 kHz, period = 8 seconds. */
    private void genSineSweep(float[] L, float[] R) {
        double sweepPeriodSamples = 8.0 * SAMPLE_RATE;
        double logMin = Math.log(20.0);
        double logMax = Math.log(20000.0);

        for (int i = 0; i < L.length; i++) {
            double pos  = (sampleCount + i) % sweepPeriodSamples / sweepPeriodSamples;
            double freq = Math.exp(logMin + (logMax - logMin) * pos);
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE;
            float s = (float) Math.sin(phase) * 0.7f;
            L[i] = s; R[i] = s;
        }
    }

    /** A musical chord: root + 3rd + 5th + octave with a few harmonics. */
    private void genMultiTone(float[] L, float[] R) {
        // E2 chord: 82.4 Hz, 103.8, 123.5, 164.8 Hz
        double[] freqs = {82.41, 103.83, 123.47, 164.81, 246.94, 329.63, 493.88, 987.77};
        double[] amps  = {0.35,  0.25,   0.20,   0.15,   0.12,   0.10,   0.08,   0.06};

        for (int i = 0; i < L.length; i++) {
            double s = 0;
            for (int k = 0; k < freqs.length; k++) {
                double ph = (sampleCount + i) * 2.0 * Math.PI * freqs[k] / SAMPLE_RATE;
                s += amps[k] * Math.sin(ph);
            }
            L[i] = (float) s; R[i] = (float) s;
        }
    }

    /** Paul Kellet's pink noise algorithm. */
    private void genPinkNoise(float[] L, float[] R) {
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < L.length; i++) {
            double white = rng.nextGaussian() * 0.05;
            b0 = 0.99886 * b0 + white * 0.0555179;
            b1 = 0.99332 * b1 + white * 0.0750759;
            b2 = 0.96900 * b2 + white * 0.1538520;
            b3 = 0.86650 * b3 + white * 0.3104856;
            b4 = 0.55000 * b4 + white * 0.5329522;
            b5 = -0.7616 * b5 - white * 0.0168980;
            double s = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362;
            b6 = white * 0.115926;
            L[i] = (float)(s * 0.11);
            R[i] = L[i];
        }
    }

    /**
     * Kick drum simulation: exponentially-decaying sine burst at 50 Hz every
     * 0.5 seconds, with a short high-frequency transient.
     */
    private void genBassKick(float[] L, float[] R) {
        double kickPeriod = 0.5 * SAMPLE_RATE;
        for (int i = 0; i < L.length; i++) {
            double pos  = (sampleCount + i) % kickPeriod;
            double t    = pos / SAMPLE_RATE;
            float  kick = (float)(Math.sin(2 * Math.PI * 50 * t) * Math.exp(-10 * t) * 0.8);
            float  snap = (float)(Math.sin(2 * Math.PI * 800 * t) * Math.exp(-60 * t) * 0.3);
            L[i] = kick + snap; R[i] = L[i];
        }
    }

    /**
     * Simulated music mix: bass line, mid tones, hi-hat rhythm.
     */
    private void genFullMusic(float[] L, float[] R) {
        double kickPeriod  = 0.5  * SAMPLE_RATE;
        double hihatPeriod = 0.25 * SAMPLE_RATE;
        java.util.Random rng2 = new java.util.Random(0); // seeded for reproducibility

        for (int i = 0; i < L.length; i++) {
            double sc = sampleCount + i;

            // Bass line 80 Hz with vibrato
            double bassFreq = 80 + 3 * Math.sin(2 * Math.PI * 0.5 * sc / SAMPLE_RATE);
            double bass = Math.sin(2 * Math.PI * bassFreq / SAMPLE_RATE * sc) * 0.4;
            // add 2nd harmonic
            bass += Math.sin(4 * Math.PI * bassFreq / SAMPLE_RATE * sc) * 0.1;

            // Mid: two guitar-like tones
            double mid1 = Math.sin(2 * Math.PI * 440 / SAMPLE_RATE * sc) * 0.15;
            double mid2 = Math.sin(2 * Math.PI * 554 / SAMPLE_RATE * sc) * 0.12;

            // Kick
            double kpos = sc % kickPeriod;
            double kt   = kpos / SAMPLE_RATE;
            double kick = Math.sin(2 * Math.PI * 50 * kt) * Math.exp(-10 * kt) * 0.6;
            kick += Math.sin(2 * Math.PI * 800 * kt) * Math.exp(-60 * kt) * 0.2;

            // Hi-hat (random bursts)
            double hpos = sc % hihatPeriod;
            double ht   = hpos / SAMPLE_RATE;
            double hat  = (rng2.nextDouble() * 2 - 1) * Math.exp(-100 * ht) * 0.15;

            float mixL = (float)((bass + mid1 + kick + hat) * 0.8);
            float mixR = (float)((bass + mid2 + kick + hat) * 0.8);
            L[i] = Math.max(-1f, Math.min(1f, mixL));
            R[i] = Math.max(-1f, Math.min(1f, mixR));
        }
    }
}
