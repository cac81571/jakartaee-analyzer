package com.example.app.service.impl;

import java.util.List;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.example.app.entity.Order;
import com.example.app.entity.OrderItem;
import com.example.app.entity.OrderSearchCondition;
import com.example.app.mapper.InventoryMapper;
import com.example.app.mapper.OrderItemMapper;
import com.example.app.mapper.OrderMapper;
import com.example.app.service.AuditService;
import com.example.app.service.OrderService;

@Stateless
public class OrderServiceImpl implements OrderService {

    @Inject
    private OrderMapper orderMapper;

    @Inject
    private OrderItemMapper orderItemMapper;

    @Inject
    private InventoryMapper inventoryMapper;

    @EJB
    private AuditService auditService;

    @Override
    public List<Order> search(OrderSearchCondition condition) {
        return orderMapper.selectByCondition(condition);
    }

    @Override
    public Order findDetail(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null) {
            order.setItems(orderItemMapper.selectByOrderId(orderId));
        }
        return order;
    }

    @Override
    public void cancel(Long orderId, String operator) {
        // 1つの Service メソッドから複数 Mapper を呼ぶパターン
        List<OrderItem> items = orderItemMapper.selectByOrderId(orderId);
        for (OrderItem item : items) {
            inventoryMapper.restock(item.getProductId(), item.getQuantity());
        }
        orderMapper.updateStatus(orderId, "CANCELLED");
        writeAudit(operator, orderId);
    }

    @Override
    public String exportCsv() {
        StringBuilder sb = new StringBuilder("order_no,customer,amount\n");
        for (Order o : orderMapper.selectAll()) {
            int count = orderItemMapper.countByOrderId(o.getId());
            sb.append(o.getOrderNo()).append(',').append(o.getCustomerName())
              .append(',').append(o.getTotalAmount()).append(',').append(count).append('\n');
        }
        return sb.toString();
    }

    /** private ヘルパー経由で別 Service を呼ぶパターン */
    private void writeAudit(String operator, Long orderId) {
        auditService.log(operator, "ORDER_CANCEL", orderId);
    }
}
