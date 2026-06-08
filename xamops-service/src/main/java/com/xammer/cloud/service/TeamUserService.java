package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserCloudAccountAccess;
import com.xammer.cloud.dto.ProfileDTO;
import com.xammer.cloud.dto.TeamUserDTO;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private UserRepository userRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EmailService emailService;

    public ProfileDTO getMyProfile() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        log.info("Getting profile for user ID: {}", currentUserId);

        User currentUser = userRepository.findById(currentUserId)
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
                log.info("Primary user - fetching all client accounts for client ID: {}", currentUser.getClient().getId());
                accounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
                log.info("Found {} accounts for primary user", accounts.size());
            } else {
                // Sub-user: show only assigned accounts
                log.info("Sub-user - fetching only assigned accounts");
                List<Long> accessibleAccountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(currentUserId);
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
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users (createdBy is null) can see team users
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can manage team members");
        }

        List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
        Set<Long> allClientAccountIds = clientAccounts.stream().map(CloudAccount::getId).collect(Collectors.toSet());

        List<User> teamUsers = userRepository.findByCreatedBy(currentUserId);
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

                    // Mark allAccountsAccess if user has all client accounts mapped
                    if (!allClientAccountIds.isEmpty() && new java.util.HashSet<>(accountIds).containsAll(allClientAccountIds)) {
                        dto.setAllAccountsAccess(true);
                    }

                    // Get full cloud account details for display
                    if (!accountIds.isEmpty()) {
                        List<CloudAccount> accounts = cloudAccountRepository.findAllById(accountIds);
                        List<ProfileDTO.CloudAccountInfo> accountInfos = accounts.stream()
                                .map(acc -> new ProfileDTO.CloudAccountInfo(acc.getId(), acc.getAccountName(), acc.getProvider()))
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
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users can create team members
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can create team members");
        }

        // Validate username is unique
        if (userRepository.findByUsername(dto.getUsername()).isPresent()) {
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
        User newUser = new User();
        newUser.setUsername(dto.getUsername());
        newUser.setEmail(dto.getEmail());
        newUser.setPassword(passwordEncoder.encode(dto.getPassword()));
        newUser.setRole(currentUser.getRole()); // Inherit role from parent
        newUser.setClient(currentUser.getClient()); // Same client
        newUser.setCreatedBy(currentUserId);

        User saved = userRepository.save(newUser);

        // Assign cloud account access
        if (dto.isAllAccountsAccess()) {
            // Grant access to all current client accounts
            List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
            for (CloudAccount account : clientAccounts) {
                userCloudAccountAccessRepository.save(new UserCloudAccountAccess(saved, account));
            }
        } else if (dto.getAccountIds() != null && !dto.getAccountIds().isEmpty()) {
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
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users can update team members
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can update team members");
        }

        User userToUpdate = userRepository.findById(userId)
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

        User saved = userRepository.save(userToUpdate);

        // Update cloud account access - delete existing and add new
        userCloudAccountAccessRepository.deleteByUserId(userId);
        entityManager.flush(); // Force deletion to complete before inserting new records

        if (dto.isAllAccountsAccess()) {
            // Grant access to all current client accounts
            List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
            for (CloudAccount account : clientAccounts) {
                userCloudAccountAccessRepository.save(new UserCloudAccountAccess(saved, account));
            }
        } else if (dto.getAccountIds() != null && !dto.getAccountIds().isEmpty()) {
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
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only primary users can delete team members
        if (currentUser.getCreatedBy() != null) {
            throw new RuntimeException("Only primary users can delete team members");
        }

        User userToDelete = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Team user not found"));

        // Verify this user was created by the current user
        if (!currentUserId.equals(userToDelete.getCreatedBy())) {
            throw new RuntimeException("You can only delete users you created");
        }

        // Delete account access first
        userCloudAccountAccessRepository.deleteByUserId(userId);

        // Then delete the user
        userRepository.delete(userToDelete);
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify old password
        if (!passwordEncoder.matches(oldPassword, currentUser.getPassword())) {
            throw new RuntimeException("Current password is incorrect");
        }

        // Update to new password
        currentUser.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(currentUser);
    }

    private void sendWelcomeEmail(String email, String username, String password, String createdBy) {
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
                "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>You have been added as a team member by <strong>" + createdBy + "</strong>. Below are your login credentials:</p>" +
                "<div style='background:#f8fafc;border:1px solid #e2e8f0;border-left:4px solid #000000;border-radius:10px;padding:20px 24px;margin-bottom:20px;'>" +
                "<h3 style='margin:0 0 14px;color:#1e293b;font-size:14px;font-weight:700;'>Your Login Credentials</h3>" +
                "<div style='margin-bottom:12px;'><span style='display:inline-block;color:#64748b;font-size:13px;font-weight:600;width:110px;'>Email (Login)</span><span style='background:#f1f5f9;padding:6px 12px;border-radius:6px;font-family:monospace;font-size:13px;color:#1e293b;'>" + email + "</span></div>" +
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
