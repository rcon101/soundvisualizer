package com.soundvisualizer;

import javax.swing.*;

public class AppSoundVisualizer {

    public static void main(String[] args) {
        // Use system look-and-feel as a base for platform integration
        System.out.println("stdout: " + System.out);
        System.out.println("stderr: " + System.err);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SoundVisualizer window = new SoundVisualizer();
            window.display();
        });
    }
}

