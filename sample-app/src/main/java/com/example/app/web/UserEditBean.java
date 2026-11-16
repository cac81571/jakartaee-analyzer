package com.example.app.web;

import java.io.Serializable;
import java.util.List;

import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import com.example.app.entity.Department;
import com.example.app.entity.User;
import com.example.app.service.AuditService;
import com.example.app.service.DepartmentService;
import com.example.app.service.UserService;

/** @Named に明示名を付けるパターン。2つ以上の Service を呼ぶ */
@Named("userEdit")
@ViewScoped
public class UserEditBean implements Serializable {

    @Inject
    private UserService userService;

    @Inject
    private DepartmentService departmentService;

    @Inject
    private AuditService auditService;

    @Inject
    private LoginBean loginBean;

    private Long id;
    private User user = new User();
    private List<Department> departments;

    public void init() {
        if (id != null) {
            user = userService.find(id);
        }
    }

    public String save() {
        userService.save(user);
        auditService.log(loginBean.getLoginId(), "USER_SAVE", user.getId());
        return "/user/userList?faces-redirect=true";
    }

    /** getter 内で遅延ロードするパターン */
    public List<Department> getDepartments() {
        if (departments == null) {
            departments = departmentService.findAll();
        }
        return departments;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
}
