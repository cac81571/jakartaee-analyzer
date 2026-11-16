package jeeanalyzer.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 登録プロジェクト一覧・前回出力先の永続化 */
final class ProjectStore {
    private final Path dir;
    private final Path projectsFile;
    private final Path settingsFile;

    ProjectStore() {
        dir = Paths.get(System.getProperty("user.home"), ".jakartaee-analyzer");
        projectsFile = dir.resolve("projects.txt");
        settingsFile = dir.resolve("settings.properties");
    }

    List<Path> load() {
        if (!Files.isRegularFile(projectsFile)) return new ArrayList<>();
        try {
            List<Path> out = new ArrayList<>();
            for (String line : Files.readAllLines(projectsFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                Path p = Paths.get(t).toAbsolutePath().normalize();
                if (Files.isDirectory(p) && !out.contains(p)) out.add(p);
            }
            return out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    void save(List<Path> projects) {
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            for (Path p : projects) sb.append(p.toAbsolutePath().normalize()).append('\n');
            Files.writeString(projectsFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignore) { }
    }

    Path loadLastOut() {
        Properties props = loadSettings();
        String v = props.getProperty("lastOut");
        if (v == null || v.isBlank()) return null;
        try {
            Path p = Paths.get(v.trim()).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    void saveLastOut(Path out) {
        if (out == null) return;
        Properties props = loadSettings();
        props.setProperty("lastOut", out.toAbsolutePath().normalize().toString());
        try {
            Files.createDirectories(dir);
            try (var w = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
                props.store(w, "jakartaee-analyzer GUI");
            }
        } catch (IOException ignore) { }
    }

    private Properties loadSettings() {
        Properties props = new Properties();
        if (!Files.isRegularFile(settingsFile)) return props;
        try (var r = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException ignore) { }
        return props;
    }
}
