package jeeanalyzer;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import jeeanalyzer.Model.*;

/** xhtml (Facelets) から EL 式・テンプレート/インクルード参照を抽出 */
final class FaceletsScanner {
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<([A-Za-z_][\\w:.-]*)((?:\\s+[\\w:.-]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*/?>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([\\w:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.DOTALL);
    private static final Pattern EL = Pattern.compile("[#$]\\{((?:[^}'\"]|'[^']*'|\"[^\"]*\")*)\\}");
    private static final Pattern REF = Pattern.compile("(?<![\\w.\\]])([A-Za-z_]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)(\\s*\\()?");
    private static final Pattern BARE = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*$");

    static final Set<String> IMPLICIT = Set.of("facesContext", "view", "request", "session", "application", "param",
            "paramValues", "header", "headerValues", "cookie", "initParam", "flash", "component", "cc", "resource",
            "requestScope", "sessionScope", "applicationScope", "viewScope", "flowScope", "pageContext", "externalContext");
    static final Set<String> EL_KEYWORDS = Set.of("empty", "not", "and", "or", "eq", "ne", "lt", "gt", "le", "ge", "div",
            "mod", "true", "false", "null", "instanceof");
    /** メソッド式を取る属性 */
    static final Set<String> METHOD_ATTRS = Set.of("action", "actionListener", "listener", "valueChangeListener",
            "validator", "returnListener", "dialogReturn");

    Facelet scan(Path root, Path file) throws Exception {
        Facelet f = new Facelet();
        f.file = file;
        f.rel = Util.rel(root, file);
        String raw = Util.read(file);
        // コメントを同じ長さの空白に置換（行番号維持）
        Matcher cm = COMMENT.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (cm.find()) cm.appendReplacement(sb, Matcher.quoteReplacement(cm.group().replaceAll("[^\\n]", " ")));
        cm.appendTail(sb);
        String text = sb.toString();

        Matcher tm = TAG.matcher(text);
        int last = 0;
        while (tm.find()) {
            // タグ間テキスト中の EL
            addEls(f, text, last, text.substring(last, tm.start()), "(テキスト)", "", null);
            String tag = tm.group(1);
            String attrs = tm.group(2);
            int attrsOffset = tm.start(2);
            // 同一タグの属性を一度集めて value（ボタン名）を取る
            List<String[]> attrList = new ArrayList<>(); // name, val, offsetStr
            String controlLabel = null;
            Matcher am = ATTR.matcher(attrs);
            while (am.find()) {
                String name = am.group(1);
                String val = am.group(2) != null ? am.group(2) : am.group(3);
                int valOffset = attrsOffset + (am.group(2) != null ? am.start(2) : am.start(3));
                attrList.add(new String[]{name, val, String.valueOf(valOffset)});
                if ("value".equals(name) && val != null && !val.isBlank()) controlLabel = val.trim();
            }
            for (String[] a : attrList) {
                String name = a[0], val = a[1];
                int valOffset = Integer.parseInt(a[2]);
                if (name.equals("var") || name.equals("varStatus")) f.vars.add(val.trim());
                String local = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag;
                if (local.equals("param") && tag.startsWith("ui:") && name.equals("name")) f.vars.add(val.trim());
                if ((local.equals("composition") || local.equals("decorate")) && name.equals("template")) {
                    f.refsRaw.add(new String[]{"template", val, String.valueOf(Util.lineOf(text, valOffset))});
                } else if (local.equals("include") && name.equals("src")) {
                    f.refsRaw.add(new String[]{"include", val, String.valueOf(Util.lineOf(text, valOffset))});
                }
                addEls(f, text, valOffset, val, tag, name, controlLabel);
            }
            last = tm.end();
        }
        addEls(f, text, last, text.substring(last), "(テキスト)", "", null);
        return f;
    }

    private void addEls(Facelet f, String text, int baseOffset, String s, String tag, String attr, String controlLabel) {
        Matcher m = EL.matcher(s);
        while (m.find()) {
            String expr = m.group(1);
            int line = Util.lineOf(text, baseOffset + m.start());
            String noStr = expr.replaceAll("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"", "''");
            Matcher r = REF.matcher(noStr);
            boolean any = false;
            while (r.find()) {
                any = true;
                ElRef e = base(f, line, tag, attr, m.group(), controlLabel);
                e.root = r.group(1);
                e.member = r.group(2);
                e.call = r.group(3) != null;
                if (e.call) e.argCount = countArgs(noStr, r.end());
                f.els.add(e);
            }
            if (!any) {
                Matcher b = BARE.matcher(noStr);
                if (b.find() && !EL_KEYWORDS.contains(b.group(1))) {
                    ElRef e = base(f, line, tag, attr, m.group(), controlLabel);
                    e.root = b.group(1);
                    f.els.add(e);
                }
            }
        }
    }

    private ElRef base(Facelet f, int line, String tag, String attr, String expr, String controlLabel) {
        ElRef e = new ElRef();
        e.file = f.file;
        e.fileRel = f.rel;
        e.line = line;
        e.tag = tag;
        e.attr = attr;
        e.expression = expr;
        // action 系にだけボタン名を付与（value 自身の EL には付けない）
        if (controlLabel != null && METHOD_ATTRS.contains(attr)) e.controlLabel = controlLabel;
        return e;
    }

    /** '(' の直後から対応する ')' までのトップレベル引数数 */
    static int countArgs(String s, int from) {
        int depth = 0, commas = 0;
        boolean any = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') {
                if (depth == 0) return any ? commas + 1 : 0;
                depth--;
            } else if (c == ',' && depth == 0) commas++;
            else if (!Character.isWhitespace(c)) any = true;
        }
        return -1;
    }

    /** template / include の参照先を解決 */
    static void linkRefs(List<Facelet> all, List<Path> webRoots, Path root, List<Issue> issues) {
        Map<Path, Facelet> byPath = new HashMap<>();
        for (Facelet f : all) byPath.put(f.file.toAbsolutePath().normalize(), f);
        for (Facelet f : all) {
            for (Path wr : webRoots) {
                if (f.file.toAbsolutePath().normalize().startsWith(wr)) {
                    f.webPath = "/" + Util.rel(wr, f.file.toAbsolutePath().normalize());
                    break;
                }
            }
            if (f.webPath == null) f.webPath = "/" + f.rel;
        }
        for (Facelet f : all) {
            for (String[] ref : f.refsRaw) {
                String src = ref[1].trim();
                if (src.contains("#{") || src.contains("${")) {
                    issues.add(new Issue("動的インクルード", f.rel + ":" + ref[2], src, "EL で指定された " + ref[0] + " は静的に解決できません"));
                    continue;
                }
                Facelet target = null;
                List<Path> cands = new ArrayList<>();
                if (src.startsWith("/")) for (Path wr : webRoots) cands.add(wr.resolve(src.substring(1)));
                else cands.add(f.file.toAbsolutePath().getParent().resolve(src));
                for (Path c : cands) {
                    target = byPath.get(c.normalize());
                    if (target != null) break;
                }
                if (target == null) { // 末尾一致でフォールバック
                    String suffix = src.startsWith("/") ? src : "/" + src;
                    for (Facelet o : all) if (("/" + o.rel).endsWith(suffix)) { target = o; break; }
                }
                if (target == null) {
                    issues.add(new Issue(ref[0].equals("template") ? "テンプレート未検出" : "インクルード未検出",
                            f.rel + ":" + ref[2], src, "参照先 xhtml が見つかりません"));
                    continue;
                }
                target.referenced = true;
                if (ref[0].equals("template")) f.templates.add(target); else f.includes.add(target);
            }
        }
    }
}
