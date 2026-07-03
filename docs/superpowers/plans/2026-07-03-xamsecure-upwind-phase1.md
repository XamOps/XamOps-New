# XamSecure Phase 1 (Upwind Integration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** White-label Upwind CNAPP data inside XamOps: a new XamSecure section that syncs threat detections, vulnerability findings, and posture/compliance findings from Upwind's API into XamOps tables and serves them to customers filtered by their own cloud account IDs.

**Architecture:** A Spring `UpwindApiClient` (OAuth2 client-credentials, Link-header pagination) feeds `UpwindSyncService`, driven by a scheduler (60s detections poll, 4h full sync). Findings land in three Postgres tables keyed by `cloud_account_id`. `XamSecureController` serves each customer their slice (resolved from `CloudAccount` by `clientId`). React page `XamSecurePage` with tabs: Overview, Threats (60s polling), Vulnerabilities, Posture, Compliance, Onboarding.

**Tech Stack:** Spring Boot (javax.persistence, Lombok, JUnit 5 + Mockito, Flyway, RestTemplate, Jackson), React + TypeScript + Vite + Tailwind, `apiClient` fetch wrapper.

**Spec:** `docs/superpowers/specs/2026-07-02-xamsecure-upwind-design.md` (contains live-verified API schemas — read it first).

## Global Constraints

- No Maven wrapper: use `mvn -f xamops-service/pom.xml …` from repo root.
- UI theme: black/white/grey only; no emojis; severity chips in greys; enterprise-restrained spacing.
- All frontend authenticated calls via `apiClient` from `frontend-app/src/utils/apiClient`.
- Upwind credentials come ONLY from env (`UPWIND_CLIENT_ID`, `UPWIND_CLIENT_SECRET`); local values are in gitignored `.env.local`. Never hardcode or commit them. Never expose to frontend.
- Sync is off by default (`upwind.sync.enabled=false`); enabling is a deploy-time decision.
- Verified Upwind facts (do not re-derive): token URL `https://oauth.upwind.io/oauth/token` (form-encoded, audience `https://api.upwind.io`, TTL 24h, JWT has `org_id` claim); base `https://api.upwind.io/v1/organizations/{orgId}`; endpoints `/threat-detections`, `/vulnerability-findings`, `/configuration-findings`; pagination via `Link` header `rel="next"`; every finding has `resource.cloud_account_id`.
- Backend package root: `com.xammer.cloud`.
- Commit after every task; before any push: `git pull --rebase origin main`.

---

### Task 1: Flyway migration

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V20260703_1__xamsecure_upwind.sql`

**Interfaces:**
- Produces: tables `upwind_threat_detection`, `upwind_vulnerability_finding`, `upwind_posture_finding`, `upwind_account_connection`, `upwind_sync_state` consumed by Task 2 entities.

- [ ] **Step 1: Write the migration**

```sql
-- XamSecure: Upwind CNAPP integration (Phase 1)

CREATE TABLE upwind_threat_detection (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    category VARCHAR(64),
    severity VARCHAR(16),
    status VARCHAR(32),
    title TEXT,
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    resource_id VARCHAR(256),
    resource_name VARCHAR(256),
    resource_type VARCHAR(128),
    mitre_tactics TEXT,
    occurrence_count INT,
    first_seen_time TIMESTAMPTZ,
    last_seen_time TIMESTAMPTZ,
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_utd_account ON upwind_threat_detection (cloud_account_id);
CREATE INDEX idx_utd_last_seen ON upwind_threat_detection (last_seen_time DESC);

CREATE TABLE upwind_vulnerability_finding (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    cve_id VARCHAR(64),
    vulnerability_name TEXT,
    severity VARCHAR(16),
    cvss_score VARCHAR(16),
    epss_score VARCHAR(16),
    exploitable BOOLEAN,
    status VARCHAR(32) DEFAULT 'OPEN',
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    region VARCHAR(64),
    resource_id VARCHAR(256),
    resource_name VARCHAR(256),
    resource_type VARCHAR(128),
    first_seen_time TIMESTAMPTZ,
    last_scan_time TIMESTAMPTZ,
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_uvf_account ON upwind_vulnerability_finding (cloud_account_id);
CREATE INDEX idx_uvf_severity ON upwind_vulnerability_finding (severity);

CREATE TABLE upwind_posture_finding (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    severity VARCHAR(16),
    status VARCHAR(32),
    title TEXT,
    framework_title VARCHAR(256),
    check_title TEXT,
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    region VARCHAR(64),
    resource_id VARCHAR(512),
    resource_name VARCHAR(256),
    resource_type VARCHAR(128),
    first_seen_time TIMESTAMPTZ,
    last_seen_time TIMESTAMPTZ,
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_upf_account ON upwind_posture_finding (cloud_account_id);
CREATE INDEX idx_upf_framework ON upwind_posture_finding (framework_title);

CREATE TABLE upwind_account_connection (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONNECTED',
    sensor_install_command TEXT,
    notes TEXT,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE upwind_sync_state (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(32) NOT NULL UNIQUE,
    last_success_at TIMESTAMPTZ,
    last_error TEXT,
    last_error_at TIMESTAMPTZ
);
```

- [ ] **Step 2: Verify the backend still builds (Flyway validates on boot; compile catches syntax-level issues only)**

Run: `mvn -f xamops-service/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/V20260703_1__xamsecure_upwind.sql
git commit -m "feat(xamsecure): add Upwind finding/connection/sync-state tables"
```

---

### Task 2: Entities and repositories

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/UpwindThreatDetection.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/UpwindVulnerabilityFinding.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/UpwindPostureFinding.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/UpwindAccountConnection.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/UpwindSyncState.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindThreatDetectionRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindVulnerabilityFindingRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindPostureFindingRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindAccountConnectionRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindSyncStateRepository.java`

**Interfaces:**
- Consumes: Task 1 tables.
- Produces (used by Tasks 3–6): entity getters/setters via Lombok `@Data`; repository methods exactly as declared below (Tasks 4–6 call `findByUpwindId`, `findByCloudAccountIdIn`, `countBySeverityForAccounts`, `markResolvedNotSyncedSince`, `findByDomain`, `findByCloudAccountId`).

- [ ] **Step 1: Write the three finding entities**

`UpwindThreatDetection.java`:
```java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_threat_detection")
public class UpwindThreatDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    private String category;
    private String severity;
    private String status;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_type")
    private String resourceType;

    /** Comma-separated MITRE tactic names, e.g. "Initial Access,Persistence" */
    @Column(name = "mitre_tactics", columnDefinition = "TEXT")
    private String mitreTactics;

    @Column(name = "occurrence_count")
    private Integer occurrenceCount;

    @Column(name = "first_seen_time")
    private Instant firstSeenTime;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
```

`UpwindVulnerabilityFinding.java`:
```java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_vulnerability_finding")
public class UpwindVulnerabilityFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    @Column(name = "cve_id")
    private String cveId;

    @Column(name = "vulnerability_name", columnDefinition = "TEXT")
    private String vulnerabilityName;

    private String severity;

    @Column(name = "cvss_score")
    private String cvssScore;

    @Column(name = "epss_score")
    private String epssScore;

    private Boolean exploitable;

    private String status = "OPEN";

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    private String region;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "first_seen_time")
    private Instant firstSeenTime;

    @Column(name = "last_scan_time")
    private Instant lastScanTime;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
```

`UpwindPostureFinding.java`:
```java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_posture_finding")
public class UpwindPostureFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    private String severity;

    /** FAIL / PASS / RESOLVED */
    private String status;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "framework_title")
    private String frameworkTitle;

    @Column(name = "check_title", columnDefinition = "TEXT")
    private String checkTitle;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    private String region;

    @Column(name = "resource_id", length = 512)
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "first_seen_time")
    private Instant firstSeenTime;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
```

- [ ] **Step 2: Write the connection and sync-state entities**

`UpwindAccountConnection.java`:
```java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_account_connection")
public class UpwindAccountConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Provider account id: AWS account id / GCP project id / Azure subscription id */
    @Column(name = "cloud_account_id", nullable = false, unique = true)
    private String cloudAccountId;

    /** NOT_CONNECTED / PENDING / CONNECTED */
    @Column(nullable = false)
    private String status = "NOT_CONNECTED";

    /** Helm/Bash sensor install command registered by a Xammer admin. */
    @Column(name = "sensor_install_command", columnDefinition = "TEXT")
    private String sensorInstallCommand;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

`UpwindSyncState.java`:
```java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_sync_state")
public class UpwindSyncState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DETECTIONS / VULNERABILITIES / POSTURE */
    @Column(nullable = false, unique = true)
    private String domain;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;
}
```

- [ ] **Step 3: Write the repositories**

`UpwindThreatDetectionRepository.java`:
```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindThreatDetection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UpwindThreatDetectionRepository extends JpaRepository<UpwindThreatDetection, Long> {

    Optional<UpwindThreatDetection> findByUpwindId(String upwindId);

    Page<UpwindThreatDetection> findByCloudAccountIdInOrderByLastSeenTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindThreatDetection> findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    @Query("SELECT d.severity, COUNT(d) FROM UpwindThreatDetection d " +
           "WHERE d.cloudAccountId IN :accountIds AND d.status = 'OPEN' GROUP BY d.severity")
    List<Object[]> countOpenBySeverity(@Param("accountIds") List<String> accountIds);
}
```

`UpwindVulnerabilityFindingRepository.java`:
```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindVulnerabilityFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindVulnerabilityFindingRepository extends JpaRepository<UpwindVulnerabilityFinding, Long> {

    Optional<UpwindVulnerabilityFinding> findByUpwindId(String upwindId);

    Page<UpwindVulnerabilityFinding> findByCloudAccountIdInOrderByLastScanTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindVulnerabilityFinding> findByCloudAccountIdInAndSeverityOrderByLastScanTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    @Query("SELECT v.severity, COUNT(v) FROM UpwindVulnerabilityFinding v " +
           "WHERE v.cloudAccountId IN :accountIds AND v.status = 'OPEN' GROUP BY v.severity")
    List<Object[]> countOpenBySeverity(@Param("accountIds") List<String> accountIds);

    @Modifying
    @Query("UPDATE UpwindVulnerabilityFinding v SET v.status = 'RESOLVED' " +
           "WHERE v.syncedAt < :runStart AND v.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);
}
```

`UpwindPostureFindingRepository.java`:
```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindPostureFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindPostureFindingRepository extends JpaRepository<UpwindPostureFinding, Long> {

    Optional<UpwindPostureFinding> findByUpwindId(String upwindId);

    Page<UpwindPostureFinding> findByCloudAccountIdInOrderByLastSeenTimeDesc(
            List<String> accountIds, Pageable pageable);

    Page<UpwindPostureFinding> findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(
            List<String> accountIds, String severity, Pageable pageable);

    @Query("SELECT p.severity, COUNT(p) FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.status = 'FAIL' GROUP BY p.severity")
    List<Object[]> countFailBySeverity(@Param("accountIds") List<String> accountIds);

    @Query("SELECT p.frameworkTitle, p.status, COUNT(p) FROM UpwindPostureFinding p " +
           "WHERE p.cloudAccountId IN :accountIds AND p.frameworkTitle IS NOT NULL " +
           "GROUP BY p.frameworkTitle, p.status")
    List<Object[]> countByFrameworkAndStatus(@Param("accountIds") List<String> accountIds);

    @Modifying
    @Query("UPDATE UpwindPostureFinding p SET p.status = 'RESOLVED' " +
           "WHERE p.syncedAt < :runStart AND p.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);
}
```

`UpwindAccountConnectionRepository.java`:
```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindAccountConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UpwindAccountConnectionRepository extends JpaRepository<UpwindAccountConnection, Long> {

    Optional<UpwindAccountConnection> findByCloudAccountId(String cloudAccountId);

    List<UpwindAccountConnection> findByCloudAccountIdIn(List<String> cloudAccountIds);
}
```

`UpwindSyncStateRepository.java`:
```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UpwindSyncStateRepository extends JpaRepository<UpwindSyncState, Long> {

    Optional<UpwindSyncState> findByDomain(String domain);
}
```

- [ ] **Step 4: Compile**

Run: `mvn -f xamops-service/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/domain/Upwind*.java xamops-service/src/main/java/com/xammer/cloud/repository/Upwind*.java
git commit -m "feat(xamsecure): add Upwind entities and repositories"
```

---

### Task 3: UpwindApiClient (OAuth2 + paginated fetch)

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindApiClient.java`
- Modify: `xamops-service/src/main/resources/application.yml` (append `upwind:` block; if the main config is `application.properties`, append the equivalent flat keys instead)
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/UpwindApiClientTest.java`

**Interfaces:**
- Produces (consumed by Task 4):
  - `boolean isConfigured()` — true when client-id + secret are set
  - `List<JsonNode> fetchAll(String path, int maxPages)` — follows `Link: rel="next"`, concatenates JSON array pages
  - `List<JsonNode> fetchThreatDetections(int maxPages)`
  - `List<JsonNode> fetchVulnerabilityFindings(int maxPages)`
  - `List<JsonNode> fetchConfigurationFindings(int maxPages)`

- [ ] **Step 1: Add configuration block to `xamops-service/src/main/resources/application.yml`**

```yaml
upwind:
  client-id: ${UPWIND_CLIENT_ID:}
  client-secret: ${UPWIND_CLIENT_SECRET:}
  org-id: ${UPWIND_ORG_ID:org_Zwq0e1xF9Au4AF0Q}
  token-url: ${UPWIND_TOKEN_URL:https://oauth.upwind.io/oauth/token}
  api-base-url: ${UPWIND_API_BASE_URL:https://api.upwind.io}
  audience: https://api.upwind.io
  sync:
    enabled: ${UPWIND_SYNC_ENABLED:false}
```

- [ ] **Step 2: Write the failing test**

`UpwindApiClientTest.java`:
```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpwindApiClientTest {

    private RestTemplate restTemplate;
    private UpwindApiClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new UpwindApiClient(new ObjectMapper(), restTemplate);
        client.setClientId("test-id");
        client.setClientSecret("test-secret");
        client.setOrgId("org_test");
        client.setTokenUrl("https://oauth.example/token");
        client.setApiBaseUrl("https://api.example");
        client.setAudience("https://api.example");
    }

    private void mockToken() {
        ResponseEntity<String> tokenResp = ResponseEntity.ok(
                "{\"access_token\":\"tok-123\",\"expires_in\":86400}");
        when(restTemplate.exchange(eq("https://oauth.example/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(tokenResp);
    }

    @Test
    void isConfiguredFalseWhenNoCredentials() {
        client.setClientId("");
        assertFalse(client.isConfigured());
    }

    @Test
    void fetchAllFollowsLinkHeaderPagination() {
        mockToken();
        HttpHeaders page1Headers = new HttpHeaders();
        page1Headers.add("Link",
                "<https://api.example/v1/organizations/org_test/threat-detections?page-token=abc>; rel=\"next\"");
        ResponseEntity<String> page1 = new ResponseEntity<>(
                "[{\"id\":\"a\"}]", page1Headers, org.springframework.http.HttpStatus.PARTIAL_CONTENT);
        ResponseEntity<String> page2 = ResponseEntity.ok("[{\"id\":\"b\"}]");

        when(restTemplate.exchange(
                eq("https://api.example/v1/organizations/org_test/threat-detections"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page1);
        when(restTemplate.exchange(
                eq("https://api.example/v1/organizations/org_test/threat-detections?page-token=abc"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(page2);

        List<JsonNode> all = client.fetchThreatDetections(10);
        assertEquals(2, all.size());
        assertEquals("a", all.get(0).get("id").asText());
        assertEquals("b", all.get(1).get("id").asText());
    }

    @Test
    void fetchAllStopsAtMaxPages() {
        mockToken();
        HttpHeaders loopHeaders = new HttpHeaders();
        loopHeaders.add("Link",
                "<https://api.example/v1/organizations/org_test/threat-detections?page-token=again>; rel=\"next\"");
        ResponseEntity<String> looping = new ResponseEntity<>(
                "[{\"id\":\"x\"}]", loopHeaders, org.springframework.http.HttpStatus.PARTIAL_CONTENT);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(looping);

        List<JsonNode> all = client.fetchThreatDetections(3);
        assertEquals(3, all.size());
    }

    @Test
    void tokenIsReusedAcrossCalls() {
        mockToken();
        ResponseEntity<String> page = ResponseEntity.ok("[]");
        when(restTemplate.exchange(contains("/v1/organizations/"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class))).thenReturn(page);

        client.fetchThreatDetections(1);
        client.fetchConfigurationFindings(1);

        verify(restTemplate, times(1)).exchange(eq("https://oauth.example/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindApiClientTest`
Expected: COMPILATION ERROR (UpwindApiClient does not exist)

- [ ] **Step 4: Write the implementation**

`UpwindApiClient.java`:
```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Upwind CNAPP API (verified live 2026-07-03, see
 * docs/superpowers/specs/2026-07-02-xamsecure-upwind-design.md).
 *
 * Auth: OAuth2 client credentials against oauth.upwind.io; token TTL 24h,
 * cached in memory and refreshed 60s before expiry.
 * Pagination: RFC-5988 Link headers (rel="next").
 */
@Component
@Slf4j
public class UpwindApiClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Setter @Value("${upwind.client-id:}")
    private String clientId;

    @Setter @Value("${upwind.client-secret:}")
    private String clientSecret;

    @Setter @Value("${upwind.org-id:}")
    private String orgId;

    @Setter @Value("${upwind.token-url:https://oauth.upwind.io/oauth/token}")
    private String tokenUrl;

    @Setter @Value("${upwind.api-base-url:https://api.upwind.io}")
    private String apiBaseUrl;

    @Setter @Value("${upwind.audience:https://api.upwind.io}")
    private String audience;

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public UpwindApiClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty();
    }

    public List<JsonNode> fetchThreatDetections(int maxPages) {
        return fetchAll("/threat-detections", maxPages);
    }

    public List<JsonNode> fetchVulnerabilityFindings(int maxPages) {
        return fetchAll("/vulnerability-findings", maxPages);
    }

    public List<JsonNode> fetchConfigurationFindings(int maxPages) {
        return fetchAll("/configuration-findings", maxPages);
    }

    /**
     * GETs {apiBaseUrl}/v1/organizations/{orgId}{path}, following Link rel="next"
     * up to maxPages pages. Each page is a JSON array; items are concatenated.
     */
    public List<JsonNode> fetchAll(String path, int maxPages) {
        List<JsonNode> items = new ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId + path;
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getToken());
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) {
                    body.forEach(items::add);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Upwind response was not valid JSON for " + path, e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        return items;
    }

    private String nextLink(HttpHeaders headers) {
        List<String> links = headers.get("Link");
        if (links == null) return null;
        for (String header : links) {
            for (String part : header.split(",")) {
                if (part.contains("rel=\"next\"")) {
                    int start = part.indexOf('<');
                    int end = part.indexOf('>');
                    if (start >= 0 && end > start) {
                        return part.substring(start + 1, end);
                    }
                }
            }
        }
        return null;
    }

    private synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("audience", audience);
        ResponseEntity<String> resp = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        try {
            JsonNode body = objectMapper.readTree(resp.getBody());
            cachedToken = body.get("access_token").asText();
            long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600;
            tokenExpiry = Instant.now().plusSeconds(expiresIn);
            log.info("[Upwind] Obtained API token, expires in {}s", expiresIn);
            return cachedToken;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Upwind token response", e);
        }
    }
}
```

Note: this constructor requires a `RestTemplate` bean. Check whether one exists (`grep -rn "public RestTemplate" xamops-service/src/main/java/`). If none exists, add to any existing `@Configuration` class (or create `com.xammer.cloud.config.RestTemplateConfig`):

```java
package com.xammer.cloud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```
If a `RestTemplate` bean already exists, skip this and use it. If a bean conflict arises (`ConflictingBeanDefinitionException` on boot or context tests), name the bean explicitly and inject with `@Qualifier`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindApiClientTest`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/ xamops-service/src/test/java/com/xammer/cloud/service/upwind/ xamops-service/src/main/resources/application.yml
git commit -m "feat(xamsecure): add UpwindApiClient with OAuth2 token cache and Link pagination"
```

---

### Task 4: UpwindSyncService + scheduler

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncService.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncScheduler.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/UpwindSyncServiceTest.java`

**Interfaces:**
- Consumes: Task 3 `UpwindApiClient.fetchThreatDetections/fetchVulnerabilityFindings/fetchConfigurationFindings(int maxPages)`, Task 2 repositories.
- Produces (consumed by Task 5): `upwind_sync_state` rows with domains `DETECTIONS`, `VULNERABILITIES`, `POSTURE`; methods `syncDetections()`, `syncAll()`.

- [ ] **Step 1: Write the failing tests**

`UpwindSyncServiceTest.java`:
```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.UpwindThreatDetection;
import com.xammer.cloud.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class UpwindSyncServiceTest {

    private UpwindApiClient apiClient;
    private UpwindThreatDetectionRepository detectionRepo;
    private UpwindVulnerabilityFindingRepository vulnRepo;
    private UpwindPostureFindingRepository postureRepo;
    private UpwindSyncStateRepository syncStateRepo;
    private UpwindSyncService service;

    private static final String DETECTION_JSON =
        "{\"id\":\"uwnd-1\",\"category\":\"CLOUD_TRAIL\",\"severity\":\"MEDIUM\",\"status\":\"OPEN\"," +
        "\"title\":\"Console login without MFA\"," +
        "\"resource\":{\"id\":\"ep-1\",\"name\":\"kartik\",\"type\":\"AWS_IAM_USER\"," +
        "\"cloud_provider\":\"AWS\",\"cloud_account_id\":\"605134457560\"}," +
        "\"first_seen_time\":\"2026-07-02T08:41:06Z\",\"last_seen_time\":\"2026-07-02T18:20:06Z\"," +
        "\"occurrence_count\":2," +
        "\"mitre_attacks\":[{\"tactic_id\":\"TA0001\",\"tactic_name\":\"Initial Access\"," +
        "\"technique_id\":\"T1078\",\"technique_name\":\"Valid Accounts\"}]}";

    @BeforeEach
    void setUp() {
        apiClient = mock(UpwindApiClient.class);
        detectionRepo = mock(UpwindThreatDetectionRepository.class);
        vulnRepo = mock(UpwindVulnerabilityFindingRepository.class);
        postureRepo = mock(UpwindPostureFindingRepository.class);
        syncStateRepo = mock(UpwindSyncStateRepository.class);
        when(syncStateRepo.findByDomain(any())).thenReturn(Optional.empty());
        service = new UpwindSyncService(apiClient, detectionRepo, vulnRepo, postureRepo, syncStateRepo);
    }

    private JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void syncDetectionsInsertsNewDetection() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(List.of(parse(DETECTION_JSON)));
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.empty());

        service.syncDetections();

        ArgumentCaptor<UpwindThreatDetection> captor = ArgumentCaptor.forClass(UpwindThreatDetection.class);
        verify(detectionRepo).save(captor.capture());
        UpwindThreatDetection saved = captor.getValue();
        assertEquals("uwnd-1", saved.getUpwindId());
        assertEquals("MEDIUM", saved.getSeverity());
        assertEquals("605134457560", saved.getCloudAccountId());
        assertEquals("Initial Access", saved.getMitreTactics());
        assertEquals(2, saved.getOccurrenceCount());
        assertNotNull(saved.getSyncedAt());
    }

    @Test
    void syncDetectionsUpdatesExistingByUpwindId() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(List.of(parse(DETECTION_JSON)));
        UpwindThreatDetection existing = new UpwindThreatDetection();
        existing.setId(7L);
        existing.setUpwindId("uwnd-1");
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.of(existing));

        service.syncDetections();

        ArgumentCaptor<UpwindThreatDetection> captor = ArgumentCaptor.forClass(UpwindThreatDetection.class);
        verify(detectionRepo).save(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertEquals(2, captor.getValue().getOccurrenceCount());
    }

    @Test
    void syncSkipsWhenNotConfigured() {
        when(apiClient.isConfigured()).thenReturn(false);
        service.syncDetections();
        service.syncAll();
        verifyNoInteractions(detectionRepo, vulnRepo, postureRepo);
    }

    @Test
    void syncRecordsErrorInSyncState() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenThrow(new IllegalStateException("boom"));

        service.syncDetections();

        verify(syncStateRepo, atLeastOnce()).save(argThat(s ->
                "DETECTIONS".equals(s.getDomain()) && "boom".equals(s.getLastError())));
    }

    @Test
    void syncAllMarksStaleVulnsResolved() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchThreatDetections(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchVulnerabilityFindings(anyInt())).thenReturn(Collections.emptyList());
        when(apiClient.fetchConfigurationFindings(anyInt())).thenReturn(Collections.emptyList());

        service.syncAll();

        verify(vulnRepo).markResolvedNotSyncedSince(any());
        verify(postureRepo).markResolvedNotSyncedSince(any());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindSyncServiceTest`
Expected: COMPILATION ERROR (UpwindSyncService does not exist)

- [ ] **Step 3: Write the implementation**

`UpwindSyncService.java`:
```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Pulls Upwind findings into XamOps tables.
 *
 * Detections: polled frequently (no server-side time filter confirmed yet, so
 * we fetch recent pages and upsert by upwind_id — volumes are small).
 * Vulnerabilities/posture: full sync; anything not seen in the latest full
 * sync is marked RESOLVED.
 */
@Service
@Slf4j
public class UpwindSyncService {

    static final int DETECTION_POLL_MAX_PAGES = 5;    // ~500 newest detections per poll
    static final int FULL_SYNC_MAX_PAGES = 200;       // ~20k items cap per domain

    private final UpwindApiClient apiClient;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;

    public UpwindSyncService(UpwindApiClient apiClient,
                             UpwindThreatDetectionRepository detectionRepo,
                             UpwindVulnerabilityFindingRepository vulnRepo,
                             UpwindPostureFindingRepository postureRepo,
                             UpwindSyncStateRepository syncStateRepo) {
        this.apiClient = apiClient;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
    }

    @Transactional
    public void syncDetections() {
        if (!apiClient.isConfigured()) return;
        try {
            List<JsonNode> items = apiClient.fetchThreatDetections(DETECTION_POLL_MAX_PAGES);
            Instant now = Instant.now();
            for (JsonNode item : items) {
                upsertDetection(item, now);
            }
            recordSuccess("DETECTIONS");
        } catch (Exception e) {
            log.error("[Upwind] Detection sync failed: {}", e.getMessage());
            recordError("DETECTIONS", e);
        }
    }

    @Transactional
    public void syncAll() {
        if (!apiClient.isConfigured()) return;
        syncDetections();
        Instant runStart = Instant.now();
        try {
            for (JsonNode item : apiClient.fetchVulnerabilityFindings(FULL_SYNC_MAX_PAGES)) {
                upsertVulnerability(item, Instant.now());
            }
            vulnRepo.markResolvedNotSyncedSince(runStart);
            recordSuccess("VULNERABILITIES");
        } catch (Exception e) {
            log.error("[Upwind] Vulnerability sync failed: {}", e.getMessage());
            recordError("VULNERABILITIES", e);
        }
        runStart = Instant.now();
        try {
            for (JsonNode item : apiClient.fetchConfigurationFindings(FULL_SYNC_MAX_PAGES)) {
                upsertPosture(item, Instant.now());
            }
            postureRepo.markResolvedNotSyncedSince(runStart);
            recordSuccess("POSTURE");
        } catch (Exception e) {
            log.error("[Upwind] Posture sync failed: {}", e.getMessage());
            recordError("POSTURE", e);
        }
    }

    private void upsertDetection(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindThreatDetection d = detectionRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindThreatDetection::new);
        d.setUpwindId(upwindId);
        d.setCategory(text(item, "category"));
        d.setSeverity(text(item, "severity"));
        d.setStatus(text(item, "status"));
        d.setTitle(text(item, "title"));
        JsonNode res = item.path("resource");
        d.setCloudProvider(text(res, "cloud_provider"));
        d.setCloudAccountId(text(res, "cloud_account_id"));
        d.setResourceId(text(res, "id"));
        d.setResourceName(text(res, "name"));
        d.setResourceType(text(res, "type"));
        StringJoiner tactics = new StringJoiner(",");
        for (JsonNode attack : item.path("mitre_attacks")) {
            if (attack.hasNonNull("tactic_name")) tactics.add(attack.get("tactic_name").asText());
        }
        d.setMitreTactics(tactics.length() > 0 ? tactics.toString() : null);
        d.setOccurrenceCount(item.hasNonNull("occurrence_count") ? item.get("occurrence_count").asInt() : null);
        d.setFirstSeenTime(instant(item, "first_seen_time"));
        d.setLastSeenTime(instant(item, "last_seen_time"));
        d.setRawPayload(item.toString());
        d.setSyncedAt(now);
        detectionRepo.save(d);
    }

    private void upsertVulnerability(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindVulnerabilityFinding v = vulnRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindVulnerabilityFinding::new);
        v.setUpwindId(upwindId);
        JsonNode vuln = item.path("vulnerability");
        v.setCveId(text(vuln, "nvd_cve_id"));
        v.setVulnerabilityName(text(vuln, "name"));
        v.setSeverity(text(vuln, "nvd_cvss_v3_severity"));
        v.setCvssScore(text(vuln, "nvd_cvss_v3_score"));
        v.setEpssScore(text(vuln, "epss_score"));
        v.setExploitable(vuln.hasNonNull("exploitable") ? vuln.get("exploitable").asBoolean() : null);
        v.setStatus("OPEN");
        JsonNode res = item.path("resource");
        v.setCloudProvider(text(res, "cloud_provider"));
        v.setCloudAccountId(text(res, "cloud_account_id"));
        v.setRegion(text(res, "region"));
        v.setResourceId(text(res, "id"));
        v.setResourceName(text(res, "name"));
        v.setResourceType(text(res, "type"));
        v.setFirstSeenTime(instant(item, "first_seen_time"));
        v.setLastScanTime(instant(item, "last_scan_time"));
        v.setRawPayload(item.toString());
        v.setSyncedAt(now);
        vulnRepo.save(v);
    }

    private void upsertPosture(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindPostureFinding p = postureRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindPostureFinding::new);
        p.setUpwindId(upwindId);
        p.setSeverity(text(item, "severity"));
        p.setStatus(text(item, "status"));
        p.setTitle(text(item, "title"));
        p.setFrameworkTitle(text(item.path("framework"), "title"));
        p.setCheckTitle(text(item.path("check"), "title"));
        JsonNode res = item.path("resource");
        p.setCloudProvider(text(res, "cloud_provider"));
        p.setCloudAccountId(text(res, "cloud_account_id"));
        p.setRegion(text(res, "region"));
        p.setResourceId(text(res, "id"));
        p.setResourceName(text(res, "name"));
        p.setResourceType(text(res, "type"));
        p.setFirstSeenTime(instant(item, "first_seen_time"));
        p.setLastSeenTime(instant(item, "last_seen_time"));
        p.setRawPayload(item.toString());
        p.setSyncedAt(now);
        postureRepo.save(p);
    }

    private void recordSuccess(String domain) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastSuccessAt(Instant.now());
        s.setLastError(null);
        s.setLastErrorAt(null);
        syncStateRepo.save(s);
    }

    private void recordError(String domain, Exception e) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastError(e.getMessage());
        s.setLastErrorAt(Instant.now());
        syncStateRepo.save(s);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
}
```

`UpwindSyncScheduler.java`:
```java
package com.xammer.cloud.service.upwind;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runtime hybrid cadence (see spec): detections every 60s, full sync every 4h.
 * Enabled only when upwind.sync.enabled=true (default false).
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "upwind.sync.enabled", havingValue = "true")
public class UpwindSyncScheduler {

    private final UpwindSyncService syncService;

    @Scheduled(fixedDelayString = "60000", initialDelayString = "15000")
    public void pollDetections() {
        syncService.syncDetections();
    }

    @Scheduled(fixedDelayString = "14400000", initialDelayString = "60000")
    public void fullSync() {
        log.info("[Upwind] Starting full sync (vulnerabilities + posture)");
        syncService.syncAll();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindSyncServiceTest`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/ xamops-service/src/test/java/com/xammer/cloud/service/upwind/
git commit -m "feat(xamsecure): add Upwind sync service and scheduler (60s detections, 4h full)"
```

---

### Task 5: XamSecureService + XamSecureController (tenant-filtered read API)

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java`

**Interfaces:**
- Consumes: Task 2 repositories, `CloudAccountRepository.findByClientId(Long)`, `CloudAccount.getProviderAccountId()`, `ClientUserDetails.getClientId()`, `RedisCacheService.get(String, TypeReference)/put(String, Object, int)`.
- Produces (consumed by Task 7 frontend): REST endpoints under `/api/xamsecure`:
  - `GET /summary?accountId=` → `{threatsBySeverity: {…}, vulnerabilitiesBySeverity: {…}, postureBySeverity: {…}, syncState: [{domain,lastSuccessAt,lastError}]}`
  - `GET /threats?accountId=&severity=&page=&size=` → Spring `Page` JSON of detections
  - `GET /vulnerabilities?accountId=&severity=&page=&size=` → `Page` of vulns
  - `GET /posture?accountId=&severity=&page=&size=` → `Page` of posture findings
  - `GET /compliance?accountId=` → `[{framework, failCount, passCount}]`
  - All 403 when `accountId` isn’t owned by the caller’s client.

- [ ] **Step 1: Write the failing tests (tenant isolation is the critical case)**

`XamSecureServiceTest.java`:
```java
package com.xammer.cloud.service.upwind;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class XamSecureServiceTest {

    private CloudAccountRepository cloudAccountRepo;
    private UpwindThreatDetectionRepository detectionRepo;
    private UpwindVulnerabilityFindingRepository vulnRepo;
    private UpwindPostureFindingRepository postureRepo;
    private UpwindSyncStateRepository syncStateRepo;
    private UpwindAccountConnectionRepository connectionRepo;
    private RedisCacheService redisCacheService;
    private XamSecureService service;

    private CloudAccount aws(String awsId) {
        CloudAccount a = new CloudAccount();
        a.setProvider("AWS");
        a.setAwsAccountId(awsId);
        a.setAccountName("acct-" + awsId);
        return a;
    }

    @BeforeEach
    void setUp() {
        cloudAccountRepo = mock(CloudAccountRepository.class);
        detectionRepo = mock(UpwindThreatDetectionRepository.class);
        vulnRepo = mock(UpwindVulnerabilityFindingRepository.class);
        postureRepo = mock(UpwindPostureFindingRepository.class);
        syncStateRepo = mock(UpwindSyncStateRepository.class);
        connectionRepo = mock(UpwindAccountConnectionRepository.class);
        redisCacheService = mock(RedisCacheService.class);
        when(redisCacheService.get(any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
        service = new XamSecureService(cloudAccountRepo, detectionRepo, vulnRepo,
                postureRepo, syncStateRepo, connectionRepo, redisCacheService);
    }

    @Test
    void resolveAccountIdsReturnsAllOwnedProviderIds() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111"), aws("222")));
        List<String> ids = service.resolveAccountIds(1L, null);
        assertEquals(List.of("111", "222"), ids);
    }

    @Test
    void resolveAccountIdsFiltersToRequestedOwnedAccount() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111"), aws("222")));
        List<String> ids = service.resolveAccountIds(1L, "222");
        assertEquals(List.of("222"), ids);
    }

    @Test
    void resolveAccountIdsRejectsForeignAccount() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        assertThrows(AccessDeniedException.class, () -> service.resolveAccountIds(1L, "999"));
    }

    @Test
    void resolveAccountIdsEmptyWhenClientHasNoAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of());
        assertTrue(service.resolveAccountIds(1L, null).isEmpty());
    }

    @Test
    void summaryUsesSeverityCountsFromRepos() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        when(detectionRepo.countOpenBySeverity(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{"HIGH", 3L}));
        when(vulnRepo.countOpenBySeverity(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{"CRITICAL", 5L}));
        when(postureRepo.countFailBySeverity(List.of("111")))
                .thenReturn(List.<Object[]>of(new Object[]{"MEDIUM", 2L}));
        when(syncStateRepo.findAll()).thenReturn(List.of());

        var summary = service.getSummary(1L, null);

        assertEquals(3L, summary.get("threatsBySeverity")
                instanceof java.util.Map ? ((java.util.Map<?, ?>) summary.get("threatsBySeverity")).get("HIGH") : null);
        assertEquals(5L, ((java.util.Map<?, ?>) summary.get("vulnerabilitiesBySeverity")).get("CRITICAL"));
        assertEquals(2L, ((java.util.Map<?, ?>) summary.get("postureBySeverity")).get("MEDIUM"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: COMPILATION ERROR (XamSecureService does not exist)

- [ ] **Step 3: Write XamSecureService**

```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read side of XamSecure. Every public method takes the caller's clientId and
 * filters strictly to cloud accounts owned by that client — org-wide Upwind
 * data must never leave this layer unfiltered.
 */
@Service
@Slf4j
public class XamSecureService {

    private static final int SUMMARY_CACHE_MINUTES = 5;

    private final CloudAccountRepository cloudAccountRepository;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final RedisCacheService redisCacheService;

    public XamSecureService(CloudAccountRepository cloudAccountRepository,
                            UpwindThreatDetectionRepository detectionRepo,
                            UpwindVulnerabilityFindingRepository vulnRepo,
                            UpwindPostureFindingRepository postureRepo,
                            UpwindSyncStateRepository syncStateRepo,
                            UpwindAccountConnectionRepository connectionRepo,
                            RedisCacheService redisCacheService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
        this.connectionRepo = connectionRepo;
        this.redisCacheService = redisCacheService;
    }

    /**
     * Resolves the provider account ids (AWS account / GCP project / Azure
     * subscription) owned by the client. If requestedAccountId is given it
     * must be one of them, otherwise AccessDeniedException.
     */
    public List<String> resolveAccountIds(Long clientId, String requestedAccountId) {
        List<String> owned = cloudAccountRepository.findByClientId(clientId).stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (requestedAccountId == null || requestedAccountId.isEmpty()) {
            return owned;
        }
        if (!owned.contains(requestedAccountId)) {
            throw new AccessDeniedException("Account not accessible: " + requestedAccountId);
        }
        return List.of(requestedAccountId);
    }

    public Map<String, Object> getSummary(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        String cacheKey = "xamsecure:summary:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<Map<String, Object>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Object>>() {});
        if (cached.isPresent()) return cached.get();

        Map<String, Object> summary = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            summary.put("threatsBySeverity", Map.of());
            summary.put("vulnerabilitiesBySeverity", Map.of());
            summary.put("postureBySeverity", Map.of());
            summary.put("syncState", List.of());
            return summary;
        }
        summary.put("threatsBySeverity", toSeverityMap(detectionRepo.countOpenBySeverity(ids)));
        summary.put("vulnerabilitiesBySeverity", toSeverityMap(vulnRepo.countOpenBySeverity(ids)));
        summary.put("postureBySeverity", toSeverityMap(postureRepo.countFailBySeverity(ids)));
        summary.put("syncState", syncStateRepo.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", s.getDomain());
            m.put("lastSuccessAt", s.getLastSuccessAt());
            m.put("lastError", s.getLastError());
            return m;
        }).collect(Collectors.toList()));
        redisCacheService.put(cacheKey, summary, SUMMARY_CACHE_MINUTES);
        return summary;
    }

    public Page<UpwindThreatDetection> getThreats(Long clientId, String accountId,
                                                  String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return detectionRepo.findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(ids, severity, pageable);
        }
        return detectionRepo.findByCloudAccountIdInOrderByLastSeenTimeDesc(ids, pageable);
    }

    public Page<UpwindVulnerabilityFinding> getVulnerabilities(Long clientId, String accountId,
                                                               String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return vulnRepo.findByCloudAccountIdInAndSeverityOrderByLastScanTimeDesc(ids, severity, pageable);
        }
        return vulnRepo.findByCloudAccountIdInOrderByLastScanTimeDesc(ids, pageable);
    }

    public Page<UpwindPostureFinding> getPosture(Long clientId, String accountId,
                                                 String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return postureRepo.findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(ids, severity, pageable);
        }
        return postureRepo.findByCloudAccountIdInOrderByLastSeenTimeDesc(ids, pageable);
    }

    public List<Map<String, Object>> getCompliance(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return List.of();
        Map<String, Map<String, Long>> byFramework = new LinkedHashMap<>();
        for (Object[] row : postureRepo.countByFrameworkAndStatus(ids)) {
            String framework = (String) row[0];
            String status = (String) row[1];
            Long count = (Long) row[2];
            byFramework.computeIfAbsent(framework, k -> new HashMap<>()).put(status, count);
        }
        return byFramework.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("framework", e.getKey());
            m.put("failCount", e.getValue().getOrDefault("FAIL", 0L));
            m.put("passCount", e.getValue().getOrDefault("PASS", 0L));
            return m;
        }).collect(Collectors.toList());
    }

    private static Map<String, Long> toSeverityMap(List<Object[]> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) m.put((String) row[0], (Long) row[1]);
        }
        return m;
    }

    public List<Map<String, Object>> getOnboardingStatus(Long clientId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);
        List<String> ids = accounts.stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<String, UpwindAccountConnection> connections = connectionRepo
                .findByCloudAccountIdIn(ids).stream()
                .collect(Collectors.toMap(UpwindAccountConnection::getCloudAccountId, c -> c));
        return accounts.stream()
                .filter(a -> a.getProviderAccountId() != null)
                .map(a -> {
                    UpwindAccountConnection c = connections.get(a.getProviderAccountId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountName", a.getAccountName());
                    m.put("cloudAccountId", a.getProviderAccountId());
                    m.put("provider", a.getProvider());
                    m.put("status", c == null ? "NOT_CONNECTED" : c.getStatus());
                    m.put("sensorInstallCommand", c == null ? null : c.getSensorInstallCommand());
                    return m;
                }).collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: 5 tests PASS

- [ ] **Step 5: Write XamSecureController**

```java
package com.xammer.cloud.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.upwind.XamSecureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/xamsecure")
@RequiredArgsConstructor
public class XamSecureController {

    private final XamSecureService xamSecureService;

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getSummary(user.getClientId(), accountId));
    }

    @GetMapping("/threats")
    public ResponseEntity<?> threats(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getThreats(user.getClientId(), accountId, severity, page, size));
    }

    @GetMapping("/vulnerabilities")
    public ResponseEntity<?> vulnerabilities(@AuthenticationPrincipal ClientUserDetails user,
                                             @RequestParam(required = false) String accountId,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getVulnerabilities(user.getClientId(), accountId, severity, page, size));
    }

    @GetMapping("/posture")
    public ResponseEntity<?> posture(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getPosture(user.getClientId(), accountId, severity, page, size));
    }

    @GetMapping("/compliance")
    public ResponseEntity<?> compliance(@AuthenticationPrincipal ClientUserDetails user,
                                        @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getCompliance(user.getClientId(), accountId));
    }
}
```

Note: `AccessDeniedException` from the service maps to HTTP 403 via Spring Security's default handling. `rawPayload` IS serialized in list responses (the detail drawer uses it); if payload size becomes a problem later, add a DTO — not now (YAGNI).

- [ ] **Step 6: Compile everything**

Run: `mvn -f xamops-service/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java
git commit -m "feat(xamsecure): add tenant-filtered read API (summary/threats/vulns/posture/compliance)"
```

---

### Task 6: Onboarding endpoints

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureOnboardingController.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureOnboardingTest.java`

**Interfaces:**
- Consumes: Task 5 `XamSecureService.getOnboardingStatus(Long clientId)`, Task 2 `UpwindAccountConnectionRepository`.
- Produces (consumed by Task 9 Onboarding tab):
  - `GET /api/xamsecure/onboarding/status` → `[{accountName, cloudAccountId, provider, status, sensorInstallCommand}]`
  - `POST /api/xamsecure/onboarding/register` (SUPER_ADMIN only) body `{cloudAccountId, status, sensorInstallCommand, notes}` → upserts a connection row

- [ ] **Step 1: Write the failing test**

`XamSecureOnboardingTest.java`:
```java
package com.xammer.cloud.service.upwind;

import com.xammer.cloud.controller.XamSecureOnboardingController;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XamSecureOnboardingTest {

    private UpwindAccountConnectionRepository connectionRepo;
    private XamSecureService xamSecureService;
    private XamSecureOnboardingController controller;

    @BeforeEach
    void setUp() {
        connectionRepo = mock(UpwindAccountConnectionRepository.class);
        xamSecureService = mock(XamSecureService.class);
        controller = new XamSecureOnboardingController(xamSecureService, connectionRepo);
    }

    @Test
    void registerCreatesConnectionWhenMissing() {
        when(connectionRepo.findByCloudAccountId("111")).thenReturn(Optional.empty());

        controller.register(Map.of(
                "cloudAccountId", "111",
                "status", "CONNECTED",
                "sensorInstallCommand", "helm install upwind-sensor ..."));

        ArgumentCaptor<UpwindAccountConnection> captor =
                ArgumentCaptor.forClass(UpwindAccountConnection.class);
        verify(connectionRepo).save(captor.capture());
        assertEquals("111", captor.getValue().getCloudAccountId());
        assertEquals("CONNECTED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getUpdatedAt());
    }

    @Test
    void registerUpdatesExistingConnection() {
        UpwindAccountConnection existing = new UpwindAccountConnection();
        existing.setId(4L);
        existing.setCloudAccountId("111");
        existing.setStatus("PENDING");
        when(connectionRepo.findByCloudAccountId("111")).thenReturn(Optional.of(existing));

        controller.register(Map.of("cloudAccountId", "111", "status", "CONNECTED"));

        ArgumentCaptor<UpwindAccountConnection> captor =
                ArgumentCaptor.forClass(UpwindAccountConnection.class);
        verify(connectionRepo).save(captor.capture());
        assertEquals(4L, captor.getValue().getId());
        assertEquals("CONNECTED", captor.getValue().getStatus());
    }

    @Test
    void registerRejectsMissingAccountId() {
        var resp = controller.register(Map.of("status", "CONNECTED"));
        assertEquals(400, resp.getStatusCodeValue());
        verify(connectionRepo, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureOnboardingTest`
Expected: COMPILATION ERROR (controller does not exist)

- [ ] **Step 3: Write the controller**

```java
package com.xammer.cloud.controller;

import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.upwind.XamSecureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/xamsecure/onboarding")
@RequiredArgsConstructor
public class XamSecureOnboardingController {

    private final XamSecureService xamSecureService;
    private final UpwindAccountConnectionRepository connectionRepo;

    /** Customer-facing: connection + sensor status for the caller's own accounts. */
    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(xamSecureService.getOnboardingStatus(user.getClientId()));
    }

    /**
     * Admin-only: register/update an Upwind connection for a cloud account.
     * Phase 1 fallback flow — a Xammer admin creates the Cloud account /
     * Sensor credential in the Upwind console and registers the resulting
     * install command here; customers then see it in their Onboarding tab.
     */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required"));
        }
        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(UpwindAccountConnection::new);
        conn.setCloudAccountId(cloudAccountId);
        if (body.get("status") != null) conn.setStatus(body.get("status"));
        if (body.get("sensorInstallCommand") != null) conn.setSensorInstallCommand(body.get("sensorInstallCommand"));
        if (body.get("notes") != null) conn.setNotes(body.get("notes"));
        conn.setUpdatedAt(Instant.now());
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "saved"));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureOnboardingTest`
Expected: 3 tests PASS

- [ ] **Step 5: Run the full backend test suite (regression gate)**

Run: `mvn -f xamops-service/pom.xml test -q`
Expected: BUILD SUCCESS, no failures

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureOnboardingController.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureOnboardingTest.java
git commit -m "feat(xamsecure): add onboarding status endpoint and admin connection registration"
```

---

### Task 7: Frontend types + data hook

**Files:**
- Create: `frontend-app/src/types/xamsecure.ts`
- Create: `frontend-app/src/hooks/useXamSecureData.ts`
- Test: `frontend-app/src/hooks/__tests__/useXamSecureData.test.ts`

**Interfaces:**
- Consumes: Task 5/6 endpoints; `apiClient` from `utils/apiClient` (drop-in fetch wrapper: `apiClient(url)` → `Response`).
- Produces (consumed by Tasks 8–9): types `XamSecureSummary`, `ThreatDetection`, `VulnerabilityFinding`, `PostureFinding`, `ComplianceFramework`, `OnboardingAccount`, `PageResp<T>`; hook return `{ summary, threats, vulnerabilities, posture, compliance, onboarding, isLoading, refresh, loadTab }` — exact shape below.

- [ ] **Step 1: Write the types**

`frontend-app/src/types/xamsecure.ts`:
```typescript
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | string;

export interface PageResp<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface ThreatDetection {
  id: number;
  upwindId: string;
  category: string | null;
  severity: Severity | null;
  status: string | null;
  title: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  resourceName: string | null;
  resourceType: string | null;
  mitreTactics: string | null;
  occurrenceCount: number | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  rawPayload: string | null;
}

export interface VulnerabilityFinding {
  id: number;
  upwindId: string;
  cveId: string | null;
  vulnerabilityName: string | null;
  severity: Severity | null;
  cvssScore: string | null;
  epssScore: string | null;
  exploitable: boolean | null;
  status: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  region: string | null;
  resourceName: string | null;
  resourceType: string | null;
  firstSeenTime: string | null;
  lastScanTime: string | null;
  rawPayload: string | null;
}

export interface PostureFinding {
  id: number;
  upwindId: string;
  severity: Severity | null;
  status: string | null;
  title: string | null;
  frameworkTitle: string | null;
  checkTitle: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  region: string | null;
  resourceName: string | null;
  resourceType: string | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  rawPayload: string | null;
}

export interface ComplianceFramework {
  framework: string;
  failCount: number;
  passCount: number;
}

export interface SyncStateEntry {
  domain: string;
  lastSuccessAt: string | null;
  lastError: string | null;
}

export interface XamSecureSummary {
  threatsBySeverity: Record<string, number>;
  vulnerabilitiesBySeverity: Record<string, number>;
  postureBySeverity: Record<string, number>;
  syncState: SyncStateEntry[];
}

export interface OnboardingAccount {
  accountName: string;
  cloudAccountId: string;
  provider: string;
  status: 'NOT_CONNECTED' | 'PENDING' | 'CONNECTED';
  sensorInstallCommand: string | null;
}

export const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export function severityTotal(m: Record<string, number> | undefined): number {
  if (!m) return 0;
  return Object.values(m).reduce((a, b) => a + b, 0);
}
```

- [ ] **Step 2: Write the failing test (pure helpers)**

`frontend-app/src/hooks/__tests__/useXamSecureData.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { severityTotal, SEVERITY_ORDER } from '../../types/xamsecure';
import { xamSecureCacheKey } from '../useXamSecureData';

describe('xamsecure helpers', () => {
  it('severityTotal sums all severities', () => {
    expect(severityTotal({ CRITICAL: 2, HIGH: 3 })).toBe(5);
    expect(severityTotal(undefined)).toBe(0);
  });

  it('severity order is fixed CRITICAL-first', () => {
    expect(SEVERITY_ORDER[0]).toBe('CRITICAL');
  });

  it('cache key is scoped per account', () => {
    expect(xamSecureCacheKey('summary', '111')).toBe('xamsecure_summary_111');
    expect(xamSecureCacheKey('summary', null)).toBe('xamsecure_summary_all');
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend-app && npx vitest run src/hooks/__tests__/useXamSecureData.test.ts`
Expected: FAIL (`xamSecureCacheKey` not exported / file missing)

- [ ] **Step 4: Write the hook**

`frontend-app/src/hooks/useXamSecureData.ts`:
```typescript
import { useState, useEffect, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';
import type {
  XamSecureSummary, ThreatDetection, VulnerabilityFinding, PostureFinding,
  ComplianceFramework, OnboardingAccount, PageResp,
} from '../types/xamsecure';

const CACHE_DURATION = 30 * 60 * 1000; // 30 min for scan-based tabs
const THREAT_POLL_MS = 60 * 1000;      // runtime feed

export function xamSecureCacheKey(section: string, accountId: string | null): string {
  return `xamsecure_${section}_${accountId ?? 'all'}`;
}

function loadCache<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    const expiry = localStorage.getItem(`${key}_expiry`);
    if (raw && expiry && Date.now() <= parseInt(expiry)) return JSON.parse(raw) as T;
  } catch { /* ignore */ }
  return null;
}

function saveCache(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    localStorage.setItem(`${key}_expiry`, (Date.now() + CACHE_DURATION).toString());
  } catch { /* ignore */ }
}

interface UseXamSecureDataParams {
  selectedAccountId: string | null;
  activeTab: string;
}

export const useXamSecureData = ({ selectedAccountId, activeTab }: UseXamSecureDataParams) => {
  const [summary, setSummary] = useState<XamSecureSummary | null>(null);
  const [threats, setThreats] = useState<PageResp<ThreatDetection> | null>(null);
  const [vulnerabilities, setVulnerabilities] = useState<PageResp<VulnerabilityFinding> | null>(null);
  const [posture, setPosture] = useState<PageResp<PostureFinding> | null>(null);
  const [compliance, setCompliance] = useState<ComplianceFramework[] | null>(null);
  const [onboarding, setOnboarding] = useState<OnboardingAccount[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const pollRef = useRef<number | null>(null);

  const acct = selectedAccountId ? `accountId=${encodeURIComponent(selectedAccountId)}&` : '';

  const fetchJson = useCallback(async (url: string) => {
    const res = await apiClient(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }, []);

  const loadSummary = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('summary', selectedAccountId);
    if (!force) {
      const cached = loadCache<XamSecureSummary>(key);
      if (cached) { setSummary(cached); return; }
    }
    const data = await fetchJson(`/api/xamsecure/summary?${acct}`);
    setSummary(data);
    saveCache(key, data);
  }, [acct, selectedAccountId, fetchJson]);

  const loadThreats = useCallback(async (page = 0, severity = '') => {
    const data = await fetchJson(
      `/api/xamsecure/threats?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setThreats(data);
  }, [acct, fetchJson]);

  const loadVulnerabilities = useCallback(async (page = 0, severity = '', force = false) => {
    const key = xamSecureCacheKey(`vulns_${page}_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<VulnerabilityFinding>>(key);
      if (cached) { setVulnerabilities(cached); return; }
    }
    const data = await fetchJson(
      `/api/xamsecure/vulnerabilities?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setVulnerabilities(data);
    saveCache(key, data);
  }, [acct, selectedAccountId, fetchJson]);

  const loadPosture = useCallback(async (page = 0, severity = '', force = false) => {
    const key = xamSecureCacheKey(`posture_${page}_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<PostureFinding>>(key);
      if (cached) { setPosture(cached); return; }
    }
    const data = await fetchJson(
      `/api/xamsecure/posture?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setPosture(data);
    saveCache(key, data);
  }, [acct, selectedAccountId, fetchJson]);

  const loadCompliance = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('compliance', selectedAccountId);
    if (!force) {
      const cached = loadCache<ComplianceFramework[]>(key);
      if (cached) { setCompliance(cached); return; }
    }
    const data = await fetchJson(`/api/xamsecure/compliance?${acct}`);
    setCompliance(data);
    saveCache(key, data);
  }, [acct, selectedAccountId, fetchJson]);

  const loadOnboarding = useCallback(async () => {
    const data = await fetchJson('/api/xamsecure/onboarding/status');
    setOnboarding(data);
  }, [fetchJson]);

  // Load the active tab's data; Threats tab also polls every 60s.
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      setIsLoading(true);
      try {
        if (activeTab === 'overview') await loadSummary();
        else if (activeTab === 'threats') await loadThreats();
        else if (activeTab === 'vulnerabilities') await loadVulnerabilities();
        else if (activeTab === 'posture') await loadPosture();
        else if (activeTab === 'compliance') await loadCompliance();
        else if (activeTab === 'onboarding') await loadOnboarding();
      } catch (err) {
        console.error('[XamSecure] load failed:', err);
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    };
    run();

    if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    if (activeTab === 'threats') {
      pollRef.current = window.setInterval(() => {
        loadThreats().catch((err) => console.error('[XamSecure] threat poll failed:', err));
      }, THREAT_POLL_MS);
    }
    return () => {
      cancelled = true;
      if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    };
  }, [activeTab, selectedAccountId, loadSummary, loadThreats, loadVulnerabilities,
      loadPosture, loadCompliance, loadOnboarding]);

  const refresh = useCallback(() => {
    if (activeTab === 'overview') return loadSummary(true);
    if (activeTab === 'threats') return loadThreats();
    if (activeTab === 'vulnerabilities') return loadVulnerabilities(0, '', true);
    if (activeTab === 'posture') return loadPosture(0, '', true);
    if (activeTab === 'compliance') return loadCompliance(true);
    if (activeTab === 'onboarding') return loadOnboarding();
    return Promise.resolve();
  }, [activeTab, loadSummary, loadThreats, loadVulnerabilities, loadPosture, loadCompliance, loadOnboarding]);

  return {
    summary, threats, vulnerabilities, posture, compliance, onboarding,
    isLoading, refresh,
    loadThreats, loadVulnerabilities, loadPosture,
  };
};
```

Note: check `utils/apiClient`'s export style first (`grep -n "export" frontend-app/src/utils/apiClient.ts`); if it's a named export, adjust the import to match.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend-app && npx vitest run src/hooks/__tests__/useXamSecureData.test.ts`
Expected: 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/types/xamsecure.ts frontend-app/src/hooks/useXamSecureData.ts frontend-app/src/hooks/__tests__/useXamSecureData.test.ts
git commit -m "feat(xamsecure): add frontend types and data hook (cached tabs + 60s threat polling)"
```

---

### Task 8: Shared UI components + Overview and Threats tabs

**Files:**
- Create: `frontend-app/src/components/XamSecure/SeverityChip.tsx`
- Create: `frontend-app/src/components/XamSecure/XamSecureHeader.tsx`
- Create: `frontend-app/src/components/XamSecure/FindingDrawer.tsx`
- Create: `frontend-app/src/components/XamSecure/OverviewTab.tsx`
- Create: `frontend-app/src/components/XamSecure/ThreatsTab.tsx`
- Create: `frontend-app/src/components/XamSecure/index.ts`

**Interfaces:**
- Consumes: Task 7 hook data shapes and types.
- Produces (consumed by Task 10 page): components `XamSecureHeader` (sets Topbar title/actions), `OverviewTab {summary, isLoading}`, `ThreatsTab {threats, isLoading, onPage(page: number)}`, `SeverityChip {severity}`, `FindingDrawer {title, rawPayload, onClose}`.

Design constraints (apply to Task 9 too): black/white/grey only — severity chips use grey scale (`bg-gray-900 text-white` CRITICAL, `bg-gray-700 text-white` HIGH, `bg-gray-400 text-white` MEDIUM, `bg-gray-200 text-gray-700` LOW). No emojis. Tables: `text-xs`, `border-gray-200`, white cards `rounded-xl shadow-sm`.

- [ ] **Step 1: Write SeverityChip**

`SeverityChip.tsx`:
```tsx
import React from 'react';

const STYLES: Record<string, string> = {
  CRITICAL: 'bg-gray-900 text-white',
  HIGH: 'bg-gray-700 text-white',
  MEDIUM: 'bg-gray-400 text-white',
  LOW: 'bg-gray-200 text-gray-700',
};

const SeverityChip: React.FC<{ severity: string | null }> = ({ severity }) => {
  const sev = (severity ?? 'UNKNOWN').toUpperCase();
  return (
    <span className={`inline-block px-2 py-0.5 rounded-full text-[10px] font-bold tracking-wide ${STYLES[sev] ?? 'bg-gray-100 text-gray-500'}`}>
      {sev}
    </span>
  );
};

export default SeverityChip;
```

- [ ] **Step 2: Write XamSecureHeader (Topbar title + refresh action)**

`XamSecureHeader.tsx`:
```tsx
import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';

interface Props {
  isLoading: boolean;
  onRefresh: () => void;
}

const XamSecureHeader: React.FC<Props> = ({ isLoading, onRefresh }) => {
  const { setTitle, setActions, clearActions } = useTopbar();

  useEffect(() => {
    setTitle('XamSecure');
    setActions(
      <button
        onClick={onRefresh}
        disabled={isLoading}
        className="flex items-center gap-2 px-4 py-2 bg-gray-900 hover:bg-black text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
      >
        <Icon icon="mdi:refresh" width={14} className={isLoading ? 'animate-spin' : ''} />
        <span>{isLoading ? 'Loading...' : 'Refresh'}</span>
      </button>
    );
    return () => { clearActions(); setTitle(null); };
  }, [isLoading, onRefresh, setTitle, setActions, clearActions]);

  return null;
};

export default XamSecureHeader;
```

- [ ] **Step 3: Write FindingDrawer (detail panel rendering raw payload fields)**

`FindingDrawer.tsx`:
```tsx
import React from 'react';
import { Icon } from '@iconify/react';

interface Props {
  title: string;
  rawPayload: string | null;
  onClose: () => void;
}

/** Right-side drawer showing the full finding payload as a key/value tree. */
const FindingDrawer: React.FC<Props> = ({ title, rawPayload, onClose }) => {
  let pretty = '';
  try {
    pretty = rawPayload ? JSON.stringify(JSON.parse(rawPayload), null, 2) : '';
  } catch {
    pretty = rawPayload ?? '';
  }
  return (
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="relative w-full max-w-xl h-full bg-white shadow-2xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <h3 className="text-sm font-bold text-gray-900 pr-4 line-clamp-2">{title}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100" aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>
        <pre className="flex-1 overflow-auto p-5 text-[11px] leading-relaxed text-gray-700 bg-gray-50">
          {pretty}
        </pre>
      </div>
    </div>
  );
};

export default FindingDrawer;
```

- [ ] **Step 4: Write OverviewTab**

`OverviewTab.tsx`:
```tsx
import React from 'react';
import type { XamSecureSummary } from '../../types/xamsecure';
import { SEVERITY_ORDER, severityTotal } from '../../types/xamsecure';

interface Props {
  summary: XamSecureSummary | null;
  isLoading: boolean;
}

const Card: React.FC<{ label: string; counts: Record<string, number> | undefined }> = ({ label, counts }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</p>
    <p className="text-3xl font-bold text-gray-900 mt-1">{severityTotal(counts)}</p>
    <div className="flex gap-3 mt-3">
      {SEVERITY_ORDER.map((sev) => (
        <div key={sev} className="text-center">
          <p className="text-sm font-bold text-gray-800">{counts?.[sev] ?? 0}</p>
          <p className="text-[10px] text-gray-400 uppercase">{sev.slice(0, 4)}</p>
        </div>
      ))}
    </div>
  </div>
);

const OverviewTab: React.FC<Props> = ({ summary, isLoading }) => {
  if (isLoading && !summary) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading security overview...</div>;
  }
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card label="Open Threats" counts={summary?.threatsBySeverity} />
        <Card label="Vulnerabilities" counts={summary?.vulnerabilitiesBySeverity} />
        <Card label="Posture Findings" counts={summary?.postureBySeverity} />
      </div>
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Data Freshness</p>
        <div className="space-y-1.5">
          {(summary?.syncState ?? []).map((s) => (
            <div key={s.domain} className="flex items-center justify-between text-xs">
              <span className="font-medium text-gray-700">{s.domain}</span>
              <span className={s.lastError ? 'text-gray-900 font-semibold' : 'text-gray-400'}>
                {s.lastError
                  ? `Sync issue — showing last data (${s.lastError})`
                  : s.lastSuccessAt
                    ? `Last synced ${new Date(s.lastSuccessAt).toLocaleString()}`
                    : 'Not synced yet'}
              </span>
            </div>
          ))}
          {(summary?.syncState ?? []).length === 0 && (
            <p className="text-xs text-gray-400">Sync has not run yet.</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default OverviewTab;
```

- [ ] **Step 5: Write ThreatsTab (live feed)**

`ThreatsTab.tsx`:
```tsx
import React, { useState } from 'react';
import type { PageResp, ThreatDetection } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import FindingDrawer from './FindingDrawer';

interface Props {
  threats: PageResp<ThreatDetection> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const ThreatsTab: React.FC<Props> = ({ threats, isLoading, onPage }) => {
  const [selected, setSelected] = useState<ThreatDetection | null>(null);
  const rows = threats?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Runtime Threat Detections</p>
        <span className="flex items-center gap-1.5 text-[10px] font-semibold text-gray-500">
          <span className="w-1.5 h-1.5 rounded-full bg-gray-900 animate-pulse" />
          LIVE — refreshes every 60s
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Last Seen</th>
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Detection</th>
              <th className="px-5 py-2.5">Resource</th>
              <th className="px-5 py-2.5">MITRE Tactic</th>
              <th className="px-5 py-2.5">Count</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(t)}>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {t.lastSeenTime ? new Date(t.lastSeenTime).toLocaleString() : '—'}
                </td>
                <td className="px-5 py-3"><SeverityChip severity={t.severity} /></td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{t.title}</td>
                <td className="px-5 py-3 text-gray-600">
                  {t.resourceName}
                  <span className="block text-[10px] text-gray-400">{t.resourceType}</span>
                </td>
                <td className="px-5 py-3 text-gray-600">{t.mitreTactics ?? '—'}</td>
                <td className="px-5 py-3 text-gray-600">{t.occurrenceCount ?? 1}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading detections...' : 'No threat detections for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {threats && threats.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {threats.number + 1} of {threats.totalPages} · {threats.totalElements} detections</span>
          <div className="flex gap-2">
            <button disabled={threats.number === 0} onClick={() => onPage(threats.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={threats.number + 1 >= threats.totalPages} onClick={() => onPage(threats.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer title={selected.title ?? 'Detection'} rawPayload={selected.rawPayload}
                       onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default ThreatsTab;
```

- [ ] **Step 6: Write the barrel export**

`index.ts`:
```typescript
export { default as SeverityChip } from './SeverityChip';
export { default as XamSecureHeader } from './XamSecureHeader';
export { default as FindingDrawer } from './FindingDrawer';
export { default as OverviewTab } from './OverviewTab';
export { default as ThreatsTab } from './ThreatsTab';
```
(Task 9 appends its tabs to this file.)

- [ ] **Step 7: Type-check**

Run: `cd frontend-app && npx tsc --noEmit`
Expected: no errors in `src/components/XamSecure/**` (pre-existing errors elsewhere, if any, are out of scope)

- [ ] **Step 8: Commit**

```bash
git add frontend-app/src/components/XamSecure/
git commit -m "feat(xamsecure): add shared components, Overview and Threats tabs"
```

---

### Task 9: Vulnerabilities, Posture, Compliance, Onboarding tabs

**Files:**
- Create: `frontend-app/src/components/XamSecure/VulnerabilitiesTab.tsx`
- Create: `frontend-app/src/components/XamSecure/PostureTab.tsx`
- Create: `frontend-app/src/components/XamSecure/ComplianceTab.tsx`
- Create: `frontend-app/src/components/XamSecure/OnboardingTab.tsx`
- Modify: `frontend-app/src/components/XamSecure/index.ts` (append exports)

**Interfaces:**
- Consumes: Task 7 types, Task 8 `SeverityChip`, `FindingDrawer`.
- Produces (consumed by Task 10): `VulnerabilitiesTab {data, isLoading, onPage}`, `PostureTab {data, isLoading, onPage}`, `ComplianceTab {data, isLoading}`, `OnboardingTab {accounts, isLoading}`.

- [ ] **Step 1: Write VulnerabilitiesTab**

```tsx
import React, { useState } from 'react';
import type { PageResp, VulnerabilityFinding } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import FindingDrawer from './FindingDrawer';

interface Props {
  data: PageResp<VulnerabilityFinding> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const VulnerabilitiesTab: React.FC<Props> = ({ data, isLoading, onPage }) => {
  const [selected, setSelected] = useState<VulnerabilityFinding | null>(null);
  const rows = data?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">CVE</th>
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Vulnerability</th>
              <th className="px-5 py-2.5">CVSS</th>
              <th className="px-5 py-2.5">Exploitable</th>
              <th className="px-5 py-2.5">Resource</th>
              <th className="px-5 py-2.5">Last Scan</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((v) => (
              <tr key={v.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(v)}>
                <td className="px-5 py-3 font-mono text-gray-700 whitespace-nowrap">{v.cveId ?? '—'}</td>
                <td className="px-5 py-3"><SeverityChip severity={v.severity} /></td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{v.vulnerabilityName}</td>
                <td className="px-5 py-3 text-gray-600">{v.cvssScore ?? '—'}</td>
                <td className="px-5 py-3 text-gray-600">{v.exploitable === null ? '—' : v.exploitable ? 'Yes' : 'No'}</td>
                <td className="px-5 py-3 text-gray-600">
                  {v.resourceName}
                  <span className="block text-[10px] text-gray-400">{v.resourceType}</span>
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {v.lastScanTime ? new Date(v.lastScanTime).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading vulnerabilities...' : 'No vulnerability findings for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {data.number + 1} of {data.totalPages} · {data.totalElements} findings</span>
          <div className="flex gap-2">
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer title={selected.vulnerabilityName ?? selected.cveId ?? 'Vulnerability'}
                       rawPayload={selected.rawPayload} onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default VulnerabilitiesTab;
```

- [ ] **Step 2: Write PostureTab**

```tsx
import React, { useState } from 'react';
import type { PageResp, PostureFinding } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import FindingDrawer from './FindingDrawer';

interface Props {
  data: PageResp<PostureFinding> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const PostureTab: React.FC<Props> = ({ data, isLoading, onPage }) => {
  const [selected, setSelected] = useState<PostureFinding | null>(null);
  const rows = data?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Status</th>
              <th className="px-5 py-2.5">Finding</th>
              <th className="px-5 py-2.5">Resource</th>
              <th className="px-5 py-2.5">Provider</th>
              <th className="px-5 py-2.5">Last Seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(p)}>
                <td className="px-5 py-3"><SeverityChip severity={p.severity} /></td>
                <td className="px-5 py-3 font-semibold text-gray-700">{p.status}</td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{p.title}</td>
                <td className="px-5 py-3 text-gray-600">
                  {p.resourceName}
                  <span className="block text-[10px] text-gray-400">{p.resourceType}</span>
                </td>
                <td className="px-5 py-3 text-gray-600">{p.cloudProvider}</td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {p.lastSeenTime ? new Date(p.lastSeenTime).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading posture findings...' : 'No posture findings for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {data.number + 1} of {data.totalPages} · {data.totalElements} findings</span>
          <div className="flex gap-2">
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer title={selected.title ?? 'Posture finding'} rawPayload={selected.rawPayload}
                       onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default PostureTab;
```

- [ ] **Step 3: Write ComplianceTab**

```tsx
import React from 'react';
import type { ComplianceFramework } from '../../types/xamsecure';

interface Props {
  data: ComplianceFramework[] | null;
  isLoading: boolean;
}

const ComplianceTab: React.FC<Props> = ({ data, isLoading }) => {
  const frameworks = data ?? [];
  if (isLoading && frameworks.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading compliance frameworks...</div>;
  }
  if (frameworks.length === 0) {
    return (
      <div className="text-center py-16 bg-white border border-gray-200 rounded-xl shadow-sm text-sm text-gray-400">
        No compliance framework data for the selected account.
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {frameworks.map((f) => {
        const total = f.failCount + f.passCount;
        const passPct = total === 0 ? 0 : Math.round((f.passCount / total) * 100);
        return (
          <div key={f.framework} className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
            <div className="flex items-start justify-between">
              <p className="text-sm font-bold text-gray-900 pr-3">{f.framework}</p>
              <p className="text-lg font-bold text-gray-900">{passPct}%</p>
            </div>
            <div className="mt-3 h-1.5 bg-gray-100 rounded-full overflow-hidden">
              <div className="h-full bg-gray-900" style={{ width: `${passPct}%` }} />
            </div>
            <div className="flex gap-4 mt-3 text-xs text-gray-500">
              <span><span className="font-bold text-gray-900">{f.passCount}</span> passing</span>
              <span><span className="font-bold text-gray-900">{f.failCount}</span> failing</span>
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default ComplianceTab;
```

- [ ] **Step 4: Write OnboardingTab**

```tsx
import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { OnboardingAccount } from '../../types/xamsecure';

interface Props {
  accounts: OnboardingAccount[] | null;
  isLoading: boolean;
}

const STATUS_LABEL: Record<string, string> = {
  CONNECTED: 'Connected',
  PENDING: 'Pending',
  NOT_CONNECTED: 'Not connected',
};

const OnboardingTab: React.FC<Props> = ({ accounts, isLoading }) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const rows = accounts ?? [];

  const copy = async (acct: OnboardingAccount) => {
    if (!acct.sensorInstallCommand) return;
    await navigator.clipboard.writeText(acct.sensorInstallCommand);
    setCopiedId(acct.cloudAccountId);
    window.setTimeout(() => setCopiedId(null), 2000);
  };

  if (isLoading && rows.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading onboarding status...</div>;
  }

  return (
    <div className="space-y-4">
      {rows.map((a) => (
        <div key={a.cloudAccountId} className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-bold text-gray-900">{a.accountName}</p>
              <p className="text-[11px] text-gray-400 font-mono">{a.provider} · {a.cloudAccountId}</p>
            </div>
            <span className={`px-3 py-1 rounded-full text-[10px] font-bold ${
              a.status === 'CONNECTED' ? 'bg-gray-900 text-white'
              : a.status === 'PENDING' ? 'bg-gray-300 text-gray-800'
              : 'bg-gray-100 text-gray-500'}`}>
              {STATUS_LABEL[a.status] ?? a.status}
            </span>
          </div>
          {a.status !== 'CONNECTED' && !a.sensorInstallCommand && (
            <p className="mt-3 text-xs text-gray-500">
              Security monitoring is not yet enabled for this account. Contact your XamOps
              administrator to enable XamSecure coverage.
            </p>
          )}
          {a.sensorInstallCommand && (
            <div className="mt-4">
              <p className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide mb-1.5">
                Sensor install command (run on your cluster)
              </p>
              <div className="flex items-start gap-2">
                <pre className="flex-1 bg-gray-50 border border-gray-200 rounded-lg p-3 text-[11px] text-gray-700 overflow-x-auto">
                  {a.sensorInstallCommand}
                </pre>
                <button onClick={() => copy(a)}
                        className="p-2 border border-gray-200 rounded-lg hover:bg-gray-50"
                        aria-label="Copy install command">
                  <Icon icon={copiedId === a.cloudAccountId ? 'mdi:check' : 'mdi:content-copy'} width={14} />
                </button>
              </div>
            </div>
          )}
        </div>
      ))}
      {rows.length === 0 && !isLoading && (
        <div className="text-center py-16 bg-white border border-gray-200 rounded-xl shadow-sm text-sm text-gray-400">
          No cloud accounts found. Add a cloud account to XamOps first.
        </div>
      )}
    </div>
  );
};

export default OnboardingTab;
```

- [ ] **Step 5: Append to `index.ts`**

```typescript
export { default as VulnerabilitiesTab } from './VulnerabilitiesTab';
export { default as PostureTab } from './PostureTab';
export { default as ComplianceTab } from './ComplianceTab';
export { default as OnboardingTab } from './OnboardingTab';
```

- [ ] **Step 6: Type-check**

Run: `cd frontend-app && npx tsc --noEmit`
Expected: no errors in `src/components/XamSecure/**`

- [ ] **Step 7: Commit**

```bash
git add frontend-app/src/components/XamSecure/
git commit -m "feat(xamsecure): add Vulnerabilities, Posture, Compliance, Onboarding tabs"
```

---

### Task 10: XamSecurePage + routing + sidebar

**Files:**
- Create: `frontend-app/src/pages/XamSecurePage.tsx`
- Modify: `frontend-app/src/App.tsx` (add lazy import + route next to the `/security` route at ~line 508)
- Modify: `frontend-app/src/components/Sidebar/Sidebar.tsx` (add NavItem in the SecOps dropdown at ~line 325; add `/xamsecure` to `secopsRoutes` at ~line 83)

**Interfaces:**
- Consumes: Tasks 7–9 hook + components; `useAccounts()` from `contexts/AccountContext`; `useTabState` from `hooks/useTabState`; `AriaTabGroup` from `components/ui`.

- [ ] **Step 1: Write the page**

`XamSecurePage.tsx`:
```tsx
import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTabState } from '../hooks/useTabState';
import { AriaTabGroup } from '../components/ui';
import { useXamSecureData } from '../hooks/useXamSecureData';
import {
  XamSecureHeader, OverviewTab, ThreatsTab, VulnerabilitiesTab,
  PostureTab, ComplianceTab, OnboardingTab,
} from '../components/XamSecure';

type ActiveTab = 'overview' | 'threats' | 'vulnerabilities' | 'posture' | 'compliance' | 'onboarding';

const XamSecurePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const [activeTab, setActiveTab] = useTabState<ActiveTab>('overview');

  const {
    summary, threats, vulnerabilities, posture, compliance, onboarding,
    isLoading, refresh, loadThreats, loadVulnerabilities, loadPosture,
  } = useXamSecureData({ selectedAccountId, activeTab });

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <XamSecureHeader isLoading={isLoading} onRefresh={refresh} />
      <div className="p-6 space-y-6">
        <AriaTabGroup
          variant="pill"
          tabs={[
            { id: 'overview', label: 'Overview' },
            { id: 'threats', label: 'Threats' },
            { id: 'vulnerabilities', label: 'Vulnerabilities' },
            { id: 'posture', label: 'Posture' },
            { id: 'compliance', label: 'Compliance' },
            { id: 'onboarding', label: 'Onboarding' },
          ]}
          activeTab={activeTab}
          onChange={(id) => setActiveTab(id as ActiveTab)}
        />
        {activeTab === 'overview' && <OverviewTab summary={summary} isLoading={isLoading} />}
        {activeTab === 'threats' && (
          <ThreatsTab threats={threats} isLoading={isLoading} onPage={(p) => loadThreats(p)} />
        )}
        {activeTab === 'vulnerabilities' && (
          <VulnerabilitiesTab data={vulnerabilities} isLoading={isLoading}
                              onPage={(p) => loadVulnerabilities(p)} />
        )}
        {activeTab === 'posture' && (
          <PostureTab data={posture} isLoading={isLoading} onPage={(p) => loadPosture(p)} />
        )}
        {activeTab === 'compliance' && <ComplianceTab data={compliance} isLoading={isLoading} />}
        {activeTab === 'onboarding' && <OnboardingTab accounts={onboarding} isLoading={isLoading} />}
      </div>
    </main>
  );
};

export default XamSecurePage;
```

Before writing, check `AriaTabGroup`'s actual prop names (`grep -n "interface.*TabGroup\|tabs\|activeTab\|onChange" frontend-app/src/components/ui/AriaTabGroup.tsx`) and match them — existing pages like `AzureSecurityPage.tsx` show the working usage.

- [ ] **Step 2: Register the route in `App.tsx`**

Add the import alongside the other page imports (match the file's existing import style — static or `lazy`):
```tsx
import XamSecurePage from './pages/XamSecurePage';
```
Add next to the `/security` routes (~line 508):
```tsx
<Route path="/xamsecure" element={<XamSecurePage />} />
```

- [ ] **Step 3: Add the sidebar entry**

In `frontend-app/src/components/Sidebar/Sidebar.tsx`:

At ~line 83 add `/xamsecure` to `secopsRoutes`:
```tsx
const secopsRoutes = ['/xamsecure', '/security', '/gcp-security', '/azure-security', '/complianceops',
```
(keep the rest of the original array unchanged).

At ~line 325, inside the SecOps dropdown after the Security Center NavItem, add:
```tsx
<NavItem href="/xamsecure" icon="fas fa-user-shield" label="XamSecure" isActive={isActive('/xamsecure')} />
```

- [ ] **Step 4: Build the frontend**

Run: `cd frontend-app && npm run build`
Expected: tsc + vite build succeed

- [ ] **Step 5: Run frontend tests**

Run: `cd frontend-app && npm run test`
Expected: all pass, including Task 7's tests

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/pages/XamSecurePage.tsx frontend-app/src/App.tsx frontend-app/src/components/Sidebar/Sidebar.tsx
git commit -m "feat(xamsecure): add XamSecurePage with 6 tabs, route, and SecOps sidebar entry"
```

---

### Task 11: Full verification + smoke test with live credentials

**Files:**
- No new files (verification only)

- [ ] **Step 1: Full backend test suite**

Run: `mvn -f xamops-service/pom.xml test -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Full frontend build + tests**

Run: `cd frontend-app && npm run build && npm run test`
Expected: both succeed

- [ ] **Step 3: Local smoke test of the sync (optional but recommended if local Postgres + Redis are running)**

```bash
set -a; source .env.local; set +a
export UPWIND_SYNC_ENABLED=true
mvn -f xamops-service/pom.xml spring-boot:run
```
Expected within ~2 minutes of boot: logs `[Upwind] Obtained API token`, then detection/full sync activity; `upwind_threat_detection` table gains rows. Then open the frontend dev server (`cd frontend-app && npm run dev`), log in, navigate to XamSecure — Overview shows counts; Threats tab shows the live feed.

- [ ] **Step 4: Final commit and push**

```bash
git pull --rebase origin main
git push origin main
```

---

## Deferred to Phase 2 (tracked in spec, not this plan)

- Inventory tab — `POST /inventory/search` 403s with the current credential; fix permission in the Upwind console first.
- Webhook ingestion replacing the 60s detections poll (needs license confirmation).
- API Security + Identity tabs (endpoints unverified).
- Automated BYOC credential provisioning for onboarding (token has the permissions; endpoint paths behind authenticated docs).
- Server-side incremental time filter for the detections poll (param name behind authenticated docs).
