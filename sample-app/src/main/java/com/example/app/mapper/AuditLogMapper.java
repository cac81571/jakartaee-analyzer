package com.example.app.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.mybatis.cdi.Mapper;

/** XML を持たず、アノテーションのみで SQL を定義する Mapper */
@Mapper
public interface AuditLogMapper {
    @Insert("INSERT INTO audit_log (operator, action, target_id, created_at) "
          + "VALUES (#{operator}, #{action}, #{targetId}, CURRENT_TIMESTAMP)")
    int insert(@Param("operator") String operator, @Param("action") String action, @Param("targetId") Long targetId);
}
