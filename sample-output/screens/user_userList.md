# 画面: /user/userList.xhtml

- ファイル: `src/main/webapp/user/userList.xhtml`
- テンプレート / インクルード:
  - template: `src/main/webapp/WEB-INF/templates/layout.xhtml`（`src/main/webapp/user/userList.xhtml` から）
  - include: `src/main/webapp/WEB-INF/templates/header.xhtml`（`src/main/webapp/WEB-INF/templates/layout.xhtml` から）
- 使用 Bean:
  - `userListBean` → com.example.app.web.UserListBean（@ViewScoped）
  - `loginBean` → com.example.app.web.LoginBean（@SessionScoped）

## 呼び出しフロー

メソッド式と、下流（Service/Mapper）の呼び出しがあるプロパティのみ図示しています。全参照は下の表を参照してください。

```mermaid
flowchart LR
    n0[["/user/userList.xhtml"]]
    n1[/"/WEB-INF/templates/layout.xhtml"/]
    n2[/"/WEB-INF/templates/header.xhtml"/]
    n3("UserListBean.search()")
    n4["UserService.search()"]
    n5{{"UserMapper.selectByKeyword"}}
    n6[("users")]
    n7[("department")]
    n8("UserListBean.edit()")
    n9("UserListBean.delete()")
    n10["UserService.delete()"]
    n11{{"UserMapper.deleteById"}}
    n12("LoginBean.logout()")
    n13("UserListBean.init()")
    n0 -.->|"template"| n1
    n1 -.->|"include"| n2
    n0 -->|"action"| n3
    n3 --> n4
    n4 --> n5
    n5 -->|"R"| n6
    n5 -->|"R"| n7
    n0 -->|"action"| n8
    n0 -->|"actionListener"| n9
    n9 --> n10
    n10 --> n11
    n11 -->|"D"| n6
    n9 --> n4
    n2 -->|"action"| n12
    n0 -->|"@PostConstruct"| n13
    n13 --> n4
    classDef screen fill:#dbeafe,stroke:#1d4ed8,color:#1e3a8a
    classDef partial fill:#f1f5f9,stroke:#64748b,color:#334155
    classDef bean fill:#dcfce7,stroke:#15803d,color:#14532d
    classDef service fill:#fef9c3,stroke:#a16207,color:#713f12
    classDef mapper fill:#fae8ff,stroke:#a21caf,color:#701a75
    classDef table fill:#ffedd5,stroke:#c2410c,color:#7c2d12
    classDef ng fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d,stroke-dasharray:4 2
    class n0 screen
    class n1,n2 partial
    class n3,n8,n9,n12,n13 bean
    class n4,n10 service
    class n5,n11 mapper
    class n6,n7 table
```

## 呼び出しパス一覧

| 定義ファイル:行 | タグ@属性 | EL式 | Bean#メソッド | Service | Mapper#メソッド | SQL | テーブル | 備考 |
|---|---|---|---|---|---|---|---|---|
| src/main/webapp/user/userList.xhtml:9 | h:inputText@value | `#{userListBean.keyword}` | UserListBean#getKeyword |  |  |  |  |  |
| src/main/webapp/user/userList.xhtml:10 | h:commandButton@action | `#{userListBean.search}` | UserListBean#search | UserService#search | UserMapper#selectByKeyword | select | users, department |  |
| src/main/webapp/user/userList.xhtml:13 | h:dataTable@value | `#{userListBean.users}` | UserListBean#getUsers |  |  |  |  |  |
| src/main/webapp/user/userList.xhtml:19 | h:commandLink@action | `#{userListBean.edit(u.id)}` | UserListBean#edit |  |  |  |  |  |
| src/main/webapp/user/userList.xhtml:20 | h:commandLink@actionListener | `#{userListBean.delete(u)}` | UserListBean#delete | UserService#delete | UserMapper#deleteById | delete | users |  |
| src/main/webapp/user/userList.xhtml:20 | h:commandLink@actionListener | `#{userListBean.delete(u)}` | UserListBean#delete | UserService#search | UserMapper#selectByKeyword | select | users, department |  |
| src/main/webapp/user/userList.xhtml:24 | h:outputText@value | `#{userListBean.users.size()}` | UserListBean#getUsers |  |  |  |  |  |
| src/main/webapp/user/userList.xhtml:24 | h:outputText@rendered | `#{not empty userListBean.users}` | UserListBean#getUsers |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:4 | h:form@rendered | `#{loginBean.loggedIn}` | LoginBean#isLoggedIn |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:5 | (テキスト) | `#{loginBean.userName}` | LoginBean#getUserName |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:6 | h:commandLink@action | `#{loginBean.logout}` | LoginBean#logout |  |  |  |  |  |
| src/main/java/com/example/app/web/UserListBean.java:24 | @PostConstruct | `(@PostConstruct userListBean)` | UserListBean#init | UserService#search | UserMapper#selectByKeyword | select | users, department |  |

## 使用 SQL

### UserMapper#selectByKeyword（select・動的SQL）

- 定義元: `src/main/resources/com/example/app/mapper/UserMapper.xml:9`
- テーブル: users, department

```sql
SELECT u.id, u.login_id, u.name, u.email, u.department_id FROM users u LEFT OUTER JOIN department d ON d.id = u.department_id WHERE (u.name LIKE CONCAT('%', #{keyword}, '%') OR d.name LIKE CONCAT('%', #{keyword}, '%')) ORDER BY u.id
```

### UserMapper#deleteById（delete）

- 定義元: `src/main/resources/com/example/app/mapper/UserMapper.xml:44`
- テーブル: users

```sql
DELETE FROM users WHERE id = #{id}
```
