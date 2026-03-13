package com.soundvisualizer;

/**
 * FFT window functions applied to each audio frame before computing the spectrum.
 * Windowing prevents spectral leakage at frame boundaries.
 */
public enum WindowFunction {

    RECTANGULAR ("Rectangular",  "No windowing – maximum frequency resolution, high leakage"),
    HANN        ("Hann",         "Good general-purpose window with low sidelobe leakage"),
    HAMMING     ("Hamming",      "Similar to Hann but slightly different sidelobe shape"),
    BLACKMAN    ("Blackman",     "Very low sidelobes at the cost of reduced frequency resolution"),
    FLAT_TOP    ("Flat Top",     "Best amplitude accuracy, poor frequency resolution");

    public final String displayName;
    public final String description;

    WindowFunction(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Apply this window to {@code samples}, returning a new float[size] array
     * with padding if {@code samples.length < size}.
     */
    public float[] apply(float[] samples, int size) {
        float[] out = new float[size];
        int n = Math.min(samples.length, size);
        switch (this) {
            case RECTANGULAR -> System.arraycopy(samples, 0, out, 0, n);
            case HANN -> {
                for (int i = 0; i < n; i++) {
                    double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
                    out[i] = samples[i] * (float) w;
                }
            }
            case HAMMING -> {
                for (int i = 0; i < n; i++) {
                    double w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (size - 1));
                    out[i] = samples[i] * (float) w;
                }
            }
            case BLACKMAN -> {
                for (int i = 0; i < n; i++) {
                    double w = 0.42
                            - 0.50 * Math.cos(2.0 * Math.PI * i / (size - 1))
                            + 0.08 * Math.cos(4.0 * Math.PI * i / (size - 1));
                    out[i] = samples[i] * (float) w;
                }
            }
            case FLAT_TOP -> {
                for (int i = 0; i < n; i++) {
                    double t  = 2.0 * Math.PI * i / (size - 1);
                    double w  = 1.0 - 1.93 * Math.cos(t) + 1.29 * Math.cos(2*t)
                                    - 0.388 * Math.cos(3*t) + 0.032 * Math.cos(4*t);
                    out[i] = samples[i] * (float) w;
                }
            }
        }
        return out;
    }

    @Override
    public String toString() { return displayName; }
}
