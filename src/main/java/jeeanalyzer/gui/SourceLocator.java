package jeeanalyzer.gui;

import java.nio.file.*;
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

    /** プロジェクト配下から SimpleName.java を探す */
    static Path findJavaFile(Path project, String simpleClassName) {
        if (project == null || simpleClassName == null || simpleClassName.isBlank()) return null;
        String target = simpleClassName + ".java";
        try (Stream<Path> s = Files.walk(project)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> target.equalsIgnoreCase(p.getFileName().toString()))
                    .filter(p -> {
                        String rel = project.relativize(p).toString().replace('\\', '/');
                        return !rel.contains("/target/") && !rel.contains("/build/");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
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
                // 定義らしい行: 修飾子・戻り値・アノテーション続き
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
        if (s.endsWith(".")) return false; // obj.
        if (s.contains("=") && !s.contains("->")) return false;
        // new Foo( のコンストラクタ呼び出しはメソッド名がクラス名のときのみ問題 → 呼び出し側は . 付きで除外済み
        if (s.startsWith("new ")) return false;
        if (s.startsWith("return ") || s.startsWith("throw ") || s.startsWith("if ")
                || s.startsWith("while ") || s.startsWith("for ") || s.startsWith("switch ")) return false;
        return true;
    }

    static Path resolveJava(Path project, String classHashMethod, String fileHint) {
        if (fileHint != null && !fileHint.isBlank() && project != null) {
            Path p = project.resolve(fileHint.replace('\\', '/')).normalize();
            if (Files.isRegularFile(p)) return p;
            String name = project.getFileName() != null ? project.getFileName().toString() : "";
            String s = fileHint.replace('\\', '/');
            if (s.startsWith(name + "/")) {
                p = project.resolve(s.substring(name.length() + 1)).normalize();
                if (Files.isRegularFile(p)) return p;
            }
        }
        return findJavaFile(project, simpleClass(classHashMethod));
    }

    static void showJava(SourcePane pane, Path project, String classHashMethod, String fileHint, int lineHint) {
        Path java = resolveJava(project, classHashMethod, fileHint);
        if (java == null || !Files.isRegularFile(java)) {
            pane.clear();
            return;
        }
        String rel;
        try {
            rel = project != null && java.startsWith(project)
                    ? project.relativize(java).toString().replace('\\', '/')
                    : java.toString();
        } catch (Exception e) {
            rel = java.toString();
        }
        String method = methodName(classHashMethod);
        int line = lineHint;
        int found = findMethodLine(java, method);
        // 解析行が無い／ズレている場合はソースから再特定を優先
        if (found > 0) line = found;
        pane.showFile(project, rel, line);
    }
}
