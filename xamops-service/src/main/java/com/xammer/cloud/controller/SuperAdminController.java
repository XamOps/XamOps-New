package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailIcons;
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

        String htmlBody = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>Welcome to XamOps!</title></head>" +
                "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>" +
                "<tr><td style='background:#000000;padding:40px;'>" +
                "<div style='margin-bottom:16px;'><span style='display:inline-block;background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>" +
                "<div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.userCheck(40) + "</div>" +
                "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Welcome to XamOps!</h1>" +
                "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Your Cloud Management Platform</p>" +
                "</td></tr>" +
                "<tr><td style='padding:32px 40px;'>" +
                "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>Your XamOps account has been created by the administrator. Below are your login credentials:</p>" +
                "<div style='background:#f8fafc;border:1px solid #e2e8f0;border-left:4px solid #000000;border-radius:10px;padding:20px 24px;margin-bottom:20px;'>" +
                "<h3 style='margin:0 0 14px;color:#1e293b;font-size:14px;font-weight:700;'>Your Login Credentials</h3>" +
                "<div style='margin-bottom:12px;'><span style='display:inline-block;color:#64748b;font-size:13px;font-weight:600;width:110px;'>Username</span><span style='background:#f1f5f9;padding:6px 12px;border-radius:6px;font-family:monospace;font-size:13px;color:#1e293b;'>" + username + "</span></div>" +
                "<div style='margin-bottom:12px;'><span style='display:inline-block;color:#64748b;font-size:13px;font-weight:600;width:110px;'>Password</span><span style='background:#f1f5f9;padding:6px 12px;border-radius:6px;font-family:monospace;font-size:13px;color:#1e293b;'>" + password + "</span></div>" +
                "<div style='margin-bottom:12px;'><span style='display:inline-block;color:#64748b;font-size:13px;font-weight:600;width:110px;'>Portal URL</span><span style='background:#f1f5f9;padding:6px 12px;border-radius:6px;font-family:monospace;font-size:13px;color:#1e293b;'>https://live.xamops.com/</span></div>" +
                "</div>" +
                "<div style='background:#fffbeb;border:1px solid #fcd34d;border-left:4px solid #f59e0b;border-radius:8px;padding:14px 18px;margin-bottom:24px;'>" +
                "<p style='margin:0;color:#92400e;font-size:13px;'><strong>Security Reminder:</strong> Please change your password after your first login.</p>" +
                "</div>" +
                "<div style='text-align:center;margin-bottom:8px;'>" +
                "<a href='https://live.xamops.com/' style='display:inline-block;background:#000000;color:#fff;padding:12px 28px;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;'>Login to XamOps</a>" +
                "</div>" +
                "</td></tr>" +
                "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>" +
                "<span style='display:inline-block;background:#000000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>" +
                "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>" +
                "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>" +
                "</td></tr>" +
                "</table></td></tr></table></body></html>";

        emailService.sendHtmlEmail(email, subject, htmlBody);
    }
}