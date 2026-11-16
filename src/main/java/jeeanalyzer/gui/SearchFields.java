package jeeanalyzer.gui;

/** 呼び出しパス検索の対象列 */
final class SearchFields {
    boolean xhtml = true;
    boolean button = true;
    boolean bean = true;
    boolean service = true;
    boolean dao = true;
    /** SQL 種別・定義元・テーブル名 */
    boolean sqlTable = true;

    boolean any() {
        return xhtml || button || bean || service || dao || sqlTable;
    }

    boolean matches(PathRowView v, String query) {
        if (query == null || query.isBlank()) return true;
        if (!any()) return false;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (xhtml && contains(v.xhtml(), q)) return true;
        if (button && contains(v.buttonSearchText(), q)) return true;
        if (bean && (contains(v.backingBean(), q) || contains(v.row.beanName, q))) return true;
        if (service && contains(v.service(), q)) return true;
        if (dao && contains(v.dao(), q)) return true;
        if (sqlTable && (contains(v.sql(), q) || contains(v.row.sqlSource, q) || contains(v.table(), q))) return true;
        return false;
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains(q);
    }
}
