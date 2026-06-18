# CI/CD Failure Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add failure categorization, error-snippet extraction, diff-vs-last-good-run, email/Slack alerting, and a pipeline health scorecard to the existing CI/CD Pipelines feature.

**Architecture:** Hybrid sync + on-demand — failure category and failed stage are derived at sync time from job names (no log fetch), while error snippets and diff are fetched on-demand only when a user opens the failure detail view. Notifications fire on status flip during sync.

**Tech Stack:** Java 17 / Spring Boot, JPA/Hibernate, Flyway, RestTemplate, React/TypeScript, existing `EmailService`, existing `apiClient` util.

---

## File Map

### Backend — New Files
- `xamops-service/src/main/resources/db/migration/V20260618_1__cicd_failure_intelligence.sql`
- `xamops-service/src/main/java/com/xammer/cloud/domain/CicdNotificationConfig.java`
- `xamops-service/src/main/java/com/xammer/cloud/repository/CicdNotificationConfigRepository.java`
- `xamops-service/src/main/java/com/xammer/cloud/service/FailureCategoryResolver.java`
- `xamops-service/src/main/java/com/xammer/cloud/service/CicdNotificationService.java`
- `xamops-service/src/main/java/com/xammer/cloud/service/FailureAnalysisService.java`
- `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/FailureAnalysisDto.java`
- `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/DiffSummaryDto.java`
- `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/NotificationConfigDto.java`
- `xamops-service/src/main/java/com/xammer/cloud/controller/CicdNotificationController.java`
- `xamops-service/src/test/java/com/xammer/cloud/service/FailureCategoryResolverTest.java`
- `xamops-service/src/test/java/com/xammer/cloud/service/FailureAnalysisServiceTest.java`

### Backend — Modified Files
- `xamops-service/src/main/java/com/xammer/cloud/domain/CicdRunRecord.java` — add `failureCategory`, `notificationSent`
- `xamops-service/src/main/java/com/xammer/cloud/repository/CicdRunRecordRepository.java` — add query for last success run
- `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/GitHubWorkflowRunDto.java` — add `failureCategory`, `failedStage`
- `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/JenkinsJobDto.java` — add `failureCategory`, `failedStage`
- `xamops-service/src/main/java/com/xammer/cloud/service/CicdSyncScheduler.java` — call resolver + notification trigger
- `xamops-service/src/main/java/com/xammer/cloud/controller/CicdController.java` — enrich DTOs with DB data
- `xamops-service/src/main/java/com/xammer/cloud/controller/CicdMetricsController.java` — add failure-analysis + diff-summary endpoints

### Frontend — New Files
- `frontend-app/src/components/CICDPipelines/CicdHealthScorecard.tsx`
- `frontend-app/src/components/CICDPipelines/CicdFailureDetail.tsx`
- `frontend-app/src/components/CICDPipelines/CicdDiffSummary.tsx`
- `frontend-app/src/components/CICDPipelines/CicdNotificationSettings.tsx`
- `frontend-app/src/hooks/useCicdFailureAnalysis.ts`
- `frontend-app/src/hooks/useCicdNotificationConfig.ts`

### Frontend — Modified Files
- `frontend-app/src/types/cicdPipelines.ts` — add new types
- `frontend-app/src/pages/CICDPipelinesPage.tsx` — add Health tab + dialog tab strip

---

## Task 1: Flyway Migration

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V20260618_1__cicd_failure_intelligence.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Add failure intelligence columns to existing run records
ALTER TABLE cicd_run_record
    ADD COLUMN IF NOT EXISTS failure_category VARCHAR(30),
    ADD COLUMN IF NOT EXISTS notification_sent BOOLEAN NOT NULL DEFAULT FALSE;

-- Notification config: one row per user
CREATE TABLE IF NOT EXISTS cicd_notification_config (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    email_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    slack_webhook_url VARCHAR(500),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
```

- [ ] **Step 2: Verify migration runs**

```bash
cd xamops-service && mvn flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/xamops_db -Dflyway.user=<user> -Dflyway.password=<pass>
```
Expected: `Successfully applied 1 migration`

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/V20260618_1__cicd_failure_intelligence.sql
git commit -m "feat(cicd): add failure_category, notification_sent columns + cicd_notification_config table"
```

---

## Task 2: Domain & Repository Updates

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/domain/CicdRunRecord.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/CicdNotificationConfig.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/CicdNotificationConfigRepository.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/repository/CicdRunRecordRepository.java`

- [ ] **Step 1: Add two fields to `CicdRunRecord`**

Add after the `failedStage` field (around line 35):

```java
@Column(name = "failure_category", length = 30)
private String failureCategory;

@Column(name = "notification_sent", nullable = false)
private boolean notificationSent = false;
```

Add getters/setters at the bottom of the class (before the last `}`):

```java
public String getFailureCategory() { return failureCategory; }
public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }

public boolean isNotificationSent() { return notificationSent; }
public void setNotificationSent(boolean notificationSent) { this.notificationSent = notificationSent; }
```

- [ ] **Step 2: Add last-success query to `CicdRunRecordRepository`**

```java
@Query("SELECT r FROM CicdRunRecord r WHERE r.userId = :userId AND r.workflowName = :workflowName " +
       "AND r.provider = :provider AND r.status = 'success' ORDER BY r.startedAt DESC")
List<CicdRunRecord> findTopSuccessRun(
        @Param("userId") Long userId,
        @Param("workflowName") String workflowName,
        @Param("provider") String provider,
        Pageable pageable);
```

- [ ] **Step 3: Create `CicdNotificationConfig` entity**

```java
package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cicd_notification_config")
public class CicdNotificationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "slack_webhook_url", length = 500)
    private String slackWebhookUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }
    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String slackWebhookUrl) { this.slackWebhookUrl = slackWebhookUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 4: Create `CicdNotificationConfigRepository`**

```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CicdNotificationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CicdNotificationConfigRepository extends JpaRepository<CicdNotificationConfig, Long> {
    Optional<CicdNotificationConfig> findByUserId(Long userId);
}
```

- [ ] **Step 5: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/domain/CicdRunRecord.java \
        xamops-service/src/main/java/com/xammer/cloud/domain/CicdNotificationConfig.java \
        xamops-service/src/main/java/com/xammer/cloud/repository/CicdNotificationConfigRepository.java \
        xamops-service/src/main/java/com/xammer/cloud/repository/CicdRunRecordRepository.java
git commit -m "feat(cicd): add failureCategory/notificationSent to CicdRunRecord + CicdNotificationConfig entity"
```

---

## Task 3: FailureCategoryResolver (TDD)

**Files:**
- Create: `xamops-service/src/test/java/com/xammer/cloud/service/FailureCategoryResolverTest.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/FailureCategoryResolver.java`

- [ ] **Step 1: Write failing tests**

```java
package com.xammer.cloud.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FailureCategoryResolverTest {

    private final FailureCategoryResolver resolver = new FailureCategoryResolver();

    @Test
    void testJobName_returnsTestFailure() {
        assertThat(resolver.resolve("Run Tests", null)).isEqualTo("TEST_FAILURE");
        assertThat(resolver.resolve("unit-spec", null)).isEqualTo("TEST_FAILURE");
        assertThat(resolver.resolve("Integration Tests", null)).isEqualTo("TEST_FAILURE");
    }

    @Test
    void buildJobName_returnsBuildError() {
        assertThat(resolver.resolve("Build & Compile", null)).isEqualTo("BUILD_ERROR");
        assertThat(resolver.resolve("maven-build", null)).isEqualTo("BUILD_ERROR");
    }

    @Test
    void deployJobName_returnsDeployFailure() {
        assertThat(resolver.resolve("Deploy to UAT", null)).isEqualTo("DEPLOY_FAILURE");
        assertThat(resolver.resolve("release-pipeline", null)).isEqualTo("DEPLOY_FAILURE");
    }

    @Test
    void jenkinsUnstable_returnsTestFailure() {
        assertThat(resolver.resolve("my-job", "UNSTABLE")).isEqualTo("TEST_FAILURE");
    }

    @Test
    void unknownJob_returnsUnknown() {
        assertThat(resolver.resolve("misc-job", null)).isEqualTo("UNKNOWN");
        assertThat(resolver.resolve(null, null)).isEqualTo("UNKNOWN");
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd xamops-service && mvn test -pl . -Dtest=FailureCategoryResolverTest -q 2>&1 | tail -5
```
Expected: `BUILD FAILURE` — class not found

- [ ] **Step 3: Implement `FailureCategoryResolver`**

```java
package com.xammer.cloud.service;

import org.springframework.stereotype.Component;

@Component
public class FailureCategoryResolver {

    public String resolve(String jobName, String jenkinsStatus) {
        if ("UNSTABLE".equalsIgnoreCase(jenkinsStatus)) return "TEST_FAILURE";
        if (jobName == null) return "UNKNOWN";
        String lower = jobName.toLowerCase();
        if (lower.contains("test") || lower.contains("spec")) return "TEST_FAILURE";
        if (lower.contains("build") || lower.contains("compile")) return "BUILD_ERROR";
        if (lower.contains("deploy") || lower.contains("release")) return "DEPLOY_FAILURE";
        return "UNKNOWN";
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
cd xamops-service && mvn test -pl . -Dtest=FailureCategoryResolverTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, `Tests run: 5, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/FailureCategoryResolver.java \
        xamops-service/src/test/java/com/xammer/cloud/service/FailureCategoryResolverTest.java
git commit -m "feat(cicd): add FailureCategoryResolver with TDD"
```

---

## Task 4: CicdSyncScheduler — Enrichment + Notification Trigger

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/CicdSyncScheduler.java`

- [ ] **Step 1: Add constructor dependencies**

Add `FailureCategoryResolver` and `CicdNotificationService` to the constructor:

```java
private final FailureCategoryResolver failureCategoryResolver;
private final CicdNotificationService cicdNotificationService;
private final CicdNotificationConfigRepository notificationConfigRepo;
private final com.xammer.cloud.repository.UserRepository userRepository;
```

Update the constructor signature to include all four new deps (keep all existing deps, add these four at the end):

```java
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
```

- [ ] **Step 2: Update `upsertGitHubRun` to set category and trigger notification**

In the existing `upsertGitHubRun` method, after `record.setStatus(...)`, add:

```java
String previousStatus = existing.map(CicdRunRecord::getStatus).orElse(null);

if ("failure".equals(record.getStatus())) {
    record.setFailureCategory(failureCategoryResolver.resolve(run.getName(), null));
    // failedStage is set separately from job-level data — leave as-is if already populated
} else {
    record.setFailureCategory(null);
}

runRecordRepo.save(record);

// Fire notification on first failure (status flip)
if ("failure".equals(record.getStatus())
        && !"failure".equals(previousStatus)
        && !record.isNotificationSent()) {
    triggerNotification(record);
}
```

- [ ] **Step 3: Add `triggerNotification` helper**

Add this private method to the class:

```java
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
```

- [ ] **Step 4: Apply same pattern to `syncJenkinsConfig`**

In `syncJenkinsConfig`, after `record.setStatus(normalizeJenkinsStatus(...))`, add:

```java
String previousStatus = existing.map(CicdRunRecord::getStatus).orElse(null);

if ("failure".equals(record.getStatus())) {
    String jenkinsRaw = job.getDisplayStatus().toUpperCase();
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
```

- [ ] **Step 5: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/CicdSyncScheduler.java
git commit -m "feat(cicd): enrich sync with failure category + notification trigger on status flip"
```

---

## Task 5: CicdNotificationService

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/CicdNotificationService.java`

- [ ] **Step 1: Create the service**

```java
package com.xammer.cloud.service;

import com.xammer.cloud.domain.CicdNotificationConfig;
import com.xammer.cloud.domain.CicdRunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class CicdNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(CicdNotificationService.class);

    private final EmailService emailService;
    private final RestTemplate restTemplate;

    public CicdNotificationService(EmailService emailService, RestTemplate restTemplate) {
        this.emailService = emailService;
        this.restTemplate = restTemplate;
    }

    public void notifyFailure(CicdRunRecord record, CicdNotificationConfig config, String userEmail) {
        String subject = "[XamOps] Pipeline failed: " + record.getWorkflowName()
                + (record.getRepoName() != null ? " in " + record.getRepoName() : "");

        String htmlBody = buildEmailBody(record);

        if (config.isEmailEnabled() && userEmail != null) {
            try {
                emailService.sendHtmlEmail(userEmail, subject, htmlBody);
                logger.info("Failure notification email sent for run {}", record.getRunId());
            } catch (Exception e) {
                logger.error("Failed to send failure email: {}", e.getMessage());
                throw e; // Caller decides whether to set notificationSent
            }
        }

        if (config.getSlackWebhookUrl() != null && !config.getSlackWebhookUrl().isBlank()) {
            sendSlackNotification(config.getSlackWebhookUrl(), record);
        }
    }

    private void sendSlackNotification(String webhookUrl, CicdRunRecord record) {
        try {
            String text = ":x: *Pipeline failed*: `" + record.getWorkflowName() + "`"
                    + (record.getRepoName() != null ? " in `" + record.getRepoName() + "`" : "")
                    + "\n*Category:* " + (record.getFailureCategory() != null ? record.getFailureCategory() : "UNKNOWN")
                    + (record.getFailedStage() != null ? "\n*Failed stage:* " + record.getFailedStage() : "")
                    + (record.getHtmlUrl() != null ? "\n<" + record.getHtmlUrl() + "|View run>" : "");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> req = new HttpEntity<>(Map.of("text", text), headers);
            restTemplate.postForEntity(webhookUrl, req, String.class);
            logger.info("Slack notification sent for run {}", record.getRunId());
        } catch (Exception e) {
            logger.warn("Slack notification failed for run {}: {}", record.getRunId(), e.getMessage());
        }
    }

    private String buildEmailBody(CicdRunRecord record) {
        return "<html><body style='font-family:sans-serif;'>"
                + "<h2 style='color:#e53e3e;'>Pipeline Failed</h2>"
                + "<table style='border-collapse:collapse;'>"
                + row("Workflow", record.getWorkflowName())
                + (record.getRepoName() != null ? row("Repository", record.getRepoOwner() + "/" + record.getRepoName()) : "")
                + row("Category", record.getFailureCategory() != null ? record.getFailureCategory() : "UNKNOWN")
                + (record.getFailedStage() != null ? row("Failed Stage", record.getFailedStage()) : "")
                + "</table>"
                + (record.getHtmlUrl() != null
                    ? "<br><a href='" + record.getHtmlUrl() + "' style='background:#3182ce;color:white;padding:8px 16px;border-radius:4px;text-decoration:none;'>View Run</a>"
                    : "")
                + "</body></html>";
    }

    private String row(String label, String value) {
        return "<tr><td style='padding:4px 12px 4px 0;font-weight:bold;'>" + label + "</td>"
                + "<td style='padding:4px 0;'>" + value + "</td></tr>";
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/CicdNotificationService.java
git commit -m "feat(cicd): add CicdNotificationService for email + Slack failure alerts"
```

---

## Task 6: Enrich Run DTOs with DB Failure Data

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/GitHubWorkflowRunDto.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/JenkinsJobDto.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/CicdController.java`

- [ ] **Step 1: Add fields to `GitHubWorkflowRunDto`**

Add two fields after the `owner` field:

```java
private String failureCategory;
private String failedStage;
```

Add getters/setters:

```java
public String getFailureCategory() { return failureCategory; }
public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }
public String getFailedStage() { return failedStage; }
public void setFailedStage(String failedStage) { this.failedStage = failedStage; }
```

- [ ] **Step 2: Add fields to `JenkinsJobDto`**

Add two fields after the `color` field:

```java
private String failureCategory;
private String failedStage;
```

Add getters/setters:

```java
public String getFailureCategory() { return failureCategory; }
public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }
public String getFailedStage() { return failedStage; }
public void setFailedStage(String failedStage) { this.failedStage = failedStage; }
```

- [ ] **Step 3: Inject `CicdRunRecordRepository` into `CicdController`**

Add to the existing `@Autowired` fields in `CicdController`:

```java
@Autowired
private CicdRunRecordRepository cicdRunRecordRepository;
```

- [ ] **Step 4: Enrich GitHub runs in `getGithubActionsRunsForUser`**

After `allRuns.sort(...)`, add:

```java
// Enrich with failure category and failed stage from DB
for (GitHubWorkflowRunDto run : allRuns) {
    configs.stream()
        .filter(c -> run.getRepository() != null
            && run.getRepository().getFullName() != null
            && run.getRepository().getFullName().equals(c.getGithubOwner() + "/" + c.getGithubRepo()))
        .findFirst()
        .ifPresent(config -> {
            cicdRunRecordRepository
                .findByConfigIdAndProviderAndRunId(config.getId(), "GITHUB", String.valueOf(run.getId()))
                .ifPresent(record -> {
                    run.setFailureCategory(record.getFailureCategory());
                    run.setFailedStage(record.getFailedStage());
                });
        });
}
```

- [ ] **Step 5: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/cicd/GitHubWorkflowRunDto.java \
        xamops-service/src/main/java/com/xammer/cloud/dto/cicd/JenkinsJobDto.java \
        xamops-service/src/main/java/com/xammer/cloud/controller/CicdController.java
git commit -m "feat(cicd): enrich run DTOs with failureCategory and failedStage from DB"
```

---

## Task 7: Notification Config Endpoints

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/NotificationConfigDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/CicdNotificationController.java`

- [ ] **Step 1: Create `NotificationConfigDto`**

```java
package com.xammer.cloud.dto.cicd;

public class NotificationConfigDto {
    private boolean emailEnabled;
    private String slackWebhookUrl;

    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }
    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String slackWebhookUrl) { this.slackWebhookUrl = slackWebhookUrl; }
}
```

- [ ] **Step 2: Create `CicdNotificationController`**

```java
package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CicdNotificationConfig;
import com.xammer.cloud.dto.cicd.NotificationConfigDto;
import com.xammer.cloud.repository.CicdNotificationConfigRepository;
import com.xammer.cloud.service.CicdConfigurationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping({"/api/cicd", "/cicd"})
@CrossOrigin(origins = "*")
public class CicdNotificationController {

    private final CicdNotificationConfigRepository configRepo;
    private final CicdConfigurationService cicdConfigService;

    public CicdNotificationController(CicdNotificationConfigRepository configRepo,
                                       CicdConfigurationService cicdConfigService) {
        this.configRepo = configRepo;
        this.cicdConfigService = cicdConfigService;
    }

    @GetMapping("/notification-config")
    public ResponseEntity<NotificationConfigDto> getConfig() {
        return cicdConfigService.getCurrentUser()
            .map(user -> {
                Optional<CicdNotificationConfig> cfg = configRepo.findByUserId(user.getId());
                NotificationConfigDto dto = new NotificationConfigDto();
                dto.setEmailEnabled(cfg.map(CicdNotificationConfig::isEmailEnabled).orElse(true));
                dto.setSlackWebhookUrl(cfg.map(CicdNotificationConfig::getSlackWebhookUrl).orElse(null));
                return ResponseEntity.ok(dto);
            })
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/notification-config")
    public ResponseEntity<Void> saveConfig(@RequestBody NotificationConfigDto dto) {
        return cicdConfigService.getCurrentUser()
            .map(user -> {
                CicdNotificationConfig cfg = configRepo.findByUserId(user.getId())
                        .orElseGet(() -> { CicdNotificationConfig c = new CicdNotificationConfig(); c.setUserId(user.getId()); return c; });
                cfg.setEmailEnabled(dto.isEmailEnabled());
                cfg.setSlackWebhookUrl(dto.getSlackWebhookUrl());
                cfg.setUpdatedAt(Instant.now());
                configRepo.save(cfg);
                return ResponseEntity.ok().<Void>build();
            })
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/cicd/NotificationConfigDto.java \
        xamops-service/src/main/java/com/xammer/cloud/controller/CicdNotificationController.java
git commit -m "feat(cicd): add GET/POST /api/cicd/notification-config endpoints"
```

---

## Task 8: FailureAnalysisService — Log Parsing (TDD)

**Files:**
- Create: `xamops-service/src/test/java/com/xammer/cloud/service/FailureAnalysisServiceTest.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/FailureAnalysisDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/FailureAnalysisService.java`

- [ ] **Step 1: Create `FailureAnalysisDto`**

```java
package com.xammer.cloud.dto.cicd;

import java.util.List;

public class FailureAnalysisDto {

    public static class Snippet {
        private int matchLine;       // 0-based index of the triggering line within the snippet
        private List<String> lines;  // up to 5 lines of context

        public int getMatchLine() { return matchLine; }
        public void setMatchLine(int matchLine) { this.matchLine = matchLine; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> lines) { this.lines = lines; }
    }

    private List<Snippet> snippets;
    private boolean fallback; // true when no pattern matched; snippets = last 20 lines

    public List<Snippet> getSnippets() { return snippets; }
    public void setSnippets(List<Snippet> snippets) { this.snippets = snippets; }
    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
}
```

- [ ] **Step 2: Write failing tests for log parsing**

```java
package com.xammer.cloud.service;

import com.xammer.cloud.dto.cicd.FailureAnalysisDto;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FailureAnalysisServiceTest {

    private final FailureAnalysisService service = new FailureAnalysisService(null, null, null, null);

    @Test
    void javaException_extracted() {
        String log = "Starting build...\nLine2\nLine3\n"
                + "java.lang.NullPointerException: Cannot read field\n"
                + "  at com.example.Foo.bar(Foo.java:42)\n"
                + "  at com.example.Main.main(Main.java:10)\n"
                + "Build complete.";
        FailureAnalysisDto result = service.parseLog(log);
        assertThat(result.getSnippets()).isNotEmpty();
        assertThat(result.isFallback()).isFalse();
        assertThat(result.getSnippets().get(0).getLines())
                .anyMatch(l -> l.contains("NullPointerException"));
    }

    @Test
    void npmError_extracted() {
        String log = "npm ERR! code ELIFECYCLE\nnpm ERR! errno 1\n";
        FailureAnalysisDto result = service.parseLog(log);
        assertThat(result.getSnippets()).isNotEmpty();
        assertThat(result.getSnippets().get(0).getLines()).anyMatch(l -> l.contains("npm ERR!"));
    }

    @Test
    void noMatch_returnsFallbackLastLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) sb.append("log line ").append(i).append("\n");
        FailureAnalysisDto result = service.parseLog(sb.toString());
        assertThat(result.isFallback()).isTrue();
        assertThat(result.getSnippets()).hasSize(1);
        assertThat(result.getSnippets().get(0).getLines()).hasSize(20);
        assertThat(result.getSnippets().get(0).getLines().get(0)).contains("log line 11");
    }

    @Test
    void maxThreeSnippets_returned() {
        String log = "Exception: a\nException: b\nException: c\nException: d\n";
        FailureAnalysisDto result = service.parseLog(log);
        assertThat(result.getSnippets().size()).isLessThanOrEqualTo(3);
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
cd xamops-service && mvn test -Dtest=FailureAnalysisServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD FAILURE` — class not found

- [ ] **Step 4: Implement `FailureAnalysisService.parseLog`**

```java
package com.xammer.cloud.service;

import com.xammer.cloud.dto.cicd.FailureAnalysisDto;
import com.xammer.cloud.repository.CicdRunRecordRepository;
import com.xammer.cloud.service.CicdStatusService;
import com.xammer.cloud.service.JenkinsService;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class FailureAnalysisService {

    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("Exception:|Caused by:|at com\\.|at org\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("BUILD FAILURE|Tests run:.*Failures:[^0]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("npm ERR!|AssertionError", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Error:|FAILED|exit code [^0]", Pattern.CASE_INSENSITIVE)
    );
    private static final int CONTEXT = 2; // lines before + after match
    private static final int MAX_SNIPPETS = 3;
    private static final int FALLBACK_LINES = 20;

    private final CicdRunRecordRepository runRecordRepo;
    private final CicdStatusService cicdStatusService;
    private final JenkinsService jenkinsService;
    private final org.springframework.web.client.RestTemplate restTemplate;

    public FailureAnalysisService(CicdRunRecordRepository runRecordRepo,
                                   CicdStatusService cicdStatusService,
                                   JenkinsService jenkinsService,
                                   RestTemplateBuilder restTemplateBuilder) {
        this.runRecordRepo = runRecordRepo;
        this.cicdStatusService = cicdStatusService;
        this.jenkinsService = jenkinsService;
        this.restTemplate = restTemplateBuilder != null ? restTemplateBuilder.build() : null;
    }

    /** Pure log parsing — no I/O. */
    public FailureAnalysisDto parseLog(String rawLog) {
        FailureAnalysisDto dto = new FailureAnalysisDto();
        if (rawLog == null || rawLog.isBlank()) {
            dto.setSnippets(List.of());
            dto.setFallback(true);
            return dto;
        }

        String[] lines = rawLog.split("\n", -1);
        List<FailureAnalysisDto.Snippet> snippets = new ArrayList<>();
        Set<Integer> usedLines = new HashSet<>();

        outer:
        for (Pattern pattern : PATTERNS) {
            for (int i = 0; i < lines.length; i++) {
                if (snippets.size() >= MAX_SNIPPETS) break outer;
                if (pattern.matcher(lines[i]).find() && !usedLines.contains(i)) {
                    int from = Math.max(0, i - CONTEXT);
                    int to = Math.min(lines.length - 1, i + CONTEXT);
                    List<String> ctx = new ArrayList<>();
                    for (int j = from; j <= to; j++) {
                        ctx.add(lines[j]);
                        usedLines.add(j);
                    }
                    FailureAnalysisDto.Snippet s = new FailureAnalysisDto.Snippet();
                    s.setMatchLine(i - from);
                    s.setLines(ctx);
                    snippets.add(s);
                }
            }
        }

        if (snippets.isEmpty()) {
            // Fallback: last FALLBACK_LINES
            dto.setFallback(true);
            int from = Math.max(0, lines.length - FALLBACK_LINES);
            FailureAnalysisDto.Snippet s = new FailureAnalysisDto.Snippet();
            s.setMatchLine(0);
            s.setLines(Arrays.asList(Arrays.copyOfRange(lines, from, lines.length)));
            dto.setSnippets(List.of(s));
        } else {
            dto.setFallback(false);
            dto.setSnippets(snippets);
        }
        return dto;
    }
}
```

- [ ] **Step 5: Run tests — verify they pass**

```bash
cd xamops-service && mvn test -Dtest=FailureAnalysisServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`, `Tests run: 4, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/cicd/FailureAnalysisDto.java \
        xamops-service/src/main/java/com/xammer/cloud/service/FailureAnalysisService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/FailureAnalysisServiceTest.java
git commit -m "feat(cicd): add FailureAnalysisService with regex log parsing (TDD)"
```

---

## Task 9: FailureAnalysisService — Diff Summary

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/cicd/DiffSummaryDto.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/FailureAnalysisService.java`

- [ ] **Step 1: Create `DiffSummaryDto`**

```java
package com.xammer.cloud.dto.cicd;

import java.util.List;

public class DiffSummaryDto {

    public static class CommitInfo {
        private String sha;
        private String message;
        private String author;
        private String timestamp;

        public String getSha() { return sha; }
        public void setSha(String sha) { this.sha = sha; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }

    private List<CommitInfo> commits;
    private int filesChanged;
    private List<String> changedFiles;
    private boolean noPreviousSuccess; // true when no baseline run found

    public List<CommitInfo> getCommits() { return commits; }
    public void setCommits(List<CommitInfo> commits) { this.commits = commits; }
    public int getFilesChanged() { return filesChanged; }
    public void setFilesChanged(int filesChanged) { this.filesChanged = filesChanged; }
    public List<String> getChangedFiles() { return changedFiles; }
    public void setChangedFiles(List<String> changedFiles) { this.changedFiles = changedFiles; }
    public boolean isNoPreviousSuccess() { return noPreviousSuccess; }
    public void setNoPreviousSuccess(boolean noPreviousSuccess) { this.noPreviousSuccess = noPreviousSuccess; }
}
```

- [ ] **Step 2: Add `getDiffSummary` to `FailureAnalysisService`**

Add these imports at the top of `FailureAnalysisService.java`:

```java
import com.xammer.cloud.dto.cicd.DiffSummaryDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import java.util.stream.Collectors;
```

Add the method to the class:

```java
public DiffSummaryDto getDiffSummaryForGitHub(Long userId, String workflowName,
                                               String owner, String repo,
                                               String headSha, String pat) {
    DiffSummaryDto dto = new DiffSummaryDto();

    List<com.xammer.cloud.domain.CicdRunRecord> successRuns =
            runRecordRepo.findTopSuccessRun(userId, workflowName, "GITHUB", PageRequest.of(0, 1));

    if (successRuns.isEmpty()) {
        dto.setNoPreviousSuccess(true);
        dto.setCommits(List.of());
        dto.setChangedFiles(List.of());
        return dto;
    }

    // Use the run URL to extract base SHA — stored in htmlUrl which contains the run ID
    // GitHub compare API needs SHAs; query runs API for head_sha
    try {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + pat);
        headers.set("Accept", "application/vnd.github.v3+json");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // Fetch the successful run to get its head_sha
        String baseRunId = successRuns.get(0).getRunId();
        String runUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + baseRunId;
        var runResp = restTemplate.exchange(runUrl, HttpMethod.GET, entity, com.fasterxml.jackson.databind.JsonNode.class);
        String baseSha = runResp.getBody() != null ? runResp.getBody().path("head_sha").asText() : null;

        if (baseSha == null || baseSha.isBlank()) {
            dto.setNoPreviousSuccess(true);
            dto.setCommits(List.of());
            dto.setChangedFiles(List.of());
            return dto;
        }

        String compareUrl = "https://api.github.com/repos/" + owner + "/" + repo
                + "/compare/" + baseSha + "..." + headSha;
        var compareResp = restTemplate.exchange(compareUrl, HttpMethod.GET, entity, com.fasterxml.jackson.databind.JsonNode.class);
        var body = compareResp.getBody();

        List<DiffSummaryDto.CommitInfo> commits = new ArrayList<>();
        if (body != null && body.has("commits")) {
            for (var c : body.get("commits")) {
                DiffSummaryDto.CommitInfo ci = new DiffSummaryDto.CommitInfo();
                ci.setSha(c.path("sha").asText("").substring(0, Math.min(7, c.path("sha").asText("").length())));
                ci.setMessage(c.path("commit").path("message").asText("").split("\n")[0]);
                ci.setAuthor(c.path("commit").path("author").path("name").asText(""));
                ci.setTimestamp(c.path("commit").path("author").path("date").asText(""));
                commits.add(ci);
            }
        }

        List<String> files = new ArrayList<>();
        int fileCount = 0;
        if (body != null && body.has("files")) {
            fileCount = body.get("files").size();
            for (var f : body.get("files")) {
                files.add(f.path("filename").asText(""));
            }
        }

        dto.setCommits(commits);
        dto.setFilesChanged(fileCount);
        dto.setChangedFiles(files);
        dto.setNoPreviousSuccess(false);
    } catch (Exception e) {
        dto.setNoPreviousSuccess(true);
        dto.setCommits(List.of());
        dto.setChangedFiles(List.of());
    }
    return dto;
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/cicd/DiffSummaryDto.java \
        xamops-service/src/main/java/com/xammer/cloud/service/FailureAnalysisService.java
git commit -m "feat(cicd): add DiffSummaryDto + getDiffSummaryForGitHub using GitHub compare API"
```

---

## Task 10: Failure Analysis & Diff Endpoints

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/CicdMetricsController.java`

- [ ] **Step 1: Inject `FailureAnalysisService` into `CicdMetricsController`**

Add to constructor params and assignment:

```java
private final FailureAnalysisService failureAnalysisService;
```

Update constructor signature (add at end):

```java
FailureAnalysisService failureAnalysisService
```

Add assignment in constructor body:

```java
this.failureAnalysisService = failureAnalysisService;
```

- [ ] **Step 2: Add two new endpoints**

Add these methods to `CicdMetricsController`:

```java
/** GET /api/cicd/github/runs/{owner}/{repo}/{jobId}/failure-analysis */
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

/** GET /api/cicd/github/runs/{owner}/{repo}/{runId}/diff-summary */
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
        // Fetch the run to get head_sha
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
```

Add import at top of file:

```java
import com.xammer.cloud.dto.cicd.FailureAnalysisDto;
import com.xammer.cloud.dto.cicd.DiffSummaryDto;
import com.xammer.cloud.service.FailureAnalysisService;
import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.User;
```

- [ ] **Step 3: Run all backend tests**

```bash
cd xamops-service && mvn test -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/CicdMetricsController.java
git commit -m "feat(cicd): add failure-analysis and diff-summary endpoints"
```

---

## Task 11: Frontend Types

**Files:**
- Modify: `frontend-app/src/types/cicdPipelines.ts`

- [ ] **Step 1: Add new types and extend existing ones**

Add `failureCategory` and `failedStage` to `GitHubRun` (after `workflow_id`):

```typescript
failureCategory?: string;
failedStage?: string;
```

Add to `JenkinsJob` (after `color`):

```typescript
failureCategory?: string;
failedStage?: string;
```

Add `'health'` to `TabType`:

```typescript
export type TabType = 'github' | 'jenkins' | 'health';
```

Append new types at the bottom of the file:

```typescript
// --- Failure Analysis ---
export interface ErrorSnippet {
  matchLine: number;
  lines: string[];
}

export interface FailureAnalysis {
  snippets: ErrorSnippet[];
  fallback: boolean;
}

// --- Diff Summary ---
export interface CommitInfo {
  sha: string;
  message: string;
  author: string;
  timestamp: string;
}

export interface DiffSummary {
  commits: CommitInfo[];
  filesChanged: number;
  changedFiles: string[];
  noPreviousSuccess: boolean;
}

// --- Notification Config ---
export interface NotificationConfig {
  emailEnabled: boolean;
  slackWebhookUrl: string | null;
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no new errors related to cicdPipelines.ts

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/types/cicdPipelines.ts
git commit -m "feat(cicd): add FailureAnalysis, DiffSummary, NotificationConfig types"
```

---

## Task 12: `useCicdFailureAnalysis` Hook

**Files:**
- Create: `frontend-app/src/hooks/useCicdFailureAnalysis.ts`

- [ ] **Step 1: Create the hook**

```typescript
import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { FailureAnalysis, DiffSummary } from '../types/cicdPipelines';

interface UseCicdFailureAnalysisReturn {
  failureAnalysis: FailureAnalysis | null;
  diffSummary: DiffSummary | null;
  isLoadingAnalysis: boolean;
  isLoadingDiff: boolean;
  analysisError: string | null;
  diffError: string | null;
  fetchFailureAnalysis: (owner: string, repo: string, jobId: number | string) => Promise<void>;
  fetchDiffSummary: (owner: string, repo: string, runId: number | string) => Promise<void>;
  reset: () => void;
}

export const useCicdFailureAnalysis = (): UseCicdFailureAnalysisReturn => {
  const [failureAnalysis, setFailureAnalysis] = useState<FailureAnalysis | null>(null);
  const [diffSummary, setDiffSummary] = useState<DiffSummary | null>(null);
  const [isLoadingAnalysis, setIsLoadingAnalysis] = useState(false);
  const [isLoadingDiff, setIsLoadingDiff] = useState(false);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [diffError, setDiffError] = useState<string | null>(null);

  const fetchFailureAnalysis = useCallback(async (owner: string, repo: string, jobId: number | string) => {
    setIsLoadingAnalysis(true);
    setAnalysisError(null);
    try {
      const data = await apiClient.get(`/api/cicd/github/runs/${owner}/${repo}/${jobId}/failure-analysis`);
      setFailureAnalysis(data as FailureAnalysis);
    } catch {
      setAnalysisError('Could not load failure analysis');
    } finally {
      setIsLoadingAnalysis(false);
    }
  }, []);

  const fetchDiffSummary = useCallback(async (owner: string, repo: string, runId: number | string) => {
    setIsLoadingDiff(true);
    setDiffError(null);
    try {
      const data = await apiClient.get(`/api/cicd/github/runs/${owner}/${repo}/${runId}/diff-summary`);
      setDiffSummary(data as DiffSummary);
    } catch {
      setDiffError('Could not load diff summary');
    } finally {
      setIsLoadingDiff(false);
    }
  }, []);

  const reset = useCallback(() => {
    setFailureAnalysis(null);
    setDiffSummary(null);
    setAnalysisError(null);
    setDiffError(null);
  }, []);

  return {
    failureAnalysis, diffSummary,
    isLoadingAnalysis, isLoadingDiff,
    analysisError, diffError,
    fetchFailureAnalysis, fetchDiffSummary, reset,
  };
};
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/hooks/useCicdFailureAnalysis.ts
git commit -m "feat(cicd): add useCicdFailureAnalysis hook"
```

---

## Task 13: `useCicdNotificationConfig` Hook

**Files:**
- Create: `frontend-app/src/hooks/useCicdNotificationConfig.ts`

- [ ] **Step 1: Create the hook**

```typescript
import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { NotificationConfig } from '../types/cicdPipelines';

interface UseCicdNotificationConfigReturn {
  config: NotificationConfig | null;
  isLoading: boolean;
  isSaving: boolean;
  saveError: string | null;
  fetchConfig: () => Promise<void>;
  saveConfig: (config: NotificationConfig) => Promise<void>;
  testSlack: (webhookUrl: string) => Promise<boolean>;
}

export const useCicdNotificationConfig = (): UseCicdNotificationConfigReturn => {
  const [config, setConfig] = useState<NotificationConfig | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const fetchConfig = useCallback(async () => {
    setIsLoading(true);
    try {
      const data = await apiClient.get('/api/cicd/notification-config');
      setConfig(data as NotificationConfig);
    } catch {
      setConfig({ emailEnabled: true, slackWebhookUrl: null });
    } finally {
      setIsLoading(false);
    }
  }, []);

  const saveConfig = useCallback(async (cfg: NotificationConfig) => {
    setIsSaving(true);
    setSaveError(null);
    try {
      await apiClient.post('/api/cicd/notification-config', cfg);
      setConfig(cfg);
    } catch {
      setSaveError('Failed to save notification settings');
    } finally {
      setIsSaving(false);
    }
  }, []);

  const testSlack = useCallback(async (webhookUrl: string): Promise<boolean> => {
    try {
      await fetch(webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: '[XamOps] Test notification — Slack is connected!' }),
      });
      return true;
    } catch {
      return false;
    }
  }, []);

  useEffect(() => { fetchConfig(); }, [fetchConfig]);

  return { config, isLoading, isSaving, saveError, fetchConfig, saveConfig, testSlack };
};
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/hooks/useCicdNotificationConfig.ts
git commit -m "feat(cicd): add useCicdNotificationConfig hook"
```

---

## Task 14: `CicdHealthScorecard` Component

**Files:**
- Create: `frontend-app/src/components/CICDPipelines/CicdHealthScorecard.tsx`

- [ ] **Step 1: Create the component**

```tsx
import React from 'react';
import { GitHubRun, JenkinsJob } from '../../types/cicdPipelines';
import { CicdDailyStat } from '../../types/cicdPipelines';

interface ScorecardEntry {
  name: string;
  provider: 'github' | 'jenkins';
  successRate: number;
  lastStatus: string;
  lastRunAgo: string;
  sparkline: number[]; // pass/fail per day for 7 days (1=success, 0=failure)
}

interface Props {
  githubRuns: GitHubRun[];
  jenkinsJobs: JenkinsJob[];
  githubDailyStats: CicdDailyStat[];
  jenkinsDailyStats: CicdDailyStat[];
  onCardClick: (provider: 'github' | 'jenkins', name: string) => void;
}

function timeAgo(dateStr: string | undefined): string {
  if (!dateStr) return '—';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

function statusColor(status: string): string {
  const s = (status || '').toLowerCase();
  if (s === 'success') return 'bg-green-100 text-green-700 border-green-200';
  if (s === 'failure' || s === 'failed') return 'bg-red-100 text-red-700 border-red-200';
  if (s === 'running' || s === 'in_progress') return 'bg-blue-100 text-blue-700 border-blue-200';
  return 'bg-gray-100 text-gray-500 border-gray-200';
}

const Sparkline: React.FC<{ data: number[] }> = ({ data }) => (
  <div className="flex items-end gap-[2px] h-6">
    {data.map((v, i) => (
      <div
        key={i}
        className={`w-2 rounded-sm ${v > 0 ? 'bg-green-400' : 'bg-red-400'}`}
        style={{ height: `${Math.max(30, v > 0 ? 100 : 40)}%` }}
      />
    ))}
  </div>
);

const CicdHealthScorecard: React.FC<Props> = ({
  githubRuns, jenkinsJobs, githubDailyStats, jenkinsDailyStats, onCardClick,
}) => {
  const sparklineData = (stats: CicdDailyStat[]) =>
    stats.slice(-7).map(s => s.successCount > 0 ? 1 : 0);

  const githubSuccessRate = githubRuns.length === 0 ? 0 :
    Math.round((githubRuns.filter(r => r.displayStatus === 'success').length / githubRuns.length) * 100);

  const jenkinsSuccessRate = jenkinsJobs.length === 0 ? 0 :
    Math.round((jenkinsJobs.filter(j => j.displayStatus === 'Success').length / jenkinsJobs.length) * 100);

  if (githubRuns.length === 0 && jenkinsJobs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-[rgba(0,0,0,0.4)]">
        <p className="text-[14px]">Connect a GitHub repo or Jenkins server to see health scores</p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 p-4">
      {githubRuns.length > 0 && (
        <div
          className="border border-[rgba(0,0,0,0.08)] rounded-lg p-4 cursor-pointer hover:border-[rgba(0,0,0,0.2)] transition-all bg-white"
          onClick={() => onCardClick('github', 'GitHub Actions')}
        >
          <div className="flex items-center justify-between mb-2">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)]">GitHub Actions</span>
            <span className={`text-[11px] px-2 py-0.5 rounded border font-medium ${statusColor(githubRuns[0]?.displayStatus)}`}>
              {(githubRuns[0]?.displayStatus || 'Unknown').toUpperCase()}
            </span>
          </div>
          <div className="text-[24px] font-bold text-[rgba(0,0,0,0.85)] mb-1">{githubSuccessRate}%</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">success rate · {timeAgo(githubRuns[0]?.updated_at)}</div>
          <Sparkline data={sparklineData(githubDailyStats)} />
        </div>
      )}
      {jenkinsJobs.map(job => (
        <div
          key={job.name}
          className="border border-[rgba(0,0,0,0.08)] rounded-lg p-4 cursor-pointer hover:border-[rgba(0,0,0,0.2)] transition-all bg-white"
          onClick={() => onCardClick('jenkins', job.name)}
        >
          <div className="flex items-center justify-between mb-2">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)] truncate max-w-[160px]">{job.name}</span>
            <span className={`text-[11px] px-2 py-0.5 rounded border font-medium ${statusColor(job.displayStatus)}`}>
              {job.displayStatus.toUpperCase()}
            </span>
          </div>
          <div className="text-[24px] font-bold text-[rgba(0,0,0,0.85)] mb-1">{jenkinsSuccessRate}%</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">
            success rate · {job.lastBuild ? timeAgo(new Date(job.lastBuild.timestamp).toISOString()) : '—'}
          </div>
          <Sparkline data={sparklineData(jenkinsDailyStats)} />
        </div>
      ))}
    </div>
  );
};

export default CicdHealthScorecard;
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/components/CICDPipelines/CicdHealthScorecard.tsx
git commit -m "feat(cicd): add CicdHealthScorecard component"
```

---

## Task 15: `CicdFailureDetail` Component

**Files:**
- Create: `frontend-app/src/components/CICDPipelines/CicdFailureDetail.tsx`

- [ ] **Step 1: Create the component**

```tsx
import React from 'react';
import { FailureAnalysis } from '../../types/cicdPipelines';

interface Props {
  failureCategory: string | undefined;
  failedStage: string | undefined;
  analysis: FailureAnalysis | null;
  isLoading: boolean;
  error: string | null;
  onRetry: () => void;
  onViewFullLog: () => void;
}

const CATEGORY_STYLES: Record<string, string> = {
  TEST_FAILURE:   'bg-yellow-100 text-yellow-800 border-yellow-200',
  BUILD_ERROR:    'bg-red-100 text-red-800 border-red-200',
  DEPLOY_FAILURE: 'bg-orange-100 text-orange-800 border-orange-200',
  INFRA_ERROR:    'bg-purple-100 text-purple-800 border-purple-200',
  UNKNOWN:        'bg-gray-100 text-gray-600 border-gray-200',
};

const CicdFailureDetail: React.FC<Props> = ({
  failureCategory, failedStage, analysis, isLoading, error, onRetry, onViewFullLog,
}) => {
  const catStyle = CATEGORY_STYLES[failureCategory || 'UNKNOWN'] || CATEGORY_STYLES.UNKNOWN;

  return (
    <div className="p-4 space-y-4">
      {/* Instant metadata from sync */}
      <div className="flex items-center gap-3 flex-wrap">
        {failureCategory && (
          <span className={`text-[11px] font-semibold px-2.5 py-1 rounded border ${catStyle}`}>
            {failureCategory.replace('_', ' ')}
          </span>
        )}
        {failedStage && (
          <span className="text-[12px] text-[rgba(0,0,0,0.5)]">
            Failed in: <span className="font-medium text-[rgba(0,0,0,0.75)]">{failedStage}</span>
          </span>
        )}
      </div>

      <hr className="border-[rgba(0,0,0,0.06)]" />

      {/* Error snippets (on-demand) */}
      {isLoading && (
        <div className="space-y-2">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-16 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />
          ))}
        </div>
      )}

      {error && !isLoading && (
        <div className="flex items-center justify-between p-3 bg-red-50 rounded border border-red-100">
          <span className="text-[12px] text-red-700">{error}</span>
          <button onClick={onRetry} className="text-[11px] text-red-700 underline ml-3">Retry</button>
        </div>
      )}

      {analysis && !isLoading && (
        <div className="space-y-3">
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wide">
            {analysis.fallback ? 'Last log lines' : 'Why it failed'}
          </p>
          {analysis.snippets.map((snippet, si) => (
            <div key={si} className="rounded border border-[rgba(0,0,0,0.08)] overflow-hidden">
              <pre className="text-[11px] font-mono leading-relaxed overflow-x-auto">
                {snippet.lines.map((line, li) => (
                  <div
                    key={li}
                    className={`px-3 py-0.5 ${li === snippet.matchLine ? 'bg-red-50 text-red-800' : 'text-[rgba(0,0,0,0.65)]'}`}
                  >
                    {line || ' '}
                  </div>
                ))}
              </pre>
            </div>
          ))}
        </div>
      )}

      <button
        onClick={onViewFullLog}
        className="text-[12px] text-blue-600 underline mt-2"
      >
        View full logs
      </button>
    </div>
  );
};

export default CicdFailureDetail;
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/components/CICDPipelines/CicdFailureDetail.tsx
git commit -m "feat(cicd): add CicdFailureDetail component with error snippet rendering"
```

---

## Task 16: `CicdDiffSummary` and `CicdNotificationSettings` Components

**Files:**
- Create: `frontend-app/src/components/CICDPipelines/CicdDiffSummary.tsx`
- Create: `frontend-app/src/components/CICDPipelines/CicdNotificationSettings.tsx`

- [ ] **Step 1: Create `CicdDiffSummary`**

```tsx
import React, { useState } from 'react';
import { DiffSummary } from '../../types/cicdPipelines';

interface Props {
  diff: DiffSummary | null;
  isLoading: boolean;
  error: string | null;
  onRetry: () => void;
}

const CicdDiffSummary: React.FC<Props> = ({ diff, isLoading, error, onRetry }) => {
  const [filesExpanded, setFilesExpanded] = useState(false);

  if (isLoading) {
    return (
      <div className="p-4 space-y-2">
        {[1, 2, 3].map(i => <div key={i} className="h-8 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />)}
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-4 flex items-center justify-between bg-red-50 rounded border border-red-100">
        <span className="text-[12px] text-red-700">{error}</span>
        <button onClick={onRetry} className="text-[11px] text-red-700 underline ml-3">Retry</button>
      </div>
    );
  }

  if (!diff || diff.noPreviousSuccess) {
    return (
      <div className="p-4 text-[12px] text-[rgba(0,0,0,0.4)]">
        No previous successful run to compare against.
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center gap-2">
        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wide">
          Changes since last success
        </span>
        <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
          {diff.commits.length} commit{diff.commits.length !== 1 ? 's' : ''} · {diff.filesChanged} file{diff.filesChanged !== 1 ? 's' : ''} changed
        </span>
      </div>

      <div className="space-y-2">
        {diff.commits.map((c, i) => (
          <div key={i} className="flex items-start gap-3 text-[12px]">
            <span className="font-mono text-[rgba(0,0,0,0.4)] shrink-0">{c.sha}</span>
            <span className="text-[rgba(0,0,0,0.75)] flex-1 truncate">{c.message}</span>
            <span className="text-[rgba(0,0,0,0.35)] shrink-0">{c.author}</span>
          </div>
        ))}
      </div>

      {diff.changedFiles.length > 0 && (
        <div>
          <button
            onClick={() => setFilesExpanded(e => !e)}
            className="text-[12px] text-blue-600 underline"
          >
            {filesExpanded ? 'Hide' : 'Show'} {diff.changedFiles.length} changed files
          </button>
          {filesExpanded && (
            <ul className="mt-2 space-y-1">
              {diff.changedFiles.map((f, i) => (
                <li key={i} className="text-[11px] font-mono text-[rgba(0,0,0,0.6)]">{f}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
};

export default CicdDiffSummary;
```

- [ ] **Step 2: Create `CicdNotificationSettings`**

```tsx
import React, { useState } from 'react';
import { NotificationConfig } from '../../types/cicdPipelines';

interface Props {
  config: NotificationConfig | null;
  isLoading: boolean;
  isSaving: boolean;
  saveError: string | null;
  onSave: (config: NotificationConfig) => Promise<void>;
  onTestSlack: (url: string) => Promise<boolean>;
}

const CicdNotificationSettings: React.FC<Props> = ({
  config, isLoading, isSaving, saveError, onSave, onTestSlack,
}) => {
  const [emailEnabled, setEmailEnabled] = useState(config?.emailEnabled ?? true);
  const [slackUrl, setSlackUrl] = useState(config?.slackWebhookUrl ?? '');
  const [slackTestResult, setSlackTestResult] = useState<'success' | 'fail' | null>(null);
  const [testing, setTesting] = useState(false);

  React.useEffect(() => {
    if (config) {
      setEmailEnabled(config.emailEnabled);
      setSlackUrl(config.slackWebhookUrl ?? '');
    }
  }, [config]);

  const handleSave = () => {
    onSave({ emailEnabled, slackWebhookUrl: slackUrl || null });
  };

  const handleTestSlack = async () => {
    if (!slackUrl) return;
    setTesting(true);
    setSlackTestResult(null);
    const ok = await onTestSlack(slackUrl);
    setSlackTestResult(ok ? 'success' : 'fail');
    setTesting(false);
  };

  if (isLoading) {
    return <div className="p-4 h-32 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />;
  }

  return (
    <div className="p-4 space-y-5">
      <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)]">Failure Notifications</h3>

      {/* Email toggle */}
      <div className="flex items-center justify-between">
        <div>
          <p className="text-[13px] text-[rgba(0,0,0,0.75)]">Email alerts</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Send an email when a pipeline fails</p>
        </div>
        <button
          onClick={() => setEmailEnabled(e => !e)}
          className={`relative w-10 h-6 rounded-full transition-colors ${emailEnabled ? 'bg-blue-500' : 'bg-gray-200'}`}
        >
          <span className={`absolute top-1 w-4 h-4 bg-white rounded-full shadow transition-transform ${emailEnabled ? 'translate-x-5' : 'translate-x-1'}`} />
        </button>
      </div>

      {/* Slack webhook */}
      <div className="space-y-2">
        <label className="text-[13px] text-[rgba(0,0,0,0.75)]">Slack webhook URL</label>
        <div className="flex gap-2">
          <input
            type="url"
            value={slackUrl}
            onChange={e => { setSlackUrl(e.target.value); setSlackTestResult(null); }}
            placeholder="https://hooks.slack.com/services/..."
            className="flex-1 text-[12px] px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded focus:outline-none focus:border-blue-400"
          />
          <button
            onClick={handleTestSlack}
            disabled={!slackUrl || testing}
            className="text-[12px] px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded hover:bg-gray-50 disabled:opacity-40"
          >
            {testing ? 'Testing…' : 'Test'}
          </button>
        </div>
        {slackTestResult === 'success' && <p className="text-[11px] text-green-600">Slack connected!</p>}
        {slackTestResult === 'fail' && <p className="text-[11px] text-red-600">Could not reach webhook URL</p>}
      </div>

      {saveError && <p className="text-[11px] text-red-600">{saveError}</p>}

      <button
        onClick={handleSave}
        disabled={isSaving}
        className="text-[12px] px-4 py-2 bg-[rgba(0,0,0,0.85)] text-white rounded hover:bg-black disabled:opacity-40"
      >
        {isSaving ? 'Saving…' : 'Save settings'}
      </button>
    </div>
  );
};

export default CicdNotificationSettings;
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no errors

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/components/CICDPipelines/CicdDiffSummary.tsx \
        frontend-app/src/components/CICDPipelines/CicdNotificationSettings.tsx
git commit -m "feat(cicd): add CicdDiffSummary and CicdNotificationSettings components"
```

---

## Task 17: Wire Everything into `CICDPipelinesPage`

**Files:**
- Modify: `frontend-app/src/pages/CICDPipelinesPage.tsx`

- [ ] **Step 1: Add imports**

At the top of `CICDPipelinesPage.tsx`, add:

```tsx
import CicdHealthScorecard from '../components/CICDPipelines/CicdHealthScorecard';
import CicdFailureDetail from '../components/CICDPipelines/CicdFailureDetail';
import CicdDiffSummary from '../components/CICDPipelines/CicdDiffSummary';
import CicdNotificationSettings from '../components/CICDPipelines/CicdNotificationSettings';
import { useCicdFailureAnalysis } from '../hooks/useCicdFailureAnalysis';
import { useCicdNotificationConfig } from '../hooks/useCicdNotificationConfig';
```

- [ ] **Step 2: Add hooks inside `CICDPipelinesPage`**

Inside the component body (alongside the existing `useCicdMetrics` lines):

```tsx
const failureAnalysisHook = useCicdFailureAnalysis();
const notificationConfig = useCicdNotificationConfig();
const [dialogTab, setDialogTab] = useState<'overview' | 'analysis' | 'diff'>('overview');
```

- [ ] **Step 3: Update `TABS` constant to include Health**

Replace the existing `TABS` constant:

```tsx
const TABS = [
  { id: 'github' as const,  label: 'GitHub Actions' },
  { id: 'jenkins' as const, label: 'Jenkins' },
  { id: 'health' as const,  label: 'Pipeline Health' },
];
```

- [ ] **Step 4: Add the Health tab render block**

After the closing `}` of the Jenkins tab block (`{activeTab === 'jenkins' && ...}`), add:

```tsx
{activeTab === 'health' && (
  <div className="space-y-6">
    <CicdHealthScorecard
      githubRuns={githubRuns}
      jenkinsJobs={jenkinsJobs}
      githubDailyStats={githubMetrics.metrics?.dailyStats ?? []}
      jenkinsDailyStats={jenkinsMetrics.metrics?.dailyStats ?? []}
      onCardClick={(provider, _name) => setActiveTab(provider)}
    />
    <div className="border-t border-[rgba(0,0,0,0.06)] pt-4">
      <CicdNotificationSettings
        config={notificationConfig.config}
        isLoading={notificationConfig.isLoading}
        isSaving={notificationConfig.isSaving}
        saveError={notificationConfig.saveError}
        onSave={notificationConfig.saveConfig}
        onTestSlack={notificationConfig.testSlack}
      />
    </div>
  </div>
)}
```

- [ ] **Step 5: Update dialog to reset `dialogTab` on open and add tab strip**

In the `viewWorkflowHistory` call or wherever `selectedWorkflow` is set, add a reset:

```tsx
// Inside closeWorkflow callback, add:
setDialogTab('overview');
failureAnalysisHook.reset();
```

Inside the `DialogPanel`, before the existing stage/history content, add a tab strip:

```tsx
{/* Dialog tab strip */}
<div className="flex gap-0 border-b border-[rgba(0,0,0,0.06)] mb-4">
  {(['overview', 'analysis', 'diff'] as const).map(tab => (
    <button
      key={tab}
      onClick={() => {
        setDialogTab(tab);
        if (tab === 'analysis' && selectedRun && selectedWorkflow) {
          const owner = 'repository' in selectedWorkflow && selectedWorkflow.repository
            ? (selectedWorkflow.repository as any).full_name?.split('/')[0] : '';
          const repo = 'repository' in selectedWorkflow && selectedWorkflow.repository
            ? (selectedWorkflow.repository as any).full_name?.split('/')[1] : '';
          const jobId = 'id' in selectedRun ? (selectedRun as any).id : '';
          if (owner && repo && jobId) {
            failureAnalysisHook.fetchFailureAnalysis(owner, repo, jobId);
          }
        }
        if (tab === 'diff' && selectedRun && selectedWorkflow) {
          const owner = 'repository' in selectedWorkflow && selectedWorkflow.repository
            ? (selectedWorkflow.repository as any).full_name?.split('/')[0] : '';
          const repo = 'repository' in selectedWorkflow && selectedWorkflow.repository
            ? (selectedWorkflow.repository as any).full_name?.split('/')[1] : '';
          const runId = 'id' in selectedRun ? (selectedRun as any).id : '';
          if (owner && repo && runId) {
            failureAnalysisHook.fetchDiffSummary(owner, repo, runId);
          }
        }
      }}
      className={`px-4 py-2 text-[12px] font-medium capitalize border-b-2 transition-colors ${
        dialogTab === tab
          ? 'border-[rgba(0,0,0,0.75)] text-[rgba(0,0,0,0.85)]'
          : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.65)]'
      }`}
    >
      {tab === 'analysis' ? 'Failure Analysis' : tab === 'diff' ? 'Diff' : 'Overview'}
    </button>
  ))}
</div>
```

Wrap the existing dialog body content so it only renders when `dialogTab === 'overview'`, and add the other two tab bodies:

```tsx
{dialogTab === 'overview' && (
  <> {/* existing stage visualization + run history JSX — no changes needed inside */} </>
)}

{dialogTab === 'analysis' && (
  <CicdFailureDetail
    failureCategory={(selectedWorkflow as any)?.failureCategory}
    failedStage={(selectedWorkflow as any)?.failedStage}
    analysis={failureAnalysisHook.failureAnalysis}
    isLoading={failureAnalysisHook.isLoadingAnalysis}
    error={failureAnalysisHook.analysisError}
    onRetry={() => {
      const owner = 'repository' in selectedWorkflow! && (selectedWorkflow as any).repository?.full_name?.split('/')[0];
      const repo = 'repository' in selectedWorkflow! && (selectedWorkflow as any).repository?.full_name?.split('/')[1];
      const jobId = selectedRun && 'id' in selectedRun ? (selectedRun as any).id : '';
      if (owner && repo && jobId) failureAnalysisHook.fetchFailureAnalysis(owner, repo, jobId);
    }}
    onViewFullLog={() => setDialogTab('overview')}
  />
)}

{dialogTab === 'diff' && (
  <CicdDiffSummary
    diff={failureAnalysisHook.diffSummary}
    isLoading={failureAnalysisHook.isLoadingDiff}
    error={failureAnalysisHook.diffError}
    onRetry={() => {
      const owner = 'repository' in selectedWorkflow! && (selectedWorkflow as any).repository?.full_name?.split('/')[0];
      const repo = 'repository' in selectedWorkflow! && (selectedWorkflow as any).repository?.full_name?.split('/')[1];
      const runId = selectedRun && 'id' in selectedRun ? (selectedRun as any).id : '';
      if (owner && repo && runId) failureAnalysisHook.fetchDiffSummary(owner, repo, runId);
    }}
  />
)}
```

- [ ] **Step 6: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: no new errors

- [ ] **Step 7: Run all frontend tests**

```bash
cd frontend-app && npm test -- --watchAll=false 2>&1 | tail -10
```
Expected: `Tests: X passed`

- [ ] **Step 8: Commit**

```bash
git add frontend-app/src/pages/CICDPipelinesPage.tsx
git commit -m "feat(cicd): wire Health tab, Failure Analysis dialog tabs, and Notification Settings into CICDPipelinesPage"
```

---

## Self-Review Checklist (completed inline)

1. **Spec coverage:**
   - ✅ Flyway migration — Task 1
   - ✅ `failureCategory` + `notificationSent` on `CicdRunRecord` — Task 2
   - ✅ `cicd_notification_config` table + entity + repo — Tasks 1, 2
   - ✅ `FailureCategoryResolver` with rule-based logic — Task 3
   - ✅ Sync scheduler enrichment + notification trigger — Task 4
   - ✅ Email + Slack notification — Task 5
   - ✅ Enrich run DTOs with DB failure data — Task 6
   - ✅ GET/POST notification-config endpoints — Task 7
   - ✅ Log parsing with regex patterns, top 3 snippets + fallback — Task 8
   - ✅ GitHub compare API diff summary — Task 9
   - ✅ failure-analysis + diff-summary endpoints — Task 10
   - ✅ Frontend types — Task 11
   - ✅ `useCicdFailureAnalysis` hook — Task 12
   - ✅ `useCicdNotificationConfig` hook — Task 13
   - ✅ `CicdHealthScorecard` — Task 14
   - ✅ `CicdFailureDetail` — Task 15
   - ✅ `CicdDiffSummary` + `CicdNotificationSettings` — Task 16
   - ✅ `CICDPipelinesPage` wired — Task 17

2. **No placeholders:** All code blocks are complete.

3. **Type consistency:**
   - `FailureAnalysis`, `DiffSummary`, `NotificationConfig` defined in Task 11, used in Tasks 12–17 ✅
   - `FailureAnalysisDto.Snippet.matchLine` defined Task 8, rendered in Task 15 ✅
   - `DiffSummaryDto.CommitInfo` fields match `CommitInfo` TS interface ✅
