package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.ProfileDTO;
import com.xammer.billops.dto.TeamUserDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.util.SecurityUtils;
import com.xammer.cloud.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TeamUserService {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    public ProfileDTO getMyProfile() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        ProfileDTO profile = new ProfileDTO();
        profile.setId(currentUser.getId());
        profile.setUsername(currentUser.getUsername());
        profile.setEmail(currentUser.getEmail());
        profile.setRole(currentUser.getRole());
        profile.setClientName(currentUser.getClient() != null ? currentUser.getClient().getName() : "Unknown");
        profile.setPrimaryUser(currentUser.getCreatedBy() == null);

        // Get cloud accounts for this client
        if (currentUser.getClient() != null) {
            List<CloudAccount> accounts = cloudAccountRepository.findByClientId(currentUser.getClient().getId());
            List<ProfileDTO.CloudAccountInfo> accountInfos = accounts.stream()
                    .filter(acc -> "CONNECTED".equals(acc.getStatus()))
                    .map(acc -> new ProfileDTO.CloudAccountInfo(acc.getId(), acc.getAccountName(), acc.getProvider()))
                    .collect(Collectors.toList());
            profile.setCloudAccounts(accountInfos);
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

        // Create new user with same role and client as current user
        AppUser newUser = new AppUser();
        newUser.setUsername(dto.getUsername());
        newUser.setEmail(dto.getEmail());
        newUser.setPassword(passwordEncoder.encode(dto.getPassword()));
        newUser.setRole(currentUser.getRole()); // Inherit role from parent
        newUser.setClient(currentUser.getClient()); // Same client = same cloud accounts
        newUser.setCreatedBy(currentUserId);

        AppUser saved = appUserRepository.save(newUser);

        TeamUserDTO result = new TeamUserDTO();
        result.setId(saved.getId());
        result.setUsername(saved.getUsername());
        result.setEmail(saved.getEmail());
        result.setRole(saved.getRole());
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

        // Update fields
        userToUpdate.setUsername(dto.getUsername());
        userToUpdate.setEmail(dto.getEmail());

        // Only update password if provided
        if (dto.getPassword() != null && !dto.getPassword().trim().isEmpty()) {
            userToUpdate.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        AppUser saved = appUserRepository.save(userToUpdate);

        TeamUserDTO result = new TeamUserDTO();
        result.setId(saved.getId());
        result.setUsername(saved.getUsername());
        result.setEmail(saved.getEmail());
        result.setRole(saved.getRole());
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

        appUserRepository.delete(userToDelete);
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
}
