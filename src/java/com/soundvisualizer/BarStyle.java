package com.soundvisualizer;

/** Visual style used to render frequency bins in Spectrum and Radial modes. */
public enum BarStyle {
    BARS     ("Bars",    "Solid filled rectangles"),
    LINES    ("Lines",   "Thin vertical lines"),
    DOTS     ("Dots",    "Circles at the tip of each bin"),
    GRADIENT ("Gradient","Bars with vertical color gradient"),
    MIRROR   ("Mirror",  "Bars mirrored symmetrically from the center");

    public final String displayName;
    public final String description;

    BarStyle(String d, String desc) { this.displayName = d; this.description = desc; }

    @Override public String toString() { return displayName; }
}
