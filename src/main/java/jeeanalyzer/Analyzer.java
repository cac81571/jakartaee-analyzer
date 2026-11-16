package jeeanalyzer;

import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

import com.github.javaparser.ast.body.MethodDeclaration;

import jeeanalyzer.Main.Screen;
import jeeanalyzer.Model.*;

/** 解析実行（CLI / GUI 共通） */
public final class Analyzer {

    /** 解析オプション */
    public static final class Options {
        public Path project;
        public Path out = Paths.get("analysis-output");
        public Charset encoding = Util.preferred;
        public List<Path> webRoots = new ArrayList<>();
        /** 空なら全画面。webPath（例: /login.xhtml）または相対パスで指定 */
        public Set<String> xhtmlFilters = new LinkedHashSet<>();
        public boolean writeFiles = true;
        /**
         * 既存出力とマージするか。null のとき: 部分解析なら true、全画面なら false。
         * 部分解析で true の場合、未指定画面の paths / screens は削除せず残す。
         */
        public Boolean merge;
    }

    /** 解析結果 */
    public static final class Result {
        public Path project;
        public Path out;
        public List<Screen> screens = new ArrayList<>();
        public List<Facelet> facelets = new ArrayList<>();
        public List<Issue> issues = new ArrayList<>();
        public List<PathRow> rows = new ArrayList<>();
        public JavaIndex java;
        public MapperIndex mappers;
        public int javaCount;
        public int xmlCount;
        public int xhtmlCount;
    }

    private Analyzer() {}

    public static Result run(Options opt) throws Exception {
        return run(opt, msg -> {});
    }

    public static Result run(Options opt, Consumer<String> log) throws Exception {
        if (opt.project == null) throw new IllegalArgumentException("project が未指定です");
        Path root = opt.project.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("ディレクトリが存在しません: " + root);
        Path out = opt.out.toAbsolutePath().normalize();
        Charset prev = Util.preferred;
        Util.preferred = opt.encoding != null ? opt.encoding : prev;
        Main.root = root;

        Result r = new Result();
        r.project = root;
        r.out = out;
        try {
            log.accept("解析対象: " + root);
            List<Path> javaFiles = Util.listFiles(root, ".java").stream()
                    .filter(p -> !Util.rel(root, p).contains("src/test/")).toList();
            List<Path> xmlFiles = Util.listFiles(root, ".xml");
            List<Path> xhtmlFiles = Util.listFiles(root, ".xhtml");
            r.javaCount = javaFiles.size();
            r.xmlCount = xmlFiles.size();
            r.xhtmlCount = xhtmlFiles.size();
            log.accept(String.format("  Java: %d / XML: %d / xhtml: %d", r.javaCount, r.xmlCount, r.xhtmlCount));

            MapperIndex mappers = new MapperIndex();
            mappers.parseXml(root, xmlFiles);
            JavaIndex java = new JavaIndex();
            java.parse(root, javaFiles);
            java.link(mappers.namespaceFiles.keySet());
            Set<String> bundles = Main.readFacesConfig(xmlFiles, java);

            List<Path> webRoots = opt.webRoots.isEmpty() ? Main.detectWebRoots(root, xhtmlFiles) : opt.webRoots;
            FaceletsScanner scanner = new FaceletsScanner();
            List<Facelet> facelets = new ArrayList<>();
            for (Path p : xhtmlFiles) facelets.add(scanner.scan(root, p));
            List<Issue> linkIssues = new ArrayList<>();
            FaceletsScanner.linkRefs(facelets, webRoots, root, linkIssues);

            Tracer tracer = new Tracer(java, mappers);
            tracer.bundles.addAll(bundles);
            facelets.forEach(f -> tracer.globalVars.addAll(f.vars));
            facelets.forEach(tracer::resolve);

            Set<String> filters = normalizeFilters(opt.xhtmlFilters);
            List<Screen> screens = new ArrayList<>();
            for (Facelet f : facelets) {
                if (f.referenced || f.webPath.startsWith("/WEB-INF/") || f.webPath.startsWith("/resources/")
                        || f.webPath.startsWith("/META-INF/")) continue;
                if (!filters.isEmpty() && !matchesFilter(f, filters)) continue;
                screens.add(Main.buildScreen(f, tracer, java));
            }

            List<Issue> extra = new ArrayList<>();
            mappers.namespaceFiles.forEach((ns, p) -> {
                if (!java.byFqn.containsKey(ns)) extra.add(new Issue("Mapper XML: インターフェースなし", Util.rel(root, p), ns,
                        "namespace に対応する Java インターフェースが見つかりません"));
            });
            for (SqlStatement st : new ArrayList<>(mappers.statements.values())) {
                ClassInfo m = java.byFqn.get(st.namespace);
                if (st.annotation || m == null) continue;
                if (java.findMethod(m, st.id, -1) == null) extra.add(new Issue("Mapper XML: インターフェースにメソッドなし",
                        st.source, m.simpleName + "#" + st.id, "XML のステートメント id に対応するメソッドがインターフェースにありません"));
            }
            for (ClassInfo c : java.byFqn.values()) {
                if (c.role != Role.MAPPER) continue;
                for (MethodDeclaration md : c.methods) {
                    if (mappers.find(c, c, md, md.getNameAsString()) == null && !md.isDefault() && !md.isStatic()) {
                        tracer.issue("Mapper: SQL未定義", Util.rel(root, c.file) + ":" + md.getBegin().map(b -> b.line).orElse(0),
                                c.simpleName + "#" + md.getNameAsString(), "Mapper XML（namespace=" + c.fqn + "）にもアノテーションにも SQL がありません");
                    }
                }
            }

            List<Issue> all = new ArrayList<>();
            all.addAll(tracer.issues);
            all.addAll(linkIssues);
            all.addAll(extra);
            all.addAll(java.issues);
            all.addAll(mappers.issues);

            boolean merge = opt.merge != null ? opt.merge : !filters.isEmpty();
            r.screens = screens;
            r.facelets = facelets;
            r.issues = all;
            r.java = java;
            r.mappers = mappers;

            if (opt.writeFiles) {
                Reporter rep = new Reporter(out, java, mappers, tracer);
                List<PathRow> written = rep.write(screens, facelets, all, merge);
                r.rows.addAll(written);
                log.accept((merge ? "出力先（マージ）: " : "出力先: ") + out);
            } else if (merge && Files.isRegularFile(out.resolve("paths.csv"))) {
                List<PathRow> existing = PathCsv.read(out.resolve("paths.csv"));
                List<PathRow> fresh = new ArrayList<>();
                for (Screen s : screens) fresh.addAll(s.rows);
                Set<String> updated = new LinkedHashSet<>();
                for (Screen s : screens) updated.add(PathCsv.normalizeScreen(s.facelet.webPath));
                r.rows.addAll(PathCsv.mergeByScreen(existing, fresh, updated));
            } else {
                for (Screen s : screens) r.rows.addAll(s.rows);
            }

            log.accept(String.format("  画面(今回): %d / Bean: %d / Mapper: %d / パス(合計): %d / 未解決: %d%s",
                    screens.size(), java.beansByName.size(), mappers.statements.size(), r.rows.size(), all.size(),
                    merge ? " / マージあり" : ""));
            return r;
        } finally {
            Util.preferred = prev;
        }
    }

    /** プロジェクト内の画面候補（テンプレート等を除く）を列挙 */
    public static List<Facelet> listScreens(Path project) throws Exception {
        Path root = project.toAbsolutePath().normalize();
        Main.root = root;
        List<Path> xhtmlFiles = Util.listFiles(root, ".xhtml");
        List<Path> webRoots = Main.detectWebRoots(root, xhtmlFiles);
        FaceletsScanner scanner = new FaceletsScanner();
        List<Facelet> facelets = new ArrayList<>();
        for (Path p : xhtmlFiles) facelets.add(scanner.scan(root, p));
        FaceletsScanner.linkRefs(facelets, webRoots, root, new ArrayList<>());
        List<Facelet> screens = new ArrayList<>();
        for (Facelet f : facelets) {
            if (f.webPath == null) continue;
            if (f.referenced || f.webPath.startsWith("/WEB-INF/") || f.webPath.startsWith("/resources/")
                    || f.webPath.startsWith("/META-INF/")) continue;
            screens.add(f);
        }
        screens.sort(Comparator.comparing(f -> f.webPath));
        return screens;
    }

    private static Set<String> normalizeFilters(Set<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim().replace('\\', '/');
            out.add(t);
            if (!t.startsWith("/")) out.add("/" + t);
            // ファイル名だけでもマッチできるよう末尾も保持
            int slash = t.lastIndexOf('/');
            if (slash >= 0) out.add(t.substring(slash + 1));
        }
        return out;
    }

    private static boolean matchesFilter(Facelet f, Set<String> filters) {
        String web = f.webPath == null ? "" : f.webPath.replace('\\', '/');
        String rel = f.rel == null ? "" : f.rel.replace('\\', '/');
        String name = f.file != null ? f.file.getFileName().toString() : "";
        for (String g : filters) {
            if (g.equals(web) || g.equals(rel) || g.equals(name)) return true;
            if (web.endsWith(g) || rel.endsWith(g)) return true;
        }
        return false;
    }
}
