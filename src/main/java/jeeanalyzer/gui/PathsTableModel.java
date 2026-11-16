package jeeanalyzer.gui;

import java.util.*;
import javax.swing.table.AbstractTableModel;

/** paths.csv 相当の一覧（簡略列）＋検索 */
final class PathsTableModel extends AbstractTableModel {
    static final String[] COLS = {"xhtml", "BackingBean", "Service", "Dao", "SQL", "TABLE"};

    private final List<PathRowView> all = new ArrayList<>();
    private final List<PathRowView> filtered = new ArrayList<>();
    private String query = "";
    private SearchFields fields = new SearchFields();

    void setRows(List<PathRowView> rows) {
        all.clear();
        all.addAll(rows);
        applyFilter();
    }

    void mergeRows(List<PathRowView> updated) {
        Set<String> keys = new HashSet<>();
        for (PathRowView v : updated) keys.add(key(v));
        all.removeIf(v -> keys.contains(key(v)));
        all.addAll(updated);
        applyFilter();
    }

    List<PathRowView> snapshot() { return new ArrayList<>(all); }

    List<PathRowView> filteredSnapshot() { return new ArrayList<>(filtered); }

    private static String key(PathRowView v) {
        return v.project.toAbsolutePath().normalize() + "\0" + normalize(v.xhtml());
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\\', '/');
        return t.startsWith("/") ? t : "/" + t;
    }

    void setQuery(String q) {
        this.query = q == null ? "" : q.trim();
        applyFilter();
    }

    String query() { return query; }

    void setSearchFields(SearchFields fields) {
        this.fields = fields != null ? fields : new SearchFields();
        applyFilter();
    }

    SearchFields searchFields() { return fields; }

    private void applyFilter() {
        filtered.clear();
        for (PathRowView r : all) {
            if (r.isGetterOrSetter()) continue; // getter / setter / プロパティは非表示
            if (query.isEmpty() || fields.matches(r, query)) filtered.add(r);
        }
        fireTableDataChanged();
    }

    PathRowView getAt(int viewRow) {
        if (viewRow < 0 || viewRow >= filtered.size()) return null;
        return filtered.get(viewRow);
    }

    int size() { return filtered.size(); }

    @Override public int getRowCount() { return filtered.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int column) { return COLS[column]; }
    @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
    @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return false; }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        PathRowView r = filtered.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> r.xhtml();
            case 1 -> r.backingBean();
            case 2 -> r.service();
            case 3 -> r.dao();
            case 4 -> r.sql();
            case 5 -> r.table();
            default -> "";
        };
    }
}
