# jakartaee-analyzer（試作版）

Jakarta EE（Faces + CDI + MyBatis）のソースを静的解析し、**画面ごと**に次の呼び出し関係を一覧化するツールです。

```
xhtml の EL 式 (#{bean.method} / #{bean.prop})
  → CDI バッキング Bean (@Named)
    → Service (@Inject / @EJB、インターフェース → 実装クラス)
      → MyBatis Mapper (@Mapper / mapper XML の namespace)
        → SQL（select/insert/update/delete）とテーブル名
```

- 言語: Java 17 以上で動作（JavaParser 3.28 を同梱した実行可能 jar）
- ソースはコンパイル不要。依存ライブラリの解決も不要です（import 文から型を解決）。

## 実行方法

```bash
# GUI（FlatLaf）— 引数なし、または --gui
java -jar dist/jakartaee-analyzer.jar
java -jar dist/jakartaee-analyzer.jar --gui
./run.sh            # Linux / macOS
run.bat             # Windows

# CLI
java -jar dist/jakartaee-analyzer.jar <解析するプロジェクトのディレクトリ> -o <出力先>

# 例: 同梱サンプルを解析
java -jar dist/jakartaee-analyzer.jar sample-app -o sample-output

# スクリプト（出力先省略時は ./analysis-output）
./run.sh /path/to/your-project out          # Linux / macOS
run.bat C:\work\your-project out           # Windows
```

### GUI でできること

- 解析対象プロジェクトを複数登録（一覧は `~/.jakartaee-analyzer/projects.txt` に保存）
- 解析する xhtml を複数選択（「全画面を解析」のチェックを外す）
- 呼び出しパスのツリー表示（タブ切替）
  - **XHTMLから全て**: XHTML → ボタン → Beanメソッド → Serviceメソッド → DAO → SQL → テーブル
  - **テーブルから全て**: テーブル → SQL → DAO → Service → Bean → ボタン → XHTML
  - **XHTMLとテーブル**: XHTML → テーブル（CRUD 文字）
  - **テーブルとXHTML**: テーブル → CRUD → XHTML
- 検索は xhtml / button / bean / service / dao / sql/table をチェックして対象列を指定
- パネル幅の目安は プロジェクト : パス : ソース = 1 : 2 : 3。プロジェクトパネルは表示切替可
- ノード選択で定義 xhtml・Bean・SQL 定義元を表示

オプション:

| オプション | 内容 |
|---|---|
| `-o, --out <dir>` | 出力先（既定: `./analysis-output`） |
| `-e, --encoding <cs>` | ソースの文字コード（既定 UTF-8。読めないファイルは Windows-31J で自動再試行） |
| `-w, --web-root <dir>` | Web ルート（`/WEB-INF/...` のような `/` 始まりのパスの起点）。既定は `WEB-INF` の親ディレクトリや `src/main/webapp` を自動検出。複数指定可 |
| `-x, --xhtml <list>` | 解析する画面のみ（webPath または相対パス。カンマ区切り） |
| `--merge` | 既存の `paths.csv` 等と画面単位でマージ（`-x` 指定時の既定） |
| `--replace` | 既存結果を破棄して今回分だけ出力（全画面解析時の既定） |
| `-g, --gui` | FlatLaf Swing GUI を起動 |

部分解析（`-x` または GUI で xhtml を絞る）では、未指定画面の結果を残して今回分だけ差し替えます。全画面解析は既定で置き換えです。

マルチモジュール構成の場合は、親ディレクトリを指定すると全モジュールをまとめて解析します（Bean 名が重複する場合はモジュール単位での実行を推奨）。

### ソースからビルドする場合

```bash
mvn package        # target/jakartaee-analyzer.jar が生成されます
```

## 出力ファイル

| ファイル | 内容 |
|---|---|
| `index.md` | 画面一覧、CRUD 図（画面 × テーブル）、Bean 一覧、Mapper SQL 一覧 |
| `screens/<画面>.md` | 画面ごとのレポート（Mermaid フローチャート、呼び出しパス表、使用 SQL 全文、未解決項目） |
| `paths.csv` / `paths.tsv` | 1 行 = 1 呼び出しパス（画面, 定義ファイル, 行, タグ@属性, EL式, 参照種別, Bean名, Bean#メソッド, Service呼び出し, Mapper#メソッド, SQL種別, テーブル, SQL定義元, 備考）。CSV は Excel 向けに BOM 付き UTF-8 |
| `crud.csv` | 画面 × テーブルの CRUD 図 |
| `unresolved.md` / `unresolved.csv` | 未解決・要確認の一覧（存在しない Bean / メソッド / getter、SQL 定義のない Mapper メソッド、インターフェースに無い XML ステートメント、実装クラスなし／複数、テンプレート未検出など）＋どの画面からも到達しない SQL |

Mermaid 図は GitHub / GitLab / VS Code（Markdown Preview Mermaid Support 拡張）などでそのまま表示できます。

## 解析ルール

- **画面**: どこからも `template` / `ui:include` されておらず、`/WEB-INF/`・`/resources/` 配下でない xhtml。テンプレート・インクルード先の EL も画面に含めて集計（`定義ファイル` 列で区別）。
- **EL**: `#{a.b}` / `#{a.b(...)}` / `${...}` を属性値・テキストから抽出。`action`・`actionListener`・`listener`・`valueChangeListener`・`validator` 属性と括弧付き呼び出しはメソッド、それ以外はプロパティ（`getXxx()` / `isXxx()`）として解決。`var`・`ui:param` の変数、暗黙オブジェクト（`facesContext` 等）、faces-config の resource-bundle は対象外。
- **Bean 名**: `@Named("名前")`、省略時はクラス名の先頭を小文字化。`@Model`、`@Produces @Named`、faces-config の `managed-bean` にも対応。
- **呼び出し追跡**: メソッド本体の `field.method(...)` / `this.field.method(...)` / `field::method` を追跡。対象は `@Inject` / `@EJB` / `@Resource` フィールド（コンストラクタ・セッター注入含む）、または Service / Mapper と判定された型のフィールド。同一クラスの private ヘルパーはインライン展開。インターフェースは実装クラスが 1 つならそれに解決（複数なら全展開＋警告）。Bean の `@PostConstruct` は画面で Bean を使う時点の呼び出しとして追加。
- **Mapper**: `@Mapper` 付きインターフェース、または mapper XML の namespace に一致するインターフェース。`namespace + "." + メソッド名` で XML のステートメントを、なければ `@Select/@Insert/@Update/@Delete` を参照。`<include refid>` を展開し、動的 SQL（`<if>` 等）はすべての分岐を含めた形でテーブルを抽出。
- **テーブル抽出**: `FROM`（カンマ区切り含む）/ `JOIN` / `INSERT INTO` / `UPDATE` / `DELETE FROM` / `MERGE INTO ... USING`。WITH 句の CTE 名・サブクエリは除外。

## 既知の制約（試作版）

- 型解決は import 文ベースの簡易方式（シンボルソルバー不使用）。ローカル変数経由・メソッド戻り値経由（`getService().foo()`）・static ユーティリティ経由の呼び出しは追跡しません。
- オーバーロードは引数の個数のみで区別。CDI の `@Alternative` / Qualifier による実装選択、`Instance<T>`、Producer メソッドから返される Service は考慮しません。
- EL はネストしたプロパティ（`#{bean.user.name}`）の先頭 1 段（`getUser()`）のみ解決。`#{bean[prop]}` 形式や `#{cc.attrs.xxx}` 経由の参照は対象外。
- Mapper の継承（汎用 BaseMapper 等）はメソッド探索のみ対応。`SqlSession#selectList("ns.id")` の文字列指定や `@SelectProvider` の SQL は特定できません。
- `${}` による文字列置換・`<bind>` を含む SQL、`EXTRACT(YEAR FROM x)` のような関数内の FROM はテーブル名の誤検出がありえます。
- `ui:include src="#{...}"` のような動的インクルードは解決できず、未解決一覧に出力します。
- JSP（.jsp）や Struts / Spring MVC の画面定義は対象外です。

## ディレクトリ構成

```
jakartaee-analyzer/
├── pom.xml / src/main/java/jeeanalyzer/   解析ツール本体
├── dist/jakartaee-analyzer.jar            実行可能 jar
├── run.sh / run.bat
├── sample-app/                            試験用サンプル（Jakarta EE 10 + MyBatis CDI）
└── sample-output/                         サンプルの解析結果（preview/ に Mermaid 図の PNG）
```

### サンプルに含めた確認用パターン

- 2 つ以上の Service を呼ぶ Bean（`UserEditBean#save` → `UserService` + `AuditService`）
- 複数 Mapper を呼ぶ Service（`OrderServiceImpl#cancel`）、Service → Service（`@EJB AuditService`、private ヘルパー経由）
- インターフェース + 実装（`OrderService` / `OrderServiceImpl`）、コンストラクタインジェクション（`OrderDetailBean`）
- `@Named("userEdit")` の明示名、getter 内の遅延ロード（`getDepartments()`）、`@PostConstruct`
- アノテーション SQL（`@Select` / `@Insert` / `@Delete`）と XML の混在、`@Mapper` なし Mapper、`<sql>`/`<include>`、動的 SQL
- テンプレート（`ui:composition template`）＋インクルード（`ui:include`）
- 意図的な不整合: 存在しない Bean（`orderSummaryBean`）、存在しないメソッド（`userEdit.cancelEdit`）、SQL のない Mapper メソッド（`InventoryMapper#restock`）、インターフェースに無い XML ステートメント（`OrderMapper.purgeDeleted`）
