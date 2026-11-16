package jeeanalyzer.gui;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.tree.*;

/** 呼び出しパステーブ付きツリー（全経路 / XHTML↔テーブル） */
final class PathTreePanel extends JPanel {
    private static final Color HIT_FG = new Color(200, 0, 0);

    private final JTree xhtmlAllTree = new JTree();
    private final JTree tableAllTree = new JTree();
    private final JTree xhtmlTableTree = new JTree();
    private final JTree tableXhtmlTree = new JTree();
    private final JTree[] trees = {xhtmlAllTree, tableAllTree, xhtmlTableTree, tableXhtmlTree};
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel countLabel = new JLabel("0 件");
    private Consumer<PathTreeNode> onSelect = n -> {};

    private String searchQuery = "";
    private SearchFields searchFields = new SearchFields();

    PathTreePanel() {
        super(new BorderLayout(4, 4));
        for (JTree t : trees) configure(t);
        tabs.addTab("XHTMLから全て", new JScrollPane(xhtmlAllTree));
        tabs.addTab("テーブルから全て", new JScrollPane(tableAllTree));
        tabs.addTab("XHTMLとテーブル", new JScrollPane(xhtmlTableTree));
        tabs.addTab("テーブルとXHTML", new JScrollPane(tableXhtmlTree));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton collapseAll = new JButton("全て折りたたみ");
        JButton expandAll = new JButton("全て展開");
        collapseAll.setToolTipText("表示中タブのツリーを全て折りたたみ");
        expandAll.setToolTipText("表示中タブのツリーを全て展開");
        collapseAll.addActionListener(e -> collapseAll(activeTree()));
        expandAll.addActionListener(e -> expandAll(activeTree()));
        bar.add(collapseAll);
        bar.add(expandAll);
        for (int d = 1; d <= 5; d++) {
            final int depth = d;
            JButton btn = new JButton("展開" + d);
            btn.setToolTipText("表示中タブを深さ " + d + " まで展開");
            btn.addActionListener(e -> expandToDepth(activeTree(), depth));
            bar.add(btn);
        }

        add(bar, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        setRows(List.of(), "", new SearchFields());
    }

    private JTree activeTree() {
        int i = tabs.getSelectedIndex();
        if (i < 0 || i >= trees.length) return xhtmlAllTree;
        return trees[i];
    }

    void expandAll() { expandAll(activeTree()); }

    void collapseAll() { collapseAll(activeTree()); }

    private static void expandToDepth(JTree tree, int depth) {
        collapseAll(tree);
        TreeModel model = tree.getModel();
        if (model == null) return;
        expand(tree, new TreePath(model.getRoot()), Math.max(0, depth));
        tree.revalidate();
        tree.repaint();
    }

    private static void expandAll(JTree tree) {
        TreeModel model = tree.getModel();
        if (model == null) return;
        expand(tree, new TreePath(model.getRoot()), Integer.MAX_VALUE / 4);
        tree.revalidate();
        tree.repaint();
    }

    private static void collapseAll(JTree tree) {
        TreeModel model = tree.getModel();
        if (model == null) return;
        Object root = model.getRoot();
        collapseChildren(tree, new TreePath(root));
        int n = model.getChildCount(root);
        for (int i = 0; i < n; i++) {
            tree.collapsePath(new TreePath(root).pathByAddingChild(model.getChild(root, i)));
        }
        tree.revalidate();
        tree.repaint();
    }

    private static void collapseChildren(JTree tree, TreePath path) {
        Object node = path.getLastPathComponent();
        int count = tree.getModel().getChildCount(node);
        for (int i = 0; i < count; i++) {
            Object child = tree.getModel().getChild(node, i);
            TreePath childPath = path.pathByAddingChild(child);
            collapseChildren(tree, childPath);
            tree.collapsePath(childPath);
        }
    }

    void setOnSelect(Consumer<PathTreeNode> onSelect) {
        this.onSelect = onSelect != null ? onSelect : n -> {};
    }

    JLabel countLabel() { return countLabel; }

    void setRows(List<PathRowView> rows) {
        setRows(rows, searchQuery, searchFields);
    }

    void setRows(List<PathRowView> rows, String query, SearchFields fields) {
        List<PathRowView> list = rows == null ? List.of() : rows;
        this.searchQuery = query == null ? "" : query.trim();
        this.searchFields = fields != null ? fields : new SearchFields();
        countLabel.setText(list.size() + " パス");
        xhtmlAllTree.setModel(new DefaultTreeModel(PathTreeBuilder.buildFromXhtml(list)));
        tableAllTree.setModel(new DefaultTreeModel(PathTreeBuilder.buildFromTable(list)));
        xhtmlTableTree.setModel(new DefaultTreeModel(PathTreeBuilder.buildXhtmlToTable(list)));
        tableXhtmlTree.setModel(new DefaultTreeModel(PathTreeBuilder.buildTableToXhtml(list)));
        for (JTree t : trees) {
            if (searchQuery.isEmpty()) {
                expandShallow(t, 2);
            } else {
                expandHits(t);
            }
            t.repaint();
        }
    }

    private void configure(JTree tree) {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof PathTreeNode p) {
                    setText(format(p));
                    setToolTipText(p.view != null ? tooltip(p) : null);
                    if (isSearchHit(p) && !searchQuery.isEmpty()) {
                        setForeground(HIT_FG);
                    }
                    // 非ヒットは super が設定した前景色のまま
                }
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) return;
            Object last = path.getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof PathTreeNode p) {
                onSelect.accept(p);
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
    }

    /**
     * ハイライト判定は「このノードの表示ラベル」に検索語が含まれるかのみ。
     * PathRow 全体（例: tables="orders, customer"）で見ると、無関係な
     * 「テーブル orders」までヒット扱いになるため。
     */
    private boolean isSearchHit(PathTreeNode p) {
        if (searchQuery.isEmpty() || searchFields == null || !searchFields.any()) return false;
        String q = searchQuery.toLowerCase(Locale.ROOT);
        String label = format(p).toLowerCase(Locale.ROOT);
        if (!contains(label, q)) return false;
        return switch (p.kind) {
            case XHTML -> searchFields.xhtml;
            case BUTTON -> searchFields.button;
            case BEAN -> searchFields.bean;
            case SERVICE -> searchFields.service;
            case DAO -> searchFields.dao;
            case SQL, TABLE, CRUD -> searchFields.sqlTable;
            case GROUP, ROOT -> false;
        };
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    private void expandHits(JTree tree) {
        TreeModel model = tree.getModel();
        if (model == null) return;
        List<TreePath> hits = new ArrayList<>();
        collectHits(tree, new TreePath(model.getRoot()), hits);
        for (TreePath hit : hits) {
            TreePath p = hit.getParentPath();
            while (p != null) {
                tree.expandPath(p);
                p = p.getParentPath();
            }
            tree.expandPath(hit);
        }
        if (!hits.isEmpty()) {
            tree.scrollPathToVisible(hits.get(0));
        } else {
            expandShallow(tree, 2);
        }
    }

    private void collectHits(JTree tree, TreePath path, List<TreePath> out) {
        Object node = path.getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof PathTreeNode p) {
            if (isSearchHit(p)) out.add(path);
        }
        int count = tree.getModel().getChildCount(node);
        for (int i = 0; i < count; i++) {
            Object child = tree.getModel().getChild(node, i);
            collectHits(tree, path.pathByAddingChild(child), out);
        }
    }

    private static String format(PathTreeNode p) {
        return switch (p.kind) {
            case XHTML -> p.label;
            case BUTTON -> p.label;
            case BEAN -> "Bean  " + p.label;
            case SERVICE -> "Service  " + p.label;
            case DAO -> "DAO  " + p.label;
            case SQL -> p.label.startsWith("SQL ") ? p.label : "SQL " + p.label;
            case TABLE -> p.label.startsWith("テーブル") ? p.label : "テーブル  " + p.label;
            case CRUD -> p.label;
            case GROUP, ROOT -> p.label;
        };
    }

    private static String tooltip(PathTreeNode p) {
        PathRowView v = p.view;
        if (v == null) return null;
        return "<html>" + esc(p.label)
                + "<br/>EL: " + esc(v.row.el)
                + "<br/>定義: " + esc(v.row.definedIn) + ":" + v.row.line
                + (v.row.sqlSource == null || v.row.sqlSource.isBlank() ? "" : "<br/>SQL: " + esc(v.row.sqlSource))
                + "</html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void expandShallow(JTree tree, int depth) {
        TreeModel model = tree.getModel();
        if (model == null) return;
        expand(tree, new TreePath(model.getRoot()), depth);
    }

    private static void expand(JTree tree, TreePath path, int depth) {
        if (depth < 0) return;
        tree.expandPath(path);
        Object node = path.getLastPathComponent();
        int count = tree.getModel().getChildCount(node);
        for (int i = 0; i < count; i++) {
            Object child = tree.getModel().getChild(node, i);
            expand(tree, path.pathByAddingChild(child), depth - 1);
        }
    }
}
