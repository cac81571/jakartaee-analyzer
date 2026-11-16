package jeeanalyzer.gui;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.*;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import jeeanalyzer.Util;

/** ソース表示（行番号・シンタックスハイライト・該当行強調） */
final class SourcePane extends JPanel {
    private final JLabel title = new JLabel(" ");
    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll;
    private Object lineHighlightTag;
    private Timer lineFocusRetry;
    private int lineFocusSeq;
    private static final Color LINE_HL = new Color(255, 235, 150);

    SourcePane() {
        super(new BorderLayout(4, 4));
        title.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        area.setEditable(false);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setTabSize(4);
        area.setHighlightCurrentLine(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        try {
            Theme theme = Theme.load(getClass().getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/default.xml"));
            theme.apply(area);
        } catch (Exception ignore) { }

        scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(true);

        add(title, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    void clear() {
        title.setText("ソース未選択");
        clearLineHighlight();
        area.setText("");
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    void showFile(Path project, String relativeOrSource, int line) {
        if (relativeOrSource == null || relativeOrSource.isBlank()) {
            clear();
            title.setText("ソースパスなし");
            return;
        }
        Path resolved = resolve(project, relativeOrSource);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            title.setText("見つかりません: " + relativeOrSource);
            clearLineHighlight();
            area.setText("");
            return;
        }
        try {
            String text = Util.read(resolved);
            title.setText(resolved + (line > 0 ? "  (行 " + line + ")" : ""));
            area.setSyntaxEditingStyle(syntaxFor(resolved));
            area.setText(text);
            // setText 直後は行数が未反映のことがあり、1 回の invokeLater では外れることがある
            scheduleLineFocus(line);
        } catch (Exception e) {
            title.setText("読込失敗: " + resolved);
            clearLineHighlight();
            area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            area.setText(e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private void scheduleLineFocus(int line) {
        if (lineFocusRetry != null) {
            lineFocusRetry.stop();
            lineFocusRetry = null;
        }
        if (line <= 0) {
            clearLineHighlight();
            return;
        }
        final int target = line;
        final int seq = ++lineFocusSeq;
        Runnable apply = () -> {
            if (seq != lineFocusSeq) return; // 新しい選択で無効化
            highlightLine(target);
            scrollToLineNow(target);
        };
        SwingUtilities.invokeLater(() -> {
            if (seq != lineFocusSeq) return;
            apply.run();
            // レイアウト／折りたたみ解析後にもう一度（間欠的な未選択を防ぐ）
            SwingUtilities.invokeLater(apply);
            lineFocusRetry = new Timer(80, e -> apply.run());
            lineFocusRetry.setRepeats(false);
            lineFocusRetry.start();
        });
    }

    private static String syntaxFor(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".java")) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if (name.endsWith(".xhtml") || name.endsWith(".xml") || name.endsWith(".xsl") || name.endsWith(".xslt"))
            return SyntaxConstants.SYNTAX_STYLE_XML;
        if (name.endsWith(".html") || name.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (name.endsWith(".sql")) return SyntaxConstants.SYNTAX_STYLE_SQL;
        if (name.endsWith(".js")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        if (name.endsWith(".css")) return SyntaxConstants.SYNTAX_STYLE_CSS;
        if (name.endsWith(".json")) return SyntaxConstants.SYNTAX_STYLE_JSON;
        if (name.endsWith(".properties")) return SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
        if (name.endsWith(".md")) return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    private Path resolve(Path project, String loc) {
        String s = loc.trim();
        int colon = s.lastIndexOf(':');
        if (colon > 1) {
            String maybeLine = s.substring(colon + 1).trim();
            int sp = maybeLine.indexOf(' ');
            if (sp > 0) maybeLine = maybeLine.substring(0, sp);
            if (maybeLine.matches("\\d+")) s = s.substring(0, colon);
        }
        s = s.replace('\\', '/');
        Path abs = PathsSafe(s);
        if (abs != null && Files.isRegularFile(abs)) return abs;
        if (project != null) {
            Path p = project.resolve(s).normalize();
            if (Files.isRegularFile(p)) return p;
            String name = project.getFileName() != null ? project.getFileName().toString() : "";
            if (s.startsWith(name + "/")) {
                p = project.resolve(s.substring(name.length() + 1)).normalize();
                if (Files.isRegularFile(p)) return p;
            }
        }
        return abs;
    }

    private static Path PathsSafe(String s) {
        try {
            Path p = Path.of(s);
            return p.isAbsolute() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void clearLineHighlight() {
        if (lineHighlightTag != null) {
            area.removeLineHighlight(lineHighlightTag);
            lineHighlightTag = null;
        }
    }

    private void highlightLine(int line) {
        clearLineHighlight();
        if (line <= 0) return;
        try {
            lineHighlightTag = area.addLineHighlight(line - 1, LINE_HL);
        } catch (Exception ignore) { }
    }

    private void scrollToLineNow(int line) {
        if (line <= 0) return;
        try {
            int max = area.getLineCount();
            if (max <= 0) return;
            int idx = Math.min(Math.max(1, line), max) - 1;
            int offset = area.getLineStartOffset(idx);
            int end = area.getLineEndOffset(idx);
            String doc = area.getText();
            if (end > offset && end - 1 < doc.length() && doc.charAt(end - 1) == '\n') end--;
            area.setCaretPosition(offset);
            area.moveCaretPosition(Math.max(offset, end)); // 行選択
            area.getCaret().setSelectionVisible(true);
            area.requestFocusInWindow();
            try {
                Rectangle r = area.modelToView2D(offset).getBounds();
                r.y = Math.max(0, r.y - 80);
                r.height = Math.max(r.height, 60);
                area.scrollRectToVisible(r);
            } catch (Exception ignoreLayout) {
                // 未レイアウト時は選択だけ残す
            }
        } catch (Exception ignore) { }
    }

    static int parseLineFromSource(String sqlSource) {
        if (sqlSource == null) return 0;
        int colon = sqlSource.lastIndexOf(':');
        if (colon < 0) return 0;
        String rest = sqlSource.substring(colon + 1).trim();
        int sp = rest.indexOf(' ');
        if (sp > 0) rest = rest.substring(0, sp);
        try { return Integer.parseInt(rest); } catch (NumberFormatException e) { return 0; }
    }
}
