# 画面: /order/orderList.xhtml

- ファイル: `src/main/webapp/order/orderList.xhtml`
- テンプレート / インクルード:
  - template: `src/main/webapp/WEB-INF/templates/layout.xhtml`（`src/main/webapp/order/orderList.xhtml` から）
  - include: `src/main/webapp/WEB-INF/templates/header.xhtml`（`src/main/webapp/WEB-INF/templates/layout.xhtml` から）
- 使用 Bean:
  - `orderListBean` → com.example.app.web.OrderListBean（@ViewScoped）
  - `loginBean` → com.example.app.web.LoginBean（@SessionScoped）

## 呼び出しフロー

メソッド式と、下流（Service/Mapper）の呼び出しがあるプロパティのみ図示しています。全参照は下の表を参照してください。

```mermaid
flowchart LR
    n0[["/order/orderList.xhtml"]]
    n1[/"/WEB-INF/templates/layout.xhtml"/]
    n2[/"/WEB-INF/templates/header.xhtml"/]
    n3("OrderListBean.search()")
    n4["OrderServiceImpl.search()<br/>IF: OrderService"]
    n5{{"OrderMapper.selectByCondition"}}
    n6[("orders")]
    n7[("customer")]
    n8("OrderListBean.export()")
    n9["OrderServiceImpl.exportCsv()<br/>IF: OrderService"]
    n10{{"OrderMapper.selectAll"}}
    n11{{"OrderItemMapper.countByOrderId"}}
    n12[("order_item")]
    n13["orderSummaryBean.totalCount ✖ 未解決"]
    n14("LoginBean.logout()")
    n0 -.->|"template"| n1
    n1 -.->|"include"| n2
    n0 -->|"action"| n3
    n3 --> n4
    n4 --> n5
    n5 -->|"R"| n6
    n5 -->|"R"| n7
    n0 -->|"actionListener"| n8
    n8 --> n9
    n9 --> n10
    n10 -->|"R"| n6
    n10 -->|"R"| n7
    n9 --> n11
    n11 -->|"R"| n12
    n0 -->|"value"| n13
    n2 -->|"action"| n14
    classDef screen fill:#dbeafe,stroke:#1d4ed8,color:#1e3a8a
    classDef partial fill:#f1f5f9,stroke:#64748b,color:#334155
    classDef bean fill:#dcfce7,stroke:#15803d,color:#14532d
    classDef service fill:#fef9c3,stroke:#a16207,color:#713f12
    classDef mapper fill:#fae8ff,stroke:#a21caf,color:#701a75
    classDef table fill:#ffedd5,stroke:#c2410c,color:#7c2d12
    classDef ng fill:#fee2e2,stroke:#b91c1c,color:#7f1d1d,stroke-dasharray:4 2
    class n0 screen
    class n1,n2 partial
    class n3,n8,n14 bean
    class n4,n9 service
    class n5,n10,n11 mapper
    class n6,n7,n12 table
    class n13 ng
```

## 呼び出しパス一覧

| 定義ファイル:行 | タグ@属性 | EL式 | Bean#メソッド | Service | Mapper#メソッド | SQL | テーブル | 備考 |
|---|---|---|---|---|---|---|---|---|
| src/main/webapp/order/orderList.xhtml:9 | h:inputText@value | `#{orderListBean.condition.orderNo}` | OrderListBean#getCondition |  |  |  |  |  |
| src/main/webapp/order/orderList.xhtml:10 | h:inputText@value | `#{orderListBean.condition.customerName}` | OrderListBean#getCondition |  |  |  |  |  |
| src/main/webapp/order/orderList.xhtml:11 | h:commandButton@action | `#{orderListBean.search()}` | OrderListBean#search | OrderServiceImpl#search（IF: OrderService） | OrderMapper#selectByCondition | select | orders, customer |  |
| src/main/webapp/order/orderList.xhtml:12 | h:commandButton@actionListener | `#{orderListBean.export}` | OrderListBean#export | OrderServiceImpl#exportCsv（IF: OrderService） | OrderMapper#selectAll | select | orders, customer |  |
| src/main/webapp/order/orderList.xhtml:12 | h:commandButton@actionListener | `#{orderListBean.export}` | OrderListBean#export | OrderServiceImpl#exportCsv（IF: OrderService） | OrderItemMapper#countByOrderId | select | order_item |  |
| src/main/webapp/order/orderList.xhtml:13 | h:dataTable@value | `#{orderListBean.orders}` | OrderListBean#getOrders |  |  |  |  |  |
| src/main/webapp/order/orderList.xhtml:22 | h:outputText@value | `#{orderSummaryBean.totalCount}` |  |  |  |  |  | Bean 'orderSummaryBean' が見つかりません（@Named 未定義） |
| src/main/webapp/WEB-INF/templates/header.xhtml:4 | h:form@rendered | `#{loginBean.loggedIn}` | LoginBean#isLoggedIn |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:5 | (テキスト) | `#{loginBean.userName}` | LoginBean#getUserName |  |  |  |  |  |
| src/main/webapp/WEB-INF/templates/header.xhtml:6 | h:commandLink@action | `#{loginBean.logout}` | LoginBean#logout |  |  |  |  |  |

## 使用 SQL

### OrderMapper#selectByCondition（select・動的SQL）

- 定義元: `src/main/resources/com/example/app/mapper/OrderMapper.xml:18`
- テーブル: orders, customer

```sql
SELECT o.id, o.order_no, o.customer_id, c.name AS customer_name, o.order_date, o.status, o.total_amount FROM orders o INNER JOIN customer c ON c.id = o.customer_id WHERE AND o.order_no = #{orderNo} AND c.name LIKE CONCAT(#{customerName}, '%') AND o.order_date >= #{fromDate} AND o.order_date <= #{toDate} AND o.status = #{status} AND o.status <> 'DELETED' ORDER BY o.order_date DESC
```

### OrderMapper#selectAll（select）

- 定義元: `src/main/resources/com/example/app/mapper/OrderMapper.xml:33`
- テーブル: orders, customer

```sql
SELECT o.id, o.order_no, o.customer_id, c.name AS customer_name, o.order_date, o.status, o.total_amount FROM orders o INNER JOIN customer c ON c.id = o.customer_id ORDER BY o.id
```

### OrderItemMapper#countByOrderId（select）

- 定義元: `src/main/resources/com/example/app/mapper/OrderItemMapper.xml:4`
- テーブル: order_item

```sql
SELECT COUNT(*) FROM order_item WHERE order_id = #{orderId}
```

## 未解決・要確認

- **EL: Bean未定義** src/main/webapp/order/orderList.xhtml:22 `#{orderSummaryBean.totalCount}`: Bean 'orderSummaryBean' が見つかりません（@Named 未定義）
