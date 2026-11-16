package jeeanalyzer;

import java.util.*;
import java.util.regex.*;

/** SQL の正規化とテーブル名抽出 */
final class SqlUtil {
    private SqlUtil() {}

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*(?!include).*?\\*/", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("(?i)\\b(FROM|JOIN|INTO|UPDATE|USING)\\b");
    private static final Pattern IDENT = Pattern.compile("^\\s*([A-Za-z_\"`\\[][\\w$.\"`\\]]*)");
    private static final Pattern CTE = Pattern.compile("(?i)(?:\\bWITH\\b(?:\\s+RECURSIVE)?|,)\\s*([A-Za-z_]\\w*)\\s+AS\\s*\\(");
    private static final Set<String> STOP = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION", "LIMIT", "OFFSET",
            "FETCH", "FOR", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "ON", "SET", "VALUES", "SELECT",
            "WINDOW", "MINUS", "EXCEPT", "INTERSECT", "RETURNING", "CONNECT", "START");
    private static final Set<String> NOT_TABLE = Set.of("SELECT", "DUAL", "LATERAL", "TABLE", "ONLY", "IGNORE", "LOW_PRIORITY");

    static String normalize(String sql) {
        String s = LINE_COMMENT.matcher(sql).replaceAll(" ");
        s = BLOCK_COMMENT.matcher(s).replaceAll(" ");
        s = s.replace("<script>", " ").replace("</script>", " ");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    /** FROM/JOIN/INSERT INTO/UPDATE/DELETE FROM/MERGE INTO の対象テーブルを抽出 */
    static List<String> tables(String sql) {
        String s = sql.replaceAll("[#$]\\{[^}]*\\}", "?").replaceAll("'(?:[^']|'')*'", "''");
        Set<String> ctes = new HashSet<>();
        Matcher cm = CTE.matcher(s);
        while (cm.find()) ctes.add(cm.group(1).toUpperCase(Locale.ROOT));
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            String kw = m.group(1).toUpperCase(Locale.ROOT);
            int pos = m.end();
            if (kw.equals("UPDATE")) {
                String before = s.substring(0, m.start()).trim().toUpperCase(Locale.ROOT);
                if (before.endsWith("FOR") || before.endsWith("KEY")) continue; // FOR UPDATE / ON DUPLICATE KEY UPDATE
            }
            if (kw.equals("FROM")) {
                // FROM a, b c, (subquery) d ... カンマ区切りを考慮
                for (String item : fromItems(s, pos)) add(out, item, ctes);
            } else {
                Matcher im = IDENT.matcher(s.substring(pos));
                if (im.find()) add(out, im.group(1), ctes);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, String> out, String name, Set<String> ctes) {
        if (name == null) return;
        name = name.replaceAll("[\"`\\[\\]]", "");
        if (name.isEmpty()) return;
        String up = name.toUpperCase(Locale.ROOT);
        if (NOT_TABLE.contains(up) || STOP.contains(up) || ctes.contains(up) || up.equals("?")) return;
        out.putIfAbsent(up, name);
    }

    private static List<String> fromItems(String s, int pos) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        int i = pos;
        outer:
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            if (c == ')') { if (depth == 0) break; depth--; }
            if (depth == 0) {
                if (c == ',') { items.add(cur.toString()); cur.setLength(0); continue; }
                if (Character.isLetter(c) && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_')) {
                    Matcher w = Pattern.compile("^[A-Za-z_]+").matcher(s.substring(i));
                    if (w.find() && STOP.contains(w.group().toUpperCase(Locale.ROOT))) break outer;
                }
            }
            cur.append(c);
        }
        items.add(cur.toString());
        List<String> names = new ArrayList<>();
        for (String it : items) {
            String t = it.trim();
            if (t.isEmpty() || t.startsWith("(")) continue; // サブクエリ内の FROM は別途検出される
            Matcher im = IDENT.matcher(t);
            if (im.find()) names.add(im.group(1));
        }
        return names;
    }
}
