package com.example.app.web;

import java.io.Serializable;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.example.app.entity.User;
import com.example.app.service.UserService;

@Named
@ViewScoped
public class UserListBean implements Serializable {

    @Inject
    private UserService userService;

    private String keyword;
    private List<User> users;

    @PostConstruct
    public void init() {
        search();
    }

    public void search() {
        users = userService.search(keyword);
    }

    public String edit(Long id) {
        return "/user/userEdit?faces-redirect=true&id=" + id;
    }

    public void delete(User user) {
        userService.delete(user.getId());
        search();
    }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public List<User> getUsers() { return users; }
}
