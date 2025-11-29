package com.xammer.billops.controller;

import com.xammer.billops.dto.TenantDto;
import com.xammer.billops.dto.UserDTO; // Import the DTO
import com.xammer.cloud.domain.User;
import com.xammer.billops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

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

    // FIX: Return List<UserDTO> instead of List<User>
    @GetMapping("/users")
    public List<UserDTO> getTenantUsers() {
        List<User> users = userRepository.findAll();
        
        // Convert Entity to DTO to break recursion
        return users.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    // Helper method to map Entity -> DTO
    private UserDTO convertToDto(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        
        // Map Client Name safely
        if (user.getClient() != null) {
            dto.setClientId(user.getClient().getId());
            dto.setClientName(user.getClient().getName());
        }
        
        return dto;
    }
}