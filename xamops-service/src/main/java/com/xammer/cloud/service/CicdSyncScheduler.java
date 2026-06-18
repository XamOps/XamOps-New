package com.xammer.cloud.service;

import com.xammer.cloud.domain.CicdRunRecord;
import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.JenkinsIntegrationConfig;
import com.xammer.cloud.dto.cicd.GitHubWorkflowRunDto;
import com.xammer.cloud.dto.cicd.JenkinsJobDto;
import com.xammer.cloud.repository.CicdNotificationConfigRepository;
import com.xammer.cloud.repository.CicdRunRecordRepository;
import com.xammer.cloud.repository.GitHubIntegrationConfigRepository;
import com.xammer.cloud.repository.JenkinsIntegrationConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CicdSyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CicdSyncScheduler.class);
    private static final int BACKFILL_DAYS = 90;

    private final GitHubIntegrationConfigRepository githubConfigRepo;
    private final JenkinsIntegrationConfigRepository jenkinsConfigRepo;
    private final CicdRunRecordRepository runRecordRepo;
    private final CicdStatusService cicdStatusService;
    private final JenkinsService jenkinsService;
    private final EncryptionService encryptionService;
    private final FailureCategoryResolver failureCategoryResolver;
    private final CicdNotificationService cicdNotificationService;
    private final CicdNotificationConfigRepository notificationConfigRepo;
    private final com.xammer.cloud.repository.UserRepository userRepository;

    public CicdSyncScheduler(GitHubIntegrationConfigRepository githubConfigRepo,
                              JenkinsIntegrationConfigRepository jenkinsConfigRepo,
                              CicdRunRecordRepository runRecordRepo,
                              CicdStatusService cicdStatusService,
                              JenkinsService jenkinsService,
                              EncryptionService encryptionService,
                              FailureCategoryResolver failureCategoryResolver,
                              CicdNotificationService cicdNotificationService,
                              CicdNotificationConfigRepository notificationConfigRepo,
                              com.xammer.cloud.repository.UserRepository userRepository) {
        this.githubConfigRepo = githubConfigRepo;
        this.jenkinsConfigRepo = jenkinsConfigRepo;
        this.runRecordRepo = runRecordRepo;
        this.cicdStatusService = cicdStatusService;
        this.jenkinsService = jenkinsService;
        this.encryptionService = encryptionService;
        this.failureCategoryResolver = failureCategoryResolver;
        this.cicdNotificationService = cicdNotificationService;
        this.notificationConfigRepo = notificationConfigRepo;
        this.userRepository = userRepository;
    }

    @Scheduled(fixedDelay = 900000) // 15 minutes
    @Transactional
    public void syncAll() {
        logger.info("CicdSyncScheduler: starting sync");
        syncGitHub();
        syncJenkins();
        logger.info("CicdSyncScheduler: sync complete");
    }

    @Transactional
    public void syncForUser(Long userId) {
        logger.info("CicdSyncScheduler: on-demand sync for user {}", userId);
        syncGitHubForUser(userId);
        syncJenkinsForUser(userId);
    }

    private void syncGitHub() {
        githubConfigRepo.findAll().forEach(config -> {
            try {
                syncGitHubConfig(config);
            } catch (Exception e) {
                logger.error("GitHub sync failed for config {}: {}", config.getId(), e.getMessage());
            }
        });
    }

    private void syncGitHubForUser(Long userId) {
        githubConfigRepo.findByUserId(userId).forEach(config -> {
            try {
                syncGitHubConfig(config);
            } catch (Exception e) {
                logger.error("GitHub sync failed for config {}: {}", config.getId(), e.getMessage());
            }
        });
    }

    private void syncGitHubConfig(GitHubIntegrationConfig config) {
        String pat = encryptionService.decrypt(config.getEncryptedPat());
        if (pat == null) return;

        List<GitHubWorkflowRunDto> runs = cicdStatusService.getLatestRunPerWorkflow(
                config.getGithubOwner(), config.getGithubRepo(), pat);

        for (GitHubWorkflowRunDto run : runs) {
            upsertGitHubRun(run, config);
        }

        config.setLastSyncedAt(Instant.now());
        githubConfigRepo.save(config);
    }

    private void upsertGitHubRun(GitHubWorkflowRunDto run, GitHubIntegrationConfig config) {
        String runId = String.valueOf(run.getId());
        Optional<CicdRunRecord> existing = runRecordRepo.findByConfigIdAndProviderAndRunId(
                config.getId(), "GITHUB", runId);

        CicdRunRecord record = existing.orElseGet(CicdRunRecord::new);
        record.setProvider("GITHUB");
        record.setConfigId(config.getId());
        record.setUserId(config.getUser().getId());
        record.setWorkflowName(run.getName() != null ? run.getName() : "unknown");
        record.setRepoOwner(config.getGithubOwner());
        record.setRepoName(config.getGithubRepo());
        record.setRunId(runId);
        // GitHubWorkflowRunDto.getRunNumber() returns Long; CicdRunRecord expects Integer
        record.setRunNumber(run.getRunNumber() != null ? run.getRunNumber().intValue() : null);
        record.setStatus(normalizeGitHubStatus(run));
        record.setHtmlUrl(run.getHtmlUrl());
        record.setDeployment(run.getName() != null && run.getName().toLowerCase().contains("deploy"));

        if (run.getUpdatedAt() != null) {
            try { record.setStartedAt(Instant.parse(run.getUpdatedAt())); } catch (Exception ignored) {}
        }
        if (record.getStartedAt() == null) record.setStartedAt(Instant.now());

        String previousStatus = existing.map(CicdRunRecord::getStatus).orElse(null);

        if ("failure".equals(record.getStatus())) {
            record.setFailureCategory(failureCategoryResolver.resolve(run.getName(), null));
        } else {
            record.setFailureCategory(null);
        }

        runRecordRepo.save(record);

        if ("failure".equals(record.getStatus())
                && !"failure".equals(previousStatus)
                && !record.isNotificationSent()) {
            triggerNotification(record);
        }
    }

    private String normalizeGitHubStatus(GitHubWorkflowRunDto run) {
        String display = run.getDisplayStatus();
        if (display == null) display = "";
        String lower = display.toLowerCase();
        if (lower.equals("success")) return "success";
        if (lower.equals("failure") || lower.equals("failed")) return "failure";
        if (lower.equals("cancelled")) return "cancelled";
        return "unknown";
    }

    private void syncJenkins() {
        jenkinsConfigRepo.findAll().forEach(config -> {
            try {
                syncJenkinsConfig(config);
            } catch (Exception e) {
                logger.error("Jenkins sync failed for config {}: {}", config.getId(), e.getMessage());
            }
        });
    }

    private void syncJenkinsForUser(Long userId) {
        jenkinsConfigRepo.findByUserId(userId).forEach(config -> {
            try {
                syncJenkinsConfig(config);
            } catch (Exception e) {
                logger.error("Jenkins sync failed for config {}: {}", config.getId(), e.getMessage());
            }
        });
    }

    private void syncJenkinsConfig(JenkinsIntegrationConfig config) {
        String token = encryptionService.decrypt(config.getEncryptedApiToken());
        if (token == null) return;

        List<JenkinsJobDto> jobs = jenkinsService.getJobs(config, token);
        for (JenkinsJobDto job : jobs) {
            if (job.getLastBuild() == null) continue;
            String runId = String.valueOf(job.getLastBuild().getNumber());
            Optional<CicdRunRecord> existing = runRecordRepo.findByConfigIdAndProviderAndRunId(
                    config.getId(), "JENKINS", runId);

            CicdRunRecord record = existing.orElseGet(CicdRunRecord::new);
            record.setProvider("JENKINS");
            record.setConfigId(config.getId());
            record.setUserId(config.getUser().getId());
            record.setWorkflowName(job.getName());
            record.setRunId(runId);
            record.setRunNumber(job.getLastBuild().getNumber());
            record.setStatus(normalizeJenkinsStatus(job.getDisplayStatus()));
            record.setHtmlUrl(job.getLastBuild().getUrl());
            record.setDeployment(false);
            long ts = job.getLastBuild().getTimestamp();
            if (ts > 0) record.setStartedAt(Instant.ofEpochMilli(ts));
            else record.setStartedAt(Instant.now());

            String previousStatus = existing.map(CicdRunRecord::getStatus).orElse(null);

            if ("failure".equals(record.getStatus())) {
                String jenkinsRaw = job.getDisplayStatus() != null ? job.getDisplayStatus().toUpperCase() : null;
                record.setFailureCategory(failureCategoryResolver.resolve(job.getName(), jenkinsRaw));
                record.setFailedStage(job.getName());
            } else {
                record.setFailureCategory(null);
            }

            runRecordRepo.save(record);

            if ("failure".equals(record.getStatus())
                    && !"failure".equals(previousStatus)
                    && !record.isNotificationSent()) {
                triggerNotification(record);
            }
        }

        config.setLastSyncedAt(Instant.now());
        jenkinsConfigRepo.save(config);
    }

    private String normalizeJenkinsStatus(String displayStatus) {
        if (displayStatus == null) return "unknown";
        String lower = displayStatus.toLowerCase();
        if (lower.equals("success")) return "success";
        if (lower.equals("failure") || lower.equals("failed")) return "failure";
        if (lower.equals("cancelled") || lower.equals("aborted")) return "cancelled";
        return "unknown";
    }

    private void triggerNotification(CicdRunRecord record) {
        try {
            notificationConfigRepo.findByUserId(record.getUserId()).ifPresent(config -> {
                userRepository.findById(record.getUserId()).ifPresent(user -> {
                    cicdNotificationService.notifyFailure(record, config, user.getEmail());
                    record.setNotificationSent(true);
                    runRecordRepo.save(record);
                });
            });
        } catch (Exception e) {
            logger.error("Notification failed for run {}: {}", record.getRunId(), e.getMessage());
            // Do NOT set notificationSent — will retry next sync
        }
    }
}
