package com.example.app.mapper;

import java.util.List;

import com.example.app.entity.Department;

/** @Mapper アノテーションなし（XML の namespace で紐付くパターン） */
public interface DepartmentMapper {
    List<Department> selectAll();
}
