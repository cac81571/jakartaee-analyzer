package jeeanalyzer.gui;

import javax.swing.*;

import com.formdev.flatlaf.FlatLightLaf;

/** FlatLaf Swing GUI 入口 */
public final class GuiApp {
    private GuiApp() {}

    public static void launch() {
        FlatLightLaf.setup();
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            AnalyzerFrame frame = new AnalyzerFrame();
            frame.setVisible(true);
        });
    }
}
