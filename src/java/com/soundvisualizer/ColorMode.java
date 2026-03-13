package com.soundvisualizer;

/** Controls how spectrum bars and waveform elements are colored. */
public enum ColorMode {
    SOLID           ("Solid",         "Single color from theme"),
    FREQUENCY       ("Frequency",     "Color varies from low (cool) to high (warm) frequency"),
    AMPLITUDE       ("Amplitude",     "Color varies with signal level (low=blue → high=white)"),
    RAINBOW         ("Rainbow",       "Full HSB rainbow swept across frequencies"),
    FIRE            ("Fire",          "Black → red → orange → yellow gradient by amplitude"),
    COOL            ("Cool",          "Blue → cyan → green gradient by amplitude");

    public final String displayName;
    public final String description;

    ColorMode(String d, String desc) { this.displayName = d; this.description = desc; }

    @Override public String toString() { return displayName; }
}
