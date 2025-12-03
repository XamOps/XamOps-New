package com.xammer.cloud.controller;

import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
// CHANGE: Move under /api/xamops to match existing proxy rules
@RequestMapping("/api/xamops/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminController {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private UserRepository userRepository;

    // Endpoint: /api/xamops/superadmin/tenants
    @GetMapping("/tenants")
    public List<TenantDto> getAllTenants() {
        return tenantService.getAllActiveTenants();
    }

    // Endpoint: /api/xamops/superadmin/users
    @GetMapping("/users")
    public List<User> getTenantUsers() {
        return userRepository.findAll();
    }
}