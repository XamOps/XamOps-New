package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.EnvironmentService;
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
import java.util.stream.Collectors;

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
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private com.xammer.cloud.repository.UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private EnvironmentService environmentService;

    // --- ENVIRONMENT & TENANT MANAGEMENT ---

    @GetMapping("/environment")
    public String getEnvironment() {
        return "{\"environment\": \"" + environmentService.getCurrentEnvironment() + "\"}";
    }

    @GetMapping("/tenants")
    public List<TenantDto> getAllTenants() {
        return environmentService.getTenantsForEnvironment();
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
        String env = environmentService.getCurrentEnvironment();

        // If local/UAT, tenantId selection doesn't change the DB context (it's single
        // DB)
        // but it might be used for filtering in the future if needed.
        if ("prod".equals(env)) {
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setCurrentTenant(tenantId);
            }
        }

        try {
            return userRepository.findAll();
        } finally {
            if ("prod".equals(env) && tenantId != null && !tenantId.isBlank()) {
                TenantContext.clear();
            }
        }
    }

    @GetMapping("/accounts-by-user/{userId}")
    public List<AccountDto> getAccountsByUserId(@PathVariable Long userId,
            @RequestParam(required = false) String tenantId) {
        String env = environmentService.getCurrentEnvironment();

        if ("prod".equals(env) && tenantId != null && !tenantId.isBlank()) {
            TenantContext.setCurrentTenant(tenantId);
        }

        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));

            if (user.getClient() == null) {
                return java.util.Collections.emptyList();
            }

            List<CloudAccount> accounts;
            List<Long> accessibleAccountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);

            if (!accessibleAccountIds.isEmpty()) {
                log.info("Mapped accounts found for user {} - restricting view to {} accounts", userId,
                        accessibleAccountIds.size());
                accounts = cloudAccountRepository.findAllById(accessibleAccountIds);
            } else if (user.getCreatedBy() == null) {
                // Primary user with no specific mappings: return all client accounts
                log.info("Primary user detected with no specific mappings - showing all client accounts");
                accounts = cloudAccountRepository.findByClientId(user.getClient().getId());
            } else {
                // Sub-user with no mapped accounts: return empty
                log.info("Sub-user detected with no mapped accounts - returning empty list");
                accounts = new java.util.ArrayList<>();
            }

            return accounts.stream()
                    .map(this::mapToAccountDto)
                    .collect(Collectors.toList());
        } finally {
            if ("prod".equals(env) && tenantId != null && !tenantId.isBlank()) {
                TenantContext.clear();
            }
        }
    }

    private AccountDto mapToAccountDto(CloudAccount account) {
        String connectionType;
        String accountIdentifier;

        switch (account.getProvider()) {
            case "AWS":
                connectionType = "Cross-account role";
                accountIdentifier = account.getAwsAccountId();
                break;
            case "GCP":
                connectionType = "Workload Identity Federation";
                accountIdentifier = account.getGcpProjectId();
                break;
            case "Azure":
                connectionType = "Service Principal";
                accountIdentifier = account.getAzureSubscriptionId();
                break;
            default:
                connectionType = "Unknown";
                accountIdentifier = "N/A";
                break;
        }
        AccountDto dto = new AccountDto(
                account.getId(),
                account.getAccountName(),
                accountIdentifier,
                account.getAccessType(),
                connectionType,
                account.getStatus(),
                account.getRoleArn(),
                account.getExternalId(),
                account.getProvider());
        dto.setGrafanaIp(account.getGrafanaIp());
        dto.setGrafanaDashboardUid(account.getGrafanaDashboardUid());
        dto.setGrafanaDashboardSlug(account.getGrafanaDashboardSlug());
        return dto;
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
                ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }"
                +
                ".content { background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px; }" +
                ".credentials-box { background: white; border-left: 4px solid #667eea; padding: 20px; margin: 20px 0; border-radius: 5px; }"
                +
                ".credential-item { margin: 15px 0; }" +
                ".label { font-weight: bold; color: #667eea; display: inline-block; width: 120px; }" +
                ".value { background: #f3f4f6; padding: 8px 12px; border-radius: 4px; font-family: monospace; display: inline-block; }"
                +
                ".button { display: inline-block; background: #667eea; color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; margin: 20px 0; }"
                +
                ".footer { text-align: center; padding: 20px; color: #6b7280; font-size: 12px; }" +
                ".warning { background: #fef3c7; border-left: 4px solid #f59e0b; padding: 15px; margin: 20px 0; border-radius: 5px; }"
                +
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
                "<strong>⚠️ Security Reminder:</strong> Please change your password after your first login for security purposes."
                +
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