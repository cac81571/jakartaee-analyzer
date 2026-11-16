package jeeanalyzer.gui;

/** ツリーノードの表示データ */
final class PathTreeNode {
    enum Kind { ROOT, GROUP, XHTML, BUTTON, BEAN, SERVICE, DAO, SQL, TABLE, CRUD }

    final Kind kind;
    final String label;
    /** ソース表示用 */
    final PathRowView view;
    /** ソースペインの表示対象ヒント: xhtml / bean / service / dao / sql */
    final String sourceHint;
    /** services / serviceFiles のインデックス（SERVICE 用）。それ以外は -1 */
    final int hopIndex;

    PathTreeNode(Kind kind, String label, PathRowView view, String sourceHint, int hopIndex) {
        this.kind = kind;
        this.label = label;
        this.view = view;
        this.sourceHint = sourceHint;
        this.hopIndex = hopIndex;
    }

    static PathTreeNode root(String label) {
        return new PathTreeNode(Kind.ROOT, label, null, null, -1);
    }

    static PathTreeNode of(Kind kind, String label, PathRowView view, String hint) {
        return new PathTreeNode(kind, label, view, hint, -1);
    }

    static PathTreeNode of(Kind kind, String label, PathRowView view, String hint, int hopIndex) {
        return new PathTreeNode(kind, label, view, hint, hopIndex);
    }

    @Override
    public String toString() {
        return label;
    }
}
