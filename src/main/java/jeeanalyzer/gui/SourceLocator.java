package jeeanalyzer.gui;

import java.nio.file.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jeeanalyzer.Util;

/** プロジェクト内の Java ソース位置を推定 */
final class SourceLocator {
    private SourceLocator() {}

    /** Class#method または ClassName から単純クラス名を取り出す */
    static String simpleClass(String classHashMethod) {
        if (classHashMethod == null || classHashMethod.isBlank()) return "";
        String s = classHashMethod.trim();
        int hash = s.indexOf('#');
        if (hash > 0) s = s.substring(0, hash);
        int slash = Math.max(s.lastIndexOf('.'), s.lastIndexOf('/'));
        if (slash >= 0) s = s.substring(slash + 1);
        int cut = s.indexOf('（');
        if (cut > 0) s = s.substring(0, cut);
        return s.trim();
    }

    static String methodName(String classHashMethod) {
        if (classHashMethod == null) return "";
        String s = PathTreeBuilder.cleanServiceLabel(classHashMethod);
        int hash = s.indexOf('#');
        if (hash < 0) return "";
        String m = s.substring(hash + 1).trim();
        int cut = m.indexOf('（');
        if (cut < 0) cut = m.indexOf('(');
        if (cut > 0) m = m.substring(0, cut);
        return m.trim();
    }

    static Path findJavaFile(Path project, String simpleClassName) {
        return findJavaFile(project == null ? List.of() : List.of(project), simpleClassName);
    }

    /** 各ルート配下から SimpleName.java を探す */
    static Path findJavaFile(List<Path> roots, String simpleClassName) {
        if (roots == null || roots.isEmpty() || simpleClassName == null || simpleClassName.isBlank()) return null;
        String target = simpleClassName + ".java";
        for (Path project : roots) {
            if (project == null || !Files.isDirectory(project)) continue;
            try (Stream<Path> s = Files.walk(project)) {
                Path found = s.filter(Files::isRegularFile)
                        .filter(p -> target.equalsIgnoreCase(p.getFileName().toString()))
                        .filter(p -> {
                            String rel = project.relativize(p).toString().replace('\\', '/');
                            return !rel.contains("/target/") && !rel.contains("/build/");
                        })
                        .findFirst()
                        .orElse(null);
                if (found != null) return found;
            } catch (Exception ignore) { }
        }
        return null;
    }

    /**
     * メソッド定義行を探す（1-based）。
     * {@code public void foo(} や {@code void foo(}、アノテーション直後など幅広く拾う。
     * 呼び出し {@code obj.foo(} は除外。
     */
    static int findMethodLine(Path javaFile, String method) {
        if (javaFile == null || method == null || method.isBlank()) return 0;
        try {
            String text = Util.read(javaFile);
            Pattern p = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(");
            Matcher m = p.matcher(text);
            int fallback = 0;
            while (m.find()) {
                int start = m.start();
                if (start > 0 && text.charAt(start - 1) == '.') continue; // 呼び出し
                int lineStart = text.lastIndexOf('\n', start - 1) + 1;
                String before = text.substring(lineStart, start);
                String trimmed = before.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                if (looksLikeDeclaration(before, method)) {
                    return Util.lineOf(text, start);
                }
                if (fallback == 0) fallback = Util.lineOf(text, start);
            }
            return fallback;
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static boolean looksLikeDeclaration(String beforeOnLine, String method) {
        String s = beforeOnLine.trim();
        if (s.endsWith(".")) return false;
        if (s.contains("=") && !s.contains("->")) return false;
        if (s.startsWith("new ")) return false;
        if (s.startsWith("return ") || s.startsWith("throw ") || s.startsWith("if ")
                || s.startsWith("while ") || s.startsWith("for ") || s.startsWith("switch ")) return false;
        return true;
    }

    static Path resolveJava(Path project, String classHashMethod, String fileHint) {
        return resolveJava(project == null ? List.of() : List.of(project), classHashMethod, fileHint);
    }

    static Path resolveJava(List<Path> roots, String classHashMethod, String fileHint) {
        if (fileHint != null && !fileHint.isBlank() && roots != null) {
            String s = fileHint.replace('\\', '/');
            for (Path project : roots) {
                if (project == null) continue;
                Path p = project.resolve(s).normalize();
                if (Files.isRegularFile(p)) return p;
                String name = project.getFileName() != null ? project.getFileName().toString() : "";
                if (!name.isEmpty() && s.startsWith(name + "/")) {
                    p = project.resolve(s.substring(name.length() + 1)).normalize();
                    if (Files.isRegularFile(p)) return p;
                }
            }
            Path abs = Path.of(s);
            if (abs.isAbsolute() && Files.isRegularFile(abs)) return abs;
        }
        return findJavaFile(roots, simpleClass(classHashMethod));
    }

    static void showJava(SourcePane pane, Path project, String classHashMethod, String fileHint, int lineHint) {
        showJava(pane, project == null ? List.of() : List.of(project), classHashMethod, fileHint, lineHint);
    }

    static void showJava(SourcePane pane, List<Path> roots, String classHashMethod, String fileHint, int lineHint) {
        Path java = resolveJava(roots, classHashMethod, fileHint);
        if (java == null || !Files.isRegularFile(java)) {
            pane.clear();
            return;
        }
        String rel = java.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (roots != null) {
            for (Path project : roots) {
                if (project == null) continue;
                try {
                    Path base = project.toAbsolutePath().normalize();
                    if (java.startsWith(base)) {
                        rel = base.relativize(java).toString().replace('\\', '/');
                        break;
                    }
                } catch (Exception ignore) { }
            }
        }
        String method = methodName(classHashMethod);
        int line = lineHint;
        int found = findMethodLine(java, method);
        if (found > 0) line = found;
        pane.showFile(roots, rel, line);
    }
}
