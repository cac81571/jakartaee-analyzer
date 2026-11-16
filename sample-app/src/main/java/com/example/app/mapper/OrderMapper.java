package com.example.app.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.mybatis.cdi.Mapper;

import com.example.app.entity.Order;
import com.example.app.entity.OrderSearchCondition;

@Mapper
public interface OrderMapper {
    List<Order> selectByCondition(OrderSearchCondition condition);
    List<Order> selectAll();
    Order selectById(Long id);
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
