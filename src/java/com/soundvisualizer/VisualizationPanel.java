package com.soundvisualizer;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Main visualization canvas.  Renders audio data in one of several
 * {@link VisualizationMode}s:
 *
 * <ul>
 *   <li><b>SPECTRUM</b>   – FFT frequency bars with optional gradient/style</li>
 *   <li><b>WAVEFORM</b>   – Stereo time-domain oscilloscope</li>
 *   <li><b>SPECTROGRAM</b> – Rolling waterfall heat map</li>
 *   <li><b>VU_METER</b>   – Stereo RMS VU meters with peak hold</li>
 *   <li><b>RADIAL</b>     – Circular frequency spectrum</li>
 *   <li><b>LISSAJOUS</b>  – Stereo X-Y phase correlation scope</li>
 * </ul>
 *
 * All rendering options are set via properties and take effect at the next
 * {@link #repaint()} cycle.  Call {@link #update} from the timer tick to
 * push fresh data.
 */
public class VisualizationPanel extends JPanel {

    // -----------------------------------------------------------------------
    // Rendering options
    // -----------------------------------------------------------------------
    private VisualizationMode mode        = VisualizationMode.SPECTRUM;
    private ColorMode         colorMode   = ColorMode.FREQUENCY;
    private BarStyle          barStyle    = BarStyle.GRADIENT;
    private Theme             theme       = Themes.DEFAULT;

    private boolean showGrid        = true;
    private boolean showFreqLabels  = true;
    private boolean showDbScale     = true;
    private boolean showPeakLine    = true;
    private boolean mirrorSpectrum  = false;

    // -----------------------------------------------------------------------
    // Current audio data (set by update())
    // -----------------------------------------------------------------------
    private float[]   spectrum      = new float[0];
    private float[]   peaks         = new float[0];
    private float[]   barFreqs      = new float[0];
    private float[]   waveL         = new float[0];
    private float[]   waveR         = new float[0];
    private float[]   lissL         = new float[0];
    private float[]   lissR         = new float[0];
    private float[][] spectrogram   = new float[0][0];
    private float     vuL, vuR, peakVuL, peakVuR;
    private String    statusText    = "No audio source – select one in the Audio tab";

    /** True while an audio source is running (even if silent). */
    private boolean captureActive   = false;
    private String  captureSource   = "";
    /** Rolling maximum RMS over last ~0.5s — used to detect silence. */
    private float   recentPeakRms   = 0f;

    // -----------------------------------------------------------------------
    // Spectrogram offscreen buffer
    // -----------------------------------------------------------------------
    private BufferedImage spectrogramImg;
    private int           spectrogramLastRow = -1;

    // -----------------------------------------------------------------------
    // Fonts
    // -----------------------------------------------------------------------
    private static final Font FONT_LABEL  = new Font("Segoe UI", Font.PLAIN, 10);
    private static final Font FONT_STATUS = new Font("Segoe UI", Font.ITALIC, 12);
    private static final Font FONT_MODE   = new Font("Segoe UI", Font.BOLD, 11);

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public VisualizationPanel(Theme theme) {
        applyTheme(theme);
        setPreferredSize(new Dimension(960, 540));
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void applyTheme(Theme t) {
        this.theme = t;
        setBackground(t.background);
        spectrogramImg = null; // invalidate cache
        repaint();
    }

    /** Push a new frame of audio data and request a repaint. */
    public void update(float[] spectrum, float[] peaks, float[] barFreqs,
                       float[] waveL, float[] waveR,
                       float[] lissL, float[] lissR,
                       float[][] spectrogram,
                       float vuL, float vuR, float peakVuL, float peakVuR) {
        this.spectrum    = spectrum;
        this.peaks       = peaks;
        this.barFreqs    = barFreqs;
        this.waveL       = waveL;
        this.waveR       = waveR;
        this.lissL       = lissL;
        this.lissR       = lissR;
        this.spectrogram = spectrogram;
        this.vuL         = vuL;
        this.vuR         = vuR;
        this.peakVuL     = peakVuL;
        this.peakVuR     = peakVuR;
        repaint();
    }

    public void setStatusText(String text)             { this.statusText = text; repaint(); }

    /**
     * Called by SoundVisualizer when capture starts / stops.
     * Drives the level overlay and silence warning.
     */
    public void setCapturing(boolean active, String sourceName) {
        this.captureActive = active;
        this.captureSource = (sourceName != null) ? sourceName : "";
        if (!active) recentPeakRms = 0f;
        repaint();
    }
    public void setMode(VisualizationMode m)           { this.mode = m; spectrogramImg = null; repaint(); }
    public void setColorMode(ColorMode cm)             { this.colorMode = cm; repaint(); }
    public void setBarStyle(BarStyle bs)               { this.barStyle = bs; repaint(); }
    public void setShowGrid(boolean v)                 { this.showGrid = v; repaint(); }
    public void setShowFreqLabels(boolean v)           { this.showFreqLabels = v; repaint(); }
    public void setShowDbScale(boolean v)              { this.showDbScale = v; repaint(); }
    public void setShowPeakLine(boolean v)             { this.showPeakLine = v; repaint(); }
    public void setMirrorSpectrum(boolean v)           { this.mirrorSpectrum = v; repaint(); }

    // -----------------------------------------------------------------------
    // Paint dispatch
    // -----------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);

        // Update rolling peak RMS for silence detection
        float currentRms = (vuL + vuR) / 2f;
        recentPeakRms = Math.max(recentPeakRms * 0.97f, currentRms);

        if (spectrum.length == 0 && mode != VisualizationMode.WAVEFORM
                && mode != VisualizationMode.LISSAJOUS && mode != VisualizationMode.VU_METER) {
            drawPlaceholder(g2);
            drawLevelOverlay(g2);
            return;
        }

        switch (mode) {
            case SPECTRUM    -> drawSpectrum(g2);
            case WAVEFORM    -> drawWaveform(g2);
            case SPECTROGRAM -> drawSpectrogram(g2);
            case VU_METER    -> drawVuMeter(g2);
            case RADIAL      -> drawRadial(g2);
            case LISSAJOUS   -> drawLissajous(g2);
        }

        drawModeLabel(g2);
        drawLevelOverlay(g2);
    }

    // -----------------------------------------------------------------------
    // Spectrum renderer
    // -----------------------------------------------------------------------

    private void drawSpectrum(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        int bottomPad = showFreqLabels ? 26 : 8;
        int leftPad   = showDbScale    ? 40 : 8;
        int topPad    = 10;

        int chartX = leftPad;
        int chartY = topPad;
        int chartW = W - leftPad - 8;
        int chartH = H - topPad - bottomPad - 20; // room for status

        if (showGrid) drawSpectrumGrid(g2, chartX, chartY, chartW, chartH, bottomPad, leftPad);
        if (showDbScale) drawDbScale(g2, chartX, chartY, chartH);

        int n = spectrum.length;
        if (n == 0) return;

        float barW  = (float) chartW / n;
        float barW2 = Math.max(1f, barW - (barW > 3f ? 1f : 0f));

        for (int i = 0; i < n; i++) {
            int idx   = mirrorSpectrum ? mirrorIndex(i, n) : i;
            float val = Math.min(1f, spectrum[idx]);
            if (val < 0.001f) continue;

            float t   = (float) idx / n; // frequency frequency position [0,1]
            Color c   = barColor(t, val, idx, n);

            int x     = chartX + (int) (i * barW);
            int barH  = (int) (val * chartH);

            drawBar(g2, c, x, chartY, (int) barW2, barH, chartH, val);

            // Peak hold line
            if (showPeakLine && peaks.length > idx) {
                float pVal = Math.min(1f, peaks[idx]);
                if (pVal > 0.01f) {
                    int py = chartY + chartH - (int) (pVal * chartH);
                    g2.setColor(theme.peakColor);
                    g2.drawLine(x, py, x + (int) barW2, py);
                }
            }
        }

        if (showFreqLabels) drawFreqLabels(g2, chartX, chartY + chartH + 2, chartW, bottomPad);
        drawStatus(g2, 0, H - 18, W, 18);
    }

    private void drawBar(Graphics2D g2, Color c, int x, int chartY,
                         int barW, int barH, int chartH, float val) {
        int y = chartY + chartH - barH;

        switch (barStyle) {
            case BARS -> {
                g2.setColor(c);
                g2.fillRect(x, y, barW, barH);
                // Highlight top edge
                g2.setColor(c.brighter().brighter());
                g2.drawLine(x, y, x + barW - 1, y);
            }
            case LINES -> {
                g2.setColor(c);
                Stroke old = g2.getStroke();
                g2.setStroke(new BasicStroke(Math.max(1f, barW * 0.6f)));
                int cx = x + barW / 2;
                g2.drawLine(cx, chartY + chartH, cx, y);
                g2.setStroke(old);
            }
            case DOTS -> {
                g2.setColor(c);
                int d = Math.max(2, barW);
                g2.fillOval(x, y - d / 2, d, d);
                // optional ghost line below dot
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                g2.fillRect(x, y, barW, barH);
            }
            case GRADIENT -> {
                GradientPaint gp = new GradientPaint(
                        x, chartY + chartH, new Color(c.getRed(), c.getGreen(), c.getBlue(), 200),
                        x, y,              new Color(c.getRed(), c.getGreen(), c.getBlue(), 255));
                g2.setPaint(gp);
                g2.fillRect(x, y, barW, barH);
                g2.setColor(c.brighter());
                g2.drawLine(x, y, x + barW - 1, y);
            }
            case MIRROR -> {
                int midY = chartY + chartH / 2;
                int half = barH / 2;
                g2.setColor(c);
                g2.fillRect(x, midY - half, barW, half);
                g2.fillRect(x, midY,        barW, half);
                g2.setColor(c.brighter());
                g2.drawLine(x, midY - half, x + barW - 1, midY - half);
                g2.drawLine(x, midY + half, x + barW - 1, midY + half);
            }
        }
    }

    private Color barColor(float freqT, float ampT, int idx, int n) {
        return switch (colorMode) {
            case SOLID     -> theme.spectrumColor;
            case FREQUENCY -> interpolate(theme.gradientLow, theme.gradientMid, theme.gradientHigh, freqT);
            case AMPLITUDE -> interpolate(theme.gradientLow, theme.gradientMid, theme.gradientHigh, ampT);
            case RAINBOW   -> Color.getHSBColor(freqT * 0.75f, 1f, 1f);
            case FIRE      -> fireColor(ampT);
            case COOL      -> coolColor(ampT);
        };
    }

    private static Color fireColor(float t) {
        if (t < 0.33f) return interpolate(Color.BLACK, new Color(180, 0, 0), new Color(180,0,0), t / 0.33f);
        if (t < 0.66f) return interpolate(new Color(180,0,0), new Color(255,120,0), new Color(255,120,0), (t-0.33f)/0.33f);
        return interpolate(new Color(255,120,0), new Color(255,220,0), new Color(255,255,200), (t-0.66f)/0.34f);
    }

    private static Color coolColor(float t) {
        if (t < 0.5f) return interpolate(new Color(0,0,180), new Color(0,180,220), new Color(0,180,220), t / 0.5f);
        return interpolate(new Color(0,180,220), new Color(0,255,200), new Color(200,255,255), (t-0.5f)/0.5f);
    }

    private static Color interpolate(Color lo, Color mid, Color hi, float t) {
        if (t <= 0.5f) {
            float u = t * 2f;
            return blend(lo, mid, u);
        } else {
            float u = (t - 0.5f) * 2f;
            return blend(mid, hi, u);
        }
    }

    private static Color blend(Color a, Color b, float t) {
        int r = clamp((int)(a.getRed()   + (b.getRed()   - a.getRed())   * t));
        int g = clamp((int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t));
        int bv= clamp((int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
        return new Color(r, g, bv);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static int mirrorIndex(int i, int n) {
        int half = n / 2;
        if (i < half) return i * 2;
        return (n - 1 - i) * 2 + 1;
    }

    // -----------------------------------------------------------------------
    // Spectrum grid and labels
    // -----------------------------------------------------------------------

    private void drawSpectrumGrid(Graphics2D g2, int cx, int cy, int cw, int ch,
                                   int bottomPad, int leftPad) {
        g2.setColor(theme.gridColor);
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{3f, 3f}, 0f));

        // Horizontal dB lines
        for (int db = 0; db <= 100; db += 20) {
            int y = cy + (int) (ch * (1f - db / 100f));
            g2.drawLine(cx, y, cx + cw, y);
        }
        g2.setStroke(old);

        // Bottom border
        g2.setColor(theme.border);
        g2.drawLine(cx, cy + ch, cx + cw, cy + ch);
        g2.drawLine(cx, cy, cx, cy + ch);
    }

    private void drawDbScale(Graphics2D g2, int cx, int cy, int ch) {
        g2.setFont(FONT_LABEL);
        g2.setColor(theme.textSecondary);
        for (int db = 0; db <= 100; db += 20) {
            int y = cy + (int) (ch * (1f - db / 100f));
            String label = "-" + (100 - db) + "dB";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(label, cx - fm.stringWidth(label) - 4, y + 4);
        }
    }

    private void drawFreqLabels(Graphics2D g2, int cx, int labelY, int cw, int h) {
        if (barFreqs.length == 0) return;
        g2.setFont(FONT_LABEL);
        g2.setColor(theme.textSecondary);
        FontMetrics fm = g2.getFontMetrics();

        // Show a selection of frequency labels
        float[] targets = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
        int n = barFreqs.length;
        for (float targetHz : targets) {
            // find closest bar
            int best = 0;
            float bestDiff = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float d = Math.abs(barFreqs[i] - targetHz);
                if (d < bestDiff) { bestDiff = d; best = i; }
            }
            if (bestDiff > targetHz * 0.5f) continue; // too far away
            int x = cx + (int) ((float) best / n * cw);
            String lbl = targetHz >= 1000 ? ((int)(targetHz/1000)) + "k" : ((int)targetHz) + "";
            int lw = fm.stringWidth(lbl);
            g2.drawString(lbl, x - lw/2, labelY + fm.getAscent());
            // tick
            g2.drawLine(x, labelY - 2, x, labelY + 2);
        }
    }

    // -----------------------------------------------------------------------
    // Waveform renderer
    // -----------------------------------------------------------------------

    private void drawWaveform(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        int midL = H / 3;
        int midR = 2 * H / 3;
        int halfH = H / 4;
        int statusH = 20;

        if (showGrid) {
            g2.setColor(theme.gridColor);
            g2.drawLine(0, midL, W, midL);
            g2.drawLine(0, midR, W, midR);
            g2.setColor(theme.border);
            g2.drawLine(0, H/2, W, H/2);
        }

        // Channel labels
        g2.setFont(FONT_LABEL);
        g2.setColor(theme.textSecondary);
        g2.drawString("L", 4, midL - halfH - 2);
        g2.drawString("R", 4, midR - halfH - 2);

        drawChannel(g2, waveL, 0, W, midL, halfH, theme.waveformColor, 0.8f);
        Color rColor = blend(theme.waveformColor, theme.spectrumColor, 0.5f);
        drawChannel(g2, waveR, 0, W, midR, halfH, rColor, 0.8f);

        drawStatus(g2, 0, H - statusH, W, statusH);
    }

    private void drawChannel(Graphics2D g2, float[] wave, int x0, int w, int midY,
                              int halfH, Color c, float alpha) {
        if (wave.length < 2) return;
        int n = wave.length;

        // Sample down to screen width
        int pts = Math.min(w, n);
        int[] xs = new int[pts];
        int[] ys = new int[pts];
        for (int i = 0; i < pts; i++) {
            int sampleIdx = (int) ((float) i / pts * n);
            xs[i] = x0 + i;
            float s = wave[sampleIdx];
            ys[i] = midY - (int) (s * halfH);
        }

        // Glow effect
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g2.setStroke(new BasicStroke(4f));
        g2.drawPolyline(xs, ys, pts);

        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(alpha*255)));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawPolyline(xs, ys, pts);
        g2.setStroke(new BasicStroke(1f));
    }

    // -----------------------------------------------------------------------
    // Spectrogram renderer
    // -----------------------------------------------------------------------

    private void drawSpectrogram(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        int statusH = 20;
        int labelH  = showFreqLabels ? 22 : 0;
        int chartH  = H - statusH - labelH;

        if (spectrogram.length == 0) { drawPlaceholder(g2); return; }

        int rows = spectrogram.length;
        int cols = spectrogram[0].length;
        if (cols == 0) { drawPlaceholder(g2); return; }

        // Rebuild offscreen image if size changed
        if (spectrogramImg == null || spectrogramImg.getWidth() != W || spectrogramImg.getHeight() != chartH) {
            spectrogramImg = new BufferedImage(Math.max(1,W), Math.max(1,chartH), BufferedImage.TYPE_INT_RGB);
        }

        // Render every row of spectrogram ring
        for (int row = 0; row < rows; row++) {
            int imgY = chartH - 1 - (int) ((float) row / rows * chartH);
            if (imgY < 0 || imgY >= chartH) continue;
            float[] rowData = spectrogram[row];
            for (int col = 0; col < cols; col++) {
                int imgX = (int) ((float) col / cols * W);
                if (imgX >= W) imgX = W - 1;
                float v = Math.min(1f, rowData[col]);
                int rgb = spectrogramColor(v);
                spectrogramImg.setRGB(imgX, imgY, rgb);
            }
        }

        g2.drawImage(spectrogramImg, 0, 0, null);
        if (showFreqLabels) drawFreqLabels(g2, 0, chartH, W, labelH);
        drawStatus(g2, 0, H - statusH, W, statusH);
    }

    private int spectrogramColor(float v) {
        // Black → dark blue → cyan → yellow → white heat map
        if (v < 0.25f) {
            float t = v / 0.25f;
            return blendRgb(0x000000, 0x0000aa, t);
        } else if (v < 0.5f) {
            float t = (v - 0.25f) / 0.25f;
            return blendRgb(0x0000aa, 0x00aaff, t);
        } else if (v < 0.75f) {
            float t = (v - 0.5f) / 0.25f;
            return blendRgb(0x00aaff, 0xffff00, t);
        } else {
            float t = (v - 0.75f) / 0.25f;
            return blendRgb(0xffff00, 0xffffff, t);
        }
    }

    private static int blendRgb(int c1, int c2, float t) {
        int r = clamp((int)(((c1>>16)&0xFF) + (((c2>>16)&0xFF) - ((c1>>16)&0xFF)) * t));
        int g = clamp((int)(((c1>>8 )&0xFF) + (((c2>>8 )&0xFF) - ((c1>>8 )&0xFF)) * t));
        int b = clamp((int)(( c1     &0xFF) + (( c2     &0xFF) - ( c1     &0xFF)) * t));
        return (r<<16)|(g<<8)|b;
    }

    // -----------------------------------------------------------------------
    // VU Meter renderer
    // -----------------------------------------------------------------------

    private void drawVuMeter(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        int statusH = 20;
        int meterW  = (W - 60) / 2;
        int meterH  = H - statusH - 80;
        int meterY  = 40;
        int gapX    = 30;

        int lx = (W - 2*meterW - gapX) / 2;
        int rx = lx + meterW + gapX;

        drawMeterChannel(g2, lx, meterY, meterW, meterH, vuL, peakVuL, "L");
        drawMeterChannel(g2, rx, meterY, meterW, meterH, vuR, peakVuR, "R");
        drawStatus(g2, 0, H - statusH, W, statusH);
    }

    private void drawMeterChannel(Graphics2D g2, int x, int y, int w, int h,
                                   float level, float peak, String label) {
        // Background
        g2.setColor(new Color(20, 20, 30));
        g2.fillRoundRect(x, y, w, h, 6, 6);
        g2.setColor(theme.border);
        g2.drawRoundRect(x, y, w, h, 6, 6);

        int filled = (int) (Math.min(1f, level) * h);
        int safeH    = (int) (0.70f * h);
        int cautionH = (int) (0.90f * h);

        // Draw safe zone (bottom 70%)
        if (filled > 0) {
            int greenH = Math.min(filled, safeH);
            g2.setColor(theme.vuSafe);
            g2.fillRect(x + 2, y + h - greenH, w - 4, greenH);
        }
        // Caution zone (70-90%)
        if (filled > safeH) {
            int yellowH = Math.min(filled, cautionH) - safeH;
            g2.setColor(theme.vuCaution);
            g2.fillRect(x + 2, y + h - safeH - yellowH, w - 4, yellowH);
        }
        // Clip zone (90-100%)
        if (filled > cautionH) {
            int redH = filled - cautionH;
            g2.setColor(theme.vuClip);
            g2.fillRect(x + 2, y + h - cautionH - redH, w - 4, redH);
        }

        // Segment lines
        g2.setColor(new Color(0, 0, 0, 100));
        for (int seg = 1; seg < 20; seg++) {
            int lineY = y + h - (int) (h * seg / 20f);
            g2.drawLine(x + 1, lineY, x + w - 1, lineY);
        }

        // Peak hold bar
        if (peak > 0.01f) {
            int peakY = y + h - (int)(peak * h);
            g2.setColor(theme.peakColor);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x + 2, peakY, x + w - 2, peakY);
            g2.setStroke(new BasicStroke(1f));
        }

        // dB labels on the right
        g2.setFont(FONT_LABEL);
        g2.setColor(theme.textSecondary);
        int[] dbs = {0, -6, -12, -18, -24, -36};
        for (int db : dbs) {
            float norm = (float)Math.pow(10.0, db / 20.0);
            int ly = y + h - (int)(norm * h);
            g2.drawString(db + "", x + w + 3, ly + 4);
        }

        // Label
        g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g2.setColor(theme.textPrimary);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, x + w/2 - fm.stringWidth(label)/2, y - 8);

        // Level text
        g2.setFont(FONT_LABEL);
        g2.setColor(theme.textAccent);
        float dBval = level > 0.00001f ? 20f * (float)Math.log10(level) : -999f;
        String dbtxt = String.format("%.1f dBFS", dBval);
        g2.drawString(dbtxt, x + w/2 - g2.getFontMetrics().stringWidth(dbtxt)/2, y + h + 14);
    }

    // -----------------------------------------------------------------------
    // Radial (circular) spectrum renderer
    // -----------------------------------------------------------------------

    private void drawRadial(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        int cx = W / 2, cy = H / 2;
        float baseR  = Math.min(W, H) * 0.18f;
        float maxAdd = Math.min(W, H) * 0.30f;
        int n = spectrum.length;
        if (n == 0) { drawPlaceholder(g2); return; }

        // Glow circle base
        for (int r = (int)baseR + 8; r > (int)baseR - 2; r--) {
            float alpha = (r - baseR + 2) / 10f;
            g2.setColor(new Color(theme.spectrumColor.getRed(),
                                  theme.spectrumColor.getGreen(),
                                  theme.spectrumColor.getBlue(),
                                  (int)(30 * alpha)));
            g2.drawOval(cx - r, cy - r, r*2, r*2);
        }
        g2.setColor(new Color(theme.border.getRed(), theme.border.getGreen(), theme.border.getBlue(), 80));
        g2.drawOval((int)(cx-baseR), (int)(cy-baseR), (int)(2*baseR), (int)(2*baseR));

        g2.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < n; i++) {
            float angle = (float) i / n * 2f * (float) Math.PI - (float) Math.PI / 2f;
            float val   = Math.min(1f, mirrorSpectrum ? spectrum[mirrorIndex(i, n)] : spectrum[i]);
            float rOuter = baseR + val * maxAdd;

            float freqT = (float) i / n;
            Color c = barColor(freqT, val, i, n);

            float x1 = cx + baseR  * (float)Math.cos(angle);
            float y1 = cy + baseR  * (float)Math.sin(angle);
            float x2 = cx + rOuter * (float)Math.cos(angle);
            float y2 = cy + rOuter * (float)Math.sin(angle);

            g2.setColor(c);
            g2.draw(new Line2D.Float(x1, y1, x2, y2));

            // Peak dot
            if (showPeakLine && peaks.length > i) {
                float pVal = Math.min(1f, peaks[i]);
                float rp = baseR + pVal * maxAdd;
                float px = cx + rp * (float)Math.cos(angle);
                float py = cy + rp * (float)Math.sin(angle);
                g2.setColor(theme.peakColor);
                g2.fill(new Ellipse2D.Float(px - 1, py - 1, 3, 3));
            }
        }
        g2.setStroke(new BasicStroke(1f));

        drawStatus(g2, 0, H - 20, W, 20);
    }

    // -----------------------------------------------------------------------
    // Lissajous (X-Y phase scope) renderer
    // -----------------------------------------------------------------------

    private void drawLissajous(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        int cx = W / 2, cy = H / 2;
        int radius = (int)(Math.min(W, H) * 0.42f);

        // Bounding circle
        g2.setColor(new Color(theme.border.getRed(), theme.border.getGreen(), theme.border.getBlue(), 60));
        g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // Cross-hairs
        if (showGrid) {
            g2.setColor(theme.gridColor);
            g2.drawLine(cx - radius, cy, cx + radius, cy);
            g2.drawLine(cx, cy - radius, cx, cy + radius);
        }

        int n = Math.min(lissL.length, lissR.length);
        if (n < 2) { drawPlaceholder(g2); return; }

        // Draw trace with fading alpha
        for (int i = 1; i < n; i++) {
            float alpha = (float) i / n;
            int a = (int)(alpha * 200);
            Color c = new Color(theme.waveformColor.getRed(), theme.waveformColor.getGreen(),
                                theme.waveformColor.getBlue(), a);
            g2.setColor(c);
            int x1 = cx + (int)(lissL[i-1] * radius);
            int y1 = cy - (int)(lissR[i-1] * radius);
            int x2 = cx + (int)(lissL[i]   * radius);
            int y2 = cy - (int)(lissR[i]   * radius);
            g2.drawLine(x1, y1, x2, y2);
        }

        // Axis labels
        g2.setFont(FONT_LABEL);
        g2.setColor(theme.textSecondary);
        g2.drawString("L →", cx + radius - 20, cy + 12);
        g2.drawString("↑ R", cx + 4, cy - radius + 10);

        drawStatus(g2, 0, H - 20, W, 20);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private void drawModeLabel(Graphics2D g2) {
        g2.setFont(FONT_MODE);
        g2.setColor(new Color(theme.textSecondary.getRed(), theme.textSecondary.getGreen(),
                              theme.textSecondary.getBlue(), 160));
        g2.drawString(mode.displayName, 8, 14);
    }

    private void drawStatus(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(new Color(theme.overlayBackground.getRed(), theme.overlayBackground.getGreen(),
                              theme.overlayBackground.getBlue(), 180));
        g2.fillRect(x, y, w, h);
        g2.setFont(FONT_STATUS);
        g2.setColor(theme.overlayText);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(statusText, x + 6, y + (h + fm.getAscent()) / 2 - 2);
    }

    private void drawPlaceholder(Graphics2D g2) {
        int W = getWidth(), H = getHeight();
        g2.setColor(theme.background);
        g2.fillRect(0, 0, W, H);

        if (captureActive && recentPeakRms < 0.0005f) {
            // Capturing but silent — show informative multi-line overlay
            drawSilenceWarning(g2, W, H);
        } else {
            // Standard placeholder
            g2.setFont(new Font("Segoe UI", Font.ITALIC, 14));
            g2.setColor(new Color(theme.textSecondary.getRed(), theme.textSecondary.getGreen(),
                                  theme.textSecondary.getBlue(), 180));
            String msg = statusText;
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (W - fm.stringWidth(msg)) / 2, H / 2);
        }
    }

    private void drawSilenceWarning(Graphics2D g2, int W, int H) {
        // Pulsing circle indicator
        long t = System.currentTimeMillis();
        float pulse = 0.5f + 0.5f * (float) Math.sin(t / 600.0);
        int alpha = (int)(80 + 120 * pulse);
        g2.setColor(new Color(180, 180, 60, alpha));
        g2.fillOval(W / 2 - 6, H / 2 - 60, 12, 12);

        g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
        g2.setColor(new Color(220, 200, 80));
        String line1 = "Capturing — no audio signal detected";
        FontMetrics fm1 = g2.getFontMetrics();
        g2.drawString(line1, (W - fm1.stringWidth(line1)) / 2, H / 2 - 30);

        g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g2.setColor(new Color(theme.textSecondary.getRed(), theme.textSecondary.getGreen(),
                              theme.textSecondary.getBlue(), 200));
        String src = captureSource.isEmpty() ? "current source" : captureSource;
        String line2 = "Source: " + src;
        FontMetrics fm2 = g2.getFontMetrics();
        g2.drawString(line2, (W - fm2.stringWidth(line2)) / 2, H / 2 - 8);

        String[] hints = {
            "Play audio through a Linux/WSLg app  (browser, media player, speaker-test)",
            "— OR —",
            "Use 'make native' to access Windows audio devices directly"
        };
        int y = H / 2 + 16;
        for (String hint : hints) {
            String h = hint;
            FontMetrics fmh = g2.getFontMetrics();
            g2.drawString(h, (W - fmh.stringWidth(h)) / 2, y);
            y += 18;
        }
    }

    /**
     * Draws a compact input-level meter in the top-right corner.
     * Always visible when capture is active, so the user can see
     * whether audio is flowing even before bars appear.
     */
    private void drawLevelOverlay(Graphics2D g2) {
        if (!captureActive) return;

        int W = getWidth();
        int barW   = 120;
        int barH   = 8;
        int margin = 8;
        int x      = W - barW - margin;
        int y      = margin;

        // Background
        g2.setColor(new Color(0, 0, 0, 100));
        g2.fillRoundRect(x - 46, y - 2, barW + 48, barH + 4, 4, 4);

        // Label
        g2.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        g2.setColor(new Color(200, 200, 200, 180));
        g2.drawString("INPUT", x - 42, y + barH - 1);

        // Bar fill — colour shifts green→yellow→red with level
        float rms = (vuL + vuR) / 2f;
        float fill = Math.max(0f, Math.min(1f,
                rms > 0 ? (float)((20 * Math.log10(rms) + 60) / 60.0) : 0f));
        Color barColor;
        if (fill > 0.85f)      barColor = new Color(255, 60, 60);
        else if (fill > 0.6f)  barColor = new Color(255, 220, 30);
        else if (fill > 0.02f) barColor = new Color(60, 210, 80);
        else                   barColor = new Color(80, 80, 80);

        // Background track
        g2.setColor(new Color(40, 40, 40, 160));
        g2.fillRect(x, y, barW, barH);
        // Active fill
        g2.setColor(barColor);
        int fillW = (int)(fill * barW);
        if (fillW > 0) g2.fillRect(x, y, fillW, barH);
        // Border
        g2.setColor(new Color(120, 120, 120, 120));
        g2.drawRect(x, y, barW, barH);

        // dBFS label
        float dBFS = rms > 0 ? (float)(20 * Math.log10(rms)) : Float.NEGATIVE_INFINITY;
        String dbStr = Float.isInfinite(dBFS) ? "-∞ dB" : String.format("%.0f dB", dBFS);
        g2.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        g2.setColor(barColor);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(dbStr, x + barW - fm.stringWidth(dbStr) - 2, y + barH - 1);
    }
}

