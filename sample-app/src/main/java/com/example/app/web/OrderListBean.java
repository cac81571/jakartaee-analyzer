package com.example.app.web;

import java.io.Serializable;
import java.util.List;

import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.example.app.entity.Order;
import com.example.app.entity.OrderSearchCondition;
import com.example.app.service.OrderService;

@Named
@ViewScoped
public class OrderListBean implements Serializable {

    @Inject
    private OrderService orderService;

    private final OrderSearchCondition condition = new OrderSearchCondition();
    private List<Order> orders;
    private String csv;

    public void search() {
        orders = orderService.search(condition);
    }

    public void export() {
        csv = orderService.exportCsv();
    }

    public OrderSearchCondition getCondition() { return condition; }
    public List<Order> getOrders() { return orders; }
    public String getCsv() { return csv; }
}
