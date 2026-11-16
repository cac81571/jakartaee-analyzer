# 画面: /user/userEdit.xhtml

- ファイル: `src/main/webapp/user/userEdit.xhtml`
- テンプレート / インクルード:
  - template: `src/main/webapp/WEB-INF/templates/layout.xhtml`（`src/main/webapp/user/userEdit.xhtml` から）
  - include: `src/main/webapp/WEB-INF/templates/header.xhtml`（`src/main/webapp/WEB-INF/templates/layout.xhtml` から）
- 使用 Bean:
  - `userEdit` → com.example.app.web.UserEditBean（@ViewScoped）
  - `loginBean` → com.example.app.web.LoginBean（@SessionScoped）

## 呼び出しフロー

メソッド式と、下流（Service/Mapper）の呼び出しがあるプロパティのみ図示しています。全参照は下の表を参照してください。

```mermaid
flowchart LR
    n0[["/user/userEdit.xhtml"]]
    n1[/"/WEB-INF/templates/layout.xhtml"/]
    n2[/"/WEB-INF/templates/header.xhtml"/]
    n3("UserEditBean.init()")
    n4["UserService.find()"]
    n5{{"UserMapper.selectById"}}
    n6[("users")]
    n7("UserEditBean.getDepartments()")
    n8["DepartmentService.findAll()"]
    n9{{"DepartmentMapper.selectAll"}}
    n10[("department")]
    n11("UserEditBean.save()")
    n12["UserService.save()"]
    n13{{"UserMapper.insert"}}
    n14{{"UserMapper.update"}}
    n15["AuditService.log()"]
    n16{{"AuditLogMapper.insert"}}
    n17[("audit_log")]
    n18["userEdit.cancelEdit ✖ 未解決"]
    n19("LoginBean.logout()")
    n0 -.->|"template"| n1
    n1 -.->|"include"| n2
    n0 -->|"action"| n3
    n3 --> n4
    n4 --> n5
    n5 -->|"R"| n6
    n0 -->|"value"| n7
    n7 --> n8
    n8 --> n9
    n9 -->|"R"| n10
    n0 -->|"action"| n11
    n11 --> n12
    n12 --> n13
    n13 -->|"C"| n6
    n12 --> n14
    n14 -->|"U"| n6
    n11 --> n15
    n15 --> n16
    n16 -->|"C"| n17
    n0 -->|"action"| n18
    n2 -->|"action"| n19
    classDef screen fill:#dbeafe,stroke:#1d4ed8,color:#1e3a8a
    classDef partial fill:#f1f5f9,stroke:#64748b,color:#334155
    classDef bean fill:#dcfce7,stroke:#15803d,color:#14532d
    classDef service fill:#fef9c3,stroke:#a16207,color:#713f12
    classDef mapper fill:#fae8ff,stroke:#a21caf,color:#701a75
    classDef table fill:#ffedd5,stroke:#c2410c,color:#7c2d12
    classDef ng fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d,stroke-dasharray:4 2
    class n0 screen
    class n1,n2 partial
    class n3,n7,n11,n19 bean
    class n4,n8,n12,n15 service
    class n5,n9,n13,n14,n16 mapper
    class n6,n10,n17 table
    class n18 ng
```

## 呼び出しパス一覧

| 定義ファイル:行 | タグ@属性 | EL式 | Bean#メソッド | Service | Mapper#メソッド | SQL | テーブル | 備考 |
|---|---|---|---|---|---|---|---|---|
| src/main/webapp/user/userEdit.xhtml:9 | f:viewParam@value | `#{userEdit.id}` | UserEditBean#getId |  |  |  |  |  |
| src/main/webapp/user/userEdit.xhtml:10 | f:viewAction@action | `#{userEdit.init}` | UserEditBean#init | UserService#find | UserMapper#selectById | select | users |  |
| src/main/webapp/user/userEdit.xhtml:13 | h:inputText@value | `#{userEdit.user.name}` | UserEditBean#getUser |  |  |  |  |  |
| src/main/webapp/user/userEdit.xhtml:14 | h:inputText@value | `#{userEdit.user.email}` | UserEditBean#getUser |  |  |  |  |  |
| src/main/webapp/user/userEdit.xhtml:15 | h:selectOneMenu@value | `#{userEdit.user.departmentId}` | UserEditBean#getUser |  |  |  |  |  |
| src/main/webapp/user/userEdit.xhtml:16 | f:selectItems@value | `#{userEdit.departments}` | UserEditBean#getDepartments | DepartmentService#findAll | DepartmentMapper#selectAll | select | department |  |
| src/main/webapp/user/userEdit.xhtml:18 | h:commandButton@action | `#{userEdit.save}` | UserEditBean#save | UserService#save | UserMapper#insert | insert | users |  |
| src/main/webapp/user/userEdit.xhtml:18 | h:commandButton@action | `#{userEdit.save}` | UserEditBean#save | UserService#save | UserMapper#update | update | users |  |
| src/main/webapp/user/userEdit.xhtml:18 | h:commandButton@action | `#{userEdit.save}` | UserEditBean#save | AuditService#log | AuditLogMapper#insert | insert | audit_log |  |
| src/main/webapp/user/userEdit.xhtml:20 | h:commandButton@action | `#{userEdit.cancelEdit}` | UserEditBean#cancelEdit（未定義） |  |  |  |  | UserEditBean にメソッド 'cancelEdit' がありません |
| src/main/webapp/WEB-INF/templates/header.xhtml:4 | h:form@rendered | `#{loginBean.loggedIn}` | LoginBean#isLoggedIn |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:5 | (テキスト) | `#{loginBean.userName}` | LoginBean#getUserName |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:6 | h:commandLink@action | `#{loginBean.logout}` | LoginBean#logout |  |  |  |  |  |

## 使用 SQL

### UserMapper#selectById（select）

- 定義元: `src/main/resources/com/example/app/mapper/UserMapper.xml:21`
- テーブル: users

```sql
SELECT u.id, u.login_id, u.name, u.email, u.department_id FROM users u WHERE u.id = #{id}
```

### DepartmentMapper#selectAll（select）

- 定義元: `src/main/resources/com/example/app/mapper/DepartmentMapper.xml:4`
- テーブル: department

```sql
SELECT id, name FROM department ORDER BY id
```

### UserMapper#insert（insert）

- 定義元: `src/main/resources/com/example/app/mapper/UserMapper.xml:29`
- テーブル: users

```sql
INSERT INTO users (login_id, name, email, department_id) VALUES (#{loginId}, #{name}, #{email}, #{departmentId})
```

### UserMapper#update（update・動的SQL）

- 定義元: `src/main/resources/com/example/app/mapper/UserMapper.xml:34`
- テーブル: users

```sql
UPDATE users SET name = #{name}, email = #{email}, department_id = #{departmentId} WHERE id = #{id}
```

### AuditLogMapper#insert（insert）

- 定義元: `src/main/java/com/example/app/mapper/AuditLogMapper.java:10 @Insert`
- テーブル: audit_log

```sql
INSERT INTO audit_log (operator, action, target_id, created_at) VALUES (#{operator}, #{action}, #{targetId}, CURRENT_TIMESTAMP)
```

## 未解決・要確認

- **EL: メソッド未定義** src/main/webapp/user/userEdit.xhtml:20 `#{userEdit.cancelEdit}`: UserEditBean にメソッド 'cancelEdit' がありません
