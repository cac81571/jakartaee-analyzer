package com.example.app.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.mybatis.cdi.Mapper;

import com.example.app.entity.OrderItem;

/** XML とアノテーション SQL の混在パターン */
@Mapper
public interface OrderItemMapper {

    @Select({
        "SELECT i.id, i.order_id, i.product_id, p.name AS product_name, i.quantity, i.unit_price",
        "  FROM order_item i",
        "  INNER JOIN product p ON p.id = i.product_id",
        " WHERE i.order_id = #{orderId}",
        " ORDER BY i.id"
    })
    List<OrderItem> selectByOrderId(Long orderId);

    /** mapper XML 側に定義 */
    int countByOrderId(Long orderId);

    @Delete("DELETE FROM order_item WHERE order_id = #{orderId}")
    int deleteByOrderId(Long orderId);
}
