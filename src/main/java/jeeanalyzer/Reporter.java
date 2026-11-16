package jeeanalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import jeeanalyzer.Main.RefTrace;
import jeeanalyzer.Main.Screen;
import jeeanalyzer.Model.*;

/** CSV / TSV / Markdown(Mermaid) 出力 */
final class Reporter {
    final Path out;
    final JavaIndex java;
    final MapperIndex mappers;
    final Tracer tracer;

    static final String[] HEADER = {"画面", "定義ファイル", "行", "タグ@属性", "EL式", "参照種別", "Bean名", "Beanクラス#メソッド",
            "Service呼び出し(経由順)", "Mapper#メソッド", "SQL種別", "テーブル", "SQL定義元", "備考"};

    Reporter(Path out, JavaIndex java, MapperIndex mappers, Tracer tracer) {
        this.out = out;
        this.java = java;
        this.mappers = mappers;
        this.tracer = tracer;
    }

    /**
     * @param merge true のとき既存 paths.csv と画面単位でマージし、未更新画面の screens/*.md は残す
     * @return 最終的に書き出したパス行（マージ後）
     */
    List<PathRow> write(List<Screen> screens, List<Facelet> facelets, List<Issue> issues, boolean merge) throws IOException {
        Files.createDirectories(out.resolve("screens"));
        List<PathRow> newRows = new ArrayList<>();
        for (Screen s : screens) newRows.addAll(s.rows);

        Set<String> updated = new LinkedHashSet<>();
        for (Screen s : screens) updated.add(PathCsv.normalizeScreen(s.facelet.webPath));

        List<PathRow> finalRows;
        if (merge && Files.isRegularFile(out.resolve("paths.csv"))) {
            List<PathRow> existing = PathCsv.read(out.resolve("paths.csv"));
            finalRows = PathCsv.mergeByScreen(existing, newRows, updated);
        } else {
            finalRows = newRows;
        }

        writePathRows(finalRows);
        writeIssues(issues, screens, merge);
        writeCrudFromRows(finalRows);
        for (Screen s : screens) writeScreen(s, issues);
        writeIndexMerged(screens, facelets, issues, finalRows, merge);
        return finalRows;
    }

    /* ---------------- CSV / TSV ---------------- */

    private static String[] cells(PathRow r) {
        return new String[]{r.screen, r.definedIn, String.valueOf(r.line), r.tagAttr, r.el, r.kind, r.beanName, r.beanMethod,
                String.join(" > ", r.services), r.mapperMethod, r.stmtType, r.tables, r.sqlSource, r.note};
    }

    private void writePathRows(List<PathRow> rows) throws IOException {
        List<String[]> cells = new ArrayList<>();
        for (PathRow r : rows) cells.add(cells(r));
        writeCsv(out.resolve("paths.csv"), HEADER, cells);
        writeTsv(out.resolve("paths.tsv"), HEADER, cells);
    }

    static void writeCsv(Path p, String[] header, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder("\uFEFF"); // Excel で文字化けしないよう BOM 付き UTF-8
        sb.append(csvLine(header));
        for (String[] r : rows) sb.append(csvLine(r));
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String csvLine(String[] r) {
        return Arrays.stream(r).map(c -> "\"" + (c == null ? "" : c).replace("\"", "\"\"") + "\"").collect(Collectors.joining(",")) + "\r\n";
    }

    static void writeTsv(Path p, String[] header, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", header)).append('\n');
        for (String[] r : rows)
            sb.append(Arrays.stream(r).map(c -> c == null ? "" : c.replace('\t', ' ').replace('\n', ' ')).collect(Collectors.joining("\t"))).append('\n');
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeIssues(List<Issue> issues, List<Screen> screens, boolean merge) throws IOException {
        List<Issue> finalIssues = issues;
        if (merge && Files.isRegularFile(out.resolve("unresolved.csv"))) {
            Set<String> touchPrefixes = new HashSet<>();
            for (Screen s : screens) {
                for (Facelet f : s.files) {
                    if (f.rel != null) touchPrefixes.add(f.rel.replace('\\', '/'));
                }
            }
            List<Issue> old = readUnresolvedCsv(out.resolve("unresolved.csv"));
            List<Issue> kept = new ArrayList<>();
            for (Issue i : old) {
                String loc = i.location() == null ? "" : i.location().replace('\\', '/');
                boolean touched = touchPrefixes.stream().anyMatch(p -> loc.equals(p) || loc.startsWith(p + ":"));
                if (!touched) kept.add(i);
            }
            // グローバル系（Mapper/Java）は今回の結果で置き換え
            Set<String> globalCats = Set.of(
                    "Mapper XML: インターフェースなし", "Mapper XML: インターフェースにメソッドなし",
                    "Mapper: SQL未定義");
            kept.removeIf(i -> globalCats.contains(i.category()));
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            finalIssues = new ArrayList<>();
            for (Issue i : kept) {
                String key = i.category() + "|" + i.location() + "|" + i.target() + "|" + i.message();
                if (seen.add(key)) finalIssues.add(i);
            }
            for (Issue i : issues) {
                String key = i.category() + "|" + i.location() + "|" + i.target() + "|" + i.message();
                if (seen.add(key)) finalIssues.add(i);
            }
        }

        List<String[]> rows = finalIssues.stream().map(i -> new String[]{i.category(), i.location(), i.target(), i.message()}).toList();
        writeCsv(out.resolve("unresolved.csv"), new String[]{"種別", "場所", "対象", "内容"}, rows);
        StringBuilder md = new StringBuilder("# 未解決・要確認の参照一覧\n\n");
        md.append("件数: ").append(finalIssues.size()).append("\n\n");
        Map<String, List<Issue>> byCat = new LinkedHashMap<>();
        finalIssues.forEach(i -> byCat.computeIfAbsent(i.category(), k -> new ArrayList<>()).add(i));
        md.append("| 種別 | 件数 |\n|---|---:|\n");
        byCat.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v.size()).append(" |\n"));
        byCat.forEach((k, v) -> {
            md.append("\n## ").append(k).append("\n\n| 場所 | 対象 | 内容 |\n|---|---|---|\n");
            for (Issue i : v) md.append("| ").append(esc(i.location())).append(" | ").append(code(i.target())).append(" | ").append(esc(i.message())).append(" |\n");
        });
        List<SqlStatement> unused = mappers.statements.values().stream().filter(s -> !tracer.usedStatements.contains(s.key())).toList();
        md.append("\n## 参考: どの画面からも到達しない SQL ステートメント\n\n");
        md.append("バッチや Web API など xhtml 以外から呼ばれている可能性もあります。\n");
        if (merge) md.append("（部分解析時は今回解析した画面からの到達のみで判定しています）\n");
        md.append("\n| Mapper#ID | 種別 | テーブル | 定義元 |\n|---|---|---|---|\n");
        for (SqlStatement s : unused)
            md.append("| ").append(simple(s.namespace)).append("#").append(s.id).append(" | ").append(s.type).append(" | ")
              .append(esc(String.join(", ", s.tables))).append(" | ").append(esc(s.source)).append(" |\n");
        Files.writeString(out.resolve("unresolved.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static List<Issue> readUnresolvedCsv(Path csv) throws IOException {
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<Issue> out = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            List<String> cols = PathCsv.parseCsvLine(line);
            if (cols.size() < 4) continue;
            out.add(new Issue(cols.get(0), cols.get(1), cols.get(2), cols.get(3)));
        }
        return out;
    }

    /* ---------------- CRUD ---------------- */

    static String crudLetter(String type) {
        return switch (type) { case "insert" -> "C"; case "select" -> "R"; case "update" -> "U"; case "delete" -> "D"; default -> "?"; };
    }

    private Map<String, Map<String, Set<String>>> crudFromRows(List<PathRow> pathRows) {
        Map<String, Map<String, Set<String>>> m = new LinkedHashMap<>();
        for (PathRow r : pathRows) {
            Map<String, Set<String>> row = m.computeIfAbsent(r.screen, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
            if (r.stmtType == null || r.stmtType.isEmpty()) continue;
            for (String t : r.tables.split(",\\s*")) if (!t.isBlank())
                row.computeIfAbsent(t, k -> new TreeSet<>(Comparator.comparing("CRUD"::indexOf))).add(crudLetter(r.stmtType));
        }
        return m;
    }

    private Set<String> allTables(Map<String, Map<String, Set<String>>> m) {
        Set<String> t = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        m.values().forEach(r -> t.addAll(r.keySet()));
        return t;
    }

    private void writeCrudFromRows(List<PathRow> pathRows) throws IOException {
        var m = crudFromRows(pathRows);
        List<String> tables = new ArrayList<>(allTables(m));
        List<String> header = new ArrayList<>(List.of("画面"));
        header.addAll(tables);
        List<String[]> rows = new ArrayList<>();
        m.forEach((scr, r) -> {
            List<String> line = new ArrayList<>(List.of(scr));
            for (String t : tables) line.add(r.containsKey(t) ? String.join("", r.get(t)) : "");
            rows.add(line.toArray(String[]::new));
        });
        writeCsv(out.resolve("crud.csv"), header.toArray(String[]::new), rows);
    }

    /* ---------------- Markdown ---------------- */

    static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    static String code(String s) {
        return s == null || s.isEmpty() ? "" : "`" + s.replace("|", "\\|").replace("`", "'") + "`";
    }

    static String simple(String fqn) {
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }

    static String screenFile(Screen s) {
        String w = s.facelet.webPath.replaceFirst("^/", "").replaceFirst("\\.xhtml$", "");
        return w.replace('/', '_') + ".md";
    }

    private void writeScreen(Screen s, List<Issue> issues) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# 画面: ").append(s.facelet.webPath).append("\n\n");
        md.append("- ファイル: `").append(s.facelet.rel).append("`\n");
        if (!s.fileEdges.isEmpty()) {
            md.append("- テンプレート / インクルード:\n");
            for (String[] e : s.fileEdges) md.append("  - ").append(e[2]).append(": `").append(e[1]).append("`（`").append(e[0]).append("` から）\n");
        }
        Map<String, ClassInfo> beans = new LinkedHashMap<>();
        for (RefTrace rt : s.refs) if (rt.bean != null) beans.putIfAbsent(rt.bean.beanName != null ? rt.bean.beanName : rt.bean.simpleName, rt.bean);
        if (!beans.isEmpty()) {
            md.append("- 使用 Bean:\n");
            beans.forEach((n, c) -> md.append("  - `").append(n).append("` → ").append(c.fqn).append("（@").append(c.scope).append("）\n"));
        }
        md.append("\n## 呼び出しフロー\n\n");
        md.append("メソッド式と、下流（Service/Mapper）の呼び出しがあるプロパティのみ図示しています。全参照は下の表を参照してください。\n\n");
        md.append("```mermaid\n").append(mermaid(s)).append("```\n");

        md.append("\n## 呼び出しパス一覧\n\n| 定義ファイル:行 | タグ@属性 | EL式 | Bean#メソッド | Service | Mapper#メソッド | SQL | テーブル | 備考 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (PathRow r : s.rows) {
            md.append("| ").append(esc(r.definedIn)).append(":").append(r.line).append(" | ").append(esc(r.tagAttr)).append(" | ")
              .append(code(r.el)).append(" | ").append(esc(r.beanMethod)).append(" | ").append(esc(String.join(" → ", r.services)))
              .append(" | ").append(esc(r.mapperMethod)).append(" | ").append(r.stmtType).append(" | ").append(esc(r.tables))
              .append(" | ").append(esc(r.note)).append(" |\n");
        }

        // 使用 SQL
        LinkedHashMap<String, SqlStatement> stmts = new LinkedHashMap<>();
        for (RefTrace rt : s.refs) collectStmts(rt.tree, stmts);
        if (!stmts.isEmpty()) {
            md.append("\n## 使用 SQL\n");
            for (SqlStatement st : stmts.values()) {
                md.append("\n### ").append(simple(st.namespace)).append("#").append(st.id).append("（").append(st.type)
                  .append(st.dynamic ? "・動的SQL" : "").append("）\n\n");
                md.append("- 定義元: `").append(st.source).append("`\n- テーブル: ").append(String.join(", ", st.tables)).append("\n\n");
                md.append("```sql\n").append(st.sql).append("\n```\n");
            }
        }
        // 画面に関する未解決
        Set<String> files = s.files.stream().map(f -> f.rel).collect(Collectors.toSet());
        List<Issue> mine = issues.stream().filter(i -> files.stream().anyMatch(f -> i.location().startsWith(f + ":"))).toList();
        List<String> noSql = s.rows.stream().filter(r -> r.note.contains("SQL 定義なし")).map(r -> r.mapperMethod).distinct().toList();
        if (!mine.isEmpty() || !noSql.isEmpty()) {
            md.append("\n## 未解決・要確認\n\n");
            for (Issue i : mine) md.append("- **").append(i.category()).append("** ").append(i.location()).append(" ").append(code(i.target())).append(": ").append(esc(i.message())).append("\n");
            for (String m : noSql) md.append("- **Mapper: SQL未定義** ").append(code(m)).append(" に SQL 定義がありません\n");
        }
        Files.writeString(out.resolve("screens").resolve(screenFile(s)), md.toString(), StandardCharsets.UTF_8);
    }

    private void collectStmts(List<CallNode> nodes, Map<String, SqlStatement> out) {
        for (CallNode n : nodes) {
            if (n.stmt != null) out.putIfAbsent(n.stmt.key(), n.stmt);
            collectStmts(n.children, out);
        }
    }

    /* ---------------- Mermaid ---------------- */

    private static final class Graph {
        final Map<String, String> ids = new LinkedHashMap<>();
        final List<String> nodes = new ArrayList<>();
        final LinkedHashSet<String> edges = new LinkedHashSet<>();
        final Map<String, String> classes = new LinkedHashMap<>();

        String node(String key, String label, String shapeOpen, String shapeClose, String cls) {
            return ids.computeIfAbsent(key, k -> {
                String id = "n" + ids.size();
                nodes.add("    " + id + shapeOpen + "\"" + label.replace("\"", "#quot;") + "\"" + shapeClose);
                classes.put(id, cls);
                return id;
            });
        }

        void edge(String a, String b, String label, boolean dotted) {
            String arrow = dotted ? "-.->" : "-->";
            edges.add("    " + a + " " + arrow + (label == null || label.isEmpty() ? "" : "|\"" + label + "\"|") + " " + b);
        }
    }

    String mermaid(Screen s) {
        Graph g = new Graph();
        Map<String, String> fileIds = new HashMap<>();
        for (Facelet f : s.files) {
            String name = f.webPath;
            boolean isScreen = f == s.facelet;
            fileIds.put(f.rel, g.node("F:" + f.rel, name, isScreen ? "[[" : "[/", isScreen ? "]]" : "/]", isScreen ? "screen" : "partial"));
        }
        for (String[] e : s.fileEdges) g.edge(fileIds.get(e[0]), fileIds.get(e[1]), e[2], true);
        for (RefTrace rt : s.refs) {
            boolean show = rt.el == null || "メソッド".equals(rt.el.kind) || !rt.tree.isEmpty() || rt.el.problem != null;
            if (!show) continue;
            String from = fileIds.get(rt.file.rel);
            String target;
            if (rt.method == null) {
                String lbl = rt.el.root + "." + rt.el.member + " ✖ 未解決";
                target = g.node("NG:" + lbl, lbl, "[", "]", "ng");
            } else {
                String lbl = rt.bean.simpleName + "." + rt.method.getNameAsString() + "()";
                target = g.node("B:" + rt.bean.fqn + "#" + rt.method.getNameAsString(), lbl, "(", ")", "bean");
            }
            g.edge(from, target, rt.attr, false);
            for (CallNode c : rt.tree) addNode(g, target, c);
        }
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        g.nodes.forEach(n -> sb.append(n).append('\n'));
        g.edges.forEach(e -> sb.append(e).append('\n'));
        sb.append("    classDef screen fill:#dbeafe,stroke:#1d4ed8,color:#1e3a8a\n");
        sb.append("    classDef partial fill:#f1f5f9,stroke:#64748b,color:#334155\n");
        sb.append("    classDef bean fill:#dcfce7,stroke:#15803d,color:#14532d\n");
        sb.append("    classDef service fill:#fef9c3,stroke:#a16207,color:#713f12\n");
        sb.append("    classDef mapper fill:#fae8ff,stroke:#a21caf,color:#701a75\n");
        sb.append("    classDef table fill:#ffedd5,stroke:#c2410c,color:#7c2d12\n");
        sb.append("    classDef ng fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d,stroke-dasharray:4 2\n");
        Map<String, List<String>> byCls = new LinkedHashMap<>();
        g.classes.forEach((id, c) -> byCls.computeIfAbsent(c, k -> new ArrayList<>()).add(id));
        byCls.forEach((c, ids) -> sb.append("    class ").append(String.join(",", ids)).append(' ').append(c).append('\n'));
        return sb.toString();
    }

    private void addNode(Graph g, String parent, CallNode n) {
        String id;
        if (n.role == Role.MAPPER) {
            boolean ng = n.stmt == null;
            String lbl = n.cls.simpleName + "." + n.method + (ng ? " ✖ " + n.note : "");
            id = g.node("M:" + n.cls.fqn + "#" + n.method, lbl, "{{", "}}", ng ? "ng" : "mapper");
            g.edge(parent, id, null, false);
            if (n.stmt != null) for (String t : n.stmt.tables) {
                String tid = g.node("T:" + t.toLowerCase(Locale.ROOT), t, "[(", ")]", "table");
                g.edge(id, tid, crudLetter(n.stmt.type), false);
            }
            return;
        }
        String lbl = n.cls.simpleName + "." + n.method + "()" + (n.note != null ? "<br/>" + n.note : "");
        id = g.node((n.role == Role.BEAN ? "B:" : "S:") + n.cls.fqn + "#" + n.method, lbl, n.role == Role.BEAN ? "(" : "[",
                n.role == Role.BEAN ? ")" : "]", n.role == Role.BEAN ? "bean" : "service");
        g.edge(parent, id, null, false);
        for (CallNode c : n.children) addNode(g, id, c);
    }

    /* ---------------- index ---------------- */

    private void writeIndexMerged(List<Screen> screens, List<Facelet> facelets, List<Issue> issues,
                                  List<PathRow> finalRows, boolean merge) throws IOException {
        Map<String, Screen> fresh = new LinkedHashMap<>();
        for (Screen s : screens) fresh.put(PathCsv.normalizeScreen(s.facelet.webPath), s);

        LinkedHashMap<String, List<PathRow>> byScreen = new LinkedHashMap<>();
        for (PathRow r : finalRows) {
            byScreen.computeIfAbsent(PathCsv.normalizeScreen(r.screen), k -> new ArrayList<>()).add(r);
        }

        StringBuilder md = new StringBuilder("# Jakarta EE ソース解析結果\n\n");
        md.append("- 解析対象: `").append(Main.root).append("`\n");
        if (merge) md.append("- モード: 部分解析（既存結果とマージ） / 今回更新: ")
                .append(screens.stream().map(s -> s.facelet.webPath).collect(Collectors.joining(", "))).append("\n");
        md.append("- 画面数: ").append(byScreen.size()).append(" / xhtml: ").append(facelets.size())
          .append(" / Bean: ").append(java.beansByName.size()).append(" / SQL ステートメント: ").append(mappers.statements.size()).append("\n");
        md.append("- 呼び出しパス CSV: [paths.csv](paths.csv)（TSV: [paths.tsv](paths.tsv)）\n");
        md.append("- CRUD 図 CSV: [crud.csv](crud.csv)\n");
        md.append("- 未解決一覧: [unresolved.md](unresolved.md)（").append(issues.size()).append(" 件※今回解析分）\n\n");

        md.append("## 画面一覧\n\n| 画面 | 更新 | 使用 Bean | パス数 | 使用テーブル |\n|---|:---:|---|---:|---|\n");
        for (Map.Entry<String, List<PathRow>> e : byScreen.entrySet()) {
            String scr = e.getKey();
            List<PathRow> rows = e.getValue();
            boolean updated = fresh.containsKey(scr);
            Screen s = fresh.get(scr);
            String file = s != null ? screenFile(s) : screenFileName(scr);
            String beans = rows.stream().map(r -> r.beanName).filter(b -> b != null && !b.isBlank()).distinct().collect(Collectors.joining(", "));
            String tables = rows.stream().flatMap(r -> Arrays.stream(r.tables.split(",\\s*"))).filter(t -> !t.isBlank()).distinct().collect(Collectors.joining(", "));
            md.append("| [").append(scr).append("](screens/").append(file).append(") | ")
              .append(updated ? "今回" : "既存").append(" | ").append(beans).append(" | ")
              .append(rows.size()).append(" | ").append(tables).append(" |\n");
        }

        var m = crudFromRows(finalRows);
        List<String> tables = new ArrayList<>(allTables(m));
        md.append("\n## CRUD 図（画面 × テーブル）\n\nC=insert, R=select, U=update, D=delete（テンプレート/インクルード経由、@PostConstruct を含む）\n\n| 画面 |");
        tables.forEach(t -> md.append(' ').append(t).append(" |"));
        md.append("\n|---|");
        tables.forEach(t -> md.append(":---:|"));
        md.append('\n');
        m.forEach((scr, r) -> {
            md.append("| ").append(scr).append(" |");
            for (String t : tables) md.append(' ').append(r.containsKey(t) ? String.join("", r.get(t)) : "").append(" |");
            md.append('\n');
        });

        md.append("\n## Bean 一覧\n\n| Bean名 | クラス | スコープ | 注入フィールド |\n|---|---|---|---|\n");
        java.beansByName.forEach((n, c) -> md.append("| `").append(n).append("` | ").append(c.fqn).append(" | @").append(c.scope).append(" | ")
                .append(c.injectedFields.stream().map(f -> f + ":" + simple(c.fieldTypesRaw.getOrDefault(f, "?"))).collect(Collectors.joining(", "))).append(" |\n"));

        md.append("\n## Mapper SQL 一覧\n\n| Mapper#ID | 種別 | テーブル | 定義元 | 画面から使用 |\n|---|---|---|---|:---:|\n");
        // マージ時は paths 上の Mapper 参照も使用扱いにする
        Set<String> usedFromPaths = new HashSet<>(tracer.usedStatements);
        if (merge) {
            for (PathRow r : finalRows) {
                if (r.mapperMethod == null || r.mapperMethod.isBlank()) continue;
                // Class#method → 簡易キーは不完全なので表示のみ ○ 判定は tracer + 今回分
            }
        }
        for (SqlStatement st : mappers.statements.values())
            md.append("| ").append(simple(st.namespace)).append("#").append(st.id).append(" | ").append(st.type).append(" | ")
              .append(esc(String.join(", ", st.tables))).append(" | ").append(esc(st.source)).append(" | ")
              .append(usedFromPaths.contains(st.key()) || usedInRows(finalRows, st) ? "○" : "").append(" |\n");
        Files.writeString(out.resolve("index.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static boolean usedInRows(List<PathRow> rows, SqlStatement st) {
        String simple = simple(st.namespace) + "#" + st.id;
        for (PathRow r : rows) {
            if (simple.equals(r.mapperMethod)) return true;
        }
        return false;
    }

    static String screenFileName(String webPath) {
        String w = webPath.replaceFirst("^/", "").replaceFirst("\\.xhtml$", "");
        return w.replace('/', '_') + ".md";
    }
}
