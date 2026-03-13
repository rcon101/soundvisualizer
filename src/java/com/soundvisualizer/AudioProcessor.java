package com.soundvisualizer;

import java.util.Arrays;

/**
 * Real-time audio signal processor.
 *
 * <p>Receives stereo float[][] chunks from {@link AudioCapture} and computes:
 * <ul>
 *   <li>FFT frequency spectrum (normalised 0–1 in dB scale)</li>
 *   <li>Smoothed spectrum with configurable exponential moving average</li>
 *   <li>Peak-hold spectrum with configurable decay</li>
 *   <li>Time-domain waveform buffer</li>
 *   <li>Stereo VU meter (RMS + peak)</li>
 *   <li>Spectrogram history (rolling ring buffer of spectrum frames)</li>
 *   <li>Lissajous (X-Y phase) sample buffer</li>
 * </ul>
 *
 * <p>All getter methods return snapshots of the internal state and are safe to
 * call from the Swing EDT after {@link #process} has been invoked from the
 * capture thread.  Internal arrays are double-buffered to avoid tearing.
 */
public class AudioProcessor {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------
    private volatile int            fftSize         = 2048;
    private volatile WindowFunction windowFunction  = WindowFunction.HANN;
    private volatile float          smoothing       = 0.75f;   // 0=none … 1=max
    private volatile boolean        peakHold        = true;
    private volatile float          peakDecay       = 0.008f;  // per-frame decay
    private volatile float          gain            = 1.0f;
    private volatile float          freqMin         = 20f;
    private volatile float          freqMax         = 20_000f;
    private volatile boolean        logScale        = true;
    private volatile int            barCount        = 120;
    private volatile StereoMode     stereoMode      = StereoMode.MERGED;
    private volatile float          sampleRate      = AudioCapture.SAMPLE_RATE;
    private volatile float          noiseFloor      = -90f;    // dB
    private volatile float          dbRange         = 90f;     // dynamic range

    // -----------------------------------------------------------------------
    // Internal DSP state  (protected by 'this' monitor via volatile swaps)
    // -----------------------------------------------------------------------
    private float[]   smoothedSpectrum  = new float[0];
    private float[]   peakSpectrum      = new float[0];
    private float[]   waveformL         = new float[0];
    private float[]   waveformR         = new float[0];
    private float[]   lissajousL        = new float[0];
    private float[]   lissajousR        = new float[0];

    private float     vuLeft, vuRight;
    private float     peakVuLeft, peakVuRight;

    private static final int SPECTROGRAM_ROWS = 512;
    private float[][] spectrogramRing  = new float[SPECTROGRAM_ROWS][0];
    private int       spectrogramHead  = 0;

    // Number of samples to keep in waveform / lissajous buffers
    private static final int WAVEFORM_HISTORY = 4096;

    // -----------------------------------------------------------------------
    // Stereo mode
    // -----------------------------------------------------------------------
    public enum StereoMode {
        MERGED ("Merged",  "Mix L+R to mono"),
        LEFT   ("Left",    "Left channel only"),
        RIGHT  ("Right",   "Right channel only"),
        MID    ("Mid",     "Mid (L+R)/2"),
        SIDE   ("Side",    "Side (L-R)/2");

        public final String displayName, description;
        StereoMode(String d, String desc) { this.displayName = d; this.description = desc; }
        @Override public String toString() { return displayName; }
    }

    // -----------------------------------------------------------------------
    // Entry point – called from capture thread
    // -----------------------------------------------------------------------

    public synchronized void process(float[][] channels) {
        float[] L = channels[0];
        float[] R = (channels.length > 1) ? channels[1] : channels[0];

        // --- Apply gain ---
        L = applyGain(L, gain);
        R = applyGain(R, gain);

        // --- Waveform / lissajous buffers ---
        waveformL = appendRing(waveformL, L, WAVEFORM_HISTORY);
        waveformR = appendRing(waveformR, R, WAVEFORM_HISTORY);
        lissajousL = Arrays.copyOf(L, Math.min(L.length, fftSize));
        lissajousR = Arrays.copyOf(R, Math.min(R.length, fftSize));

        // --- VU RMS ---
        float rmsL = 0, rmsR = 0;
        for (float v : L) rmsL += v * v;
        for (float v : R) rmsR += v * v;
        vuLeft  = (float) Math.sqrt(rmsL / L.length);
        vuRight = (float) Math.sqrt(rmsR / R.length);
        peakVuLeft  = Math.max(peakVuLeft  - peakDecay * 0.5f, vuLeft);
        peakVuRight = Math.max(peakVuRight - peakDecay * 0.5f, vuRight);

        // --- Build mono signal for FFT based on stereo mode ---
        float[] mono = buildMono(L, R);

        // --- Pad / trim to fftSize ---
        float[] frame = new float[fftSize];
        System.arraycopy(mono, 0, frame, 0, Math.min(mono.length, fftSize));

        // --- Window ---
        float[] windowed = windowFunction.apply(frame, fftSize);

        // --- FFT ---
        float[] real = windowed.clone();
        float[] imag = new float[fftSize];
        fft(real, imag);

        // --- Magnitude → dB → normalised [0,1] ---
        int bins = fftSize / 2;
        float[] rawDb = new float[bins];
        for (int i = 1; i < bins; i++) {
            float mag = (float) Math.hypot(real[i], imag[i]);
            float db  = 20f * (float) Math.log10(Math.max(mag / fftSize, 1e-12f));
            rawDb[i] = Math.max(0f, (db - noiseFloor) / dbRange);
        }

        // --- Map to barCount bins using log or linear freq scale ---
        float[] binned = mapToBars(rawDb, barCount, bins);

        // --- Ensure arrays are right size ---
        if (smoothedSpectrum.length != barCount) {
            smoothedSpectrum = new float[barCount];
            peakSpectrum     = new float[barCount];
        }

        // --- Smoothing ---
        for (int i = 0; i < barCount; i++) {
            smoothedSpectrum[i] = smoothing * smoothedSpectrum[i] + (1f - smoothing) * binned[i];
        }

        // --- Peak hold ---
        if (peakHold) {
            for (int i = 0; i < barCount; i++) {
                if (smoothedSpectrum[i] >= peakSpectrum[i]) {
                    peakSpectrum[i] = smoothedSpectrum[i];
                } else {
                    peakSpectrum[i] = Math.max(0f, peakSpectrum[i] - peakDecay);
                }
            }
        } else {
            Arrays.fill(peakSpectrum, 0f);
        }

        // --- Spectrogram ring ---
        if (spectrogramRing[0].length != barCount) {
            spectrogramRing = new float[SPECTROGRAM_ROWS][barCount];
            spectrogramHead = 0;
        }
        spectrogramRing[spectrogramHead] = smoothedSpectrum.clone();
        spectrogramHead = (spectrogramHead + 1) % SPECTROGRAM_ROWS;
    }

    // -----------------------------------------------------------------------
    // Getters (returns defensive copies for thread safety)
    // -----------------------------------------------------------------------

    public synchronized float[] getSpectrum()   { return smoothedSpectrum.clone(); }
    public synchronized float[] getPeaks()       { return peakSpectrum.clone(); }
    public synchronized float[] getWaveformL()   { return waveformL.clone(); }
    public synchronized float[] getWaveformR()   { return waveformR.clone(); }
    public synchronized float[] getLissajousL()  { return lissajousL.clone(); }
    public synchronized float[] getLissajousR()  { return lissajousR.clone(); }

    public synchronized float getVuLeft()        { return vuLeft; }
    public synchronized float getVuRight()       { return vuRight; }
    public synchronized float getPeakVuLeft()    { return peakVuLeft; }
    public synchronized float getPeakVuRight()   { return peakVuRight; }

    /**
     * Returns a 2D copy of the spectrogram ring buffer, in chronological
     * order (oldest row first).  Each row is a spectrum snapshot.
     */
    public synchronized float[][] getSpectrogram() {
        int rows = spectrogramRing.length;
        float[][] out = new float[rows][];
        for (int i = 0; i < rows; i++) {
            int idx = (spectrogramHead + i) % rows;
            out[i] = spectrogramRing[idx].clone();
        }
        return out;
    }

    /** Returns the centre frequency (Hz) for each mapped bar. */
    public float[] getBarFrequencies() {
        float[] freqs = new float[barCount];
        if (logScale) {
            double logMin = Math.log10(Math.max(freqMin, 1f));
            double logMax = Math.log10(freqMax);
            for (int i = 0; i < barCount; i++) {
                freqs[i] = (float) Math.pow(10.0, logMin + (logMax - logMin) * i / (barCount - 1));
            }
        } else {
            for (int i = 0; i < barCount; i++) {
                freqs[i] = freqMin + (freqMax - freqMin) * i / (barCount - 1);
            }
        }
        return freqs;
    }

    // -----------------------------------------------------------------------
    // Setters
    // -----------------------------------------------------------------------

    public void setFftSize(int fftSize) {
        int sz = nearestPowerOfTwo(Math.max(64, fftSize));
        synchronized (this) { this.fftSize = sz; this.smoothedSpectrum = new float[0]; }
    }

    public void setWindowFunction(WindowFunction wf)  { this.windowFunction  = wf; }
    public void setSmoothing(float s)                  { this.smoothing       = Math.max(0f, Math.min(0.99f, s)); }
    public void setPeakHold(boolean hold)              { this.peakHold        = hold; }
    public void setPeakDecay(float d)                  { this.peakDecay       = d; }
    public void setGain(float g)                       { this.gain            = g; }
    public void setFreqMin(float hz)                   { this.freqMin         = hz; }
    public void setFreqMax(float hz)                   { this.freqMax         = hz; }
    public void setLogScale(boolean log)               { this.logScale        = log; }
    public void setBarCount(int n)                     { synchronized(this) { this.barCount = n; this.smoothedSpectrum = new float[0]; } }
    public void setStereoMode(StereoMode m)            { this.stereoMode      = m; }
    public void setNoiseFloor(float db)                { this.noiseFloor      = db; }
    public void setDbRange(float range)                { this.dbRange         = range; }

    public int            getFftSize()         { return fftSize; }
    public WindowFunction getWindowFunction()  { return windowFunction; }
    public float          getSmoothing()       { return smoothing; }
    public boolean        isPeakHold()         { return peakHold; }
    public float          getPeakDecay()       { return peakDecay; }
    public float          getGain()            { return gain; }
    public float          getFreqMin()         { return freqMin; }
    public float          getFreqMax()         { return freqMax; }
    public boolean        isLogScale()         { return logScale; }
    public int            getBarCount()        { return barCount; }
    public StereoMode     getStereoMode()      { return stereoMode; }

    // -----------------------------------------------------------------------
    // Signal helpers
    // -----------------------------------------------------------------------

    private float[] buildMono(float[] L, float[] R) {
        int n = Math.min(L.length, R.length);
        float[] out = new float[n];
        switch (stereoMode) {
            case LEFT  -> System.arraycopy(L, 0, out, 0, n);
            case RIGHT -> System.arraycopy(R, 0, out, 0, n);
            case MID   -> { for (int i=0;i<n;i++) out[i] = (L[i]+R[i])*0.5f; }
            case SIDE  -> { for (int i=0;i<n;i++) out[i] = (L[i]-R[i])*0.5f; }
            default    -> { for (int i=0;i<n;i++) out[i] = (L[i]+R[i])*0.5f; }
        }
        return out;
    }

    private float[] applyGain(float[] src, float g) {
        if (g == 1f) return src;
        float[] out = new float[src.length];
        for (int i = 0; i < src.length; i++) out[i] = Math.max(-1f, Math.min(1f, src[i] * g));
        return out;
    }

    /** Appends new samples to a ring buffer of maxLen, returning the new buffer. */
    private static float[] appendRing(float[] ring, float[] newSamples, int maxLen) {
        int total = ring.length + newSamples.length;
        if (total <= maxLen) {
            float[] out = new float[total];
            System.arraycopy(ring, 0, out, 0, ring.length);
            System.arraycopy(newSamples, 0, out, ring.length, newSamples.length);
            return out;
        }
        float[] out = new float[maxLen];
        int keep = maxLen - newSamples.length;
        if (keep < 0) keep = 0;
        int from = ring.length - keep;
        if (from < 0) from = 0;
        System.arraycopy(ring, from, out, 0, Math.min(keep, ring.length - from));
        int destOff = maxLen - newSamples.length;
        if (destOff < 0) destOff = 0;
        int copyLen = Math.min(newSamples.length, maxLen);
        System.arraycopy(newSamples, Math.max(0, newSamples.length - copyLen), out, destOff, copyLen);
        return out;
    }

    /**
     * Maps raw FFT magnitude bins (0…bins) onto barCount destinations using
     * logarithmic or linear frequency mapping within [freqMin, freqMax].
     */
    private float[] mapToBars(float[] rawDb, int bars, int bins) {
        float[] out = new float[bars];
        float binHz = sampleRate / (2f * bins); // Hz per bin

        for (int b = 0; b < bars; b++) {
            float fLo, fHi;
            if (logScale) {
                double logMin = Math.log10(Math.max(freqMin, 1f));
                double logMax = Math.log10(freqMax);
                fLo = (float) Math.pow(10.0, logMin + (logMax - logMin) * b       / bars);
                fHi = (float) Math.pow(10.0, logMin + (logMax - logMin) * (b + 1) / bars);
            } else {
                float span = freqMax - freqMin;
                fLo = freqMin + span * b       / bars;
                fHi = freqMin + span * (b + 1) / bars;
            }

            int binLo = Math.max(1, (int) (fLo / binHz));
            int binHi = Math.min(bins - 1, (int) (fHi / binHz));
            if (binLo > binHi) binHi = binLo;

            float max = 0f;
            for (int i = binLo; i <= binHi; i++) {
                if (rawDb[i] > max) max = rawDb[i];
            }
            out[b] = max;
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // In-place Cooley-Tukey FFT (complex, power-of-2 size)
    // -----------------------------------------------------------------------

    private static void fft(float[] re, float[] im) {
        int n = re.length;

        // Bit-reverse shuffle
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                      t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }

        // Butterfly passes
        for (int len = 2; len <= n; len <<= 1) {
            double ang  = -2.0 * Math.PI / len;
            float wRe   = (float) Math.cos(ang);
            float wIm   = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                int half = len >> 1;
                for (int j = 0; j < half; j++) {
                    int u = i + j, v = u + half;
                    float evRe = re[u], evIm = im[u];
                    float odRe = re[v] * curRe - im[v] * curIm;
                    float odIm = re[v] * curIm + im[v] * curRe;
                    re[u] = evRe + odRe; im[u] = evIm + odIm;
                    re[v] = evRe - odRe; im[v] = evIm - odIm;
                    float nr = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nr;
                }
            }
        }
    }

    private static int nearestPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}
