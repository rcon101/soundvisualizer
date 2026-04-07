package com.soundvisualizer;

import java.awt.Color;
import java.util.List;

/**
 * Registry of built-in {@link Theme} instances.
 *
 * Each theme supplies all color roles required by both the visualizer and the
 * control panel.  Add new themes by creating a {@code public static final Theme}
 * constant below and registering it in {@link #ALL}.
 */
public final class Themes {

    // -----------------------------------------------------------------------
    // Dark (default) – deep navy/charcoal, electric cyan spectrum
    // -----------------------------------------------------------------------
    public static final Theme DARK = new Theme(
        "Dark",
        /* background        */ new Color(0x0d, 0x0d, 0x12),
        /* panelBackground   */ new Color(0x16, 0x16, 0x22),
        /* sectionBackground */ new Color(0x1e, 0x1e, 0x2e),
        /* border            */ new Color(0x33, 0x33, 0x50),
        /* textPrimary       */ new Color(0xe0, 0xe0, 0xff),
        /* textSecondary     */ new Color(0x80, 0x80, 0xaa),
        /* textAccent        */ new Color(0x00, 0xd4, 0xff),
        /* spectrumColor     */ new Color(0x00, 0xd4, 0xff),
        /* waveformColor     */ new Color(0x00, 0xff, 0xaa),
        /* peakColor         */ new Color(0xff, 0xff, 0x80),
        /* vuSafe            */ new Color(0x00, 0xcc, 0x44),
        /* vuCaution         */ new Color(0xff, 0xcc, 0x00),
        /* vuClip            */ new Color(0xff, 0x22, 0x22),
        /* gradientLow       */ new Color(0x00, 0x44, 0xff),
        /* gradientMid       */ new Color(0x00, 0xff, 0xaa),
        /* gradientHigh      */ new Color(0xff, 0x44, 0x00),
        /* gridColor         */ new Color(0x33, 0x33, 0x55, 120),
        /* overlayBackground */ new Color(0x0d, 0x0d, 0x18, 200),
        /* overlayText       */ new Color(0xe0, 0xe0, 0xff),
        /* btnPrimary        */ new Color(0x00, 0xaa, 0xdd),
        /* btnDanger         */ new Color(0xdd, 0x33, 0x33),
        /* btnNeutral        */ new Color(0x44, 0x44, 0x66),
        /* btnForeground     */ new Color(0xff, 0xff, 0xff)
    );

    // -----------------------------------------------------------------------
    // Neon – pure black, intense neon accent colors
    // -----------------------------------------------------------------------
    public static final Theme NEON = new Theme(
        "Neon",
        /* background        */ new Color(0x00, 0x00, 0x00),
        /* panelBackground   */ new Color(0x0a, 0x0a, 0x0a),
        /* sectionBackground */ new Color(0x12, 0x12, 0x12),
        /* border            */ new Color(0x33, 0x00, 0x55),
        /* textPrimary       */ new Color(0xff, 0x00, 0xff),
        /* textSecondary     */ new Color(0xaa, 0x00, 0xaa),
        /* textAccent        */ new Color(0x00, 0xff, 0x00),
        /* spectrumColor     */ new Color(0x00, 0xff, 0x00),
        /* waveformColor     */ new Color(0xff, 0x00, 0xff),
        /* peakColor         */ new Color(0xff, 0xff, 0x00),
        /* vuSafe            */ new Color(0x00, 0xff, 0x00),
        /* vuCaution         */ new Color(0xff, 0xff, 0x00),
        /* vuClip            */ new Color(0xff, 0x00, 0x00),
        /* gradientLow       */ new Color(0x00, 0x00, 0xff),
        /* gradientMid       */ new Color(0x00, 0xff, 0x00),
        /* gradientHigh      */ new Color(0xff, 0x00, 0x00),
        /* gridColor         */ new Color(0x22, 0x00, 0x33, 150),
        /* overlayBackground */ new Color(0x00, 0x00, 0x00, 210),
        /* overlayText       */ new Color(0x00, 0xff, 0x00),
        /* btnPrimary        */ new Color(0x00, 0xdd, 0x00),
        /* btnDanger         */ new Color(0xff, 0x00, 0x00),
        /* btnNeutral        */ new Color(0x22, 0x00, 0x33),
        /* btnForeground     */ new Color(0x00, 0x00, 0x00)
    );

    // -----------------------------------------------------------------------
    // Midnight – soft purple/violet dark theme
    // -----------------------------------------------------------------------
    public static final Theme MIDNIGHT = new Theme(
        "Midnight",
        /* background        */ new Color(0x08, 0x06, 0x18),
        /* panelBackground   */ new Color(0x10, 0x0e, 0x24),
        /* sectionBackground */ new Color(0x18, 0x16, 0x30),
        /* border            */ new Color(0x3a, 0x30, 0x60),
        /* textPrimary       */ new Color(0xd0, 0xc0, 0xff),
        /* textSecondary     */ new Color(0x80, 0x70, 0xaa),
        /* textAccent        */ new Color(0xc0, 0x80, 0xff),
        /* spectrumColor     */ new Color(0xaa, 0x60, 0xff),
        /* waveformColor     */ new Color(0x80, 0xc0, 0xff),
        /* peakColor         */ new Color(0xff, 0xe0, 0x60),
        /* vuSafe            */ new Color(0x60, 0xff, 0x80),
        /* vuCaution         */ new Color(0xff, 0xd0, 0x40),
        /* vuClip            */ new Color(0xff, 0x44, 0x44),
        /* gradientLow       */ new Color(0x40, 0x20, 0x80),
        /* gradientMid       */ new Color(0xaa, 0x60, 0xff),
        /* gradientHigh      */ new Color(0xff, 0x80, 0xc0),
        /* gridColor         */ new Color(0x30, 0x28, 0x50, 110),
        /* overlayBackground */ new Color(0x08, 0x06, 0x18, 200),
        /* overlayText       */ new Color(0xd0, 0xc0, 0xff),
        /* btnPrimary        */ new Color(0x99, 0x44, 0xff),
        /* btnDanger         */ new Color(0xff, 0x44, 0x66),
        /* btnNeutral        */ new Color(0x28, 0x20, 0x44),
        /* btnForeground     */ new Color(0xff, 0xff, 0xff)
    );

    // -----------------------------------------------------------------------
    // Sunrise – warm amber/orange dark theme
    // -----------------------------------------------------------------------
    public static final Theme SUNRISE = new Theme(
        "Sunrise",
        /* background        */ new Color(0x0e, 0x08, 0x02),
        /* panelBackground   */ new Color(0x18, 0x10, 0x04),
        /* sectionBackground */ new Color(0x24, 0x18, 0x06),
        /* border            */ new Color(0x50, 0x30, 0x10),
        /* textPrimary       */ new Color(0xff, 0xee, 0xcc),
        /* textSecondary     */ new Color(0xaa, 0x88, 0x55),
        /* textAccent        */ new Color(0xff, 0xaa, 0x00),
        /* spectrumColor     */ new Color(0xff, 0x88, 0x00),
        /* waveformColor     */ new Color(0xff, 0xdd, 0x44),
        /* peakColor         */ new Color(0xff, 0xff, 0xaa),
        /* vuSafe            */ new Color(0x88, 0xdd, 0x44),
        /* vuCaution         */ new Color(0xff, 0xcc, 0x00),
        /* vuClip            */ new Color(0xff, 0x33, 0x00),
        /* gradientLow       */ new Color(0xdd, 0x33, 0x00),
        /* gradientMid       */ new Color(0xff, 0x88, 0x00),
        /* gradientHigh      */ new Color(0xff, 0xff, 0x44),
        /* gridColor         */ new Color(0x50, 0x30, 0x10, 110),
        /* overlayBackground */ new Color(0x0e, 0x08, 0x02, 200),
        /* overlayText       */ new Color(0xff, 0xee, 0xcc),
        /* btnPrimary        */ new Color(0xdd, 0x88, 0x00),
        /* btnDanger         */ new Color(0xff, 0x33, 0x00),
        /* btnNeutral        */ new Color(0x33, 0x22, 0x08),
        /* btnForeground     */ new Color(0x0e, 0x08, 0x02)
    );

    // -----------------------------------------------------------------------
    // Ocean – cool blues and teals
    // -----------------------------------------------------------------------
    public static final Theme OCEAN = new Theme(
        "Ocean",
        /* background        */ new Color(0x02, 0x08, 0x14),
        /* panelBackground   */ new Color(0x04, 0x10, 0x22),
        /* sectionBackground */ new Color(0x06, 0x18, 0x30),
        /* border            */ new Color(0x10, 0x3a, 0x55),
        /* textPrimary       */ new Color(0xcc, 0xee, 0xff),
        /* textSecondary     */ new Color(0x60, 0xaa, 0xcc),
        /* textAccent        */ new Color(0x00, 0xee, 0xff),
        /* spectrumColor     */ new Color(0x00, 0xcc, 0xff),
        /* waveformColor     */ new Color(0x44, 0xff, 0xee),
        /* peakColor         */ new Color(0xaa, 0xff, 0xff),
        /* vuSafe            */ new Color(0x00, 0xee, 0x88),
        /* vuCaution         */ new Color(0xee, 0xdd, 0x00),
        /* vuClip            */ new Color(0xff, 0x33, 0x55),
        /* gradientLow       */ new Color(0x00, 0x22, 0x88),
        /* gradientMid       */ new Color(0x00, 0xcc, 0xff),
        /* gradientHigh      */ new Color(0xaa, 0xff, 0xff),
        /* gridColor         */ new Color(0x10, 0x3a, 0x55, 110),
        /* overlayBackground */ new Color(0x02, 0x08, 0x14, 200),
        /* overlayText       */ new Color(0xcc, 0xee, 0xff),
        /* btnPrimary        */ new Color(0x00, 0xaa, 0xcc),
        /* btnDanger         */ new Color(0xff, 0x33, 0x55),
        /* btnNeutral        */ new Color(0x06, 0x22, 0x33),
        /* btnForeground     */ new Color(0x02, 0x08, 0x14)
    );

    // -----------------------------------------------------------------------
    // Light – clean white theme for bright environments
    // -----------------------------------------------------------------------
    public static final Theme LIGHT = new Theme(
        "Light",
        /* background        */ new Color(0xf8, 0xf8, 0xff),
        /* panelBackground   */ new Color(0xee, 0xee, 0xf8),
        /* sectionBackground */ new Color(0xe2, 0xe2, 0xf0),
        /* border            */ new Color(0xbb, 0xbb, 0xdd),
        /* textPrimary       */ new Color(0x22, 0x22, 0x44),
        /* textSecondary     */ new Color(0x66, 0x66, 0x88),
        /* textAccent        */ new Color(0x00, 0x66, 0xcc),
        /* spectrumColor     */ new Color(0x22, 0x66, 0xff),
        /* waveformColor     */ new Color(0x00, 0x99, 0x55),
        /* peakColor         */ new Color(0xcc, 0x44, 0x00),
        /* vuSafe            */ new Color(0x22, 0xaa, 0x44),
        /* vuCaution         */ new Color(0xdd, 0xaa, 0x00),
        /* vuClip            */ new Color(0xcc, 0x22, 0x22),
        /* gradientLow       */ new Color(0x22, 0x44, 0xff),
        /* gradientMid       */ new Color(0x00, 0xaa, 0x55),
        /* gradientHigh      */ new Color(0xff, 0x44, 0x00),
        /* gridColor         */ new Color(0xaa, 0xaa, 0xcc, 100),
        /* overlayBackground */ new Color(0xf8, 0xf8, 0xff, 220),
        /* overlayText       */ new Color(0x22, 0x22, 0x44),
        /* btnPrimary        */ new Color(0x22, 0x66, 0xff),
        /* btnDanger         */ new Color(0xcc, 0x22, 0x22),
        /* btnNeutral        */ new Color(0xcc, 0xcc, 0xdd),
        /* btnForeground     */ new Color(0xff, 0xff, 0xff)
    );

    // -----------------------------------------------------------------------
    // Registry
    // -----------------------------------------------------------------------
    public static final List<Theme> ALL = List.of(DARK, NEON, MIDNIGHT, SUNRISE, OCEAN, LIGHT);

    /** Default theme applied on startup. */
    public static final Theme DEFAULT = DARK;

    /** Returns the theme whose {@link Theme#name} equals {@code name}, or {@link #DEFAULT}. */
    public static Theme byName(String name) {
        if (name == null) return DEFAULT;
        for (Theme t : ALL) {
            if (t.name.equals(name)) return t;
        }
        return DEFAULT;
    }

    private Themes() {}
}
