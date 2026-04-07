package com.soundvisualizer;

import java.util.Properties;

/**
 * Snapshot of all visualizer settings that can be persisted as a named profile.
 *
 * Enum values are stored by their {@link Enum#name()} so they survive display-name
 * changes without breaking saved files.  Theme is stored by {@link Theme#name}.
 */
public class VisualizerProfile {

    // Audio
    public String  sourceName   = "System Default";
    public String  audioFilePath = null;
    public int     gain         = 100;   // 0..400 (100 = 1.0×)
    public String  stereoMode   = "MERGED";
    public int     bufferSize   = 2048;

    // Visualize
    public String  visualizationMode = "SPECTRUM";
    public String  barStyle          = "GRADIENT";
    public String  colorMode         = "FREQUENCY";
    public boolean mirror            = false;
    public int     barCount          = 120;

    // Analysis
    public int     fftSize       = 2048;
    public String  windowFunction = "HANN";
    public int     smoothing     = 75;   // 0..99
    public boolean peakHold      = true;
    public int     peakDecay     = 8;    // 1..100
    public int     noiseFloor    = -90;  // -120..-40

    // Frequency
    public int     freqMin  = 20;
    public int     freqMax  = 20000;
    public boolean logScale = true;

    // Display
    public String  themeName      = "Dark";
    public boolean showGrid       = true;
    public boolean showFreqLabels = true;
    public boolean showDbScale    = true;
    public boolean showPeakLine   = true;

    // -----------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("sourceName",        orEmpty(sourceName));
        p.setProperty("audioFilePath",     orEmpty(audioFilePath));
        p.setProperty("gain",              String.valueOf(gain));
        p.setProperty("stereoMode",        orEmpty(stereoMode));
        p.setProperty("bufferSize",        String.valueOf(bufferSize));
        p.setProperty("visualizationMode", orEmpty(visualizationMode));
        p.setProperty("barStyle",          orEmpty(barStyle));
        p.setProperty("colorMode",         orEmpty(colorMode));
        p.setProperty("mirror",            String.valueOf(mirror));
        p.setProperty("barCount",          String.valueOf(barCount));
        p.setProperty("fftSize",           String.valueOf(fftSize));
        p.setProperty("windowFunction",    orEmpty(windowFunction));
        p.setProperty("smoothing",         String.valueOf(smoothing));
        p.setProperty("peakHold",          String.valueOf(peakHold));
        p.setProperty("peakDecay",         String.valueOf(peakDecay));
        p.setProperty("noiseFloor",        String.valueOf(noiseFloor));
        p.setProperty("freqMin",           String.valueOf(freqMin));
        p.setProperty("freqMax",           String.valueOf(freqMax));
        p.setProperty("logScale",          String.valueOf(logScale));
        p.setProperty("themeName",         orEmpty(themeName));
        p.setProperty("showGrid",          String.valueOf(showGrid));
        p.setProperty("showFreqLabels",    String.valueOf(showFreqLabels));
        p.setProperty("showDbScale",       String.valueOf(showDbScale));
        p.setProperty("showPeakLine",      String.valueOf(showPeakLine));
        return p;
    }

    public static VisualizerProfile fromProperties(Properties p) {
        VisualizerProfile pr = new VisualizerProfile();
        pr.sourceName        = p.getProperty("sourceName",        pr.sourceName);
        pr.audioFilePath     = nullIfEmpty(p.getProperty("audioFilePath", ""));
        pr.gain              = parseInt(p, "gain",         pr.gain);
        pr.stereoMode        = p.getProperty("stereoMode",        pr.stereoMode);
        pr.bufferSize        = parseInt(p, "bufferSize",   pr.bufferSize);
        pr.visualizationMode = p.getProperty("visualizationMode", pr.visualizationMode);
        pr.barStyle          = p.getProperty("barStyle",          pr.barStyle);
        pr.colorMode         = p.getProperty("colorMode",         pr.colorMode);
        pr.mirror            = parseBool(p, "mirror",      pr.mirror);
        pr.barCount          = parseInt(p, "barCount",     pr.barCount);
        pr.fftSize           = parseInt(p, "fftSize",      pr.fftSize);
        pr.windowFunction    = p.getProperty("windowFunction",    pr.windowFunction);
        pr.smoothing         = parseInt(p, "smoothing",    pr.smoothing);
        pr.peakHold          = parseBool(p, "peakHold",    pr.peakHold);
        pr.peakDecay         = parseInt(p, "peakDecay",    pr.peakDecay);
        pr.noiseFloor        = parseInt(p, "noiseFloor",   pr.noiseFloor);
        pr.freqMin           = parseInt(p, "freqMin",      pr.freqMin);
        pr.freqMax           = parseInt(p, "freqMax",      pr.freqMax);
        pr.logScale          = parseBool(p, "logScale",    pr.logScale);
        pr.themeName         = p.getProperty("themeName",         pr.themeName);
        pr.showGrid          = parseBool(p, "showGrid",    pr.showGrid);
        pr.showFreqLabels    = parseBool(p, "showFreqLabels", pr.showFreqLabels);
        pr.showDbScale       = parseBool(p, "showDbScale", pr.showDbScale);
        pr.showPeakLine      = parseBool(p, "showPeakLine", pr.showPeakLine);
        return pr;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String orEmpty(String s)       { return s != null ? s : ""; }
    private static String nullIfEmpty(String s)   { return (s == null || s.isEmpty()) ? null : s; }

    private static int parseInt(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }
}
