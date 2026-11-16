package com.example.app.entity;

import java.time.LocalDate;

public class OrderSearchCondition {
    private String orderNo;
    private String customerName;
    private LocalDate fromDate;
    private LocalDate toDate;
    private String status;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
