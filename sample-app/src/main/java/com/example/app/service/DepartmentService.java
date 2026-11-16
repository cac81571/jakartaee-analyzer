package com.example.app.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.example.app.entity.Department;
import com.example.app.mapper.DepartmentMapper;

@ApplicationScoped
public class DepartmentService {

    @Inject
    DepartmentMapper departmentMapper;

    public List<Department> findAll() {
        return this.departmentMapper.selectAll();
    }
}
