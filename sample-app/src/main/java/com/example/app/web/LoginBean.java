package com.example.app.web;

import java.io.Serializable;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.example.app.entity.User;
import com.example.app.service.AuthService;

@Named
@SessionScoped
public class LoginBean implements Serializable {

    @Inject
    private AuthService authService;

    private String loginId;
    private String password;
    private User loginUser;

    public String login() {
        loginUser = authService.authenticate(loginId, password);
        return loginUser != null ? "/user/userList?faces-redirect=true" : null;
    }

    public String logout() {
        loginUser = null;
        return "/login?faces-redirect=true";
    }

    public String getUserName() {
        return loginUser != null ? loginUser.getName() : "";
    }

    public boolean isLoggedIn() {
        return loginUser != null;
    }

    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
