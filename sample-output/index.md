# Jakarta EE ソース解析結果

- 解析対象: `/workspace/jakartaee-analyzer/sample-app`
- 画面数: 5 / xhtml: 7 / Bean: 5 / SQL ステートメント: 17
- 呼び出しパス CSV: [paths.csv](paths.csv)（TSV: [paths.tsv](paths.tsv)）
- CRUD 図 CSV: [crud.csv](crud.csv)
- 未解決一覧: [unresolved.md](unresolved.md)（4 件）

## 画面一覧

| 画面 | テンプレート/インクルード | 使用 Bean | EL参照数 | パス数 | 使用テーブル | 未解決 |
|---|---|---|---:|---:|---|---:|
| [/login.xhtml](screens/login.md) |  | loginBean | 3 | 3 | users | 0 |
| [/order/orderDetail.xhtml](screens/order_orderDetail.md) | /WEB-INF/templates/layout.xhtml<br/>/WEB-INF/templates/header.xhtml | orderDetailBean, loginBean | 9 | 13 | orders, customer, order_item, product, audit_log | 1 |
| [/order/orderList.xhtml](screens/order_orderList.md) | /WEB-INF/templates/layout.xhtml<br/>/WEB-INF/templates/header.xhtml | orderListBean, loginBean | 9 | 10 | orders, customer, order_item | 1 |
| [/user/userEdit.xhtml](screens/user_userEdit.md) | /WEB-INF/templates/layout.xhtml<br/>/WEB-INF/templates/header.xhtml | userEdit, loginBean | 11 | 13 | users, department, audit_log | 1 |
| [/user/userList.xhtml](screens/user_userList.md) | /WEB-INF/templates/layout.xhtml<br/>/WEB-INF/templates/header.xhtml | userListBean, loginBean | 10 | 12 | users, department | 0 |

## CRUD 図（画面 × テーブル）

C=insert, R=select, U=update, D=delete（テンプレート/インクルード経由、@PostConstruct を含む）

| 画面 | audit_log | customer | department | order_item | orders | product | users |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| /login.xhtml |  |  |  |  |  |  | R |
| /order/orderDetail.xhtml | C | R |  | R | RU | R |  |
| /order/orderList.xhtml |  | R |  | R | R |  |  |
| /user/userEdit.xhtml | C |  | R |  |  |  | CRU |
| /user/userList.xhtml |  |  | R |  |  |  | RD |

## Bean 一覧

| Bean名 | クラス | スコープ | 注入フィールド |
|---|---|---|---|
| `loginBean` | com.example.app.web.LoginBean | @SessionScoped | authService:AuthService |
| `orderDetailBean` | com.example.app.web.OrderDetailBean | @RequestScoped | orderService:OrderService, loginBean:LoginBean |
| `orderListBean` | com.example.app.web.OrderListBean | @ViewScoped | orderService:OrderService |
| `userEdit` | com.example.app.web.UserEditBean | @ViewScoped | userService:UserService, departmentService:DepartmentService, auditService:AuditService, loginBean:LoginBean |
| `userListBean` | com.example.app.web.UserListBean | @ViewScoped | userService:UserService |

## Mapper SQL 一覧

| Mapper#ID | 種別 | テーブル | 定義元 | 画面から使用 |
|---|---|---|---|:---:|
| DepartmentMapper#selectAll | select | department | src/main/resources/com/example/app/mapper/DepartmentMapper.xml:4 | ○ |
| InventoryMapper#selectStock | select | stock | src/main/resources/com/example/app/mapper/InventoryMapper.xml:4 |  |
| OrderItemMapper#countByOrderId | select | order_item | src/main/resources/com/example/app/mapper/OrderItemMapper.xml:4 | ○ |
| OrderMapper#selectByCondition | select | orders, customer | src/main/resources/com/example/app/mapper/OrderMapper.xml:18 | ○ |
| OrderMapper#selectAll | select | orders, customer | src/main/resources/com/example/app/mapper/OrderMapper.xml:33 | ○ |
| OrderMapper#selectById | select | orders, customer | src/main/resources/com/example/app/mapper/OrderMapper.xml:38 | ○ |
| OrderMapper#updateStatus | update | orders | src/main/resources/com/example/app/mapper/OrderMapper.xml:43 | ○ |
| OrderMapper#purgeDeleted | delete | orders | src/main/resources/com/example/app/mapper/OrderMapper.xml:48 |  |
| UserMapper#selectByKeyword | select | users, department | src/main/resources/com/example/app/mapper/UserMapper.xml:9 | ○ |
| UserMapper#selectById | select | users | src/main/resources/com/example/app/mapper/UserMapper.xml:21 | ○ |
| UserMapper#selectByLoginId | select | users | src/main/resources/com/example/app/mapper/UserMapper.xml:25 | ○ |
| UserMapper#insert | insert | users | src/main/resources/com/example/app/mapper/UserMapper.xml:29 | ○ |
| UserMapper#update | update | users | src/main/resources/com/example/app/mapper/UserMapper.xml:34 | ○ |
| UserMapper#deleteById | delete | users | src/main/resources/com/example/app/mapper/UserMapper.xml:44 | ○ |
| OrderItemMapper#selectByOrderId | select | order_item, product | src/main/java/com/example/app/mapper/OrderItemMapper.java:15 @Select | ○ |
| AuditLogMapper#insert | insert | audit_log | src/main/java/com/example/app/mapper/AuditLogMapper.java:10 @Insert | ○ |
| OrderItemMapper#deleteByOrderId | delete | order_item | src/main/java/com/example/app/mapper/OrderItemMapper.java:27 @Delete |  |
