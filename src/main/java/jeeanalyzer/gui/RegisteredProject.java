package jeeanalyzer.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 1 論理プロジェクト。主フォルダ（XHTML / Bean など）に加え、
 * DAO・SQL が別ディレクトリにある場合は {@link #extras} に登録する。
 */
final class RegisteredProject {
    final Path primary;
    final List<Path> extras;

    RegisteredProject(Path primary) {
        this(primary, List.of());
    }

    RegisteredProject(Path primary, List<Path> extras) {
        if (primary == null) throw new IllegalArgumentException("primary");
        this.primary = primary.toAbsolutePath().normalize();
        LinkedHashSet<Path> set = new LinkedHashSet<>();
        if (extras != null) {
            for (Path e : extras) {
                if (e == null) continue;
                Path n = e.toAbsolutePath().normalize();
                if (!n.equals(this.primary) && Files.isDirectory(n)) set.add(n);
            }
        }
        this.extras = List.copyOf(set);
    }

    List<Path> allRoots() {
        if (extras.isEmpty()) return List.of(primary);
        List<Path> all = new ArrayList<>(1 + extras.size());
        all.add(primary);
        all.addAll(extras);
        return all;
    }

    RegisteredProject withExtra(Path extra) {
        if (extra == null) return this;
        Path n = extra.toAbsolutePath().normalize();
        if (n.equals(primary) || extras.contains(n)) return this;
        List<Path> next = new ArrayList<>(extras);
        next.add(n);
        return new RegisteredProject(primary, next);
    }

    RegisteredProject withoutExtra(Path extra) {
        if (extra == null || extras.isEmpty()) return this;
        Path n = extra.toAbsolutePath().normalize();
        List<Path> next = new ArrayList<>();
        for (Path e : extras) if (!e.equals(n)) next.add(e);
        return next.size() == extras.size() ? this : new RegisteredProject(primary, next);
    }

    String displayName() {
        String base = primary.getFileName() != null ? primary.getFileName().toString() : primary.toString();
        if (extras.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base).append(" (+");
        for (int i = 0; i < extras.size(); i++) {
            if (i > 0) sb.append(", ");
            Path e = extras.get(i);
            sb.append(e.getFileName() != null ? e.getFileName() : e);
        }
        return sb.append(')').toString();
    }

    String tooltip() {
        StringBuilder sb = new StringBuilder("主: ").append(primary);
        for (Path e : extras) sb.append("\n関連: ").append(e);
        return sb.toString();
    }

    /** projects.txt 1 行（primary|extra|…） */
    String toStoreLine() {
        StringBuilder sb = new StringBuilder(primary.toString());
        for (Path e : extras) sb.append('|').append(e);
        return sb.toString();
    }

    static RegisteredProject parseStoreLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\\|");
        Path primary = null;
        List<Path> extras = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) continue;
            Path p = Path.of(t).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) continue;
            if (primary == null) primary = p;
            else extras.add(p);
        }
        return primary == null ? null : new RegisteredProject(primary, extras);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegisteredProject that)) return false;
        return primary.equals(that.primary) && extras.equals(that.extras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primary, extras);
    }

    @Override
    public String toString() {
        return displayName();
    }
}
