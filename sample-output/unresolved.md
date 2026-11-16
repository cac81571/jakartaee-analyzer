# 未解決・要確認の参照一覧

件数: 4

| 種別 | 件数 |
|---|---:|
| EL: Bean未定義 | 1 |
| EL: メソッド未定義 | 1 |
| Mapper: SQL未定義 | 1 |
| Mapper XML: インターフェースにメソッドなし | 1 |

## EL: Bean未定義

| 場所 | 対象 | 内容 |
|---|---|---|
| src/main/webapp/order/orderList.xhtml:22 | `#{orderSummaryBean.totalCount}` | Bean 'orderSummaryBean' が見つかりません（@Named 未定義） |

## EL: メソッド未定義

| 場所 | 対象 | 内容 |
|---|---|---|
| src/main/webapp/user/userEdit.xhtml:20 | `#{userEdit.cancelEdit}` | UserEditBean にメソッド 'cancelEdit' がありません |

## Mapper: SQL未定義

| 場所 | 対象 | 内容 |
|---|---|---|
| src/main/java/com/example/app/mapper/InventoryMapper.java:9 | `InventoryMapper#restock` | Mapper XML（namespace=com.example.app.mapper.InventoryMapper）にもアノテーションにも SQL がありません |

## Mapper XML: インターフェースにメソッドなし

| 場所 | 対象 | 内容 |
|---|---|---|
| src/main/resources/com/example/app/mapper/OrderMapper.xml:48 | `OrderMapper#purgeDeleted` | XML のステートメント id に対応するメソッドがインターフェースにありません |

## 参考: どの画面からも到達しない SQL ステートメント

バッチや Web API など xhtml 以外から呼ばれている可能性もあります。

| Mapper#ID | 種別 | テーブル | 定義元 |
|---|---|---|---|
| InventoryMapper#selectStock | select | stock | src/main/resources/com/example/app/mapper/InventoryMapper.xml:4 |
| OrderMapper#purgeDeleted | delete | orders | src/main/resources/com/example/app/mapper/OrderMapper.xml:48 |
| OrderItemMapper#deleteByOrderId | delete | order_item | src/main/java/com/example/app/mapper/OrderItemMapper.java:27 @Delete |
