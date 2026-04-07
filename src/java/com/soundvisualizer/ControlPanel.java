package com.soundvisualizer;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Right-side control panel with tabbed sections for all visualizer options.
 *
 * Tabs:
 *   1. Audio      – source, gain, stereo mode, buffer size, start/stop
 *   2. Visualize  – mode, bar style, color mode, mirror, bar count
 *   3. Analysis   – FFT size, window function, smoothing, peak hold/decay
 *   4. Frequency  – min/max Hz, log/linear scale
 *   5. Display    – theme, grid, labels, dB scale, peak line
 */
public class ControlPanel extends JPanel {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int PANEL_WIDTH = 290;
    private static final Font LBL_FONT   = new Font("Segoe UI", Font.PLAIN,  11);
    private static final Font VAL_FONT   = new Font("Segoe UI", Font.BOLD,   11);
    private static final Font HDR_FONT   = new Font("Segoe UI", Font.BOLD,   12);
    private static final Font BTN_FONT   = new Font("Segoe UI", Font.BOLD,   12);

    // -----------------------------------------------------------------------
    // Theme
    // -----------------------------------------------------------------------
    private Theme currentTheme;

    // -----------------------------------------------------------------------
    // ── Audio Tab ────────────────────────────────────────────────────────
    // -----------------------------------------------------------------------
    private final JComboBox<String>   sourceCombo;
    private final List<AudioCapture.SourceInfo> sourceInfos;
    /** Number of entries in sourceCombo that are real audio sources (not test signals). */
    private int numRealSourceEntries;
    /** Index of the "Audio File" selectable entry in sourceCombo. */
    private int audioFileSourceIndex;
    /** The audio file chosen via the Browse button, or {@code null} if none. */
    private File selectedAudioFile;
    private JLabel  fileNameLbl;
    private JButton btnBrowse;
    private final JSlider             gainSlider;         // 0..400 (100=1.0x)
    private final JLabel              gainValLbl;
    private final JComboBox<AudioProcessor.StereoMode> stereoModeCombo;
    private final JComboBox<Integer>  bufferSizeCombo;
    private final JButton             btnStart;
    private final JButton             btnStop;
    private JButton                   btnTone;
    /** Non-null while the reference tone thread is running. */
    private volatile Thread           toneThread;
    private volatile boolean          tonePlaying;
    private final JLabel              statusLabel;

    // -----------------------------------------------------------------------
    // ── Visualize Tab ────────────────────────────────────────────────────
    // -----------------------------------------------------------------------
    private final JComboBox<VisualizationMode> modeCombo;
    private final JComboBox<BarStyle>          barStyleCombo;
    private final JComboBox<ColorMode>         colorModeCombo;
    private final JCheckBox                    mirrorCheck;
    private final JSlider                      barCountSlider;   // 10..512
    private final JLabel                       barCountLbl;

    // -----------------------------------------------------------------------
    // ── Analysis Tab ─────────────────────────────────────────────────────
    // -----------------------------------------------------------------------
    private final JComboBox<Integer>       fftSizeCombo;
    private final JComboBox<WindowFunction> windowCombo;
    private final JSlider                  smoothingSlider;    // 0..99
    private final JLabel                   smoothingLbl;
    private final JCheckBox                peakHoldCheck;
    private final JSlider                  peakDecaySlider;    // 1..100
    private final JLabel                   peakDecayLbl;
    private final JSlider                  noiseFloorSlider;   // -120..-40
    private final JLabel                   noiseFloorLbl;

    // -----------------------------------------------------------------------
    // ── Frequency Tab ────────────────────────────────────────────────────
    // -----------------------------------------------------------------------
    private final JSlider    freqMinSlider;    // 10..2000
    private final JLabel     freqMinLbl;
    private final JSlider    freqMaxSlider;    // 1000..22050
    private final JLabel     freqMaxLbl;
    private final JCheckBox  logScaleCheck;

    // -----------------------------------------------------------------------
    // ── Display Tab ──────────────────────────────────────────────────────
    // -----------------------------------------------------------------------
    private final JComboBox<Theme> themeCombo;
    private final JCheckBox        showGridCheck;
    private final JCheckBox        showFreqLabelsCheck;
    private final JCheckBox        showDbScaleCheck;
    private final JCheckBox        showPeakLineCheck;

    // -----------------------------------------------------------------------
    // Sub-panels collected for live theme updates
    // -----------------------------------------------------------------------
    private JPanel audioTab, vizTab, analysisTab, freqTab, displayTab;

    // -----------------------------------------------------------------------
    // Profile panel controls
    // -----------------------------------------------------------------------
    private JPanel            profilePanel;
    private JTextField        profileNameField;
    private JComboBox<String> profileCombo;
    private JButton           btnSaveProfile;
    private JButton           btnLoadProfile;
    private JButton           btnDeleteProfile;
    /** When true, {@link #fireSettingsChange()} is suppressed (used during profile load). */
    private boolean           suppressSettingsChange;

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------
    private Runnable              onStart;
    private Runnable              onStop;
    private Consumer<Theme>       onThemeChange;
    private Runnable              onSettingsChange;
    /** Receives float[][] audio frames produced by the reference-tone generator,
     *  bypassing the capture pipeline so the visualizer always reacts. */
    private Consumer<float[][]>   onToneData;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public ControlPanel(List<AudioCapture.SourceInfo> sources, Theme initialTheme) {
        this.currentTheme = initialTheme;
        this.sourceInfos  = sources;
        setPreferredSize(new Dimension(PANEL_WIDTH, 0));
        setLayout(new BorderLayout(0, 0));

        // ── Audio Tab controls ──────────────────────────────────────────────
        sourceCombo = new JComboBox<>();
        sourceCombo.addItem("System Default");
        for (AudioCapture.SourceInfo info : sources)
            sourceCombo.addItem(info.label);
        // Record where real sources end, then add built-in test signals
        numRealSourceEntries = 1 + sources.size(); // "System Default" + real devices
        sourceCombo.addItem("── Test Signals ──"); // non-selectable separator label
        for (TestSignalSource.Signal sig : TestSignalSource.Signal.values())
            sourceCombo.addItem(sig.displayName);
        // Audio file section (placed after test signals)
        sourceCombo.addItem("── Audio File ──"); // non-selectable separator label
        audioFileSourceIndex = sourceCombo.getItemCount();
        sourceCombo.addItem("Audio File");
        // Disable all separator rows (items whose text starts with "──")
        sourceCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                boolean isSep = value instanceof String s && s.startsWith("──");
                setEnabled(!isSep);
                if (isSep) {
                    setForeground(currentTheme.textSecondary);
                    setFont(getFont().deriveFont(Font.ITALIC));
                }
                return this;
            }
        });

        gainSlider    = new JSlider(0, 400, 100);
        gainValLbl    = new JLabel("1.00×");
        gainValLbl.setFont(VAL_FONT);
        gainSlider.addChangeListener(e ->
            gainValLbl.setText(String.format("%.2f×", gainSlider.getValue() / 100.0)));

        stereoModeCombo = new JComboBox<>(AudioProcessor.StereoMode.values());
        bufferSizeCombo = new JComboBox<>(new Integer[]{512, 1024, 2048, 4096, 8192});
        bufferSizeCombo.setSelectedItem(2048);

        btnStart = makeButton("▶  Start", initialTheme.btnPrimary, initialTheme.btnForeground);
        btnStop  = makeButton("■  Stop",  initialTheme.btnDanger,  initialTheme.btnForeground);
        btnStop.setEnabled(false);
        btnTone  = makeButton("🔊 Ref Tone", initialTheme.btnNeutral, initialTheme.btnForeground);
        btnTone.setToolTipText("Play a 1 kHz reference tone via Java audio to verify live capture");
        btnBrowse = makeButton("Browse…", initialTheme.btnNeutral, initialTheme.btnForeground);
        btnBrowse.setToolTipText("Open an audio file (.m4a, .flac, .mp3, .wav, …)");
        fileNameLbl = new JLabel("(no file loaded)");
        fileNameLbl.setFont(LBL_FONT);

        statusLabel = new JLabel("Stopped");
        statusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));

        // ── Visualize Tab controls ──────────────────────────────────────────
        modeCombo      = new JComboBox<>(VisualizationMode.values());
        barStyleCombo  = new JComboBox<>(BarStyle.values());
        barStyleCombo.setSelectedItem(BarStyle.GRADIENT);
        colorModeCombo = new JComboBox<>(ColorMode.values());
        colorModeCombo.setSelectedItem(ColorMode.FREQUENCY);
        mirrorCheck    = new JCheckBox("Mirror spectrum");

        barCountSlider = new JSlider(10, 512, 120);
        barCountLbl    = new JLabel("120");
        barCountLbl.setFont(VAL_FONT);
        barCountSlider.addChangeListener(e ->
            barCountLbl.setText(String.valueOf(barCountSlider.getValue())));

        // ── Analysis Tab controls ───────────────────────────────────────────
        fftSizeCombo   = new JComboBox<>(new Integer[]{256, 512, 1024, 2048, 4096, 8192});
        fftSizeCombo.setSelectedItem(2048);
        windowCombo    = new JComboBox<>(WindowFunction.values());
        windowCombo.setSelectedItem(WindowFunction.HANN);

        smoothingSlider = new JSlider(0, 99, 75);
        smoothingLbl    = new JLabel("75%");
        smoothingLbl.setFont(VAL_FONT);
        smoothingSlider.addChangeListener(e ->
            smoothingLbl.setText(smoothingSlider.getValue() + "%"));

        peakHoldCheck  = new JCheckBox("Peak Hold");
        peakHoldCheck.setSelected(true);

        peakDecaySlider = new JSlider(1, 100, 8);
        peakDecayLbl    = new JLabel("medium");
        peakDecayLbl.setFont(VAL_FONT);
        peakDecaySlider.addChangeListener(e ->
            peakDecayLbl.setText(decayLabel(peakDecaySlider.getValue())));

        noiseFloorSlider = new JSlider(-120, -40, -90);
        noiseFloorLbl    = new JLabel("-90 dB");
        noiseFloorLbl.setFont(VAL_FONT);
        noiseFloorSlider.addChangeListener(e ->
            noiseFloorLbl.setText(noiseFloorSlider.getValue() + " dB"));

        // ── Frequency Tab controls ──────────────────────────────────────────
        freqMinSlider = new JSlider(10, 2000, 20);
        freqMinLbl    = new JLabel("20 Hz");
        freqMinLbl.setFont(VAL_FONT);
        freqMinSlider.addChangeListener(e ->
            freqMinLbl.setText(freqMinSlider.getValue() + " Hz"));

        freqMaxSlider = new JSlider(1000, 22050, 20000);
        freqMaxLbl    = new JLabel("20000 Hz");
        freqMaxLbl.setFont(VAL_FONT);
        freqMaxSlider.addChangeListener(e ->
            freqMaxLbl.setText(freqMaxSlider.getValue() + " Hz"));

        logScaleCheck = new JCheckBox("Logarithmic scale");
        logScaleCheck.setSelected(true);

        // ── Display Tab controls ────────────────────────────────────────────
        themeCombo = new JComboBox<>();
        for (Theme t : Themes.ALL) themeCombo.addItem(t);
        themeCombo.setSelectedItem(initialTheme);

        showGridCheck       = new JCheckBox("Show frequency grid");
        showGridCheck.setSelected(true);
        showFreqLabelsCheck = new JCheckBox("Show frequency labels");
        showFreqLabelsCheck.setSelected(true);
        showDbScaleCheck    = new JCheckBox("Show dB scale");
        showDbScaleCheck.setSelected(true);
        showPeakLineCheck   = new JCheckBox("Show peak line");
        showPeakLineCheck.setSelected(true);

        // ── Assemble tabs ───────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(LBL_FONT);

        audioTab    = buildAudioTab();
        vizTab      = buildVizTab();
        analysisTab = buildAnalysisTab();
        freqTab     = buildFreqTab();
        displayTab  = buildDisplayTab();

        tabs.addTab("Audio",    iconFor("🎙"), audioTab);
        tabs.addTab("Visual",   iconFor("📊"), vizTab);
        tabs.addTab("Analysis", iconFor("🔬"), analysisTab);
        tabs.addTab("Freq",     iconFor("〽"), freqTab);
        tabs.addTab("Display",  iconFor("🎨"), displayTab);

        add(tabs, BorderLayout.CENTER);

        // Profile panel + status bar stacked at bottom
        profilePanel = buildProfilePanel();
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setOpaque(false);
        statusBar.setBorder(new MatteBorder(1, 0, 0, 0, initialTheme.border));
        statusBar.add(statusLabel, BorderLayout.WEST);
        JPanel southSection = new JPanel();
        southSection.setLayout(new BoxLayout(southSection, BoxLayout.Y_AXIS));
        southSection.setOpaque(false);
        southSection.add(profilePanel);
        southSection.add(statusBar);
        add(southSection, BorderLayout.SOUTH);

        // Wire change events to onSettingsChange
        wireTriggers();
        applyTheme(initialTheme);
    }

    // -----------------------------------------------------------------------
    // Tab builders
    // -----------------------------------------------------------------------

    private JPanel buildAudioTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        p.add(sectionHeader("Audio Source"));
        p.add(labeledRow("Source", sourceCombo));
        p.add(Box.createVerticalStrut(2));
        // File picker row – shown below the source combo
        JPanel filePickRow = new JPanel(new BorderLayout(4, 0));
        filePickRow.setOpaque(false);
        filePickRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        filePickRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        filePickRow.add(fileNameLbl, BorderLayout.CENTER);
        filePickRow.add(btnBrowse,   BorderLayout.EAST);
        p.add(labeledRow("File", filePickRow));
        p.add(Box.createVerticalStrut(4));
        p.add(sectionHeader("Input"));
        p.add(labeledRow("Stereo mode", stereoModeCombo));
        p.add(labeledRow("Buffer size", bufferSizeCombo));
        p.add(sliderRow("Gain", gainSlider, gainValLbl));
        p.add(Box.createVerticalStrut(8));
        p.add(sectionHeader("Control"));

        JPanel btns = new JPanel(new GridLayout(1, 2, 6, 0));
        btns.setOpaque(false);
        btns.add(btnStart);
        btns.add(btnStop);
        btns.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(btns);

        // Reference tone — lets user verify live capture without external audio
        p.add(Box.createVerticalStrut(6));
        p.add(sectionHeader("Diagnostics"));
        btnTone.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnTone.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.add(btnTone);
        JLabel toneHint = new JLabel("<html><small>Plays 1 kHz tone → PulseAudio → monitor source</small></html>");
        toneHint.setForeground(new Color(130, 130, 130));
        toneHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(toneHint);

        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildVizTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        p.add(sectionHeader("Visualization"));
        p.add(labeledRow("Mode", modeCombo));
        p.add(labeledRow("Bar style", barStyleCombo));
        p.add(labeledRow("Color mode", colorModeCombo));
        p.add(Box.createVerticalStrut(4));
        p.add(sliderRow("Bar count", barCountSlider, barCountLbl));
        p.add(checkRow(mirrorCheck));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildAnalysisTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        p.add(sectionHeader("FFT Settings"));
        p.add(labeledRow("FFT size", fftSizeCombo));
        p.add(labeledRow("Window fn.", windowCombo));
        p.add(Box.createVerticalStrut(4));
        p.add(sectionHeader("Smoothing & Peak"));
        p.add(sliderRow("Smoothing", smoothingSlider, smoothingLbl));
        p.add(checkRow(peakHoldCheck));
        p.add(sliderRow("Peak decay", peakDecaySlider, peakDecayLbl));
        p.add(Box.createVerticalStrut(4));
        p.add(sectionHeader("Sensitivity"));
        p.add(sliderRow("Noise floor", noiseFloorSlider, noiseFloorLbl));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildFreqTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        p.add(sectionHeader("Frequency Range"));
        p.add(sliderRow("Min freq", freqMinSlider, freqMinLbl));
        p.add(sliderRow("Max freq", freqMaxSlider, freqMaxLbl));
        p.add(checkRow(logScaleCheck));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildDisplayTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        p.add(sectionHeader("Theme"));
        p.add(labeledRow("Color theme", themeCombo));
        p.add(Box.createVerticalStrut(4));
        p.add(sectionHeader("Overlays"));
        p.add(checkRow(showGridCheck));
        p.add(checkRow(showFreqLabelsCheck));
        p.add(checkRow(showDbScaleCheck));
        p.add(checkRow(showPeakLineCheck));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildProfilePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, currentTheme.border),
                new EmptyBorder(6, 8, 6, 8)));

        JLabel header = new JLabel("Profiles");
        header.setFont(HDR_FONT);
        header.setForeground(currentTheme.textAccent);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(header);
        p.add(Box.createVerticalStrut(4));

        // Row 1 – name field + Save button
        profileNameField = new JTextField("default");
        profileNameField.setFont(LBL_FONT);
        btnSaveProfile = makeButton("Save", currentTheme.btnNeutral, currentTheme.btnForeground);
        btnSaveProfile.setFont(LBL_FONT.deriveFont(Font.BOLD));
        btnSaveProfile.setPreferredSize(new Dimension(52, 22));

        JPanel saveRow = new JPanel(new BorderLayout(4, 0));
        saveRow.setOpaque(false);
        saveRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        saveRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel nameLbl = new JLabel("Name:");
        nameLbl.setFont(LBL_FONT);
        nameLbl.setForeground(currentTheme.textPrimary);
        nameLbl.setPreferredSize(new Dimension(44, 22));
        saveRow.add(nameLbl, BorderLayout.WEST);
        saveRow.add(profileNameField, BorderLayout.CENTER);
        saveRow.add(btnSaveProfile, BorderLayout.EAST);
        p.add(saveRow);
        p.add(Box.createVerticalStrut(4));

        // Row 2 – profile combo + Load + Delete buttons
        profileCombo = new JComboBox<>();
        profileCombo.setFont(LBL_FONT);
        refreshProfileList();
        btnLoadProfile = makeButton("Load", currentTheme.btnPrimary, currentTheme.btnForeground);
        btnLoadProfile.setFont(LBL_FONT.deriveFont(Font.BOLD));
        btnLoadProfile.setPreferredSize(new Dimension(52, 22));
        btnDeleteProfile = makeButton("Del", currentTheme.btnDanger, currentTheme.btnForeground);
        btnDeleteProfile.setFont(LBL_FONT.deriveFont(Font.BOLD));
        btnDeleteProfile.setPreferredSize(new Dimension(44, 22));

        JPanel loadBtns = new JPanel(new GridLayout(1, 2, 4, 0));
        loadBtns.setOpaque(false);
        loadBtns.setPreferredSize(new Dimension(100, 22));
        loadBtns.add(btnLoadProfile);
        loadBtns.add(btnDeleteProfile);

        JPanel loadRow = new JPanel(new BorderLayout(4, 0));
        loadRow.setOpaque(false);
        loadRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        loadRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadRow.add(profileCombo, BorderLayout.CENTER);
        loadRow.add(loadBtns, BorderLayout.EAST);
        p.add(loadRow);

        return p;
    }

    // -----------------------------------------------------------------------
    // Event wiring
    // -----------------------------------------------------------------------

    private void wireTriggers() {
        btnStart .addActionListener(e -> { if (onStart != null) onStart.run(); });
        btnStop  .addActionListener(e -> { if (onStop  != null) onStop .run(); });
        btnTone  .addActionListener(e -> toggleReferenceTone());
        btnBrowse.addActionListener(e -> browseForAudioFile());
        btnSaveProfile  .addActionListener(e -> saveProfileAction());
        btnLoadProfile  .addActionListener(e -> loadProfileAction());
        btnDeleteProfile.addActionListener(e -> deleteProfileAction());

        themeCombo.addActionListener(e -> {
            Theme t = (Theme) themeCombo.getSelectedItem();
            if (t != null && onThemeChange != null) onThemeChange.accept(t);
        });

        // All other controls trigger the general settings change
        for (var cb : new JComboBox[]{sourceCombo, stereoModeCombo, bufferSizeCombo,
                modeCombo, barStyleCombo, colorModeCombo, fftSizeCombo, windowCombo}) {
            cb.addActionListener(e -> fireSettingsChange());
        }
        for (var sl : new JSlider[]{gainSlider, barCountSlider, smoothingSlider,
                peakDecaySlider, noiseFloorSlider, freqMinSlider, freqMaxSlider}) {
            sl.addChangeListener(e -> fireSettingsChange());
        }
        for (var chk : new JCheckBox[]{mirrorCheck, peakHoldCheck, logScaleCheck,
                showGridCheck, showFreqLabelsCheck, showDbScaleCheck, showPeakLineCheck}) {
            chk.addActionListener(e -> fireSettingsChange());
        }
    }

    private void fireSettingsChange() {
        if (!suppressSettingsChange && onSettingsChange != null) onSettingsChange.run();
    }

    // -----------------------------------------------------------------------
    // Callbacks registration
    // -----------------------------------------------------------------------

    public void onStart(Runnable r)           { onStart = r; }
    public void onToneData(Consumer<float[][]> c) { onToneData = c; }
    public void onStop(Runnable r)            { onStop = r; }
    public void onThemeChange(Consumer<Theme> c) { onThemeChange = c; }
    public void onSettingsChange(Runnable r)  { onSettingsChange = r; }

    // -----------------------------------------------------------------------
    // Profile actions
    // -----------------------------------------------------------------------

    private void saveProfileAction() {
        String name = profileNameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Enter a profile name before saving.",
                "Save Profile", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            ProfileManager.save(name, getProfile());
            refreshProfileList();
            profileCombo.setSelectedItem(name);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save profile:\n" + ex.getMessage(),
                "Save Profile", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadProfileAction() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null || name.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Select a profile from the list to load.",
                "Load Profile", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            VisualizerProfile profile = ProfileManager.load(name);
            applyProfile(profile);
            profileNameField.setText(name);
        } catch (java.io.IOException | IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not load profile:\n" + ex.getMessage(),
                "Load Profile", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfileAction() {
        String name = (String) profileCombo.getSelectedItem();
        if (name == null || name.isEmpty()) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete profile \"" + name + "\"?",
            "Delete Profile", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            ProfileManager.delete(name);
            refreshProfileList();
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not delete profile:\n" + ex.getMessage(),
                "Delete Profile", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshProfileList() {
        if (profileCombo == null) return;
        String current = (String) profileCombo.getSelectedItem();
        profileCombo.removeAllItems();
        for (String name : ProfileManager.listProfiles()) profileCombo.addItem(name);
        if (current != null) profileCombo.setSelectedItem(current);
    }

    // -----------------------------------------------------------------------
    // Profile snapshot / restore
    // -----------------------------------------------------------------------

    /** Captures the current state of all controls into a new {@link VisualizerProfile}. */
    public VisualizerProfile getProfile() {
        VisualizerProfile p = new VisualizerProfile();
        // Audio
        Object src = sourceCombo.getSelectedItem();
        p.sourceName   = src != null ? src.toString() : "System Default";
        p.audioFilePath = selectedAudioFile != null ? selectedAudioFile.getAbsolutePath() : null;
        p.gain         = gainSlider.getValue();
        AudioProcessor.StereoMode sm = (AudioProcessor.StereoMode) stereoModeCombo.getSelectedItem();
        p.stereoMode   = sm != null ? sm.name() : "MERGED";
        Object bs = bufferSizeCombo.getSelectedItem();
        p.bufferSize   = bs != null ? (Integer) bs : 2048;
        // Visualize
        VisualizationMode vm = (VisualizationMode) modeCombo.getSelectedItem();
        p.visualizationMode = vm != null ? vm.name() : "SPECTRUM";
        BarStyle bst = (BarStyle) barStyleCombo.getSelectedItem();
        p.barStyle     = bst != null ? bst.name() : "GRADIENT";
        ColorMode cm = (ColorMode) colorModeCombo.getSelectedItem();
        p.colorMode    = cm != null ? cm.name() : "FREQUENCY";
        p.mirror       = mirrorCheck.isSelected();
        p.barCount     = barCountSlider.getValue();
        // Analysis
        Object fs = fftSizeCombo.getSelectedItem();
        p.fftSize      = fs != null ? (Integer) fs : 2048;
        WindowFunction wf = (WindowFunction) windowCombo.getSelectedItem();
        p.windowFunction = wf != null ? wf.name() : "HANN";
        p.smoothing    = smoothingSlider.getValue();
        p.peakHold     = peakHoldCheck.isSelected();
        p.peakDecay    = peakDecaySlider.getValue();
        p.noiseFloor   = noiseFloorSlider.getValue();
        // Frequency
        p.freqMin      = freqMinSlider.getValue();
        p.freqMax      = freqMaxSlider.getValue();
        p.logScale     = logScaleCheck.isSelected();
        // Display
        Theme t = (Theme) themeCombo.getSelectedItem();
        p.themeName       = t != null ? t.name : "Dark";
        p.showGrid        = showGridCheck.isSelected();
        p.showFreqLabels  = showFreqLabelsCheck.isSelected();
        p.showDbScale     = showDbScaleCheck.isSelected();
        p.showPeakLine    = showPeakLineCheck.isSelected();
        return p;
    }

    /**
     * Applies all values from {@code profile} to the UI controls, then fires a
     * single settings-change event.  Theme changes propagate via the existing
     * {@code themeCombo} action listener.
     */
    public void applyProfile(VisualizerProfile profile) {
        suppressSettingsChange = true;
        try {
            // Audio
            selectSourceByLabel(profile.sourceName);
            if (profile.audioFilePath != null) {
                File f = new File(profile.audioFilePath);
                if (f.exists()) {
                    selectedAudioFile = f;
                    fileNameLbl.setText(f.getName());
                    fileNameLbl.setToolTipText(f.getAbsolutePath());
                }
            }
            gainSlider.setValue(profile.gain);
            setComboByEnumName(stereoModeCombo, AudioProcessor.StereoMode.class, profile.stereoMode);
            bufferSizeCombo.setSelectedItem(profile.bufferSize);
            // Visualize
            setComboByEnumName(modeCombo,      VisualizationMode.class, profile.visualizationMode);
            setComboByEnumName(barStyleCombo,  BarStyle.class,          profile.barStyle);
            setComboByEnumName(colorModeCombo, ColorMode.class,         profile.colorMode);
            mirrorCheck.setSelected(profile.mirror);
            barCountSlider.setValue(profile.barCount);
            // Analysis
            fftSizeCombo.setSelectedItem(profile.fftSize);
            setComboByEnumName(windowCombo, WindowFunction.class, profile.windowFunction);
            smoothingSlider.setValue(profile.smoothing);
            peakHoldCheck.setSelected(profile.peakHold);
            peakDecaySlider.setValue(profile.peakDecay);
            noiseFloorSlider.setValue(profile.noiseFloor);
            // Frequency
            freqMinSlider.setValue(profile.freqMin);
            freqMaxSlider.setValue(profile.freqMax);
            logScaleCheck.setSelected(profile.logScale);
            // Display – theme combo fires onThemeChange directly
            Theme t = Themes.byName(profile.themeName);
            themeCombo.setSelectedItem(t);
            showGridCheck.setSelected(profile.showGrid);
            showFreqLabelsCheck.setSelected(profile.showFreqLabels);
            showDbScaleCheck.setSelected(profile.showDbScale);
            showPeakLineCheck.setSelected(profile.showPeakLine);
        } finally {
            suppressSettingsChange = false;
        }
        fireSettingsChange();
    }

    /** Selects the {@code sourceCombo} entry whose text matches {@code label}, falling back to index 0. */
    private void selectSourceByLabel(String label) {
        if (label == null) { sourceCombo.setSelectedIndex(0); return; }
        for (int i = 0; i < sourceCombo.getItemCount(); i++) {
            if (label.equals(sourceCombo.getItemAt(i))) {
                sourceCombo.setSelectedIndex(i);
                return;
            }
        }
        sourceCombo.setSelectedIndex(0);
    }

    /** Selects the enum constant whose {@link Enum#name()} matches {@code name}. */
    private static <E extends Enum<E>> void setComboByEnumName(
            JComboBox<E> combo, Class<E> cls, String name) {
        if (name == null) return;
        try {
            combo.setSelectedItem(Enum.valueOf(cls, name));
        } catch (IllegalArgumentException ignored) {
            // unknown value – keep current selection
        }
    }

    // -----------------------------------------------------------------------
    // Audio file browsing
    // -----------------------------------------------------------------------

    private void browseForAudioFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select Audio File");
        fc.setFileFilter(new FileNameExtensionFilter(
            "Audio Files (m4a, alac, flac, mp3, wav, aac, ogg, opus, aiff, aif)",
            "m4a", "alac", "flac", "mp3", "wav", "aac", "ogg", "opus", "aiff", "aif"
        ));
        fc.setAcceptAllFileFilterUsed(true);
        if (selectedAudioFile != null)
            fc.setCurrentDirectory(selectedAudioFile.getParentFile());

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedAudioFile = fc.getSelectedFile();
            fileNameLbl.setText(selectedAudioFile.getName());
            fileNameLbl.setToolTipText(selectedAudioFile.getAbsolutePath());
            // Auto-select the Audio File source in the combo
            sourceCombo.setSelectedIndex(audioFileSourceIndex);
        }
    }

    /** Returns {@code true} when the "Audio File" entry is selected in the source combo. */
    public boolean isAudioFileSource() {
        return sourceCombo.getSelectedIndex() == audioFileSourceIndex;
    }

    /** Returns the file chosen via Browse, or {@code null} if none has been chosen. */
    public File getSelectedAudioFile() {
        return selectedAudioFile;
    }

    // -----------------------------------------------------------------------
    // State setters (called from SoundVisualizer to reflect runtime state)
    // -----------------------------------------------------------------------

    public void setRunning(boolean running) {
        btnStart.setEnabled(!running);
        btnStop .setEnabled(running);
        statusLabel.setText(running ? "● Capturing audio…" : "Stopped");
    }

    public void setStatus(String text) { statusLabel.setText(text); }

    /**
     * Toggles a 1 kHz reference tone played directly via {@link SourceDataLine}.
     * Works on Windows JDK (make native) and Linux JDK (make run) without any
     * external tools.  When audio plays through the system output, the monitor
     * source will pick it up and the spectrum should respond immediately.
     */
    private void toggleReferenceTone() {
        if (tonePlaying) {
            tonePlaying = false;   // signals the tone thread to stop
            btnTone.setText("🔊 Ref Tone");
            return;
        }

        tonePlaying = true;
        btnTone.setText("⏹ Stop Tone");

        toneThread = new Thread(() -> {
            try {
                final float FREQ        = 1000f;   // 1 kHz
                final float SAMPLE_RATE = 44100f;
                final int   CHANNELS    = 2;
                final int   BITS        = 16;
                final int   CHUNK       = 2048;    // frames per write

                AudioFormat fmt = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);

                SourceDataLine line;
                try {
                    line = (SourceDataLine) AudioSystem.getLine(info);
                } catch (LineUnavailableException e) {
                    SwingUtilities.invokeLater(() -> {
                        tonePlaying = false;
                        btnTone.setText("🔊 Ref Tone");
                        JOptionPane.showMessageDialog(ControlPanel.this,
                            "Could not open playback line:\n" + e.getMessage(),
                            "Reference Tone Error", JOptionPane.WARNING_MESSAGE);
                    });
                    return;
                }

                line.open(fmt, CHUNK * fmt.getFrameSize() * 4);
                line.start();

                // Auto-stop after 15 s
                long deadline = System.currentTimeMillis() + 15_000;
                double phase = 0;
                double phaseStep = 2.0 * Math.PI * FREQ / SAMPLE_RATE;
                byte[]  buf    = new byte[CHUNK * fmt.getFrameSize()];
                float[] floatL = new float[CHUNK]; // for direct processor injection

                while (tonePlaying && System.currentTimeMillis() < deadline) {
                    for (int i = 0; i < CHUNK; i++) {
                        float fSample = (float)(0.5 * Math.sin(phase));
                        short s = (short) (Short.MAX_VALUE * fSample);
                        int off = i * 4;         // 2 bytes per sample * 2 channels
                        buf[off    ] = (byte)(s & 0xFF);        // L lo
                        buf[off + 1] = (byte)((s >> 8) & 0xFF); // L hi
                        buf[off + 2] = buf[off];                 // R lo
                        buf[off + 3] = buf[off + 1];             // R hi
                        floatL[i] = fSample;
                        phase += phaseStep;
                        if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                    }
                    line.write(buf, 0, buf.length);
                    // Inject directly into the processor so the visualizer always
                    // responds, even if no capture/loopback source is active.
                    Consumer<float[][]> cb = onToneData;
                    if (cb != null) {
                        float[] copy = floatL.clone();
                        cb.accept(new float[][]{copy, copy});
                    }
                }

                line.drain();
                line.close();

            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(ControlPanel.this,
                        "Reference tone error:\n" + e.getMessage(),
                        "Reference Tone Error", JOptionPane.WARNING_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    tonePlaying = false;
                    btnTone.setText("🔊 Ref Tone");
                });
            }
        }, "ReferenceTone");
        toneThread.setDaemon(true);
        toneThread.start();
    }

    // -----------------------------------------------------------------------
    // Settings getters
    // -----------------------------------------------------------------------

    /** Returns the selected real audio source, or {@code null} for system default / test signal. */
    public AudioCapture.SourceInfo getSelectedSource() {
        int idx = sourceCombo.getSelectedIndex();
        if (idx <= 0 || idx >= numRealSourceEntries) return null;
        return sourceInfos.get(idx - 1);
    }

    /**
     * Returns the selected {@link TestSignalSource.Signal} if a test signal
     * is chosen, or {@code null} if a real audio device is selected.
     */
    public TestSignalSource.Signal getSelectedTestSignal() {
        int idx = sourceCombo.getSelectedIndex();
        int testBase = numRealSourceEntries + 1; // +1 skips separator
        if (idx < testBase) return null;
        int sigIdx = idx - testBase;
        TestSignalSource.Signal[] sigs = TestSignalSource.Signal.values();
        return (sigIdx >= 0 && sigIdx < sigs.length) ? sigs[sigIdx] : null;
    }

    public float             getGain()           { return gainSlider.getValue() / 100.0f; }
    public AudioProcessor.StereoMode getStereoMode() { return (AudioProcessor.StereoMode) stereoModeCombo.getSelectedItem(); }
    public int               getBufferSize()     { return (Integer) bufferSizeCombo.getSelectedItem(); }
    public VisualizationMode getMode()           { return (VisualizationMode) modeCombo.getSelectedItem(); }
    public BarStyle          getBarStyle()       { return (BarStyle) barStyleCombo.getSelectedItem(); }
    public ColorMode         getColorMode()      { return (ColorMode) colorModeCombo.getSelectedItem(); }
    public boolean           isMirror()          { return mirrorCheck.isSelected(); }
    public int               getBarCount()       { return barCountSlider.getValue(); }
    public int               getFftSize()        { return (Integer) fftSizeCombo.getSelectedItem(); }
    public WindowFunction    getWindowFunction() { return (WindowFunction) windowCombo.getSelectedItem(); }
    public float             getSmoothing()      { return smoothingSlider.getValue() / 100.0f; }
    public boolean           isPeakHold()        { return peakHoldCheck.isSelected(); }
    public float             getPeakDecay()      { return peakDecaySlider.getValue() / 5000.0f; }
    public float             getNoiseFloor()     { return noiseFloorSlider.getValue(); }
    public float             getFreqMin()        { return freqMinSlider.getValue(); }
    public float             getFreqMax()        { return freqMaxSlider.getValue(); }
    public boolean           isLogScale()        { return logScaleCheck.isSelected(); }
    public Theme             getSelectedTheme()  { return (Theme) themeCombo.getSelectedItem(); }
    public boolean           isShowGrid()        { return showGridCheck.isSelected(); }
    public boolean           isShowFreqLabels()  { return showFreqLabelsCheck.isSelected(); }
    public boolean           isShowDbScale()     { return showDbScaleCheck.isSelected(); }
    public boolean           isShowPeakLine()    { return showPeakLineCheck.isSelected(); }

    // -----------------------------------------------------------------------
    // Theme application
    // -----------------------------------------------------------------------

    public void applyTheme(Theme t) {
        currentTheme = t;
        setBackground(t.panelBackground);
        applyTabTheme(audioTab, t);
        applyTabTheme(vizTab, t);
        applyTabTheme(analysisTab, t);
        applyTabTheme(freqTab, t);
        applyTabTheme(displayTab, t);
        applyTabTheme(profilePanel, t);

        for (JComboBox<?> cb : new JComboBox[]{sourceCombo, stereoModeCombo, bufferSizeCombo,
                modeCombo, barStyleCombo, colorModeCombo, fftSizeCombo, windowCombo, themeCombo,
                profileCombo}) {
            styleCombo(cb, t);
        }

        for (JSlider sl : new JSlider[]{gainSlider, barCountSlider, smoothingSlider,
                peakDecaySlider, noiseFloorSlider, freqMinSlider, freqMaxSlider}) {
            styleSlider(sl, t);
        }

        styleButton(btnStart,        t.btnPrimary, t.btnForeground);
        styleButton(btnStop,         t.btnDanger,  t.btnForeground);
        styleButton(btnTone,         t.btnNeutral, t.btnForeground);
        styleButton(btnBrowse,       t.btnNeutral, t.btnForeground);
        styleButton(btnSaveProfile,  t.btnNeutral, t.btnForeground);
        styleButton(btnLoadProfile,  t.btnPrimary, t.btnForeground);
        styleButton(btnDeleteProfile, t.btnDanger,  t.btnForeground);

        profileNameField.setBackground(t.sectionBackground);
        profileNameField.setForeground(t.textPrimary);
        profileNameField.setCaretColor(t.textPrimary);
        profileNameField.setBorder(BorderFactory.createLineBorder(t.border));

        statusLabel.setForeground(t.textSecondary);
        statusLabel.setBackground(t.panelBackground);

        revalidate(); repaint();
    }

    private void applyTabTheme(JPanel tab, Theme t) {
        applyColors(tab, t);
    }

    private void applyColors(Container c, Theme t) {
        c.setBackground(t.panelBackground);
        for (Component ch : c.getComponents()) {
            if (ch instanceof JLabel lbl) {
                lbl.setForeground(t.textPrimary);
                lbl.setBackground(t.panelBackground);
            } else if (ch instanceof JCheckBox chk) {
                chk.setForeground(t.textPrimary);
                chk.setBackground(t.panelBackground);
            } else if (ch instanceof Container cc) {
                if (!(ch instanceof JButton)) applyColors(cc, t);
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private JPanel sectionHeader(String title) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(title);
        lbl.setFont(HDR_FONT);
        lbl.setForeground(currentTheme.textAccent);

        JSeparator sep = new JSeparator();
        sep.setForeground(currentTheme.border);

        p.add(lbl, BorderLayout.NORTH);
        p.add(sep, BorderLayout.CENTER);
        return p;
    }

    private JPanel labeledRow(String label, JComponent comp) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(LBL_FONT);
        lbl.setForeground(currentTheme.textPrimary);
        lbl.setPreferredSize(new Dimension(80, 22));

        comp.setFont(LBL_FONT);
        row.add(lbl,  BorderLayout.WEST);
        row.add(comp, BorderLayout.CENTER);
        return row;
    }

    private JPanel sliderRow(String label, JSlider slider, JLabel valLbl) {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setOpaque(false);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(LBL_FONT);
        lbl.setForeground(currentTheme.textPrimary);
        valLbl.setForeground(currentTheme.textAccent);
        top.add(lbl,    BorderLayout.WEST);
        top.add(valLbl, BorderLayout.EAST);
        outer.add(top);

        slider.setOpaque(false);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.add(slider);
        return outer;
    }

    private JPanel checkRow(JCheckBox check) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        check.setFont(LBL_FONT);
        check.setOpaque(false);
        check.setForeground(currentTheme.textPrimary);
        row.add(check);
        return row;
    }

    private static JButton makeButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(BTN_FONT);
        styleButton(btn, bg, fg);
        return btn;
    }

    private static void styleButton(JButton btn, Color bg, Color fg) {
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(4, 8, 4, 8));
    }

    private static void styleCombo(JComboBox<?> cb, Theme t) {
        cb.setBackground(t.sectionBackground);
        cb.setForeground(t.textPrimary);
        cb.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        if (cb.getRenderer() instanceof DefaultListCellRenderer r) {
            r.setBackground(t.sectionBackground);
            r.setForeground(t.textPrimary);
        }
    }

    private static void styleSlider(JSlider sl, Theme t) {
        sl.setBackground(t.panelBackground);
        sl.setForeground(t.textAccent);
    }

    private static Icon iconFor(String emoji) { return null; } // icons optional

    private static String decayLabel(int v) {
        if (v < 25)  return "slow";
        if (v < 60)  return "medium";
        if (v < 85)  return "fast";
        return "instant";
    }
}
