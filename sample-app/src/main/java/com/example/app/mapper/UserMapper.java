package com.example.app.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.mybatis.cdi.Mapper;

import com.example.app.entity.User;

@Mapper
public interface UserMapper {
    List<User> selectByKeyword(@Param("keyword") String keyword);
    User selectById(Long id);
    User selectByLoginId(String loginId);
    int insert(User user);
    int update(User user);
    int deleteById(Long id);
}
