package jeeanalyzer.gui;

import java.nio.file.Path;
import java.util.List;

import jeeanalyzer.Model.PathRow;

/** テーブル表示用（プロジェクト情報付き） */
final class PathRowView {
    final Path project;
    final List<Path> roots;
    final String projectName;
    final PathRow row;

    PathRowView(Path project, PathRow row) {
        this(new RegisteredProject(project), row);
    }

    PathRowView(RegisteredProject registered, PathRow row) {
        this.project = registered.primary;
        this.roots = registered.allRoots();
        this.projectName = registered.displayName();
        this.row = row;
    }

    String xhtml() { return nullToEmpty(row.screen); }
    String backingBean() {
        if (row.beanMethod != null && !row.beanMethod.isEmpty()) return row.beanMethod;
        return nullToEmpty(row.beanName);
    }
    String service() { return row.services == null || row.services.isEmpty() ? "" : String.join(" > ", row.services); }
    String dao() { return nullToEmpty(row.mapperMethod); }
    String sql() { return nullToEmpty(row.stmtType); }
    String table() { return nullToEmpty(row.tables); }

    /** ボタン名検索用（tagAttr ＋ 「value」補完） */
    String buttonSearchText() {
        String enriched = ControlLabelLookup.enrichTagAttr(this);
        if (enriched == null) enriched = "";
        String name = "";
        int a = enriched.indexOf('「');
        int b = enriched.indexOf('」');
        if (a >= 0 && b > a) name = enriched.substring(a + 1, b);
        return (enriched + " " + name + " " + nullToEmpty(row.tagAttr)).trim();
    }

    String searchBlob() {
        return String.join(" ", projectName, xhtml(), buttonSearchText(), backingBean(), service(), dao(), sql(), table(),
                nullToEmpty(row.el), nullToEmpty(row.definedIn), nullToEmpty(row.note), nullToEmpty(row.sqlSource));
    }

    /** GUI 表示対象外: プロパティ参照および Bean の getter/setter */
    boolean isGetterOrSetter() {
        if ("プロパティ".equals(row.kind)) return true;
        String name = beanMethodName();
        return name != null && isAccessorName(name);
    }

    private String beanMethodName() {
        String bm = row.beanMethod;
        if (bm == null || bm.isBlank()) return null;
        int hash = bm.indexOf('#');
        String name = hash >= 0 ? bm.substring(hash + 1) : bm;
        int cut = name.indexOf('（');
        if (cut < 0) cut = name.indexOf('(');
        if (cut > 0) name = name.substring(0, cut);
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    private static boolean isAccessorName(String name) {
        if (name.length() >= 4 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) return true;
        if (name.length() >= 4 && name.startsWith("set") && Character.isUpperCase(name.charAt(3))) return true;
        if (name.length() >= 3 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) return true;
        return false;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
