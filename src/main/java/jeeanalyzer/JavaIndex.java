package jeeanalyzer;

import java.nio.file.Path;
import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import jeeanalyzer.Model.*;

/** Java ソースのインデックス（JavaParser 使用、シンボルソルバーは使わず import から型解決） */
final class JavaIndex {
    final Map<String, ClassInfo> byFqn = new LinkedHashMap<>();
    final Map<String, List<ClassInfo>> bySimple = new HashMap<>();
    final Map<String, ClassInfo> beansByName = new LinkedHashMap<>();
    /** @Produces @Named で EL に公開される値 */
    final Map<String, Producer> producers = new LinkedHashMap<>();
    record Producer(String name, ClassInfo owner, String member, String typeFqn, String typeRaw) {}
    final List<Issue> issues = new ArrayList<>();

    static final Set<String> INJECT_ANN = Set.of("Inject", "EJB", "Resource", "Autowired", "PersistenceContext");
    static final Set<String> SCOPE_ANN = Set.of("RequestScoped", "SessionScoped", "ApplicationScoped", "ConversationScoped",
            "ViewScoped", "FlowScoped", "Dependent", "Singleton", "Model");
    static final Set<String> SERVICE_ANN = Set.of("Stateless", "Stateful", "Singleton", "ApplicationScoped", "RequestScoped",
            "Dependent", "SessionScoped", "Transactional", "Service", "Component", "Repository", "Local", "Remote");

    void parse(Path root, List<Path> files) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        JavaParser parser = new JavaParser(cfg);
        for (Path f : files) {
            ParseResult<CompilationUnit> r = parser.parse(Util.read(f));
            if (r.getResult().isEmpty()) {
                issues.add(new Issue("Java構文解析エラー", Util.rel(root, f), "", r.getProblems().toString()));
                continue;
            }
            if (!r.isSuccessful()) {
                issues.add(new Issue("Java構文解析警告", Util.rel(root, f), "", r.getProblems().get(0).getVerboseMessage()));
            }
            CompilationUnit cu = r.getResult().get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (!(td instanceof ClassOrInterfaceDeclaration)) continue; // enum/record は対象外
                ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) td;
                ClassInfo ci = new ClassInfo();
                ci.cu = cu;
                ci.decl = td;
                ci.file = f;
                ci.pkg = pkg;
                ci.simpleName = td.getNameAsString();
                ci.fqn = td.getFullyQualifiedName().orElse((pkg.isEmpty() ? "" : pkg + ".") + ci.simpleName);
                ci.isInterface = cd.isInterface();
                for (AnnotationExpr a : td.getAnnotations()) ci.annotations.add(a.getName().getIdentifier());
                if (cd.isInterface()) {
                    cd.getExtendedTypes().forEach(t -> ci.interfacesRaw.add(t.getNameWithScope()));
                } else {
                    cd.getExtendedTypes().forEach(t -> ci.superClassRaw = t.getNameWithScope());
                    cd.getImplementedTypes().forEach(t -> ci.interfacesRaw.add(t.getNameWithScope()));
                }
                // フィールド
                for (FieldDeclaration fd : td.getFields()) {
                    boolean inj = fd.getAnnotations().stream().anyMatch(a -> INJECT_ANN.contains(a.getName().getIdentifier()));
                    for (VariableDeclarator v : fd.getVariables()) {
                        ci.fieldTypesRaw.put(v.getNameAsString(), rawType(v.getType()));
                        if (inj) ci.injectedFields.add(v.getNameAsString());
                    }
                }
                // @Inject コンストラクタ / セッター経由の注入
                List<CallableDeclaration<?>> injCallables = new ArrayList<>();
                td.getConstructors().stream().filter(c -> c.isAnnotationPresent("Inject")).forEach(injCallables::add);
                td.getMethods().stream().filter(m -> m.isAnnotationPresent("Inject")).forEach(injCallables::add);
                for (CallableDeclaration<?> c : injCallables) {
                    Set<String> params = new HashSet<>();
                    c.getParameters().forEach(p -> params.add(p.getNameAsString()));
                    for (AssignExpr ae : c.findAll(AssignExpr.class)) {
                        String target = fieldNameOf(ae.getTarget());
                        if (target != null && ae.getValue().isNameExpr() && params.contains(ae.getValue().asNameExpr().getNameAsString())) {
                            ci.injectedFields.add(target);
                        }
                    }
                }
                ci.methods.addAll(td.getMethods());
                byFqn.put(ci.fqn, ci);
                bySimple.computeIfAbsent(ci.simpleName, k -> new ArrayList<>()).add(ci);
            }
        }
    }

    static String fieldNameOf(Expression e) {
        if (e.isFieldAccessExpr() && e.asFieldAccessExpr().getScope().isThisExpr()) return e.asFieldAccessExpr().getNameAsString();
        if (e.isNameExpr()) return e.asNameExpr().getNameAsString();
        return null;
    }

    static String rawType(Type t) {
        if (t.isClassOrInterfaceType()) {
            ClassOrInterfaceType c = t.asClassOrInterfaceType();
            return c.getNameWithScope();
        }
        return t.asString();
    }

    /** インポート情報から型名を FQN に解決（インデックス内のクラスのみ） */
    String resolveType(ClassInfo ctx, String raw) {
        if (raw == null) return null;
        int lt = raw.indexOf('<');
        if (lt >= 0) raw = raw.substring(0, lt);
        if (byFqn.containsKey(raw)) return raw;
        String first = raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw;
        String rest = raw.contains(".") ? raw.substring(raw.indexOf('.')) : "";
        // ネストクラス
        String nested = ctx.fqn + "." + raw;
        if (byFqn.containsKey(nested)) return nested;
        for (ImportDeclaration imp : ctx.cu.getImports()) {
            if (imp.isStatic()) continue;
            String n = imp.getNameAsString();
            if (!imp.isAsterisk() && (n.endsWith("." + first) || n.equals(first))) {
                String cand = n + rest;
                if (byFqn.containsKey(cand)) return cand;
            }
        }
        String samePkg = (ctx.pkg.isEmpty() ? "" : ctx.pkg + ".") + raw;
        if (byFqn.containsKey(samePkg)) return samePkg;
        for (ImportDeclaration imp : ctx.cu.getImports()) {
            if (imp.isStatic() || !imp.isAsterisk()) continue;
            String cand = imp.getNameAsString() + "." + raw;
            if (byFqn.containsKey(cand)) return cand;
        }
        List<ClassInfo> l = bySimple.get(raw);
        if (l != null && l.size() == 1) return l.get(0).fqn;
        return null;
    }

    /** 型解決・ロール判定（mapperNamespaces = mapper XML の namespace 一覧） */
    void link(Set<String> mapperNamespaces) {
        for (ClassInfo ci : byFqn.values()) {
            ci.superClass = resolveType(ci, ci.superClassRaw);
            for (String i : ci.interfacesRaw) {
                String r = resolveType(ci, i);
                if (r != null) ci.interfaces.add(r);
            }
            ci.fieldTypesRaw.forEach((n, t) -> {
                String r = resolveType(ci, t);
                if (r != null) ci.fieldTypes.put(n, r);
            });
        }
        for (ClassInfo ci : byFqn.values()) {
            Optional<AnnotationExpr> named = ci.decl.getAnnotationByName("Named");
            boolean model = ci.annotations.contains("Model");
            boolean managedBean = ci.annotations.contains("ManagedBean");
            if (named.isPresent() || model || managedBean) {
                ci.role = Role.BEAN;
                String name = null;
                AnnotationExpr a = named.orElse(managedBean ? ci.decl.getAnnotationByName("ManagedBean").get() : null);
                if (a != null) name = annotationValue(a, "value", "name");
                if (name == null || name.isEmpty()) name = Util.decapitalize(ci.simpleName);
                ci.beanName = name;
                ci.scope = ci.annotations.stream().filter(SCOPE_ANN::contains).findFirst().orElse(model ? "RequestScoped" : "Dependent");
                ClassInfo dup = beansByName.put(name, ci);
                if (dup != null) issues.add(new Issue("Bean名重複", Util.rel(Main.root, ci.file), name,
                        "Bean 名 '" + name + "' が " + dup.fqn + " と " + ci.fqn + " で重複しています"));
            } else if (ci.isInterface && (ci.annotations.contains("Mapper") || mapperNamespaces.contains(ci.fqn)
                    || ci.methods.stream().anyMatch(m -> MapperIndex.sqlAnnotation(m) != null))) {
                ci.role = Role.MAPPER;
            } else if (ci.annotations.stream().anyMatch(SERVICE_ANN::contains)) {
                ci.role = Role.SERVICE;
            }
        }
        // @Produces @Named のプロデューサー（フィールド / メソッド）
        for (ClassInfo ci : byFqn.values()) {
            for (FieldDeclaration fd : ci.decl.getFields()) {
                if (!fd.isAnnotationPresent("Produces") || !fd.isAnnotationPresent("Named")) continue;
                String n = annotationValue(fd.getAnnotationByName("Named").get(), "value");
                for (VariableDeclarator v : fd.getVariables()) {
                    String name = n == null || n.isEmpty() ? v.getNameAsString() : n;
                    String raw = rawType(v.getType());
                    producers.put(name, new Producer(name, ci, v.getNameAsString(), resolveType(ci, raw), raw));
                }
            }
            for (MethodDeclaration md : ci.methods) {
                if (!md.isAnnotationPresent("Produces") || !md.isAnnotationPresent("Named")) continue;
                String name = annotationValue(md.getAnnotationByName("Named").get(), "value");
                if (name == null || name.isEmpty()) {
                    String m = md.getNameAsString();
                    if (m.startsWith("get") && m.length() > 3) name = Util.decapitalize(m.substring(3));
                    else if (m.startsWith("is") && m.length() > 2) name = Util.decapitalize(m.substring(2));
                    else name = m;
                }
                String raw = rawType(md.getType());
                producers.put(name, new Producer(name, ci, md.getNameAsString() + "()", resolveType(ci, raw), raw));
            }
        }
        // インターフェースの実装に Service がある場合、インターフェースも Service 扱い
        for (ClassInfo ci : byFqn.values()) {
            if (ci.role == Role.OTHER && ci.isInterface && !implementations(ci.fqn).isEmpty()
                    && implementations(ci.fqn).stream().anyMatch(c -> c.role == Role.SERVICE)) ci.role = Role.SERVICE;
        }
    }

    static String annotationValue(AnnotationExpr a, String... keys) {
        if (a.isSingleMemberAnnotationExpr()) return literal(a.asSingleMemberAnnotationExpr().getMemberValue());
        if (a.isNormalAnnotationExpr()) {
            for (MemberValuePair p : a.asNormalAnnotationExpr().getPairs())
                for (String k : keys) if (p.getNameAsString().equals(k)) return literal(p.getValue());
        }
        return null;
    }

    /** 文字列リテラル（連結・配列を含む）を連結して返す */
    static String literal(Expression e) {
        if (e.isStringLiteralExpr()) return e.asStringLiteralExpr().asString();
        if (e.isTextBlockLiteralExpr()) return e.asTextBlockLiteralExpr().asString();
        if (e.isBinaryExpr()) {
            String l = literal(e.asBinaryExpr().getLeft()), r = literal(e.asBinaryExpr().getRight());
            return (l == null ? "" : l) + (r == null ? "" : r);
        }
        if (e.isArrayInitializerExpr()) {
            StringBuilder sb = new StringBuilder();
            for (Expression v : e.asArrayInitializerExpr().getValues()) {
                String s = literal(v);
                if (s != null) sb.append(s).append(' ');
            }
            return sb.toString().trim();
        }
        if (e.isEnclosedExpr()) return literal(e.asEnclosedExpr().getInner());
        return null;
    }

    /** 指定インターフェースの実装クラス（具象クラスのみ、継承経由も含む） */
    List<ClassInfo> implementations(String ifaceFqn) {
        List<ClassInfo> out = new ArrayList<>();
        for (ClassInfo c : byFqn.values()) {
            if (c.isInterface || ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) c.decl).isAbstract()) continue;
            if (allSupertypes(c).contains(ifaceFqn)) out.add(c);
        }
        return out;
    }

    Set<String> allSupertypes(ClassInfo c) {
        Set<String> out = new LinkedHashSet<>();
        Deque<ClassInfo> q = new ArrayDeque<>(List.of(c));
        while (!q.isEmpty()) {
            ClassInfo x = q.poll();
            List<String> sup = new ArrayList<>(x.interfaces);
            if (x.superClass != null) sup.add(x.superClass);
            for (String s : sup) if (out.add(s) && byFqn.containsKey(s)) q.add(byFqn.get(s));
        }
        return out;
    }

    /** クラス階層をたどってメソッドを探す（argCount = -1 は引数数不問） */
    MethodHit findMethod(ClassInfo c, String name, int argCount) {
        Set<String> seen = new HashSet<>();
        Deque<ClassInfo> q = new ArrayDeque<>(List.of(c));
        MethodHit loose = null;
        while (!q.isEmpty()) {
            ClassInfo x = q.poll();
            if (!seen.add(x.fqn)) continue;
            for (MethodDeclaration m : x.methods) {
                if (!m.getNameAsString().equals(name)) continue;
                if (argCount < 0 || m.getParameters().size() == argCount
                        || (m.getParameters().isNonEmpty() && m.getParameters().getLast().get().isVarArgs())) return new MethodHit(x, m);
                if (loose == null) loose = new MethodHit(x, m);
            }
            if (x.superClass != null && byFqn.containsKey(x.superClass)) q.add(byFqn.get(x.superClass));
            for (String i : x.interfaces) if (byFqn.containsKey(i)) q.add(byFqn.get(i));
        }
        return loose;
    }

    record MethodHit(ClassInfo owner, MethodDeclaration method) {}

    /** フィールド型をクラス階層から取得 */
    String fieldType(ClassInfo c, String field) {
        ClassInfo x = c;
        Set<String> seen = new HashSet<>();
        while (x != null && seen.add(x.fqn)) {
            if (x.fieldTypes.containsKey(field)) return x.fieldTypes.get(field);
            if (x.fieldTypesRaw.containsKey(field)) return null; // 型はインデックス外
            x = x.superClass == null ? null : byFqn.get(x.superClass);
        }
        return null;
    }

    boolean isInjected(ClassInfo c, String field) {
        ClassInfo x = c;
        Set<String> seen = new HashSet<>();
        while (x != null && seen.add(x.fqn)) {
            if (x.injectedFields.contains(field)) return true;
            if (x.fieldTypesRaw.containsKey(field)) return false;
            x = x.superClass == null ? null : byFqn.get(x.superClass);
        }
        return false;
    }

    /** メソッド内のローカル変数・引数名（フィールドと区別するため） */
    static Set<String> localNames(Node body) {
        Set<String> s = new HashSet<>();
        body.findAll(Parameter.class).forEach(p -> s.add(p.getNameAsString()));
        body.findAll(VariableDeclarator.class).forEach(v -> s.add(v.getNameAsString()));
        return s;
    }

}
