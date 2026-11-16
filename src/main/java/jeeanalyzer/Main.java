package jeeanalyzer;

import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import com.github.javaparser.ast.body.MethodDeclaration;

import jeeanalyzer.Model.*;

/**
 * Jakarta EE ソース解析ツール
 * xhtml(EL) → CDI Bean → Service → MyBatis Mapper → SQL/テーブル の呼び出し関係を画面単位で出力する。
 *
 * 引数なし / {@code --gui} で FlatLaf Swing GUI を起動する。
 */
public final class Main {
    static Path root;

    /** 画面単位の解析結果 */
    static final class Screen {
        Facelet facelet;
        List<Facelet> files = new ArrayList<>();          // 画面 + テンプレート/インクルード
        List<String[]> fileEdges = new ArrayList<>();     // [親rel, 子rel, 種別]
        List<RefTrace> refs = new ArrayList<>();
        List<PathRow> rows = new ArrayList<>();
        String name() { return facelet.webPath; }
    }

    static final class RefTrace {
        Facelet file;
        ElRef el;                 // null の場合は @PostConstruct 疑似参照
        ClassInfo bean;
        MethodDeclaration method;
        String label;             // 表示用 EL / 疑似参照名
        String attr;
        List<CallNode> tree = List.of();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || (args.length == 1 && ("--gui".equals(args[0]) || "-g".equals(args[0])))) {
            jeeanalyzer.gui.GuiApp.launch();
            return;
        }

        Analyzer.Options opt = new Analyzer.Options();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> opt.out = Paths.get(args[++i]);
                case "-e", "--encoding" -> opt.encoding = Charset.forName(args[++i]);
                case "-w", "--web-root" -> opt.webRoots.add(Paths.get(args[++i]).toAbsolutePath().normalize());
                case "-x", "--xhtml" -> {
                    for (String part : args[++i].split("[,;]")) {
                        if (!part.isBlank()) opt.xhtmlFilters.add(part.trim());
                    }
                }
                case "--merge" -> opt.merge = true;
                case "--replace" -> opt.merge = false;
                case "-g", "--gui" -> { jeeanalyzer.gui.GuiApp.launch(); return; }
                case "-h", "--help" -> { usage(); return; }
                default -> opt.project = Paths.get(args[i]);
            }
        }
        if (opt.project == null) { usage(); System.exit(1); }

        Analyzer.run(opt, System.out::println);
    }

    static void usage() {
        System.out.println("""
                使い方: java -jar jakartaee-analyzer.jar <プロジェクトディレクトリ> [オプション]
                        java -jar jakartaee-analyzer.jar --gui
                  -o, --out <dir>        出力先ディレクトリ（既定: ./analysis-output）
                  -e, --encoding <cs>    ソースの文字コード（既定: UTF-8。読めない場合は Windows-31J を自動試行）
                  -w, --web-root <dir>   Web ルート（xhtml の / 起点）。複数指定可。既定は WEB-INF の親を自動検出
                  -x, --xhtml <list>     解析する画面 xhtml（webPath または相対パス。カンマ区切り）
                  --merge                既存の paths.csv 等とマージ（部分解析時の既定）
                  --replace              既存結果を捨てて今回分だけ出力
                  -g, --gui              FlatLaf Swing GUI を起動
                """);
    }

    /** faces-config.xml の resource-bundle 変数と managed-bean（旧 JSF 方式）を取り込む */
    static Set<String> readFacesConfig(List<Path> xmlFiles, JavaIndex java) throws Exception {
        Set<String> bundles = new HashSet<>();
        Pattern rb = Pattern.compile("<resource-bundle>.*?<var>\\s*([\\w$]+)\\s*</var>", Pattern.DOTALL);
        Pattern mb = Pattern.compile("<managed-bean\\b[^>]*>(.*?)</managed-bean>", Pattern.DOTALL);
        for (Path p : xmlFiles) {
            if (!p.getFileName().toString().endsWith("faces-config.xml")) continue;
            String t = Util.read(p).replaceAll("(?s)<!--.*?-->", "");
            var m = rb.matcher(t);
            while (m.find()) bundles.add(m.group(1));
            var b = mb.matcher(t);
            while (b.find()) {
                String body = b.group(1);
                String name = tagText(body, "managed-bean-name"), cls = tagText(body, "managed-bean-class"), scope = tagText(body, "managed-bean-scope");
                ClassInfo ci = cls == null ? null : java.byFqn.get(cls);
                if (name == null || ci == null || java.beansByName.containsKey(name)) continue;
                ci.role = Role.BEAN;
                ci.beanName = name;
                ci.scope = scope == null ? "none" : scope + "(faces-config)";
                java.beansByName.put(name, ci);
            }
        }
        return bundles;
    }

    private static String tagText(String body, String tag) {
        var m = Pattern.compile("<" + tag + ">\\s*([^<]*?)\\s*</" + tag + ">").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    static List<Path> detectWebRoots(List<Path> xhtml) throws Exception {
        return detectWebRoots(root, xhtml);
    }

    static List<Path> detectWebRoots(Path projectRoot, List<Path> xhtml) throws Exception {
        if (projectRoot == null) throw new IllegalStateException("projectRoot が未設定です");
        Path base = projectRoot.toAbsolutePath().normalize();
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        try (var s = Files.walk(base)) {
            s.filter(Files::isDirectory).filter(p -> p.getFileName() != null && p.getFileName().toString().equals("WEB-INF"))
             .filter(p -> !Util.rel(base, p).contains("target/"))
             .forEach(p -> roots.add(p.getParent().toAbsolutePath().normalize()));
        }
        for (Path x : xhtml) {
            for (Path p = x.getParent(); p != null && p.startsWith(base); p = p.getParent()) {
                if (p.getFileName() != null && p.getFileName().toString().equals("webapp")) { roots.add(p.toAbsolutePath().normalize()); break; }
            }
        }
        if (roots.isEmpty()) roots.add(base);
        return new ArrayList<>(roots);
    }

    static Screen buildScreen(Facelet f, Tracer tracer, JavaIndex java) {
        Screen s = new Screen();
        s.facelet = f;
        collect(f, s, new HashSet<>());
        Map<ClassInfo, Facelet> beansUsed = new LinkedHashMap<>();
        for (Facelet file : s.files) {
            for (ElRef e : file.els) {
                if (e.bean != null) beansUsed.putIfAbsent(java.beansByName.get(e.root), file);
                if (!"メソッド".equals(e.kind) && !"プロパティ".equals(e.kind) && !"未解決".equals(e.kind) && !"Producer".equals(e.kind)) continue;
                RefTrace rt = new RefTrace();
                rt.file = file;
                rt.el = e;
                rt.bean = e.bean;
                rt.method = e.method;
                rt.label = e.expression;
                rt.attr = e.attr.isEmpty() ? "text" : e.attr;
                if (e.method != null) rt.tree = tracer.trace(e.bean, e.method);
                s.refs.add(rt);
            }
        }
        // @PostConstruct は Bean 初回利用時に呼ばれるため疑似参照として追加
        beansUsed.forEach((bean, file) -> {
            if (bean == null) return;
            for (MethodDeclaration md : bean.methods) {
                if (!md.isAnnotationPresent("PostConstruct")) continue;
                RefTrace rt = new RefTrace();
                rt.file = file;
                rt.bean = bean;
                rt.method = md;
                rt.label = "(@PostConstruct " + bean.beanName + ")";
                rt.attr = "@PostConstruct";
                rt.tree = tracer.trace(bean, md);
                s.refs.add(rt);
            }
        });
        for (RefTrace rt : s.refs) s.rows.addAll(rows(s, rt));
        return s;
    }

    private static void collect(Facelet f, Screen s, Set<Facelet> seen) {
        if (!seen.add(f)) return;
        s.files.add(f);
        for (Facelet t : f.templates) { s.fileEdges.add(new String[]{f.rel, t.rel, "template"}); collect(t, s, seen); }
        for (Facelet t : f.includes) { s.fileEdges.add(new String[]{f.rel, t.rel, "include"}); collect(t, s, seen); }
    }

    static String nodeLabel(CallNode n) {
        String base = n.cls.simpleName + "#" + n.method;
        // 単一実装への IF 解決注記は表示しない（実装複数の警告のみ残す）
        if (n.note != null && !n.note.isBlank() && !n.note.startsWith("IF:")) {
            return base + "（" + n.note + "）";
        }
        return base;
    }

    /** value="検索" → 検索 / value="#{msg.x}" → #{msg.x} */
    static String prettyControlLabel(String raw) {
        String s = raw.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace('\n', ' ').trim();
    }

    private static List<PathRow> rows(Screen s, RefTrace rt) {
        List<PathRow> out = new ArrayList<>();
        List<List<CallNode>> paths = Tracer.paths(rt.tree);
        if (paths.isEmpty()) paths = List.of(List.of());
        for (List<CallNode> p : paths) {
            PathRow r = new PathRow();
            r.screen = s.facelet.webPath;
            r.definedIn = rt.file.rel;
            r.line = rt.el != null ? rt.el.line : rt.method.getBegin().map(b -> b.line).orElse(0);
            r.tagAttr = rt.el != null ? rt.el.tag + (rt.el.attr.isEmpty() ? "" : "@" + rt.el.attr) : "@PostConstruct";
            if (rt.el != null && rt.el.controlLabel != null && !rt.el.controlLabel.isBlank()) {
                r.tagAttr = r.tagAttr + "「" + prettyControlLabel(rt.el.controlLabel) + "」";
            }
            r.el = rt.label;
            r.kind = rt.el != null ? rt.el.kind : "初期化";
            r.beanName = rt.bean != null ? rt.bean.beanName : (rt.el != null ? rt.el.root : "");
            if (rt.el == null) r.definedIn = Util.rel(root, rt.bean.file);
            r.beanMethod = rt.method != null ? rt.bean.simpleName + "#" + rt.method.getNameAsString()
                    : (rt.bean != null ? rt.bean.simpleName + "#" + rt.el.member + "（未定義）" : "");
            if (rt.bean != null && rt.bean.file != null) {
                r.beanFile = Util.rel(root, rt.bean.file);
                r.beanLine = rt.method != null ? rt.method.getBegin().map(b -> b.line).orElse(0) : 0;
            }
            if (rt.el != null && rt.el.producerDesc != null) r.beanMethod = rt.el.producerDesc;
            if (rt.el != null && rt.el.problem != null) r.note = rt.el.problem;
            for (int i = 0; i < p.size(); i++) {
                CallNode n = p.get(i);
                if (n.role == Role.MAPPER && i == p.size() - 1) {
                    r.mapperMethod = n.cls.simpleName + "#" + n.method;
                    if (n.cls != null && n.cls.file != null) {
                        r.mapperFile = Util.rel(root, n.cls.file);
                        r.mapperLine = n.decl != null ? n.decl.getBegin().map(b -> b.line).orElse(0) : 0;
                    }
                    if (n.stmt != null) {
                        r.stmtType = n.stmt.type;
                        r.tables = String.join(", ", n.stmt.tables);
                        r.sqlSource = n.stmt.source;
                    } else {
                        r.note = (r.note.isEmpty() ? "" : r.note + " / ") + n.note;
                    }
                } else {
                    r.services.add(nodeLabel(n));
                    if (n.cls != null && n.cls.file != null) {
                        r.serviceFiles.add(Util.rel(root, n.cls.file));
                        r.serviceLines.add(n.decl != null ? n.decl.getBegin().map(b -> b.line).orElse(0) : 0);
                    } else {
                        r.serviceFiles.add("");
                        r.serviceLines.add(0);
                    }
                }
            }
            if (r.mapperMethod == null) r.mapperMethod = "";
            out.add(r);
        }
        return out;
    }
}
