package jeeanalyzer.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jeeanalyzer.Model.PathRow;
import jeeanalyzer.Util;

/**
 * 旧 paths.csv（ボタン名なし）向けに、xhtml から同一タグの value を拾う。
 */
final class ControlLabelLookup {
    private static final Pattern VALUE = Pattern.compile("\\bvalue\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private ControlLabelLookup() {}

    /** tagAttr に「…」が無ければ xhtml から補完した表示用ラベルを返す */
    static String enrichTagAttr(PathRowView v) {
        String tag = v.row.tagAttr;
        if (tag == null || tag.isBlank()) return tag;
        if (tag.contains("「") && tag.contains("」")) return tag;
        if (!looksLikeControl(tag)) return tag;
        String label = lookup(v.project, v.row);
        if (label == null || label.isBlank()) return tag;
        return tag + "「" + label + "」";
    }

    private static boolean looksLikeControl(String tagAttr) {
        String t = tagAttr.toLowerCase();
        return t.contains("commandbutton") || t.contains("commandlink")
                || t.contains("@action") || t.contains("actionlistener") || t.contains("@listener");
    }

    static String lookup(Path project, PathRow row) {
        if (project == null || row == null || row.definedIn == null || row.definedIn.isBlank()) return null;
        String key = project.toAbsolutePath().normalize() + "|" + row.definedIn + "|" + row.line + "|" + nullToEmpty(row.el);
        return CACHE.computeIfAbsent(key, k -> lookupUncached(project, row));
    }

    private static String lookupUncached(Path project, PathRow row) {
        Path file = resolve(project, row.definedIn);
        if (file == null || !Files.isRegularFile(file)) return "";
        try {
            String text = Util.read(file);
            String[] lines = text.split("\n", -1);
            int idx = Math.max(0, Math.min(lines.length - 1, row.line - 1));
            int from = Math.max(0, idx - 8);
            int to = Math.min(lines.length, idx + 8);
            StringBuilder win = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) win.append('\n');
                win.append(lines[i]);
            }
            String window = win.toString();
            String tag = extractTagAround(window, row.el, row.tagAttr);
            if (tag == null) return "";
            Matcher m = VALUE.matcher(tag);
            if (!m.find()) return "";
            String val = m.group(1) != null ? m.group(1) : m.group(2);
            return val == null ? "" : val.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** EL または action 属性を含むタグ断片を切り出す */
    private static String extractTagAround(String window, String el, String tagAttr) {
        String anchor = null;
        if (el != null && !el.isBlank() && window.contains(el)) anchor = el;
        else if (tagAttr != null && tagAttr.contains("@")) {
            String attr = tagAttr;
            int at = attr.indexOf('@');
            int br = attr.indexOf('「');
            if (br > 0) attr = attr.substring(0, br);
            String a = attr.substring(at + 1).trim(); // action
            if (!a.isEmpty() && window.contains(a + "=")) anchor = a + "=";
        }
        if (anchor == null) {
            // 行付近の commandButton を拾う
            int cb = indexOfIgnoreCase(window, "commandButton");
            if (cb < 0) cb = indexOfIgnoreCase(window, "commandLink");
            if (cb < 0) return null;
            int start = window.lastIndexOf('<', cb);
            int end = window.indexOf('>', cb);
            if (start < 0 || end < 0) return null;
            return window.substring(start, end + 1);
        }
        int pos = window.indexOf(anchor);
        if (pos < 0) return null;
        int start = window.lastIndexOf('<', pos);
        int end = window.indexOf('>', pos);
        if (start < 0 || end < 0 || end < start) return null;
        return window.substring(start, end + 1);
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static Path resolve(Path project, String rel) {
        String s = rel.replace('\\', '/');
        Path p = project.resolve(s).normalize();
        if (Files.isRegularFile(p)) return p;
        String name = project.getFileName() != null ? project.getFileName().toString() : "";
        if (s.startsWith(name + "/")) {
            p = project.resolve(s.substring(name.length() + 1)).normalize();
            if (Files.isRegularFile(p)) return p;
        }
        // sample-app/src/... をプロジェクト外相対で試す
        p = Path.of(s);
        if (!p.isAbsolute()) p = Path.of(".").toAbsolutePath().resolve(s).normalize();
        return Files.isRegularFile(p) ? p : null;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
