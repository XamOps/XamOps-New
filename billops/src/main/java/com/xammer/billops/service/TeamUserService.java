package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.UserCloudAccountAccess;
import com.xammer.billops.dto.ProfileDTO;
import com.xammer.billops.dto.TeamUserDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.util.SecurityUtils;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.config.multitenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TeamUserService {

    private static final Logger log = LoggerFactory.getLogger(TeamUserService.class);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EmailService emailService;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    public ProfileDTO getMyProfile() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        log.info("Getting profile for user ID: {}", currentUserId);

        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        log.info("User: {}, createdBy: {}, isPrimary: {}",
                currentUser.getUsername(), currentUser.getCreatedBy(), currentUser.getCreatedBy() == null);

        ProfileDTO profile = new ProfileDTO();
        profile.setId(currentUser.getId());
        profile.setUsername(currentUser.getUsername());
        profile.setEmail(currentUser.getEmail());
        profile.setRole(currentUser.getRole());
        profile.setClientName(currentUser.getClient() != null ? currentUser.getClient().getName() : "Unknown");
        profile.setPrimaryUser(currentUser.getCreatedBy() == null);

        // Get cloud accounts based on user type
        if (currentUser.getClient() != null) {
            List<CloudAccount> accounts;

            if (currentUser.getCreatedBy() == null) {
                // Primary user: show all client accounts
                log.info("Primary user - fetching all client accounts for client ID: {}",
                        currentUser.getClient().getId());
                accounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
                log.info("Found {} accounts for primary user", accounts.size());
            } else {
                // Sub-user: show only assigned accounts
                log.info("Sub-user - fetching only assigned accounts");
                List<Long> accessibleAccountIds = userCloudAccountAccessRepository
                        .findCloudAccountIdsByUserId(currentUserId);
                log.info("Accessible account IDs: {}", accessibleAccountIds);

                if (accessibleAccountIds.isEmpty()) {
                    accounts = new ArrayList<>();
                } else {
                    accounts = cloudAccountRepository.findAllById(accessibleAccountIds);
                }
                log.info("Found {} accounts for sub-user", accounts.size());
            }

            List<ProfileDTO.CloudAccountInfo> accountInfos = accounts.stream()
                    .filter(acc -> "CONNECTED".equals(acc.getStatus()))
                    .map(acc -> new ProfileDTO.CloudAccountInfo(acc.getId(), acc.getAccountName(), acc.getProvider()))
                    .collect(Collectors.toList());
            profile.setCloudAccounts(accountInfos);
            log.info("Returning {} cloud accounts in profile", accountInfos.size());
        }

        return profile;
    }

    public List<TeamUserDTO> getMyTeamUsers() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users (createdBy is null) can see team users
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can manage team members");
        }

        List<AppUser> teamUsers = appUserRepository.findByCreatedBy(currentUserId);
        return teamUsers.stream()
                .map(user -> {
                    TeamUserDTO dto = new TeamUserDTO();
                    dto.setId(user.getId());
                    dto.setUsername(user.getUsername());
                    dto.setEmail(user.getEmail());
                    dto.setRole(user.getRole());

                    // Get assigned account IDs
                    List<Long> accountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(user.getId());
                    dto.setAccountIds(accountIds);

                    // Get full cloud account details for display
                    if (!accountIds.isEmpty()) {
                        List<CloudAccount> accounts = cloudAccountRepository.findAllById(accountIds);
                        List<ProfileDTO.CloudAccountInfo> accountInfos = accounts.stream()
                                .map(acc -> new ProfileDTO.CloudAccountInfo(acc.getId(), acc.getAccountName(),
                                        acc.getProvider()))
                                .collect(Collectors.toList());
                        dto.setCloudAccounts(accountInfos);
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public TeamUserDTO createTeamUser(TeamUserDTO dto) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users can create team members
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can create team members");
        }

        // Validate username is unique
        if (appUserRepository.findByUsername(dto.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists");
        }

        // Validate account IDs belong to the same client
        if (dto.getAccountIds() != null && !dto.getAccountIds().isEmpty()) {
            List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
            Set<Long> validAccountIds = clientAccounts.stream().map(CloudAccount::getId).collect(Collectors.toSet());
            for (Long accountId : dto.getAccountIds()) {
                if (!validAccountIds.contains(accountId)) {
                    throw new RuntimeException("Invalid account ID: " + accountId);
                }
            }
        }

        // Create new user with same role and client as current user
        AppUser newUser = new AppUser();
        newUser.setUsername(dto.getUsername());
        newUser.setEmail(dto.getEmail());
        newUser.setPassword(passwordEncoder.encode(dto.getPassword()));
        newUser.setRole(currentUser.getRole()); // Inherit role from parent
        newUser.setClient(currentUser.getClient()); // Same client
        newUser.setCreatedBy(currentUserId);

        AppUser saved = appUserRepository.save(newUser);

        // Assign cloud account access
        if (dto.getAccountIds() != null && !dto.getAccountIds().isEmpty()) {
            for (Long accountId : dto.getAccountIds()) {
                CloudAccount account = cloudAccountRepository.findById(accountId)
                        .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
                UserCloudAccountAccess access = new UserCloudAccountAccess(saved, account);
                userCloudAccountAccessRepository.save(access);
            }
        }

        // Send welcome email with credentials
        try {
            sendWelcomeEmail(saved.getEmail(), dto.getUsername(), dto.getPassword(), currentUser.getUsername());
            log.info("Welcome email sent successfully to: {}", saved.getEmail());
        } catch (Exception e) {
            log.error("Failed to send welcome email to: {}", saved.getEmail(), e);
            // Don't fail the user creation if email fails
        }

        TeamUserDTO result = new TeamUserDTO();
        result.setId(saved.getId());
        result.setUsername(saved.getUsername());
        result.setEmail(saved.getEmail());
        result.setRole(saved.getRole());
        result.setAccountIds(dto.getAccountIds());
        return result;
    }

    @Transactional
    public TeamUserDTO updateTeamUser(Long userId, TeamUserDTO dto) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users can update team members
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can update team members");
        }

        AppUser userToUpdate = appUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Team user not found"));

        // Verify this user was created by the current user
        if (!currentUserId.equals(userToUpdate.getCreatedBy())) {
            throw new RuntimeException("You can only update users you created");
        }

        // Validate account IDs belong to the same client
        if (dto.getAccountIds() != null && !dto.getAccountIds().isEmpty()) {
            List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
            Set<Long> validAccountIds = clientAccounts.stream().map(CloudAccount::getId).collect(Collectors.toSet());
            for (Long accountId : dto.getAccountIds()) {
                if (!validAccountIds.contains(accountId)) {
                    throw new RuntimeException("Invalid account ID: " + accountId);
                }
            }
        }

        // Update fields
        userToUpdate.setUsername(dto.getUsername());
        userToUpdate.setEmail(dto.getEmail());

        // Only update password if provided
        if (dto.getPassword() != null && !dto.getPassword().trim().isEmpty()) {
            userToUpdate.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        AppUser saved = appUserRepository.save(userToUpdate);

        // Update cloud account access - delete existing and add new
        userCloudAccountAccessRepository.deleteByUserId(userId);
        entityManager.flush(); // Force deletion to complete before inserting new records

        if (dto.getAccountIds() != null && !dto.getAccountIds().isEmpty()) {
            for (Long accountId : dto.getAccountIds()) {
                CloudAccount account = cloudAccountRepository.findById(accountId)
                        .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
                UserCloudAccountAccess access = new UserCloudAccountAccess(saved, account);
                userCloudAccountAccessRepository.save(access);
            }
        }

        TeamUserDTO result = new TeamUserDTO();
        result.setId(saved.getId());
        result.setUsername(saved.getUsername());
        result.setEmail(saved.getEmail());
        result.setRole(saved.getRole());
        result.setAccountIds(dto.getAccountIds());
        return result;
    }

    @Transactional
    public void deleteTeamUser(Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users can delete team members
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can delete team members");
        }

        AppUser userToDelete = appUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Team user not found"));

        // Verify this user was created by the current user
        if (!currentUserId.equals(userToDelete.getCreatedBy())) {
            throw new RuntimeException("You can only delete users you created");
        }

        // Delete account access first
        userCloudAccountAccessRepository.deleteByUserId(userId);

        // Then delete the user
        appUserRepository.delete(userToDelete);

        // --- Synchronize deletion with Master DB ---
        try {
            log.info("Synchronizing deleted sub-user '{}' from Master DB", userToDelete.getUsername());
            masterDatabaseService.deleteGlobalUser(userToDelete.getUsername());
        } catch (Exception e) {
            log.error("Failed to sync user deletion from Master DB: {}", userToDelete.getUsername(), e);
        }
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify old password
        if (!passwordEncoder.matches(oldPassword, currentUser.getPassword())) {
            throw new RuntimeException("Current password is incorrect");
        }

        // Update to new password
        currentUser.setPassword(passwordEncoder.encode(newPassword));
        appUserRepository.save(currentUser);
    }

    private void sendWelcomeEmail(String email, String username, String password, String createdBy) {
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
                "<p>You have been added as a team member to XamOps by <strong>" + createdBy + "</strong>. " +
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
