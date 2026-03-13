package com.soundvisualizer;

import java.awt.Color;

/**
 * Immutable set of color and appearance roles used throughout the UI.
 *
 * New themes can be created by adding static constants in {@link Themes} using
 * this constructor – no other code needs to change.
 */
public final class Theme {

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------
    public final String name;

    // -----------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------
    /** Main visualization panel background. */
    public final Color background;
    /** Control panel / sidebar background. */
    public final Color panelBackground;
    /** Sub-section backgrounds within the panel. */
    public final Color sectionBackground;
    /** Divider / border lines. */
    public final Color border;

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------
    public final Color textPrimary;
    public final Color textSecondary;
    /** Value labels, selection highlights, accent elements. */
    public final Color textAccent;

    // -----------------------------------------------------------------------
    // Spectrum / waveform primary colors
    // -----------------------------------------------------------------------
    /** Default spectrum bar color (used by ColorMode.SOLID). */
    public final Color spectrumColor;
    /** Waveform line color. */
    public final Color waveformColor;
    /** Peak-hold indicator line color. */
    public final Color peakColor;
    /** VU meter – safe zone (0–70%). */
    public final Color vuSafe;
    /** VU meter – caution zone (70–90%). */
    public final Color vuCaution;
    /** VU meter – clip zone (90–100%). */
    public final Color vuClip;

    // -----------------------------------------------------------------------
    // Gradient color stops (ColorMode.FREQUENCY / AMPLITUDE / FIRE / COOL)
    // -----------------------------------------------------------------------
    /** Low-frequency or low-amplitude gradient stop. */
    public final Color gradientLow;
    /** Mid-frequency or mid-amplitude gradient stop. */
    public final Color gradientMid;
    /** High-frequency or high-amplitude gradient stop. */
    public final Color gradientHigh;

    // -----------------------------------------------------------------------
    // Grid / overlay elements
    // -----------------------------------------------------------------------
    public final Color gridColor;
    public final Color overlayBackground;
    public final Color overlayText;

    // -----------------------------------------------------------------------
    // Buttons
    // -----------------------------------------------------------------------
    public final Color btnPrimary;
    public final Color btnDanger;
    public final Color btnNeutral;
    public final Color btnForeground;

    // -----------------------------------------------------------------------
    // Constructor (canonical)
    // -----------------------------------------------------------------------
    public Theme(String name,
                 Color background, Color panelBackground, Color sectionBackground, Color border,
                 Color textPrimary, Color textSecondary, Color textAccent,
                 Color spectrumColor, Color waveformColor, Color peakColor,
                 Color vuSafe, Color vuCaution, Color vuClip,
                 Color gradientLow, Color gradientMid, Color gradientHigh,
                 Color gridColor, Color overlayBackground, Color overlayText,
                 Color btnPrimary, Color btnDanger, Color btnNeutral, Color btnForeground) {
        this.name              = name;
        this.background        = background;
        this.panelBackground   = panelBackground;
        this.sectionBackground = sectionBackground;
        this.border            = border;
        this.textPrimary       = textPrimary;
        this.textSecondary     = textSecondary;
        this.textAccent        = textAccent;
        this.spectrumColor     = spectrumColor;
        this.waveformColor     = waveformColor;
        this.peakColor         = peakColor;
        this.vuSafe            = vuSafe;
        this.vuCaution         = vuCaution;
        this.vuClip            = vuClip;
        this.gradientLow       = gradientLow;
        this.gradientMid       = gradientMid;
        this.gradientHigh      = gradientHigh;
        this.gridColor         = gridColor;
        this.overlayBackground = overlayBackground;
        this.overlayText       = overlayText;
        this.btnPrimary        = btnPrimary;
        this.btnDanger         = btnDanger;
        this.btnNeutral        = btnNeutral;
        this.btnForeground     = btnForeground;
    }

    @Override
    public String toString() { return name; }
}
