package com.xammer.cloud.controller;

import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.cicd.GitHubWorkflowRunDto;
import com.xammer.cloud.service.CicdConfigurationService;
import com.xammer.cloud.service.CicdStatusService;
import com.xammer.cloud.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.xammer.cloud.service.CicdStatusService.GitHubWorkflowRunsApiResponse;
import java.security.Principal;
import java.util.Optional;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;

// --- FIX 1: Import UserRepository, REMOVE ClientRepository ---
import com.xammer.cloud.repository.GitHubIntegrationConfigRepository;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/cicd")
@CrossOrigin(origins = "*")
public class CicdController {

    private static final Logger logger = LoggerFactory.getLogger(CicdController.class);

    @Autowired
    private CicdStatusService cicdStatusService;

    @Autowired
    private CicdConfigurationService configService;

    @Autowired
    private EncryptionService encryptionService;

    // --- FIX 2: Autowire UserRepository, REMOVE ClientRepository ---
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GitHubIntegrationConfigRepository gitHubIntegrationConfigRepository;


    @GetMapping("/github/runs")
    public ResponseEntity<List<GitHubWorkflowRunDto>> getGithubActionsRunsForUser() { 

        logger.info("Fetching latest run per workflow for current user's configurations.");

        List<GitHubIntegrationConfig> configs = configService.getGitHubConfigsForCurrentUser();
        if (configs.isEmpty()) {
            logger.info("No GitHub configurations found for the current user.");
            return ResponseEntity.ok(new ArrayList<>());
        }

        List<GitHubWorkflowRunDto> allRuns = new ArrayList<>();

        for (GitHubIntegrationConfig config : configs) {
            String decryptedPat = encryptionService.decrypt(config.getEncryptedPat());
            if (decryptedPat == null) {
                logger.error("Failed to decrypt PAT for config ID {}. Skipping repo {}/{}",
                        config.getId(), config.getGithubOwner(), config.getGithubRepo());
                continue;
            }

            try {
                List<GitHubWorkflowRunDto> runsForRepo = cicdStatusService.getLatestRunPerWorkflow(
                        config.getGithubOwner(),
                        config.getGithubRepo(),
                        decryptedPat
                );

                if (runsForRepo != null) {
                    allRuns.addAll(runsForRepo);
                }
            } catch (Exception e) {
                 logger.error("Unexpected error fetching runs for repo {}/{}: {}",
                         config.getGithubOwner(), config.getGithubRepo(), e.getMessage(), e);
            }
        }

         allRuns.sort((r1, r2) -> {
             if (r1.getUpdatedAt() == null && r2.getUpdatedAt() == null) return 0;
             if (r1.getUpdatedAt() == null) return 1;
             if (r2.getUpdatedAt() == null) return -1;
             return r2.getUpdatedAt().compareTo(r1.getUpdatedAt()); // Descending
         });

        logger.info("Returning {} total GitHub workflow runs for the user.", allRuns.size());
        return ResponseEntity.ok(allRuns);
    }
    
    @GetMapping("/github/runs/{owner}/{repo}/{workflowId}")
    public ResponseEntity<List<GitHubWorkflowRunDto>> getWorkflowRunHistory(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long workflowId,
            @RequestParam(defaultValue = "15") int count) {

        logger.info("Fetching run history for {}/{}, workflow ID: {}", owner, repo, workflowId);
        int fetchCount = Math.max(1, Math.min(count, 50)); 

        Optional<User> userOpt = configService.getCurrentUser(); 
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userOpt.get();

        Optional<GitHubIntegrationConfig> configOpt = configService.findConfigByRepo(user.getId(), owner, repo);
        if (configOpt.isEmpty()) {
            logger.warn("No GitHub config found for user {} and repo {}/{}", user.getUsername(), owner, repo);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String decryptedPat = encryptionService.decrypt(configOpt.get().getEncryptedPat());
        if (decryptedPat == null) {
            logger.error("Failed to decrypt PAT for config ID {}.", configOpt.get().getId());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        List<GitHubWorkflowRunDto> runs = cicdStatusService.getRunsForWorkflow(
                owner, repo, workflowId, decryptedPat, fetchCount);
                
        return ResponseEntity.ok(runs);
    }
    
    

    // --- REPLACE the old getWorkflowRuns method with this ---
    @GetMapping("/workflows/{owner}/{repo}/{workflowId}/runs")
    public ResponseEntity<GitHubWorkflowRunsApiResponse> getWorkflowRuns(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable String workflowId,
            Principal principal) {

        try {
            // 1. Get the current user
            String username = principal.getName();
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found: " + username));

            // 2. Find the specific config for this repo
            // (This uses a method from CicdConfigurationService you already have)
            GitHubIntegrationConfig config = configService.findConfigByRepo(user.getId(), owner, repo)
                    .orElseThrow(() -> new RuntimeException("GitHub config not found for " + owner + "/" + repo));

            // 3. Decrypt the token
            String decryptedToken = encryptionService.decrypt(config.getEncryptedPat());
            if (decryptedToken == null) {
                logger.error("Failed to decrypt PAT for config ID {}", config.getId());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
            }

            // 4. Fetch the runs
            GitHubWorkflowRunsApiResponse runs = cicdStatusService.getWorkflowRunsForWorkflow(
                owner,
                repo,
                workflowId,
                decryptedToken
            );

            return ResponseEntity.ok(runs);

        } catch (Exception e) {
            logger.error("Error fetching workflow runs for {}/{}/{}: {}", owner, repo, workflowId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
            }
        }
