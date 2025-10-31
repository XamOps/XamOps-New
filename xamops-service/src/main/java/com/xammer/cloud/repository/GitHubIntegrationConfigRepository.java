package com.xammer.cloud.repository;

import com.xammer.cloud.domain.GitHubIntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitHubIntegrationConfigRepository extends JpaRepository<GitHubIntegrationConfig, Long> {

    List<GitHubIntegrationConfig> findByUserId(Long userId);

    Optional<GitHubIntegrationConfig> findByUserIdAndGithubOwnerAndGithubRepo(Long userId, String owner, String repo);
    
    // --- FIX: Change this method name back to FindByClientId ---
    //Optional<GitHubIntegrationConfig> findByClientId(Long clientId);
}