package com.xammer.cloud.service;

import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.cicd.GitHubConfigRequestDto;
import com.xammer.cloud.repository.GitHubIntegrationConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Ensure this import is present

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class CicdConfigurationService {

    private static final Logger logger = LoggerFactory.getLogger(CicdConfigurationService.class);

    @Autowired
    private GitHubIntegrationConfigRepository configRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private UserRepository userRepository;

    @Transactional
    public GitHubIntegrationConfig saveGitHubConfig(GitHubConfigRequestDto requestDto) {
        Optional<User> currentUserOpt = getCurrentUser();
        if (currentUserOpt.isEmpty()) {
            logger.error("Cannot save GitHub config: User not authenticated.");
            return null; 
        }
        User currentUser = currentUserOpt.get();

        String encryptedPat = encryptionService.encrypt(requestDto.getPat());
        if (encryptedPat == null) {
            logger.error("Failed to encrypt PAT for user {} and repo {}/{}", currentUser.getUsername(), requestDto.getOwner(), requestDto.getRepo());
            return null;
        }

        GitHubIntegrationConfig config = new GitHubIntegrationConfig(
                currentUser,
                requestDto.getOwner(),
                requestDto.getRepo(),
                encryptedPat
        );

        try {
            GitHubIntegrationConfig savedConfig = configRepository.save(config);
            logger.info("Saved GitHub config ID {} for user {} and repo {}/{}", savedConfig.getId(), currentUser.getUsername(), savedConfig.getGithubOwner(), savedConfig.getGithubRepo());
            return savedConfig;
        } catch (Exception e) {
            logger.error("Error saving GitHub config for user {}: {}", currentUser.getUsername(), e.getMessage(), e);
            return null; 
        }
    }

    /**
     * Retrieves all GitHub configurations for the currently logged-in user.
     */
    // === *** THE FIX: Add this annotation *** ===
    @Transactional(readOnly = true) 
    public List<GitHubIntegrationConfig> getGitHubConfigsForCurrentUser() {
        Optional<User> currentUserOpt = getCurrentUser();
        if (currentUserOpt.isEmpty()) {
            logger.warn("Cannot get GitHub configs: User not authenticated.");
            return Collections.emptyList();
        }
        User currentUser = currentUserOpt.get();

        try {
            List<GitHubIntegrationConfig> configs = configRepository.findByUserId(currentUser.getId());
            logger.debug("Found {} GitHub configs for user {}", configs.size(), currentUser.getUsername());
            return configs;
        } catch (Exception e) {
            logger.error("Error retrieving GitHub configs for user {}: {}", currentUser.getUsername(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Transactional
    public boolean deleteGitHubConfig(Long configId) {
        Optional<User> currentUserOpt = getCurrentUser();
        if (currentUserOpt.isEmpty()) {
            logger.error("Cannot delete GitHub config: User not authenticated.");
            return false;
        }
        User currentUser = currentUserOpt.get();

        try {
            Optional<GitHubIntegrationConfig> configOpt = configRepository.findById(configId);
            if (configOpt.isPresent()) {
                GitHubIntegrationConfig config = configOpt.get();
                if (config.getUser().getId().equals(currentUser.getId())) {
                    configRepository.deleteById(configId);
                    logger.info("Deleted GitHub config ID {} for user {}", configId, currentUser.getUsername());
                    return true;
                } else {
                    logger.warn("User {} attempted to delete GitHub config ID {} owned by user {}",
                            currentUser.getUsername(), configId, config.getUser().getUsername());
                    return false;
                }
            } else {
                logger.warn("GitHub config ID {} not found for deletion attempt by user {}", configId, currentUser.getUsername());
                return false;
            }
        } catch (Exception e) {
            logger.error("Error deleting GitHub config ID {} for user {}: {}", configId, currentUser.getUsername(), e.getMessage(), e);
            return false;
        }
    }

    public Optional<User> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            return Optional.empty();
        }

        String username;
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails) {
            username = ((UserDetails) principal).getUsername();
        } else {
            username = principal.toString();
        }

        return userRepository.findByUsername(username);
    }
    @Transactional(readOnly = true)
public Optional<GitHubIntegrationConfig> findConfigByRepo(Long userId, String owner, String repo) {
    return configRepository.findByUserIdAndGithubOwnerAndGithubRepo(userId, owner, repo);
}
}