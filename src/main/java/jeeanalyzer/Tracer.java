package jeeanalyzer;

import java.util.*;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;

import jeeanalyzer.Model.*;

/** EL 式の解決と、Bean → Service → Mapper の呼び出しツリー構築 */
final class Tracer {
    final JavaIndex java;
    final MapperIndex mappers;
    final List<Issue> issues = new ArrayList<>();
    private final Set<String> issueKeys = new HashSet<>();
    private final Map<String, List<CallNode>> memo = new HashMap<>();
    final Set<String> usedStatements = new HashSet<>();
    final Set<String> globalVars = new HashSet<>();
    final Set<String> bundles = new HashSet<>();

    Tracer(JavaIndex java, MapperIndex mappers) {
        this.java = java;
        this.mappers = mappers;
    }

    void issue(String cat, String loc, String target, String msg) {
        if (issueKeys.add(cat + "|" + loc + "|" + target)) issues.add(new Issue(cat, loc, target, msg));
    }

    /* ===================== EL 解決 ===================== */

    void resolve(Facelet f) {
        for (ElRef e : f.els) {
            String loc = f.rel + ":" + e.line;
            if (FaceletsScanner.IMPLICIT.contains(e.root)) { e.kind = "暗黙オブジェクト"; continue; }
            if (bundles.contains(e.root) && !java.beansByName.containsKey(e.root)) { e.kind = "リソースバンドル"; continue; }
            if (f.vars.contains(e.root) || (globalVars.contains(e.root) && !java.beansByName.containsKey(e.root))) {
                e.kind = "var変数"; continue;
            }
            ClassInfo bean = java.beansByName.get(e.root);
            if (bean == null && java.producers.containsKey(e.root)) {
                JavaIndex.Producer pr = java.producers.get(e.root);
                e.kind = "Producer";
                e.producerDesc = "@Produces " + pr.owner().simpleName + "#" + pr.member() + "（型: " + pr.typeRaw() + "）";
                ClassInfo tc = pr.typeFqn() == null ? null : java.byFqn.get(pr.typeFqn());
                if (e.member != null && tc != null && !e.call && getter(tc, e.member) == null && java.findMethod(tc, e.member, -1) == null) {
                    e.problem = tc.simpleName + " にプロパティ '" + e.member + "' の getter がありません";
                    issue("EL: プロパティ未定義", loc, e.expression, e.problem);
                }
                continue;
            }
            if (bean == null) {
                e.kind = "未解決";
                e.problem = "Bean '" + e.root + "' が見つかりません（@Named 未定義）";
                issue("EL: Bean未定義", loc, e.expression, e.problem);
                continue;
            }
            e.bean = bean;
            if (e.member == null) { e.kind = "Bean参照"; continue; }
            String localAttr = e.attr.contains(":") ? e.attr.substring(e.attr.indexOf(':') + 1) : e.attr;
            boolean methodExpr = e.call || FaceletsScanner.METHOD_ATTRS.contains(localAttr);
            if (methodExpr) {
                JavaIndex.MethodHit hit = java.findMethod(bean, e.member, e.call ? e.argCount : -1);
                e.kind = "メソッド";
                if (hit == null && !e.call) { // action に getter 名を書くケースは無いが念のためプロパティも確認
                    hit = getter(bean, e.member);
                    if (hit != null) e.kind = "プロパティ";
                }
                if (hit == null) {
                    e.problem = bean.simpleName + " にメソッド '" + e.member + "' がありません";
                    issue("EL: メソッド未定義", loc, e.expression, e.problem);
                } else {
                    e.method = hit.method();
                    e.resolvedMember = hit.method().getNameAsString();
                    if (hit.owner() != bean) e.bean = hit.owner();
                }
            } else {
                e.kind = "プロパティ";
                JavaIndex.MethodHit hit = getter(bean, e.member);
                if (hit == null) {
                    e.problem = bean.simpleName + " にプロパティ '" + e.member + "' の getter がありません";
                    issue("EL: プロパティ未定義", loc, e.expression, e.problem);
                } else {
                    e.method = hit.method();
                    e.resolvedMember = hit.method().getNameAsString();
                }
            }
        }
    }

    private JavaIndex.MethodHit getter(ClassInfo bean, String prop) {
        JavaIndex.MethodHit h = java.findMethod(bean, "get" + Util.capitalize(prop), 0);
        if (h == null || !h.method().getParameters().isEmpty()) h = java.findMethod(bean, "is" + Util.capitalize(prop), 0);
        if (h != null && !h.method().getParameters().isEmpty()) h = null;
        return h;
    }

    /* ===================== 呼び出しツリー ===================== */

    List<CallNode> trace(ClassInfo cls, MethodDeclaration md) {
        return trace(cls, md, new ArrayDeque<>());
    }

    private static String sig(ClassInfo c, MethodDeclaration m) {
        return c.fqn + "#" + m.getDeclarationAsString(false, false, false);
    }

    private List<CallNode> trace(ClassInfo cls, MethodDeclaration md, Deque<String> stack) {
        String key = sig(cls, md);
        if (memo.containsKey(key)) return memo.get(key);
        if (stack.contains(key) || stack.size() > 30) return List.of();
        stack.push(key);
        List<CallNode> out = new ArrayList<>();
        if (md.getBody().isPresent()) {
            Set<String> locals = JavaIndex.localNames(md);
            List<Node> calls = new ArrayList<>();
            md.getBody().get().walk(n -> { if (n instanceof MethodCallExpr || n instanceof MethodReferenceExpr) calls.add(n); });
            // ソース上の出現順に並べる
            calls.sort(Comparator.comparing(n -> n.getBegin().map(p -> p.line * 10000 + p.column).orElse(0)));
            for (Node n : calls) {
                Optional<Expression> scope;
                String name;
                int argc;
                if (n instanceof MethodCallExpr mc) {
                    scope = mc.getScope();
                    name = mc.getNameAsString();
                    argc = mc.getArguments().size();
                } else {
                    MethodReferenceExpr mr = (MethodReferenceExpr) n;
                    scope = Optional.of(mr.getScope());
                    name = mr.getIdentifier();
                    argc = -1;
                }
                if (scope.isEmpty() || scope.get().isThisExpr()) {
                    // 自クラス（親クラス含む）のヘルパーメソッドはインライン展開
                    JavaIndex.MethodHit hit = java.findMethod(cls, name, argc);
                    if (hit != null && hit.method().getBody().isPresent()) out.addAll(trace(hit.owner(), hit.method(), stack));
                    continue;
                }
                Expression sc = scope.get();
                String field = null;
                if (sc.isNameExpr() && !locals.contains(sc.asNameExpr().getNameAsString())) field = sc.asNameExpr().getNameAsString();
                else if (sc.isFieldAccessExpr() && sc.asFieldAccessExpr().getScope().isThisExpr()) field = sc.asFieldAccessExpr().getNameAsString();
                if (field == null) continue;
                String t = java.fieldType(cls, field);
                if (t == null) continue;
                ClassInfo tc = java.byFqn.get(t);
                boolean injected = java.isInjected(cls, field);
                if (tc.role == Role.OTHER && !injected) continue; // エンティティ等は対象外
                String loc = Util.rel(Main.root, cls.file) + ":" + n.getBegin().map(p -> p.line).orElse(0);
                out.addAll(callTarget(tc, name, argc, loc, stack));
            }
        }
        stack.pop();
        List<CallNode> merged = merge(out);
        memo.put(key, merged);
        return merged;
    }

    private List<CallNode> callTarget(ClassInfo tc, String name, int argc, String loc, Deque<String> stack) {
        List<CallNode> out = new ArrayList<>();
        if (tc.role == Role.MAPPER) {
            CallNode node = new CallNode();
            node.cls = tc;
            node.method = name;
            node.role = Role.MAPPER;
            JavaIndex.MethodHit hit = java.findMethod(tc, name, argc);
            if (hit == null) {
                node.note = "Mapper にメソッドなし";
                issue("Mapper: メソッド未定義", loc, tc.simpleName + "#" + name, tc.fqn + " にメソッド " + name + " がありません");
            } else {
                node.decl = hit.method();
                node.stmt = mappers.find(tc, hit.owner(), hit.method(), name);
                if (node.stmt == null) {
                    node.note = "SQL 定義なし";
                    issue("Mapper: SQL未定義", Util.rel(Main.root, hit.owner().file) + ":" + hit.method().getBegin().map(p -> p.line).orElse(0),
                            tc.simpleName + "#" + name, "Mapper XML（namespace=" + tc.fqn + "）にもアノテーションにも SQL がありません");
                } else {
                    usedStatements.add(node.stmt.key());
                }
            }
            out.add(node);
            return out;
        }
        if (tc.isInterface) {
            List<ClassInfo> impls = java.implementations(tc.fqn);
            if (impls.isEmpty()) {
                CallNode node = node(tc, name, null);
                node.note = "実装クラスなし";
                issue("インターフェース: 実装なし", loc, tc.simpleName + "#" + name, tc.fqn + " の実装クラスが見つかりません");
                out.add(node);
                return out;
            }
            if (impls.size() > 1) issue("インターフェース: 実装複数", loc, tc.simpleName,
                    tc.fqn + " の実装が複数あります（" + impls.stream().map(c -> c.simpleName).toList() + "）。すべてを展開します");
            for (ClassInfo impl : impls) {
                JavaIndex.MethodHit hit = java.findMethod(impl, name, argc);
                CallNode node = node(hit != null ? hit.owner() : impl, name, hit);
                node.note = (impls.size() > 1 ? "実装複数: " : "IF: ") + tc.simpleName;
                if (hit == null) node.note += " / メソッド未検出";
                else node.children.addAll(trace(hit.owner(), hit.method(), stack));
                out.add(node);
            }
            return out;
        }
        JavaIndex.MethodHit hit = java.findMethod(tc, name, argc);
        if (hit == null) {
            if (tc.role == Role.OTHER) return out;
            CallNode node = node(tc, name, null);
            node.note = "メソッド未検出";
            issue("Service: メソッド未定義", loc, tc.simpleName + "#" + name, tc.fqn + " にメソッド " + name + " がありません（継承元がソース外の可能性）");
            out.add(node);
            return out;
        }
        CallNode node = node(hit.owner(), name, hit);
        node.children.addAll(trace(hit.owner(), hit.method(), stack));
        // Bean → Bean の単純な getter 呼び出し等（下流なし）は省略
        if (tc.role == Role.BEAN && node.children.isEmpty()) return out;
        out.add(node);
        return out;
    }

    private CallNode node(ClassInfo c, String name, JavaIndex.MethodHit hit) {
        CallNode n = new CallNode();
        n.cls = c;
        n.method = name;
        n.decl = hit == null ? null : hit.method();
        n.role = c.role == Role.BEAN ? Role.BEAN : Role.SERVICE;
        return n;
    }

    /** 同一メソッド内で同じ呼び出しが複数回ある場合は 1 つにまとめる */
    private static List<CallNode> merge(List<CallNode> in) {
        LinkedHashMap<String, CallNode> m = new LinkedHashMap<>();
        for (CallNode n : in) m.putIfAbsent(n.cls.fqn + "#" + n.method, n);
        return new ArrayList<>(m.values());
    }

    /** ツリーを葉までのパスに展開 */
    static List<List<CallNode>> paths(List<CallNode> roots) {
        List<List<CallNode>> out = new ArrayList<>();
        for (CallNode r : roots) walk(r, new ArrayList<>(), out);
        return out;
    }

    private static void walk(CallNode n, List<CallNode> cur, List<List<CallNode>> out) {
        cur.add(n);
        if (n.children.isEmpty()) out.add(new ArrayList<>(cur));
        else for (CallNode c : n.children) walk(c, cur, out);
        cur.remove(cur.size() - 1);
    }
}
