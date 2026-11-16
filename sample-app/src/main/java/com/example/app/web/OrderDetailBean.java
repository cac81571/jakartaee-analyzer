package com.example.app.web;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.example.app.entity.Order;
import com.example.app.service.OrderService;

/** コンストラクタインジェクションのパターン */
@Named
@RequestScoped
public class OrderDetailBean {

    private final OrderService orderService;
    private final LoginBean loginBean;

    private Long orderId;
    private Order order;

    @Inject
    public OrderDetailBean(OrderService orderService, LoginBean loginBean) {
        this.orderService = orderService;
        this.loginBean = loginBean;
    }

    protected OrderDetailBean() {
        this(null, null);
    }

    public void load() {
        order = orderService.findDetail(orderId);
    }

    public String cancel() {
        orderService.cancel(orderId, loginBean.getLoginId());
        return "/order/orderList?faces-redirect=true";
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Order getOrder() { return order; }
}
