package com.example.app.service;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.example.app.mapper.AuditLogMapper;

@Stateless
public class AuditService {

    @Inject
    private AuditLogMapper auditLogMapper;

    public void log(String operator, String action, Long targetId) {
        auditLogMapper.insert(operator, action, targetId);
    }
}
