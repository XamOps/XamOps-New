package com.xammer.cloud.controller;

import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.JenkinsIntegrationConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.cicd.CicdMetricsDto;
import com.xammer.cloud.dto.cicd.DiffSummaryDto;
import com.xammer.cloud.dto.cicd.FailureAnalysisDto;
import com.xammer.cloud.repository.GitHubIntegrationConfigRepository;
import com.xammer.cloud.repository.JenkinsIntegrationConfigRepository;
import com.xammer.cloud.service.CicdConfigurationService;
import com.xammer.cloud.service.CicdMetricsService;
import com.xammer.cloud.service.CicdSyncScheduler;
import com.xammer.cloud.service.EncryptionService;
import com.xammer.cloud.service.FailureAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping({"/api/cicd", "/cicd"})
@CrossOrigin(origins = "*")
public class CicdMetricsController {

    private static final Logger logger = LoggerFactory.getLogger(CicdMetricsController.class);

    private final CicdMetricsService metricsService;
    private final CicdSyncScheduler syncScheduler;
    private final CicdConfigurationService configService;
    private final EncryptionService encryptionService;
    private final GitHubIntegrationConfigRepository githubConfigRepo;
    private final JenkinsIntegrationConfigRepository jenkinsConfigRepo;
    private final RestTemplate restTemplate;
    private final FailureAnalysisService failureAnalysisService;

    public CicdMetricsController(CicdMetricsService metricsService,
                                  CicdSyncScheduler syncScheduler,
                                  CicdConfigurationService configService,
                                  EncryptionService encryptionService,
                                  GitHubIntegrationConfigRepository githubConfigRepo,
                                  JenkinsIntegrationConfigRepository jenkinsConfigRepo,
                                  RestTemplateBuilder restTemplateBuilder,
                                  FailureAnalysisService failureAnalysisService) {
        this.metricsService = metricsService;
        this.syncScheduler = syncScheduler;
        this.configService = configService;
        this.encryptionService = encryptionService;
        this.githubConfigRepo = githubConfigRepo;
        this.jenkinsConfigRepo = jenkinsConfigRepo;
        this.restTemplate = restTemplateBuilder.build();
        this.failureAnalysisService = failureAnalysisService;
    }

    /** GET /api/cicd/github/metrics?days=7 */
    @GetMapping("/github/metrics")
    public ResponseEntity<CicdMetricsDto> getGithubMetrics(
            @RequestParam(defaultValue = "7") int days) {
        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        int clampedDays = Math.max(1, Math.min(days, 90));
        CicdMetricsDto metrics = metricsService.computeMetrics(userOpt.get().getId(), "GITHUB", clampedDays);
        return ResponseEntity.ok(metrics);
    }

    /** GET /api/cicd/jenkins/metrics?days=7 */
    @GetMapping("/jenkins/metrics")
    public ResponseEntity<CicdMetricsDto> getJenkinsMetrics(
            @RequestParam(defaultValue = "7") int days) {
        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        int clampedDays = Math.max(1, Math.min(days, 90));
        CicdMetricsDto metrics = metricsService.computeMetrics(userOpt.get().getId(), "JENKINS", clampedDays);
        return ResponseEntity.ok(metrics);
    }

    /** GET /api/cicd/github/runs/{owner}/{repo}/{jobId}/logs */
    @GetMapping("/github/runs/{owner}/{repo}/{jobId}/logs")
    public ResponseEntity<String> getGithubJobLogs(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long jobId) {

        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<GitHubIntegrationConfig> configOpt = configService.findConfigByRepo(userOpt.get().getId(), owner, repo);
        if (configOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String pat = encryptionService.decrypt(configOpt.get().getEncryptedPat());
        if (pat == null) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + pat);
            headers.set("Accept", "application/vnd.github.v3+json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/jobs/" + jobId + "/logs";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return ResponseEntity.ok(response.getBody() != null ? response.getBody() : "");
        } catch (Exception e) {
            logger.error("Failed to fetch GitHub job logs for {}/{}/{}: {}", owner, repo, jobId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to fetch logs: " + e.getMessage());
        }
    }

    /** GET /api/cicd/jenkins/jobs/{jobName}/{buildNumber}/logs */
    @GetMapping("/jenkins/jobs/{jobName}/{buildNumber}/logs")
    public ResponseEntity<String> getJenkinsBuildLogs(
            @PathVariable String jobName,
            @PathVariable int buildNumber) {

        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        List<JenkinsIntegrationConfig> configs = jenkinsConfigRepo.findByUserId(userOpt.get().getId());
        if (configs.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        JenkinsIntegrationConfig config = configs.get(0);
        String token = encryptionService.decrypt(config.getEncryptedApiToken());
        if (token == null) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        try {
            String url = config.getJenkinsUrl() + "/job/" + jobName + "/" + buildNumber + "/consoleText";
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(config.getUsername(), token);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return ResponseEntity.ok(response.getBody() != null ? response.getBody() : "");
        } catch (Exception e) {
            logger.error("Failed to fetch Jenkins logs for {}/{}: {}", jobName, buildNumber, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to fetch logs: " + e.getMessage());
        }
    }

    @GetMapping("/github/runs/{owner}/{repo}/{jobId}/failure-analysis")
    public ResponseEntity<FailureAnalysisDto> getGithubFailureAnalysis(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long jobId) {

        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<GitHubIntegrationConfig> configOpt = configService.findConfigByRepo(userOpt.get().getId(), owner, repo);
        if (configOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String pat = encryptionService.decrypt(configOpt.get().getEncryptedPat());
        if (pat == null) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + pat);
            headers.set("Accept", "application/vnd.github.v3+json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/jobs/" + jobId + "/logs";
            ResponseEntity<String> logResp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String rawLog = logResp.getBody() != null ? logResp.getBody() : "";
            return ResponseEntity.ok(failureAnalysisService.parseLog(rawLog));
        } catch (Exception e) {
            logger.error("Failed to fetch/parse logs for job {}: {}", jobId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/github/runs/{owner}/{repo}/{runId}/diff-summary")
    public ResponseEntity<DiffSummaryDto> getGithubDiffSummary(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Long runId) {

        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<GitHubIntegrationConfig> configOpt = configService.findConfigByRepo(userOpt.get().getId(), owner, repo);
        if (configOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        String pat = encryptionService.decrypt(configOpt.get().getEncryptedPat());
        if (pat == null) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + pat);
            headers.set("Accept", "application/vnd.github.v3+json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String runUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + runId;
            ResponseEntity<com.fasterxml.jackson.databind.JsonNode> runResp =
                    restTemplate.exchange(runUrl, HttpMethod.GET, entity, com.fasterxml.jackson.databind.JsonNode.class);
            String headSha = runResp.getBody() != null ? runResp.getBody().path("head_sha").asText() : null;
            String workflowName = runResp.getBody() != null ? runResp.getBody().path("name").asText() : null;

            if (headSha == null || headSha.isBlank()) {
                DiffSummaryDto empty = new DiffSummaryDto();
                empty.setNoPreviousSuccess(true);
                empty.setCommits(List.of());
                empty.setChangedFiles(List.of());
                return ResponseEntity.ok(empty);
            }

            return ResponseEntity.ok(failureAnalysisService.getDiffSummaryForGitHub(
                    userOpt.get().getId(), workflowName, owner, repo, headSha, pat));
        } catch (Exception e) {
            logger.error("Diff summary failed for run {}: {}", runId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** POST /api/cicd/sync */
    @PostMapping("/sync")
    public ResponseEntity<Void> triggerSync() {
        Optional<User> userOpt = configService.getCurrentUser();
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        try {
            syncScheduler.syncForUser(userOpt.get().getId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("On-demand sync failed for user {}: {}", userOpt.get().getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
