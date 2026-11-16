package jeeanalyzer.gui;

import java.util.*;
import javax.swing.tree.DefaultMutableTreeNode;

/** PathRowView から呼び出しツリーを構築 */
final class PathTreeBuilder {
    private PathTreeBuilder() {}

    static DefaultMutableTreeNode buildFromXhtml(List<PathRowView> rows) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(PathTreeNode.root("XHTMLから全て"));
        // project → xhtml → …
        Map<String, DefaultMutableTreeNode> projects = new LinkedHashMap<>();
        for (PathRowView v : rows) {
            String projKey = v.project.toAbsolutePath().normalize().toString();
            DefaultMutableTreeNode proj = projects.computeIfAbsent(projKey, k ->
                    child(root, PathTreeNode.Kind.GROUP, v.projectName, null, null, -1));
            addXhtmlPath(proj, v);
        }
        pruneEmptyGroups(root);
        return root;
    }

    /** XHTML → ボタン → SQL（テーブル名） */
    static DefaultMutableTreeNode buildXhtmlToTable(List<PathRowView> rows) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(PathTreeNode.root("XHTMLとテーブル"));
        Map<String, DefaultMutableTreeNode> projects = new LinkedHashMap<>();
        for (PathRowView v : rows) {
            String sqlLabel = sqlNodeLabel(v);
            if (sqlLabel.isBlank()) continue;
            String projKey = v.project.toAbsolutePath().normalize().toString();
            DefaultMutableTreeNode proj = projects.computeIfAbsent(projKey, k ->
                    child(root, PathTreeNode.Kind.GROUP, v.projectName, null, null, -1));
            DefaultMutableTreeNode xhtml = findOrCreate(proj, PathTreeNode.Kind.XHTML, display(v.xhtml(), "(xhtml)"), v, "xhtml", -1);
            DefaultMutableTreeNode button = findOrCreate(xhtml, PathTreeNode.Kind.BUTTON, buttonLabel(v), v, "xhtml", -1);
            findOrCreate(button, PathTreeNode.Kind.SQL, sqlLabel, v, "sql", -1);
        }
        pruneEmptyGroups(root);
        return root;
    }

    /** テーブル → SQL（テーブル名） → XHTML　ボタン名 */
    static DefaultMutableTreeNode buildTableToXhtml(List<PathRowView> rows) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(PathTreeNode.root("テーブルとXHTML"));
        Map<String, DefaultMutableTreeNode> tableNodes = new LinkedHashMap<>();
        for (PathRowView v : rows) {
            String tableCsv = v.table();
            String sqlLabel = sqlNodeLabel(v);
            if (tableCsv.isBlank() || sqlLabel.isBlank()) continue;
            String leaf = display(v.xhtml(), "(xhtml)") + "　" + buttonLabel(v);
            for (String t : tableCsv.split(",\\s*")) {
                if (t.isBlank()) continue;
                DefaultMutableTreeNode tableNode = tableNodes.computeIfAbsent(t, name ->
                        child(root, PathTreeNode.Kind.TABLE, name, v, "sql", -1));
                DefaultMutableTreeNode sqlNode = findOrCreate(tableNode, PathTreeNode.Kind.SQL, sqlLabel, v, "sql", -1);
                findOrCreate(sqlNode, PathTreeNode.Kind.BUTTON, leaf, v, "xhtml", -1);
            }
        }
        sortChildrenByLabel(root);
        for (DefaultMutableTreeNode table : tableNodes.values()) {
            sortChildrenByLabel(table);
            for (int i = 0; i < table.getChildCount(); i++) {
                sortChildrenByLabel((DefaultMutableTreeNode) table.getChildAt(i));
            }
        }
        return root;
    }

    static DefaultMutableTreeNode buildFromTable(List<PathRowView> rows) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(PathTreeNode.root("テーブルから全て"));
        // table → SQL → DAO → Service → Bean → ボタン → XHTML（CRUD 階層なし）
        Map<String, DefaultMutableTreeNode> tableNodes = new LinkedHashMap<>();
        for (PathRowView v : rows) {
            String tableCsv = v.table();
            if (tableCsv.isBlank() || v.sql().isBlank()) continue;
            for (String t : tableCsv.split(",\\s*")) {
                if (t.isBlank()) continue;
                DefaultMutableTreeNode tableNode = tableNodes.computeIfAbsent(t, name ->
                        child(root, PathTreeNode.Kind.TABLE, name, v, "sql", -1));
                addReversePath(tableNode, v);
            }
        }
        sortChildrenByLabel(root);
        return root;
    }

    private static void addXhtmlPath(DefaultMutableTreeNode project, PathRowView v) {
        // XHTML → ボタン → Beanメソッド → Serviceメソッド → DAO → SQL → テーブル
        DefaultMutableTreeNode xhtml = findOrCreate(project, PathTreeNode.Kind.XHTML, display(v.xhtml(), "(xhtml)"), v, "xhtml", -1);
        DefaultMutableTreeNode button = findOrCreate(xhtml, PathTreeNode.Kind.BUTTON, buttonLabel(v), v, "xhtml", -1);

        String bean = v.backingBean();
        if (bean.isBlank()) bean = v.row.beanName != null && !v.row.beanName.isBlank() ? v.row.beanName : "(Beanなし)";
        DefaultMutableTreeNode parent = findOrCreate(button, PathTreeNode.Kind.BEAN, bean, v, "bean", -1);

        List<String> services = v.row.services == null ? List.of() : v.row.services;
        for (int i = 0; i < services.size(); i++) {
            String svc = services.get(i);
            if (svc == null || svc.isBlank()) continue;
            parent = findOrCreate(parent, PathTreeNode.Kind.SERVICE, cleanServiceLabel(svc), v, "service", i);
        }

        String dao = v.dao();
        if (!dao.isBlank()) {
            parent = findOrCreate(parent, PathTreeNode.Kind.DAO, dao, v, "dao", -1);
        }

        String sql = v.sql();
        if (!sql.isBlank()) {
            // 表示: SQL select (テーブル名、テーブル名)
            findOrCreate(parent, PathTreeNode.Kind.SQL, sqlNodeLabel(v), v, "sql", -1);
        } else if (!v.table().isBlank()) {
            for (String t : v.table().split(",\\s*")) {
                if (t.isBlank()) continue;
                findOrCreate(parent, PathTreeNode.Kind.TABLE, t, v, "sql", -1);
            }
        }
    }

    /** 例: select (users、customer) ※表示時に "SQL " 接頭辞を付与 */
    static String sqlNodeLabel(PathRowView v) {
        String sql = v.sql();
        if (sql == null || sql.isBlank()) return "";
        String tables = v.table();
        if (tables == null || tables.isBlank()) return sql;
        String joined = tables.replace(", ", "、").replace(",", "、");
        return sql + " (" + joined + ")";
    }

    /** 旧 CSV の「（IF: Xxx）」を表示から除去 */
    static String cleanServiceLabel(String label) {
        if (label == null) return "";
        return label.replaceAll("（IF:[^）]*）", "").trim();
    }

    private static String buttonLabel(PathRowView v) {
        String tag = ControlLabelLookup.enrichTagAttr(v);
        if (tag != null && !tag.isBlank() && !"text".equals(tag) && !"(テキスト)".equals(tag)) {
            // 例: h:commandButton@action「ログイン」（旧 CSV は xhtml から value を補完）
            return tag;
        }
        String el = v.row.el;
        return el != null && !el.isBlank() ? el : "(操作)";
    }

    private static void addReversePath(DefaultMutableTreeNode crudNode, PathRowView v) {
        DefaultMutableTreeNode parent = crudNode;
        String sql = v.sql();
        if (!sql.isBlank()) {
            parent = findOrCreate(parent, PathTreeNode.Kind.SQL, sqlNodeLabel(v), v, "sql", -1);
        }
        String dao = v.dao();
        if (!dao.isBlank()) {
            parent = findOrCreate(parent, PathTreeNode.Kind.DAO, dao, v, "dao", -1);
        }
        List<String> services = v.row.services == null ? List.of() : v.row.services;
        for (int i = services.size() - 1; i >= 0; i--) {
            String svc = services.get(i);
            if (svc == null || svc.isBlank()) continue;
            parent = findOrCreate(parent, PathTreeNode.Kind.SERVICE, cleanServiceLabel(svc), v, "service", i);
        }
        String bean = v.backingBean();
        if (bean.isBlank()) bean = "(Beanなし)";
        parent = findOrCreate(parent, PathTreeNode.Kind.BEAN, bean, v, "bean", -1);
        parent = findOrCreate(parent, PathTreeNode.Kind.BUTTON, buttonLabel(v), v, "xhtml", -1);
        findOrCreate(parent, PathTreeNode.Kind.XHTML, display(v.xhtml(), "(xhtml)"), v, "xhtml", -1);
    }

    private static DefaultMutableTreeNode findOrCreate(DefaultMutableTreeNode parent, PathTreeNode.Kind kind,
                                                       String label, PathRowView view, String hint, int hopIndex) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (c.getUserObject() instanceof PathTreeNode n && n.kind == kind && label.equals(n.label)
                    && n.hopIndex == hopIndex) {
                if (view != null && n.view == null) {
                    c.setUserObject(PathTreeNode.of(kind, label, view, hint, hopIndex));
                }
                return c;
            }
        }
        return child(parent, kind, label, view, hint, hopIndex);
    }

    private static DefaultMutableTreeNode child(DefaultMutableTreeNode parent, PathTreeNode.Kind kind,
                                                String label, PathRowView view, String hint, int hopIndex) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(PathTreeNode.of(kind, label, view, hint, hopIndex));
        parent.add(n);
        return n;
    }

    private static String display(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static void sortChildrenByLabel(DefaultMutableTreeNode parent) {
        List<DefaultMutableTreeNode> children = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) children.add((DefaultMutableTreeNode) parent.getChildAt(i));
        children.sort(Comparator.comparing(n -> String.valueOf(n.getUserObject()), String.CASE_INSENSITIVE_ORDER));
        parent.removeAllChildren();
        for (DefaultMutableTreeNode c : children) parent.add(c);
    }

    private static void pruneEmptyGroups(DefaultMutableTreeNode root) {
        // 単一プロジェクトならプロジェクト階層を畳む
        if (root.getChildCount() == 1) {
            DefaultMutableTreeNode only = (DefaultMutableTreeNode) root.getChildAt(0);
            if (only.getUserObject() instanceof PathTreeNode p && p.kind == PathTreeNode.Kind.GROUP) {
                root.removeAllChildren();
                while (only.getChildCount() > 0) {
                    DefaultMutableTreeNode c = (DefaultMutableTreeNode) only.getChildAt(0);
                    only.remove(0);
                    root.add(c);
                }
            }
        }
    }
}
