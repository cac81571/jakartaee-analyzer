package jeeanalyzer;

import java.nio.file.Path;
import java.util.*;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/** 解析モデル */
public final class Model {
    private Model() {}

    enum Role {
        BEAN("Bean"), SERVICE("Service"), MAPPER("Mapper"), OTHER("その他");
        final String label;
        Role(String label) { this.label = label; }
    }

    /** Java クラス/インターフェース */
    static final class ClassInfo {
        String fqn;
        String simpleName;
        String pkg;
        boolean isInterface;
        Path file;
        CompilationUnit cu;
        TypeDeclaration<?> decl;
        Set<String> annotations = new LinkedHashSet<>();
        String beanName;           // @Named の名前（Bean の場合）
        String scope;              // スコープアノテーション
        String superClassRaw;
        List<String> interfacesRaw = new ArrayList<>();
        String superClass;         // 解決済み FQN（インデックス内のみ）
        List<String> interfaces = new ArrayList<>();
        Map<String, String> fieldTypesRaw = new LinkedHashMap<>();
        Map<String, String> fieldTypes = new LinkedHashMap<>(); // name -> FQN（インデックス内のみ）
        Set<String> injectedFields = new LinkedHashSet<>();
        List<MethodDeclaration> methods = new ArrayList<>();
        Role role = Role.OTHER;

        String display() { return simpleName; }
    }

    /** MyBatis ステートメント */
    static final class SqlStatement {
        String namespace;
        String id;
        String type;          // select/insert/update/delete
        String sql;           // 正規化済み SQL
        boolean dynamic;
        String source;        // 定義元（XML ファイル:行 or @Select）
        boolean annotation;
        Set<String> tables = new LinkedHashSet<>();
        String key() { return namespace + "." + id; }
    }

    /** xhtml 内の EL 参照 */
    static final class ElRef {
        Path file;
        String fileRel;
        int line;
        String tag;
        String attr;
        String expression;     // #{...} 全体
        String root;           // 先頭識別子
        String member;         // 2 番目の識別子
        boolean call;          // 括弧付きメソッド呼び出し
        int argCount = -1;     // -1 = 不明
        /** 同一タグの value（ボタン名など）。リテラルまたは EL 文字列 */
        String controlLabel;
        // 解決結果
        String kind;           // メソッド / プロパティ / 暗黙オブジェクト / var変数 など
        ClassInfo bean;
        MethodDeclaration method;
        String resolvedMember; // getUsers など
        String problem;        // 未解決理由
        String producerDesc;   // @Produces @Named の場合 Owner#member
    }

    /** xhtml ファイル */
    public static final class Facelet {
        public Path file;
        public String rel;            // プロジェクトからの相対パス
        public String webPath;        // Web ルートからのパス (/user/userList.xhtml)
        public List<ElRef> els = new ArrayList<>();
        public Set<String> vars = new LinkedHashSet<>();
        public List<String[]> refsRaw = new ArrayList<>(); // [種別(template/include/decorate), src, line]
        public List<Facelet> templates = new ArrayList<>();
        public List<Facelet> includes = new ArrayList<>();
        public boolean referenced;
    }

    /** 呼び出しツリーのノード */
    static final class CallNode {
        ClassInfo cls;
        String method;
        MethodDeclaration decl;
        Role role;
        SqlStatement stmt;     // Mapper の場合
        String note;
        List<CallNode> children = new ArrayList<>();

        String label() { return cls.simpleName + "#" + method; }
    }

    /** CSV 1 行 = 1 呼び出しパス */
    public static final class PathRow {
        public String screen;
        public String definedIn;
        public int line;
        public String tagAttr;
        public String el;
        public String kind;
        public String beanName;
        public String beanMethod;     // Class#method
        public List<String> services = new ArrayList<>();
        /** GUI 用: services と対になるソース相対パス・行 */
        public List<String> serviceFiles = new ArrayList<>();
        public List<Integer> serviceLines = new ArrayList<>();
        public String mapperMethod;
        public String stmtType = "";
        public String tables = "";
        public String sqlSource = "";
        public String note = "";
        /** GUI 用: Bean ソース相対パス */
        public String beanFile = "";
        public int beanLine;
        /** GUI 用: Mapper ソース */
        public String mapperFile = "";
        public int mapperLine;
    }

    /** 未解決項目 */
    public record Issue(String category, String location, String target, String message) {}
}
