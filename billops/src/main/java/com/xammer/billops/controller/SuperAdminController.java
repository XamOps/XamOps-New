package com.xammer.billops.controller;

import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.CreateClientRequest;
import com.xammer.billops.dto.CreateTenantRequest;
import com.xammer.billops.dto.CreateUserRequest;
import com.xammer.billops.dto.TenantDto;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminController {

    @Autowired
    @Qualifier("masterDataSource") // Ensure we inject the master datasource bean
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // --- 1. TECHNICAL TENANT MANAGEMENT (DB Connections) ---

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

    @PostMapping("/tenants")
    public String createTenant(@RequestBody CreateTenantRequest request) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        
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

        return "Technical Tenant '" + request.getCompanyName() + "' configured successfully.";
    }

    // --- 2. BUSINESS CLIENT MANAGEMENT (Billing Entities) ---

    @PostMapping("/clients")
    public Client createClient(@RequestBody CreateClientRequest request) {
        Client client = new Client();
        client.setName(request.getName());
        client.setAddress(request.getAddress());
        client.setGstin(request.getGstin());
        client.setStateName(request.getStateName());
        client.setStateCode(request.getStateCode());
        client.setPan(request.getPan());
        client.setCin(request.getCin());
        
        return clientRepository.save(client);
    }

    @GetMapping("/clients")
    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }

    // --- 3. USER MANAGEMENT ---

    @GetMapping("/users")
    public List<UserDTO> getTenantUsers() {
        List<User> users = userRepository.findAll();
        // Convert to DTO to prevent recursion
        return users.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @PostMapping("/users")
    public UserDTO createUser(@RequestBody CreateUserRequest request) {
        // 1. Find Client
        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new RuntimeException("Client not found with ID: " + request.getClientId()));

        // 2. Create User
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setClient(client);

        // 3. Save
        User savedUser = userRepository.save(user);
        return convertToDto(savedUser);
    }

    // Helper method
    private UserDTO convertToDto(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        
        if (user.getClient() != null) {
            dto.setClientId(user.getClient().getId());
            dto.setClientName(user.getClient().getName());
        }
        return dto;
    }
}