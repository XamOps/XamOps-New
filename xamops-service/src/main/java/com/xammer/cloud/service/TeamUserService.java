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
    private UserRepository userRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

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
}
