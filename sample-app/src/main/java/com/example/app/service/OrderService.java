package com.example.app.service;

import java.util.List;

import com.example.app.entity.Order;
import com.example.app.entity.OrderSearchCondition;

/** インターフェース + 実装クラス(OrderServiceImpl) のパターン */
public interface OrderService {
    List<Order> search(OrderSearchCondition condition);
    Order findDetail(Long orderId);
    void cancel(Long orderId, String operator);
    String exportCsv();
}
