package jeeanalyzer;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import jeeanalyzer.Model.*;

/** MyBatis Mapper XML / アノテーション SQL のインデックス */
final class MapperIndex {
    final Map<String, SqlStatement> statements = new LinkedHashMap<>(); // namespace.id -> stmt
    final Map<String, Path> namespaceFiles = new LinkedHashMap<>();
    final Map<String, Element> sqlFragments = new HashMap<>();          // namespace.id -> <sql>
    final List<Issue> issues = new ArrayList<>();

    static final Set<String> STMT_TAGS = Set.of("select", "insert", "update", "delete");
    static final Map<String, String> SQL_ANN = Map.of(
            "Select", "select", "Insert", "insert", "Update", "update", "Delete", "delete",
            "SelectProvider", "select", "InsertProvider", "insert", "UpdateProvider", "update", "DeleteProvider", "delete");

    void parseXml(Path root, List<Path> xmlFiles) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        List<Object[]> docs = new ArrayList<>();
        for (Path p : xmlFiles) {
            String text = Util.read(p);
            if (!text.contains("<mapper")) continue;
            Document doc;
            try {
                DocumentBuilder b = f.newDocumentBuilder();
                b.setErrorHandler(null);
                doc = b.parse(new InputSource(new StringReader(text)));
            } catch (Exception e) {
                issues.add(new Issue("Mapper XML解析エラー", Util.rel(root, p), "", e.getMessage()));
                continue;
            }
            Element m = doc.getDocumentElement();
            if (!"mapper".equals(m.getTagName())) continue;
            String ns = m.getAttribute("namespace");
            if (ns.isEmpty()) {
                issues.add(new Issue("Mapper XML namespace なし", Util.rel(root, p), "", "namespace 属性がありません"));
                continue;
            }
            namespaceFiles.put(ns, p);
            NodeList sqls = m.getElementsByTagName("sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                Element s = (Element) sqls.item(i);
                sqlFragments.put(ns + "." + s.getAttribute("id"), s);
            }
            docs.add(new Object[]{p, m, ns, text});
        }
        for (Object[] d : docs) {
            Path p = (Path) d[0];
            Element m = (Element) d[1];
            String ns = (String) d[2];
            String text = (String) d[3];
            NodeList children = m.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element e) || !STMT_TAGS.contains(e.getTagName())) continue;
                SqlStatement st = new SqlStatement();
                st.namespace = ns;
                st.id = e.getAttribute("id");
                st.type = e.getTagName();
                boolean[] dyn = {false};
                StringBuilder sb = new StringBuilder();
                flatten(e, ns, sb, dyn, 0);
                st.sql = SqlUtil.normalize(sb.toString());
                st.dynamic = dyn[0];
                int idx = text.indexOf("id=\"" + st.id + "\"");
                st.source = Util.rel(root, p) + (idx >= 0 ? ":" + Util.lineOf(text, idx) : "");
                st.tables.addAll(SqlUtil.tables(st.sql));
                statements.put(st.key(), st);
            }
        }
    }

    /** 動的 SQL タグを展開して SQL 文字列を組み立てる（条件はすべて真とみなす） */
    private void flatten(Node n, String ns, StringBuilder sb, boolean[] dyn, int depth) {
        if (depth > 20) return;
        NodeList cs = n.getChildNodes();
        for (int i = 0; i < cs.getLength(); i++) {
            Node c = cs.item(i);
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue()).append(' ');
            } else if (c instanceof Element e) {
                String tag = e.getTagName();
                switch (tag) {
                    case "include" -> {
                        String ref = e.getAttribute("refid");
                        Element frag = sqlFragments.get(ref.contains(".") ? ref : ns + "." + ref);
                        if (frag != null) flatten(frag, ns, sb, dyn, depth + 1);
                        else sb.append(" /*include ").append(ref).append(" 未解決*/ ");
                    }
                    case "selectKey" -> { /* 別ステートメントなので除外 */ }
                    case "where" -> { dyn[0] = true; sb.append(" WHERE "); flatten(e, ns, sb, dyn, depth + 1); }
                    case "set" -> { dyn[0] = true; sb.append(" SET "); flatten(e, ns, sb, dyn, depth + 1); }
                    case "trim" -> {
                        dyn[0] = true;
                        sb.append(' ').append(e.getAttribute("prefix")).append(' ');
                        flatten(e, ns, sb, dyn, depth + 1);
                        sb.append(' ').append(e.getAttribute("suffix")).append(' ');
                    }
                    case "foreach" -> {
                        dyn[0] = true;
                        sb.append(' ').append(e.getAttribute("open"));
                        flatten(e, ns, sb, dyn, depth + 1);
                        sb.append(e.getAttribute("close")).append(' ');
                    }
                    case "bind" -> dyn[0] = true;
                    default -> { // if / choose / when / otherwise など
                        dyn[0] = true;
                        flatten(e, ns, sb, dyn, depth + 1);
                    }
                }
            }
        }
    }

    /** SQL アノテーションを返す（無ければ null） */
    static AnnotationExpr sqlAnnotation(MethodDeclaration m) {
        for (AnnotationExpr a : m.getAnnotations()) if (SQL_ANN.containsKey(a.getName().getIdentifier())) return a;
        return null;
    }

    /** Mapper インターフェースのメソッドに対応する SQL を探す */
    SqlStatement find(ClassInfo mapper, ClassInfo declaring, MethodDeclaration m, String methodName) {
        SqlStatement st = statements.get(mapper.fqn + "." + methodName);
        if (st == null && declaring != null) st = statements.get(declaring.fqn + "." + methodName);
        if (st != null) return st;
        if (m != null) {
            AnnotationExpr a = sqlAnnotation(m);
            if (a != null) {
                String key = "@" + (declaring != null ? declaring.fqn : mapper.fqn) + "." + methodName;
                SqlStatement cached = statements.get(key);
                if (cached != null) return cached;
                SqlStatement s = new SqlStatement();
                s.namespace = declaring != null ? declaring.fqn : mapper.fqn;
                s.id = methodName;
                String an = a.getName().getIdentifier();
                s.type = SQL_ANN.get(an);
                s.annotation = true;
                if (an.endsWith("Provider")) {
                    s.sql = "/* " + a + " （Provider のため SQL は静的に特定不可）*/";
                } else {
                    String v = JavaIndex.annotationValue(a, "value");
                    s.sql = SqlUtil.normalize(v == null ? "" : v);
                    s.dynamic = s.sql.contains("<script>");
                    s.tables.addAll(SqlUtil.tables(s.sql));
                }
                s.source = Util.rel(Main.root, (declaring != null ? declaring : mapper).file)
                        + ":" + m.getBegin().map(b -> b.line).orElse(0) + " @" + an;
                statements.put(key, s);
                return s;
            }
        }
        return null;
    }
}
