package com.xammer.cloud.controller;

import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.JenkinsIntegrationConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.cicd.GitHubWorkflowRunDto;
import com.xammer.cloud.dto.cicd.JenkinsJobDto;
import com.xammer.cloud.dto.cicd.PipelineStageDto;
import com.xammer.cloud.repository.GitHubIntegrationConfigRepository;
import com.xammer.cloud.repository.JenkinsIntegrationConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CicdConfigurationService;
import com.xammer.cloud.service.CicdStatusService;
import com.xammer.cloud.service.CicdStatusService.GitHubWorkflowRunsApiResponse;
import com.xammer.cloud.service.EncryptionService;
import com.xammer.cloud.service.JenkinsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GitHubIntegrationConfigRepository gitHubIntegrationConfigRepository;

    @Autowired
    private JenkinsService jenkinsService;

    @Autowired
    private JenkinsIntegrationConfigRepository jenkinsConfigRepository;

    /**
     * Fetches the latest run for each GitHub workflow.
     */
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
                        decryptedPat);

                if (runsForRepo != null) {
                    allRuns.addAll(runsForRepo);
                }
            } catch (Exception e) {
                logger.error("Unexpected error fetching runs for repo {}/{}: {}",
                        config.getGithubOwner(), config.getGithubRepo(), e.getMessage(), e);
            }
        }

        allRuns.sort((r1, r2) -> {
            if (r1.getUpdatedAt() == null && r2.getUpdatedAt() == null)
                return 0;
            if (r1.getUpdatedAt() == null)
                return 1;
            if (r2.getUpdatedAt() == null)
                return -1;
            return r2.getUpdatedAt().compareTo(r1.getUpdatedAt()); // Descending
        });

        logger.info("Returning {} total GitHub workflow runs for the user.", allRuns.size());
        return ResponseEntity.ok(allRuns);
    }

    /**
     * Fetches run history for a specific GitHub workflow.
     */
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

    /**
     * Specific workflow runs endpoint (Used internally or for specific detailed
     * views).
     */
    @GetMapping("/workflows/{owner}/{repo}/{workflowId}/runs")
    public ResponseEntity<GitHubWorkflowRunsApiResponse> getWorkflowRuns(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable String workflowId,
            Principal principal) {

        try {
            String username = principal.getName();
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found: " + username));

            GitHubIntegrationConfig config = configService.findConfigByRepo(user.getId(), owner, repo)
                    .orElseThrow(() -> new RuntimeException("GitHub config not found for " + owner + "/" + repo));

            String decryptedToken = encryptionService.decrypt(config.getEncryptedPat());
            if (decryptedToken == null) {
                logger.error("Failed to decrypt PAT for config ID {}", config.getId());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
            }

            GitHubWorkflowRunsApiResponse runs = cicdStatusService.getWorkflowRunsForWorkflow(
                    owner,
                    repo,
                    workflowId,
                    decryptedToken);

            return ResponseEntity.ok(runs);

        } catch (Exception e) {
            logger.error("Error fetching workflow runs for {}/{}/{}: {}", owner, repo, workflowId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Fetches all configured Jenkins jobs (Dashboard view).
     */
    @GetMapping("/jenkins/jobs")
    @Transactional
    public ResponseEntity<List<JenkinsJobDto>> getJenkinsJobs() {
        Optional<User> currentUserOpt = configService.getCurrentUser();
        if (currentUserOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User currentUser = currentUserOpt.get();

        List<JenkinsIntegrationConfig> configs = jenkinsConfigRepository.findByUserId(currentUser.getId());
        List<JenkinsJobDto> allJobs = new ArrayList<>();

        for (JenkinsIntegrationConfig config : configs) {
            String decryptedToken = encryptionService.decrypt(config.getEncryptedApiToken());
            if (decryptedToken != null) {
                try {
                    allJobs.addAll(jenkinsService.getJobs(config, decryptedToken));
                } catch (Exception e) {
                    logger.error("Error fetching jobs from Jenkins URL {}: {}", config.getJenkinsUrl(), e.getMessage());
                }
            }
        }
        return ResponseEntity.ok(allJobs);
    }

    // --- PIPELINE VISUALIZATION & HISTORY ENDPOINTS ---

    /**
     * Fetches stages/jobs for a specific GitHub Action run.
     */
    @GetMapping("/github/runs/{owner}/{repo}/{runId}/stages")
    public ResponseEntity<List<PipelineStageDto>> getGitHubRunStages(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long runId) {

        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<GitHubIntegrationConfig> configOpt = configService.findConfigByRepo(userOpt.get().getId(), owner,
                repo);
        if (configOpt.isEmpty())
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String decryptedPat = encryptionService.decrypt(configOpt.get().getEncryptedPat());
        if (decryptedPat == null)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        return ResponseEntity.ok(cicdStatusService.getGitHubRunJobs(owner, repo, runId, decryptedPat));
    }

    /**
     * Fetches stages for a specific Jenkins build.
     */
    @GetMapping("/jenkins/jobs/{jobName}/{buildNumber}/stages")
    @Transactional
    public ResponseEntity<List<PipelineStageDto>> getJenkinsBuildStages(
            @PathVariable String jobName,
            @PathVariable int buildNumber) {

        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        List<JenkinsIntegrationConfig> configs = jenkinsConfigRepository.findByUserId(userOpt.get().getId());
        if (configs.isEmpty())
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        // Assumption: Using first config. For multi-server support, pass configId in
        // URL.
        JenkinsIntegrationConfig config = configs.get(0);
        String decryptedToken = encryptionService.decrypt(config.getEncryptedApiToken());

        if (decryptedToken == null)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        return ResponseEntity.ok(jenkinsService.getJenkinsBuildStages(config, jobName, buildNumber, decryptedToken));
    }

    /**
     * Fetches build history for a specific Jenkins job.
     */
    @GetMapping("/jenkins/jobs/{jobName}/builds")
    @Transactional
    public ResponseEntity<List<GitHubWorkflowRunDto>> getJenkinsJobHistory(@PathVariable String jobName) {
        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        List<JenkinsIntegrationConfig> configs = jenkinsConfigRepository.findByUserId(userOpt.get().getId());
        if (configs.isEmpty())
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        // Assumption: Using first config.
        JenkinsIntegrationConfig config = configs.get(0);
        String decryptedToken = encryptionService.decrypt(config.getEncryptedApiToken());

        if (decryptedToken == null)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        List<GitHubWorkflowRunDto> history = jenkinsService.getJobHistory(config, jobName, decryptedToken);
        return ResponseEntity.ok(history);
    }
}