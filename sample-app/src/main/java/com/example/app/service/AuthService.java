package com.example.app.service;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.example.app.entity.User;
import com.example.app.mapper.UserMapper;

@Stateless
public class AuthService {

    @Inject
    private UserMapper userMapper;

    public User authenticate(String loginId, String password) {
        User user = userMapper.selectByLoginId(loginId);
        if (user == null) {
            return null;
        }
        // パスワード照合は省略
        return user;
    }
}
