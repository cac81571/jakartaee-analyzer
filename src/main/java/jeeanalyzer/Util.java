package jeeanalyzer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class Util {
    private Util() {}

    static Charset preferred = StandardCharsets.UTF_8;
    /**
     * マルチルート解析時の追加検索ルート（主ルート以外）。
     * {@link #rel(Path, Path)} が主ルート外のファイルを相対化するために参照する。
     */
    static List<Path> searchRoots = List.of();
    private static final Set<String> SKIP_DIRS = Set.of("target", "build", ".git", ".svn", "node_modules", ".idea", ".gradle", "bin");

    /** 指定拡張子のファイルを列挙（target 等は除外） */
    static List<Path> listFiles(Path root, String... exts) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
             .filter(p -> {
                 Path rel = root.relativize(p);
                 for (Path part : rel) if (SKIP_DIRS.contains(part.toString())) return false;
                 String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                 for (String e : exts) if (n.endsWith(e)) return true;
                 return false;
             })
             .sorted()
             .forEach(out::add);
        }
        return out;
    }

    /** 文字コード自動判定付き読み込み（指定文字コード → UTF-8 → Windows-31J） */
    public static String read(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        List<Charset> tries = new ArrayList<>(List.of(preferred, StandardCharsets.UTF_8, Charset.forName("windows-31j")));
        for (Charset cs : tries) {
            try {
                String s = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
                if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
                return s;
            } catch (CharacterCodingException ignore) { }
        }
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    public static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String rel(Path root, Path p) {
        Path abs = p.toAbsolutePath().normalize();
        List<Path> tryRoots = new ArrayList<>();
        if (root != null) tryRoots.add(root.toAbsolutePath().normalize());
        for (Path r : searchRoots) {
            if (r == null) continue;
            Path n = r.toAbsolutePath().normalize();
            if (!tryRoots.contains(n)) tryRoots.add(n);
        }
        for (Path r : tryRoots) {
            if (abs.startsWith(r)) return r.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString().replace('\\', '/');
    }
}
