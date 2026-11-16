package jeeanalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import jeeanalyzer.Model.PathRow;

/** paths.csv の読み書き（部分解析時のマージ用） */
public final class PathCsv {
    private PathCsv() {}

    public static List<PathRow> read(Path csv) throws IOException {
        if (!Files.isRegularFile(csv)) return new ArrayList<>();
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<String> lines = text.lines().toList();
        if (lines.isEmpty()) return new ArrayList<>();
        List<PathRow> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            List<String> cols = parseCsvLine(line);
            if (cols.size() < 12) continue;
            out.add(fromCells(cols));
        }
        return out;
    }

    static PathRow fromCells(List<String> c) {
        PathRow r = new PathRow();
        r.screen = get(c, 0);
        r.definedIn = get(c, 1);
        try { r.line = Integer.parseInt(get(c, 2)); } catch (NumberFormatException e) { r.line = 0; }
        r.tagAttr = get(c, 3);
        r.el = get(c, 4);
        r.kind = get(c, 5);
        r.beanName = get(c, 6);
        r.beanMethod = get(c, 7);
        String svc = get(c, 8);
        if (!svc.isBlank()) {
            for (String p : svc.split("\\s*>\\s*")) if (!p.isBlank()) r.services.add(p.trim());
        }
        r.mapperMethod = get(c, 9);
        r.stmtType = get(c, 10);
        r.tables = get(c, 11);
        r.sqlSource = get(c, 12);
        r.note = get(c, 13);
        return r;
    }

    private static String get(List<String> c, int i) {
        return i < c.size() && c.get(i) != null ? c.get(i) : "";
    }

    /** RFC 風の簡易 CSV パーサ（ダブルクォート対応） */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQ = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') inQ = true;
                else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 更新画面の行を差し替えてマージ（順序: 既存のまま → 末尾に新規画面） */
    static List<PathRow> mergeByScreen(List<PathRow> existing, List<PathRow> updated, Set<String> updatedScreens) {
        Set<String> screens = new LinkedHashSet<>();
        for (String s : updatedScreens) if (s != null) screens.add(normalizeScreen(s));

        List<PathRow> out = new ArrayList<>();
        Set<String> seenOrder = new LinkedHashSet<>();
        for (PathRow r : existing) {
            String scr = normalizeScreen(r.screen);
            if (screens.contains(scr)) continue;
            out.add(r);
            seenOrder.add(scr);
        }
        // 更新分を画面順に追加（既存に無かった画面は末尾）
        Map<String, List<PathRow>> by = new LinkedHashMap<>();
        for (PathRow r : updated) by.computeIfAbsent(normalizeScreen(r.screen), k -> new ArrayList<>()).add(r);
        for (Map.Entry<String, List<PathRow>> e : by.entrySet()) {
            out.addAll(e.getValue());
            seenOrder.add(e.getKey());
        }
        return out;
    }

    static String normalizeScreen(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\\', '/');
        if (!t.startsWith("/")) t = "/" + t;
        return t;
    }
}
