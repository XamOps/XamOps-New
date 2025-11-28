package com.xammer.billops.controller;

import com.xammer.billops.dto.TenantDto;
import com.xammer.cloud.domain.User;
import com.xammer.billops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.List;

@RestController
// CHANGE: Move under /api/xamops to match existing proxy rules
@RequestMapping("/api/xamops/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    // Endpoint: /api/xamops/superadmin/tenants
    @GetMapping("/tenants")
    public List<TenantDto> getAllTenants() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return jdbcTemplate.query(
            "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
            (rs, rowNum) -> new TenantDto(
                rs.getString("tenant_id"),
                rs.getString("company_name")
            )
        );
    }

    // Endpoint: /api/xamops/superadmin/users
    @GetMapping("/users")
    public List<User> getTenantUsers() {
        return userRepository.findAll();
    }
}