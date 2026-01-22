package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.CreateTenantRequest;
import com.xammer.cloud.dto.CreateUserRequest;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SuperAdminController.class);

    @Autowired
    @Qualifier("masterDataSource") // Use Master DB for tenant_config
    private DataSource masterDataSource;

    @Autowired
    private UserRepository userRepository; // Uses TenantRoutingDataSource

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    // --- TENANT MANAGEMENT (Technical) ---

    @GetMapping("/tenants")
    public List<TenantDto> getAllTenants() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        return jdbcTemplate.query(
                "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
                (rs, rowNum) -> new TenantDto(
                        rs.getString("tenant_id"),
                        rs.getString("company_name")));
    }

    @PostMapping("/tenants")
    public String createTenant(@RequestBody CreateTenantRequest request) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);

        String sql = "INSERT INTO tenant_config (tenant_id, company_name, db_url, db_username, db_password, driver_class_name, active) "
                +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.update(sql,
                request.getTenantId(),
                request.getCompanyName(),
                request.getDbUrl(),
                request.getDbUsername(),
                request.getDbPassword(),
                request.getDriverClassName(),
                request.isActive());

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

            User savedUser = userRepository.save(newUser);

            // Send welcome email with credentials
            try {
                sendWelcomeEmail(request.getEmail(), request.getUsername(), request.getPassword());
                log.info("Welcome email sent successfully to: {}", request.getEmail());
            } catch (Exception e) {
                log.error("Failed to send welcome email to: {}", request.getEmail(), e);
                // Don't fail the user creation if email fails
            }

            return savedUser;
        } finally {
            TenantContext.clear();
        }
    }

    private void sendWelcomeEmail(String email, String username, String password) {
        String subject = "Welcome to XamOps - Your Account Credentials";

        String htmlBody = "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<style>" +
                "body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }" +
                ".container { max-width: 600px; margin: 0 auto; padding: 20px; }" +
                ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }" +
                ".content { background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px; }" +
                ".credentials-box { background: white; border-left: 4px solid #667eea; padding: 20px; margin: 20px 0; border-radius: 5px; }" +
                ".credential-item { margin: 15px 0; }" +
                ".label { font-weight: bold; color: #667eea; display: inline-block; width: 120px; }" +
                ".value { background: #f3f4f6; padding: 8px 12px; border-radius: 4px; font-family: monospace; display: inline-block; }" +
                ".button { display: inline-block; background: #667eea; color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; margin: 20px 0; }" +
                ".footer { text-align: center; padding: 20px; color: #6b7280; font-size: 12px; }" +
                ".warning { background: #fef3c7; border-left: 4px solid #f59e0b; padding: 15px; margin: 20px 0; border-radius: 5px; }" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='container'>" +
                "<div class='header'>" +
                "<h1 style='margin: 0;'>Welcome to XamOps!</h1>" +
                "<p style='margin: 10px 0 0 0; opacity: 0.9;'>Your Cloud Management Platform</p>" +
                "</div>" +
                "<div class='content'>" +
                "<p>Hello,</p>" +
                "<p>Your XamOps account has been created by the administrator. " +
                "Below are your login credentials to access the platform:</p>" +
                "<div class='credentials-box'>" +
                "<h3 style='margin-top: 0; color: #667eea;'>Your Login Credentials</h3>" +
                "<div class='credential-item'>" +
                "<span class='label'>Username:</span>" +
                "<span class='value'>" + username + "</span>" +
                "</div>" +
                "<div class='credential-item'>" +
                "<span class='label'>Password:</span>" +
                "<span class='value'>" + password + "</span>" +
                "</div>" +
                "<div class='credential-item'>" +
                "<span class='label'>Portal URL:</span>" +
                "<span class='value'>https://live.xamops.com/</span>" +
                "</div>" +
                "</div>" +
                "<div class='warning'>" +
                "<strong>⚠️ Security Reminder:</strong> Please change your password after your first login for security purposes." +
                "</div>" +
                "<div style='text-align: center;'>" +
                "<a href='https://live.xamops.com/' class='button'>Login to XamOps</a>" +
                "</div>" +
                "<p>If you have any questions or need assistance, please contact your administrator.</p>" +
                "<p>Best regards,<br><strong>The XamOps Team</strong></p>" +
                "</div>" +
                "<div class='footer'>" +
                "<p>This is an automated message. Please do not reply to this email.</p>" +
                "<p>&copy; 2025 XamOps. All rights reserved.</p>" +
                "</div>" +
                "</div>" +
                "</body>" +
                "</html>";

        emailService.sendHtmlEmail(email, subject, htmlBody);
    }
}