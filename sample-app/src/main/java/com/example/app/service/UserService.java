package com.example.app.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.example.app.entity.User;
import com.example.app.mapper.UserMapper;

@ApplicationScoped
public class UserService {

    @Inject
    private UserMapper userMapper;

    public List<User> search(String keyword) {
        return userMapper.selectByKeyword(keyword);
    }

    public User find(Long id) {
        return userMapper.selectById(id);
    }

    @Transactional
    public void save(User user) {
        if (user.getId() == null) {
            userMapper.insert(user);
        } else {
            userMapper.update(user);
        }
    }

    @Transactional
    public void delete(Long id) {
        userMapper.deleteById(id);
    }
}
