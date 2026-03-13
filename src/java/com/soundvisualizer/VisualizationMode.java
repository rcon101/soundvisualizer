package com.soundvisualizer;

/**
 * All available visualization modes for the sound visualizer.
 */
public enum VisualizationMode {

    SPECTRUM    ("Spectrum",     "Frequency spectrum bars (FFT)"),
    WAVEFORM    ("Waveform",     "Time-domain oscilloscope view"),
    SPECTROGRAM ("Spectrogram",  "Rolling waterfall frequency heat map"),
    VU_METER    ("VU Meter",     "Stereo VU meters with peak hold"),
    RADIAL      ("Radial",       "Circular frequency spectrum"),
    LISSAJOUS   ("Lissajous",    "Stereo X-Y phase correlation scope");

    public final String displayName;
    public final String description;

    VisualizationMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    @Override
    public String toString() { return displayName; }
}
