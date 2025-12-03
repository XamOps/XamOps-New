package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.CreateTenantRequest;
import com.xammer.cloud.dto.CreateUserRequest;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.List;

@RestController
@RequestMapping("/api/xamops/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminController {

    @Autowired
    @Qualifier("masterDataSource") // Use Master DB for tenant_config
    private DataSource masterDataSource;

    @Autowired
    private UserRepository userRepository; // Uses TenantRoutingDataSource

    @Autowired
    private PasswordEncoder passwordEncoder;

    // --- TENANT MANAGEMENT (Technical) ---

    @GetMapping("/tenants")
    public List<TenantDto> getAllTenants() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        return jdbcTemplate.query(
            "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
            (rs, rowNum) -> new TenantDto(
                rs.getString("tenant_id"),
                rs.getString("company_name")
            )
        );
    }

    @PostMapping("/tenants")
    public String createTenant(@RequestBody CreateTenantRequest request) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        
        String sql = "INSERT INTO tenant_config (tenant_id, company_name, db_url, db_username, db_password, driver_class_name, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        jdbcTemplate.update(sql,
            request.getTenantId(),
            request.getCompanyName(),
            request.getDbUrl(),
            request.getDbUsername(),
            request.getDbPassword(),
            request.getDriverClassName(),
            request.isActive()
        );

        return "Tenant '" + request.getCompanyName() + "' registered successfully.";
    }

    // --- USER MANAGEMENT ---

    @GetMapping("/users")
    public List<User> getTenantUsers(@RequestParam(required = false) String tenantId) {
        // If tenantId is provided, switch context to fetch users from that tenant
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setCurrentTenant(tenantId);
        }
        
        try {
            return userRepository.findAll();
        } finally {
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.clear();
            }
        }
    }

    @PostMapping("/users")
    public User createUser(@RequestBody CreateUserRequest request) {
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            throw new IllegalArgumentException("Tenant ID is required to create a user.");
        }
        
        TenantContext.setCurrentTenant(request.getTenantId());

        try {
            User newUser = new User();
            newUser.setUsername(request.getUsername());
            newUser.setEmail(request.getEmail());
            newUser.setRole(request.getRole());
            newUser.setPassword(passwordEncoder.encode(request.getPassword()));
            
            return userRepository.save(newUser);
        } finally {
            TenantContext.clear();
        }
    }
}