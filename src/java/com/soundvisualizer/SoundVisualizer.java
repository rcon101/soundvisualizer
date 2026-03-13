package com.soundvisualizer;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Main application window.
 *
 * Responsibilities:
 *  - Enumerates audio sources via {@link AudioCapture}
 *  - Owns {@link AudioProcessor} and {@link VisualizationPanel}
 *  - Owns {@link ControlPanel} (right sidebar)
 *  - Drives a ~60 fps Swing {@link Timer} that pushes processed audio to the viz panel
 *  - Wires all ControlPanel callbacks to processor / visualizer settings
 */
public class SoundVisualizer extends JFrame {

    private static final int TARGET_FPS = 60;

    // -----------------------------------------------------------------------
    // Core components
    // -----------------------------------------------------------------------
    private AudioCapture       capture;
    private TestSignalSource   testSignalSource;
    private AudioFilePlayer    audioFilePlayer;
    private AudioProcessor     processor;
    private final VisualizationPanel vizPanel;
    private final ControlPanel       ctrlPanel;
    private Timer                    renderTimer;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public SoundVisualizer() {
        super("Sound Visualizer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));

        CaptureLogger.section("SoundVisualizer startup");
        CaptureLogger.info("java.version=" + System.getProperty("java.version")
            + "  os=" + System.getProperty("os.name")
            + "  user=" + System.getProperty("user.name"));

        // Enumerate audio sources
        List<AudioCapture.SourceInfo> sources = AudioCapture.getAvailableSources();
        CaptureLogger.info("Sources found: " + sources.size());
        for (var s : sources) CaptureLogger.info("  source: " + s.label
            + (s.isWasapi() ? " [WASAPI id=" + s.wasapiDeviceId + "]" : "")
            + (s.isPulse()  ? " [Pulse]" : "")
            + (s.mixerInfo != null ? " [Mixer]" : ""));

        // Create processor with defaults
        processor = new AudioProcessor();

        // Create UI panels
        Theme defaultTheme = Themes.DEFAULT;
        vizPanel  = new VisualizationPanel(defaultTheme);
        ctrlPanel = new ControlPanel(sources, defaultTheme);

        add(vizPanel,  BorderLayout.CENTER);
        add(ctrlPanel, BorderLayout.EAST);

        // Wire control panel events
        wireControls();

        // Apply initial settings
        applyAllSettings();

        // Set initial status
        boolean isWindows = WindowsAudioSessions.isWindows();
        boolean isWsl = System.getenv("WSL_DISTRO_NAME") != null
                     || System.getenv("WSL_INTEROP") != null;
        if (isWindows) {
            vizPanel.setStatusText("Select a source in the Audio tab and press \u25b6 Start"
                + " \u2013 use \u2018System Audio\u2019 to capture all playing apps");
        } else if (isWsl && sources.isEmpty()) {
            vizPanel.setStatusText(
                "WSL2 detected: select a Test Signal source, or run with 'make native' for Windows audio");
        } else {
            vizPanel.setStatusText("Select a source in the Audio tab and press  ▶ Start");
        }

        // Build window
        pack();
        setMinimumSize(new Dimension(1000, 600));
        setPreferredSize(new Dimension(1280, 720));
        pack();
        setLocationRelativeTo(null);
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void display() {
        setVisible(true);
        startRenderTimer();
    }

    // -----------------------------------------------------------------------
    // Control wiring
    // -----------------------------------------------------------------------

    private void wireControls() {
        ctrlPanel.onStart(this::startCapture);
        ctrlPanel.onStop(this::stopCapture);
        ctrlPanel.onThemeChange(this::applyTheme);
        ctrlPanel.onSettingsChange(this::applyAllSettings);
        // Feed reference-tone samples directly to the processor so the visualizer
        // always reacts, independent of which capture source is selected.
        ctrlPanel.onToneData(processor::process);
    }

    // -----------------------------------------------------------------------
    // Audio capture lifecycle
    // -----------------------------------------------------------------------

    private void startCapture() {
        stopCapture();
        CaptureLogger.section("startCapture");
        if (ctrlPanel.isAudioFileSource()) {
            File file = ctrlPanel.getSelectedAudioFile();
            if (file == null) {
                JOptionPane.showMessageDialog(this,
                    "No audio file selected.\nUse the Browse\u2026 button in the Audio tab to pick a file.",
                    "No File Selected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            startAudioFile(file);
            return;
        }

        // Check if the user selected a built-in test signal
        TestSignalSource.Signal testSig = ctrlPanel.getSelectedTestSignal();
        if (testSig != null) {
            startTestSignal(testSig);
            return;
        }

        // Real audio capture
        AudioCapture.SourceInfo sourceInfo = ctrlPanel.getSelectedSource();
        int bufferSize                     = ctrlPanel.getBufferSize();
        CaptureLogger.info("startCapture: source=\"" + (sourceInfo != null ? sourceInfo.label : "default")
            + "\" wasapi=" + (sourceInfo != null && sourceInfo.isWasapi())
            + " bufferFrames=" + bufferSize);

        capture = new AudioCapture(sourceInfo);
        capture.setBufferFrames(bufferSize);
        capture.addListener(processor::process);
        capture.setWasapiErrorCallback(msg -> javax.swing.SwingUtilities.invokeLater(() -> {
            ctrlPanel.setRunning(false);
            ctrlPanel.setStatus("Capture error");
            vizPanel.setStatusText("System audio capture failed \u2013 see error dialog");
            JOptionPane.showMessageDialog(SoundVisualizer.this, msg,
                "System Audio Capture Error", JOptionPane.ERROR_MESSAGE);
        }));

        try {
            capture.start();
            ctrlPanel.setRunning(true);
            String srcName = sourceInfo != null ? sourceInfo.label : "System Default";
            ctrlPanel.setStatus("Capturing: " + srcName);
            vizPanel.setStatusText("\u25cf Capturing: " + srcName);            vizPanel.setCapturing(true, srcName);        } catch (LineUnavailableException ex) {
            capture = null;
            boolean isWsl = System.getenv("WSL_DISTRO_NAME") != null
                    || System.getenv("WSL_INTEROP") != null;
            String wslHint = isWsl
                ? "\n\nYou appear to be running in WSL2.  The Linux JVM cannot"
                + "\naccess Windows audio devices directly.  Try:\n"
                + "  • Run with: make native   (uses the Windows JDK)\n"
                + "  • Or select a Test Signal source above to verify the visualizer"
                : "\n\nTips:\n"
                + "  • On Linux: install pavucontrol and enable monitor sources\n"
                + "  • On PulseAudio: ensure 'Monitor' sources are not muted\n"
                + "  • Try a different source in the Audio tab\n"
                + "  • Select a Test Signal source to verify the visualizer works";
            JOptionPane.showMessageDialog(this,
                "Could not open audio source:\n  " + ex.getMessage() + wslHint,
                "Audio Error", JOptionPane.ERROR_MESSAGE);
            ctrlPanel.setRunning(false);
            ctrlPanel.setStatus("Error – see Audio tab");
            vizPanel.setStatusText("Audio error – try a Test Signal source or run with 'make native'");
        }
    }

    private void startTestSignal(TestSignalSource.Signal sig) {
        testSignalSource = new TestSignalSource(sig);
        testSignalSource.addListener(processor::process);
        testSignalSource.start();
        ctrlPanel.setRunning(true);
        ctrlPanel.setStatus("Test signal: " + sig.displayName);
        vizPanel.setStatusText("\u25cf " + sig.displayName + " – " + sig.description);
        vizPanel.setCapturing(true, sig.displayName);
    }
    private void startAudioFile(File file) {
        if (!AudioFilePlayer.isFfmpegAvailable()) {
            boolean isWindows = File.separatorChar == '\\';
            String installHint = isWindows
                ? "Download the Windows build from https://ffmpeg.org/download.html\n"
                + "and add the bin\\ folder to your system PATH."
                : "Install it with:  sudo apt install ffmpeg";
            JOptionPane.showMessageDialog(this,
                "ffmpeg is required to play audio files but was not found on PATH.\n"
                + installHint,
                "ffmpeg Not Found", JOptionPane.ERROR_MESSAGE);
            ctrlPanel.setRunning(false);
            return;
        }
        audioFilePlayer = new AudioFilePlayer(file);
        audioFilePlayer.setBufferFrames(ctrlPanel.getBufferSize());
        audioFilePlayer.addListener(processor::process);
        try {
            audioFilePlayer.start();
            ctrlPanel.setRunning(true);
            String name = file.getName();
            ctrlPanel.setStatus("Playing: " + name);
            vizPanel.setStatusText("\u25b6 Playing: " + name);
            vizPanel.setCapturing(true, name);
            // Watch for natural playback completion in a background thread
            Thread watcher = new Thread(() -> {
                while (audioFilePlayer != null && audioFilePlayer.isRunning()) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                }
                SwingUtilities.invokeLater(() -> {
                    if (audioFilePlayer != null) {
                        String err = audioFilePlayer.getErrorMessage();
                        audioFilePlayer = null;
                        ctrlPanel.setRunning(false);
                        vizPanel.setCapturing(false, "");
                        if (err != null) {
                            ctrlPanel.setStatus("Playback error");
                            vizPanel.setStatusText("Playback error \u2013 see dialog");
                            JOptionPane.showMessageDialog(SoundVisualizer.this,
                                err, "Playback Error", JOptionPane.ERROR_MESSAGE);
                        } else {
                            ctrlPanel.setStatus("Playback complete");
                            vizPanel.setStatusText(
                                "Playback complete \u2013 press \u25b6 Start to replay or pick another source");
                        }
                    }
                });
            }, "AudioFilePlayer-watcher");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException ex) {
            audioFilePlayer = null;
            JOptionPane.showMessageDialog(this,
                "Could not play audio file:\n  " + ex.getMessage()
                + "\n\nEnsure ffmpeg is installed and the file is a supported audio format.",
                "Audio File Error", JOptionPane.ERROR_MESSAGE);
            ctrlPanel.setRunning(false);
            ctrlPanel.setStatus("Error \u2013 see console");
            vizPanel.setStatusText("Audio file error");
        }
    }
    private void stopCapture() {
        CaptureLogger.info("stopCapture()");
        if (audioFilePlayer != null) {
            audioFilePlayer.stop();
            audioFilePlayer = null;
        }
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        if (testSignalSource != null) {
            testSignalSource.stop();
            testSignalSource = null;
        }
        ctrlPanel.setRunning(false);
        ctrlPanel.setStatus("Stopped");
        vizPanel.setStatusText("Select a source in the Audio tab and press Start");
        vizPanel.setCapturing(false, "");
    }

    // -----------------------------------------------------------------------
    // Render timer
    // -----------------------------------------------------------------------

    private void startRenderTimer() {
        if (renderTimer != null) renderTimer.stop();
        renderTimer = new Timer(1000 / TARGET_FPS, e -> pushFrame());
        renderTimer.setCoalesce(true);
        renderTimer.start();
    }

    private void pushFrame() {
        float[] spec     = processor.getSpectrum();
        float[] peaks    = processor.getPeaks();
        float[] barFreqs = processor.getBarFrequencies();
        float[] waveL    = processor.getWaveformL();
        float[] waveR    = processor.getWaveformR();
        float[] lissL    = processor.getLissajousL();
        float[] lissR    = processor.getLissajousR();
        float[][] sg     = processor.getSpectrogram();

        vizPanel.update(spec, peaks, barFreqs,
                        waveL, waveR, lissL, lissR, sg,
                        processor.getVuLeft(),  processor.getVuRight(),
                        processor.getPeakVuLeft(), processor.getPeakVuRight());
    }

    // -----------------------------------------------------------------------
    // Settings propagation
    // -----------------------------------------------------------------------

    private void applyAllSettings() {
        // Processor settings
        processor.setGain(ctrlPanel.getGain());
        processor.setFftSize(ctrlPanel.getFftSize());
        processor.setWindowFunction(ctrlPanel.getWindowFunction());
        processor.setSmoothing(ctrlPanel.getSmoothing());
        processor.setPeakHold(ctrlPanel.isPeakHold());
        processor.setPeakDecay(ctrlPanel.getPeakDecay());
        processor.setNoiseFloor(ctrlPanel.getNoiseFloor());
        processor.setFreqMin(ctrlPanel.getFreqMin());
        processor.setFreqMax(ctrlPanel.getFreqMax());
        processor.setLogScale(ctrlPanel.isLogScale());
        processor.setBarCount(ctrlPanel.getBarCount());
        processor.setStereoMode(ctrlPanel.getStereoMode());

        // Visualization settings
        vizPanel.setMode(ctrlPanel.getMode());
        vizPanel.setBarStyle(ctrlPanel.getBarStyle());
        vizPanel.setColorMode(ctrlPanel.getColorMode());
        vizPanel.setMirrorSpectrum(ctrlPanel.isMirror());
        vizPanel.setShowGrid(ctrlPanel.isShowGrid());
        vizPanel.setShowFreqLabels(ctrlPanel.isShowFreqLabels());
        vizPanel.setShowDbScale(ctrlPanel.isShowDbScale());
        vizPanel.setShowPeakLine(ctrlPanel.isShowPeakLine());
    }

    private void applyTheme(Theme theme) {
        vizPanel.applyTheme(theme);
        ctrlPanel.applyTheme(theme);
        getContentPane().setBackground(theme.background);
        SwingUtilities.updateComponentTreeUI(this);
        repaint();
    }

    // -----------------------------------------------------------------------
    // Shutdown hook
    // -----------------------------------------------------------------------

    @Override
    public void dispose() {
        stopCapture();
        if (renderTimer != null) renderTimer.stop();
        super.dispose();
    }
}

