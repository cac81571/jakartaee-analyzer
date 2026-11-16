package com.example.app.mapper;

import org.apache.ibatis.annotations.Param;
import org.mybatis.cdi.Mapper;

@Mapper
public interface InventoryMapper {
    /** XML にもアノテーションにも SQL が無い（未解決として検出されるべき） */
    int restock(@Param("productId") Long productId, @Param("quantity") int quantity);

    int selectStock(Long productId);
}
