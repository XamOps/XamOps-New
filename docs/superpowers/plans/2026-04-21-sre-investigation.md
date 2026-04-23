# SRE Investigation Feature — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AI-powered root cause investigation to XamOps — customers click "Investigate" on any alert or K8s resource, XamOps triggers OpenSRE (Docker sidecar), and receives a structured report with summary, root cause, and fix recommendations.

**Architecture:** XamOps Spring Boot backend creates an async investigation job, calls OpenSRE's LangGraph REST API (`POST /threads`, `POST /threads/{id}/runs`, `GET /threads/{id}/state`), polls for completion, stores the report in two new DB tables (`sre_investigation`, `sre_notification`), and notifies the customer via email and in-app bell. The React frontend adds an "Investigate" button to existing alert/K8s pages and two new pages for the investigations list and report detail.

**Tech Stack:** Java 17, Spring Boot, JPA/Hibernate, Lombok, RestTemplate, @Async; React/TypeScript, axios (apiClient); OpenSRE (Docker, LangGraph API port 2024); Gemini 2.0 Flash (free tier via `GEMINI_API_KEY`); PostgreSQL.

---

## File Map

### New Backend Files
| File | Responsibility |
|------|---------------|
| `domain/SreInvestigation.java` | JPA entity — investigation job + stored report fields |
| `domain/SreNotification.java` | JPA entity — in-app bell notifications |
| `repository/SreInvestigationRepository.java` | JPA queries for investigations |
| `repository/SreNotificationRepository.java` | JPA queries for notifications |
| `dto/sre/TriggerInvestigationRequest.java` | POST body: resourceType, resourceId, resourceName, cloudAccountId |
| `dto/sre/SreInvestigationDto.java` | API response: all investigation fields |
| `dto/sre/SreNotificationDto.java` | API response: notification fields |
| `service/sre/OpenSreClient.java` | HTTP client for LangGraph API — createThread, startRun, getState |
| `service/sre/SreInvestigationService.java` | Core service — create job, async worker, parse report, save |
| `service/sre/SreAlertListener.java` | @EventListener — auto-trigger on HIGH/CRITICAL alerts |
| `service/sre/SreNotificationService.java` | Create SreNotification + send email via EmailService |
| `controller/sre/SreInvestigationController.java` | REST endpoints for investigations and notifications |

### Modified Backend Files
| File | Change |
|------|--------|
| `config/AsyncConfig.java` | Verify `@EnableAsync` — no change needed if present |

### New Frontend Files
| File | Responsibility |
|------|---------------|
| `hooks/useSreInvestigation.ts` | Trigger investigation, poll status, fetch report |
| `pages/SreInvestigationsPage.tsx` | List all investigations with status badges |
| `pages/SreInvestigationDetailPage.tsx` | Full report: summary, timeline, root cause, recommendations |

### Modified Frontend Files
| File | Change |
|------|--------|
| `App.tsx` | Add `/sre/investigations` and `/sre/investigations/:id` routes + sidebar entry |
| `pages/AlertsPage.tsx` | Add Investigate button column |
| `pages/GcpAlertsPage.tsx` | Add Investigate button column |
| `pages/AzureAlertsPage.tsx` | Add Investigate button column |
| `pages/CloudK8sPage.tsx` | Add Investigate button to pod rows |
| `pages/EksDetailsPage.tsx` | Add Investigate button to pod rows |

### New Infrastructure File
| File | Responsibility |
|------|---------------|
| `opensre/docker-compose.opensre.yml` | OpenSRE sidecar service definition |
| `opensre/.env.example` | Required env vars for OpenSRE |

---

## Task 1: OpenSRE Docker Setup

**Files:**
- Create: `opensre/docker-compose.opensre.yml`
- Create: `opensre/.env.example`

- [ ] **Step 1: Create the opensre directory**

```bash
mkdir -p /Users/apple/Desktop/XamOps-New/opensre
```

- [ ] **Step 2: Create docker-compose file**

```yaml
# opensre/docker-compose.opensre.yml
version: "3.8"

services:
  opensre:
    image: langchain/langgraph-api:3.11
    container_name: xamops-opensre
    ports:
      - "2024:2024"
    environment:
      - LANGSERVE_GRAPHS={"agent":"/deps/agent/app/graph_pipeline.py:build_graph"}
      - LLM_PROVIDER=gemini
      - GEMINI_API_KEY=${GEMINI_API_KEY}
      - OPENSRE_DISABLE_KEYRING=1
      - ENV=production
    volumes:
      - ./opensre-source:/deps/agent
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:2024/ok', timeout=5)"]
      interval: 30s
      timeout: 10s
      start_period: 60s
      retries: 3
    restart: unless-stopped
```

- [ ] **Step 3: Create .env.example**

```bash
# opensre/.env.example
GEMINI_API_KEY=your_gemini_api_key_here
```

- [ ] **Step 4: Clone OpenSRE source**

```bash
cd /Users/apple/Desktop/XamOps-New/opensre
git clone --depth=1 https://github.com/Tracer-Cloud/opensre.git opensre-source
```

- [ ] **Step 5: Add .gitignore entry for opensre source**

Add to `/Users/apple/Desktop/XamOps-New/.gitignore`:
```
opensre/opensre-source/
.superpowers/
```

- [ ] **Step 6: Commit**

```bash
git add opensre/docker-compose.opensre.yml opensre/.env.example .gitignore
git commit -m "feat: add OpenSRE Docker sidecar config"
```

---

## Task 2: DB Migration — Two New Tables

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V{next}__add_sre_investigation_tables.sql`

- [ ] **Step 1: Find the latest migration version**

```bash
ls xamops-service/src/main/resources/db/migration/ | sort | tail -3
```

Note the highest version number (e.g. `V42`) — use the next one (e.g. `V43`).

- [ ] **Step 2: Create migration file**

Replace `V43` with the actual next version number.

```sql
-- V43__add_sre_investigation_tables.sql

CREATE SEQUENCE sre_investigation_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE sre_notification_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE sre_investigation (
    id                      BIGINT PRIMARY KEY DEFAULT nextval('sre_investigation_id_seq'),
    client_id               BIGINT NOT NULL REFERENCES client(id),
    cloud_account_id        BIGINT REFERENCES cloud_account(id),
    trigger_type            VARCHAR(10) NOT NULL CHECK (trigger_type IN ('AUTO', 'MANUAL')),
    resource_type           VARCHAR(20) NOT NULL CHECK (resource_type IN ('K8S_POD', 'EC2', 'RDS', 'AZURE_VM', 'GCP_VM')),
    resource_id             VARCHAR(255) NOT NULL,
    resource_name           VARCHAR(255),
    status                  VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    alert_payload           TEXT,
    report_summary          TEXT,
    report_root_cause       TEXT,
    report_recommendations  TEXT,
    report_full_markdown    TEXT,
    opensre_thread_id       VARCHAR(255),
    error_message           TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at              TIMESTAMP,
    completed_at            TIMESTAMP
);

CREATE INDEX idx_sre_investigation_client_id ON sre_investigation(client_id);
CREATE INDEX idx_sre_investigation_status ON sre_investigation(status);

CREATE TABLE sre_notification (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('sre_notification_id_seq'),
    client_id           BIGINT NOT NULL REFERENCES client(id),
    investigation_id    BIGINT NOT NULL REFERENCES sre_investigation(id),
    title               VARCHAR(500) NOT NULL,
    message             TEXT,
    is_read             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sre_notification_client_id ON sre_notification(client_id);
CREATE INDEX idx_sre_notification_is_read ON sre_notification(client_id, is_read);
```

- [ ] **Step 3: Start the app and verify migration runs cleanly**

```bash
cd xamops-service && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | grep -E "Flyway|sre_invest|ERROR" | head -20
```

Expected: `Successfully applied 1 migration` — no ERROR lines.

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/
git commit -m "feat: add sre_investigation and sre_notification DB tables"
```

---

## Task 3: Domain Entities

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/SreNotification.java`

- [ ] **Step 1: Create SreInvestigation entity**

```java
// xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "sre_investigation")
public class SreInvestigation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sre_inv_seq_gen")
    @SequenceGenerator(name = "sre_inv_seq_gen", sequenceName = "sre_investigation_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InvestigationStatus status = InvestigationStatus.PENDING;

    @Column(name = "alert_payload", columnDefinition = "TEXT")
    private String alertPayload;

    @Column(name = "report_summary", columnDefinition = "TEXT")
    private String reportSummary;

    @Column(name = "report_root_cause", columnDefinition = "TEXT")
    private String reportRootCause;

    @Column(name = "report_recommendations", columnDefinition = "TEXT")
    private String reportRecommendations;

    @Column(name = "report_full_markdown", columnDefinition = "TEXT")
    private String reportFullMarkdown;

    @Column(name = "opensre_thread_id")
    private String opensreThreadId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum TriggerType { AUTO, MANUAL }

    public enum ResourceType { K8S_POD, EC2, RDS, AZURE_VM, GCP_VM }

    public enum InvestigationStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
```

- [ ] **Step 2: Create SreNotification entity**

```java
// xamops-service/src/main/java/com/xammer/cloud/domain/SreNotification.java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "sre_notification")
public class SreNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sre_notif_seq_gen")
    @SequenceGenerator(name = "sre_notif_seq_gen", sequenceName = "sre_notification_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 3: Compile to verify**

```bash
cd xamops-service && ./mvnw compile -q 2>&1 | grep -E "ERROR|error:" | head -10
```

Expected: no output (clean compile).

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java \
        xamops-service/src/main/java/com/xammer/cloud/domain/SreNotification.java
git commit -m "feat: add SreInvestigation and SreNotification domain entities"
```

---

## Task 4: Repositories

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/SreInvestigationRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/SreNotificationRepository.java`

- [ ] **Step 1: Write the failing test**

```java
// xamops-service/src/test/java/com/xammer/cloud/repository/SreInvestigationRepositoryTest.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreInvestigation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SreInvestigationRepositoryTest {

    @Autowired
    private SreInvestigationRepository repo;

    @Test
    void findByClientId_returnsOnlyMatchingRows() {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(42L);
        inv.setResourceType(SreInvestigation.ResourceType.K8S_POD);
        inv.setResourceId("pod-abc");
        inv.setTriggerType(SreInvestigation.TriggerType.MANUAL);
        repo.save(inv);

        List<SreInvestigation> results = repo.findByClientIdOrderByCreatedAtDesc(42L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResourceId()).isEqualTo("pod-abc");
    }

    @Test
    void findByClientIdAndStatus_filtersCorrectly() {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(1L);
        inv.setResourceType(SreInvestigation.ResourceType.EC2);
        inv.setResourceId("i-123");
        inv.setTriggerType(SreInvestigation.TriggerType.AUTO);
        inv.setStatus(SreInvestigation.InvestigationStatus.RUNNING);
        repo.save(inv);

        List<SreInvestigation> running = repo.findByClientIdAndStatus(1L, SreInvestigation.InvestigationStatus.RUNNING);
        assertThat(running).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd xamops-service && ./mvnw test -pl . -Dtest=SreInvestigationRepositoryTest -q 2>&1 | tail -5
```

Expected: `FAILED` — `SreInvestigationRepository` not found.

- [ ] **Step 3: Create SreInvestigationRepository**

```java
// xamops-service/src/main/java/com/xammer/cloud/repository/SreInvestigationRepository.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreInvestigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SreInvestigationRepository extends JpaRepository<SreInvestigation, Long> {

    List<SreInvestigation> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<SreInvestigation> findByClientIdAndStatus(Long clientId, SreInvestigation.InvestigationStatus status);

    List<SreInvestigation> findByStatus(SreInvestigation.InvestigationStatus status);
}
```

- [ ] **Step 4: Create SreNotificationRepository**

```java
// xamops-service/src/main/java/com/xammer/cloud/repository/SreNotificationRepository.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SreNotificationRepository extends JpaRepository<SreNotification, Long> {

    List<SreNotification> findByClientIdAndReadFalseOrderByCreatedAtDesc(Long clientId);

    long countByClientIdAndReadFalse(Long clientId);

    List<SreNotification> findByClientIdOrderByCreatedAtDesc(Long clientId);
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd xamops-service && ./mvnw test -Dtest=SreInvestigationRepositoryTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/repository/SreInvestigationRepository.java \
        xamops-service/src/main/java/com/xammer/cloud/repository/SreNotificationRepository.java \
        xamops-service/src/test/java/com/xammer/cloud/repository/SreInvestigationRepositoryTest.java
git commit -m "feat: add SRE investigation and notification repositories"
```

---

## Task 5: DTOs

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/TriggerInvestigationRequest.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreInvestigationDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreNotificationDto.java`

- [ ] **Step 1: Create TriggerInvestigationRequest**

```java
// xamops-service/src/main/java/com/xammer/cloud/dto/sre/TriggerInvestigationRequest.java
package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SreInvestigation;
import lombok.Data;

@Data
public class TriggerInvestigationRequest {
    private Long cloudAccountId;
    private SreInvestigation.ResourceType resourceType;
    private String resourceId;
    private String resourceName;
    private String alertPayload;
}
```

- [ ] **Step 2: Create SreInvestigationDto**

```java
// xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreInvestigationDto.java
package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SreInvestigation;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class SreInvestigationDto {
    private Long id;
    private Long clientId;
    private Long cloudAccountId;
    private SreInvestigation.TriggerType triggerType;
    private SreInvestigation.ResourceType resourceType;
    private String resourceId;
    private String resourceName;
    private SreInvestigation.InvestigationStatus status;
    private String reportSummary;
    private String reportRootCause;
    private List<String> reportRecommendations;
    private String reportFullMarkdown;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    public static SreInvestigationDto from(SreInvestigation inv) {
        SreInvestigationDto dto = new SreInvestigationDto();
        dto.setId(inv.getId());
        dto.setClientId(inv.getClientId());
        dto.setCloudAccountId(inv.getCloudAccountId());
        dto.setTriggerType(inv.getTriggerType());
        dto.setResourceType(inv.getResourceType());
        dto.setResourceId(inv.getResourceId());
        dto.setResourceName(inv.getResourceName());
        dto.setStatus(inv.getStatus());
        dto.setReportSummary(inv.getReportSummary());
        dto.setReportRootCause(inv.getReportRootCause());
        dto.setReportFullMarkdown(inv.getReportFullMarkdown());
        dto.setErrorMessage(inv.getErrorMessage());
        dto.setCreatedAt(inv.getCreatedAt());
        dto.setStartedAt(inv.getStartedAt());
        dto.setCompletedAt(inv.getCompletedAt());

        if (inv.getReportRecommendations() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                dto.setReportRecommendations(mapper.readValue(inv.getReportRecommendations(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            } catch (Exception e) {
                dto.setReportRecommendations(List.of());
            }
        }
        return dto;
    }
}
```

- [ ] **Step 3: Create SreNotificationDto**

```java
// xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreNotificationDto.java
package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SreNotification;
import lombok.Data;

import java.time.Instant;

@Data
public class SreNotificationDto {
    private Long id;
    private Long investigationId;
    private String title;
    private String message;
    private boolean read;
    private Instant createdAt;

    public static SreNotificationDto from(SreNotification n) {
        SreNotificationDto dto = new SreNotificationDto();
        dto.setId(n.getId());
        dto.setInvestigationId(n.getInvestigationId());
        dto.setTitle(n.getTitle());
        dto.setMessage(n.getMessage());
        dto.setRead(n.isRead());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}
```

- [ ] **Step 4: Compile to verify**

```bash
cd xamops-service && ./mvnw compile -q 2>&1 | grep -E "ERROR|error:" | head -10
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/sre/
git commit -m "feat: add SRE investigation DTOs"
```

---

## Task 6: OpenSreClient — HTTP Client for LangGraph API

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/OpenSreClient.java`

- [ ] **Step 1: Write the failing test**

```java
// xamops-service/src/test/java/com/xammer/cloud/service/sre/OpenSreClientTest.java
package com.xammer.cloud.service.sre;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenSreClientTest {

    private RestTemplate restTemplate;
    private OpenSreClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new OpenSreClient(restTemplate, "http://localhost:2024");
    }

    @Test
    void createThread_returnsThreadId() {
        when(restTemplate.postForObject(eq("http://localhost:2024/threads"), any(), eq(java.util.Map.class)))
            .thenReturn(java.util.Map.of("thread_id", "thread-abc-123"));

        String threadId = client.createThread();
        assertThat(threadId).isEqualTo("thread-abc-123");
    }

    @Test
    void isCompleted_returnsTrueWhenStatusSuccess() {
        when(restTemplate.getForObject(eq("http://localhost:2024/threads/t1/state"), eq(java.util.Map.class)))
            .thenReturn(java.util.Map.of("status", "success", "values", java.util.Map.of()));

        assertThat(client.isCompleted("t1")).isTrue();
    }

    @Test
    void isCompleted_returnsFalseWhenStatusRunning() {
        when(restTemplate.getForObject(eq("http://localhost:2024/threads/t1/state"), eq(java.util.Map.class)))
            .thenReturn(java.util.Map.of("status", "pending", "values", java.util.Map.of()));

        assertThat(client.isCompleted("t1")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd xamops-service && ./mvnw test -Dtest=OpenSreClientTest -q 2>&1 | tail -5
```

Expected: `FAILED` — `OpenSreClient` not found.

- [ ] **Step 3: Implement OpenSreClient**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/OpenSreClient.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenSreClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenSreClient.class);

    private final RestTemplate restTemplate;
    private final String openSreBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenSreClient(RestTemplate restTemplate,
                         @Value("${opensre.url:http://localhost:2024}") String openSreBaseUrl) {
        this.restTemplate = restTemplate;
        this.openSreBaseUrl = openSreBaseUrl;
    }

    public String createThread() {
        Map<?, ?> response = restTemplate.postForObject(openSreBaseUrl + "/threads", Map.of(), Map.class);
        if (response == null || !response.containsKey("thread_id")) {
            throw new RuntimeException("OpenSRE /threads returned no thread_id");
        }
        return (String) response.get("thread_id");
    }

    public void startRun(String threadId, Map<String, Object> input) {
        Map<String, Object> body = new HashMap<>();
        body.put("input", input);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        restTemplate.postForObject(
            openSreBaseUrl + "/threads/" + threadId + "/runs",
            request,
            Map.class
        );
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getState(String threadId) {
        Map<?, ?> response = restTemplate.getForObject(
            openSreBaseUrl + "/threads/" + threadId + "/state",
            Map.class
        );
        if (response == null) return Map.of();
        return (Map<String, Object>) response;
    }

    public boolean isCompleted(String threadId) {
        Map<String, Object> state = getState(threadId);
        String status = (String) state.getOrDefault("status", "");
        return "success".equals(status);
    }

    public boolean isFailed(String threadId) {
        Map<String, Object> state = getState(threadId);
        String status = (String) state.getOrDefault("status", "");
        return "error".equals(status);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getValues(String threadId) {
        Map<String, Object> state = getState(threadId);
        Object values = state.get("values");
        if (values instanceof Map) return (Map<String, Object>) values;
        return Map.of();
    }

    public boolean isHealthy() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(openSreBaseUrl + "/ok", Map.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("OpenSRE health check failed: {}", e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 4: Add opensre.url to application.properties**

Open `xamops-service/src/main/resources/application.properties` and add:
```properties
opensre.url=http://localhost:2024
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd xamops-service && ./mvnw test -Dtest=OpenSreClientTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/OpenSreClient.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/OpenSreClientTest.java \
        xamops-service/src/main/resources/application.properties
git commit -m "feat: add OpenSreClient for LangGraph API communication"
```

---

## Task 7: SreInvestigationService — Core Async Service

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java`

- [ ] **Step 1: Write the failing test**

```java
// xamops-service/src/test/java/com/xammer/cloud/service/sre/SreInvestigationServiceTest.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.SreInvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SreInvestigationServiceTest {

    private SreInvestigationRepository investigationRepo;
    private SreInvestigationService service;

    @BeforeEach
    void setUp() {
        investigationRepo = mock(SreInvestigationRepository.class);
        service = new SreInvestigationService(
            investigationRepo,
            mock(com.xammer.cloud.repository.SreNotificationRepository.class),
            mock(OpenSreClient.class),
            mock(com.xammer.cloud.repository.CloudAccountRepository.class),
            mock(com.xammer.cloud.repository.ObservabilityConfigRepository.class),
            mock(SreNotificationService.class),
            mock(com.xammer.cloud.service.AwsClientProvider.class)
        );
    }

    @Test
    void createInvestigation_savesPendingJob() {
        SreInvestigation saved = new SreInvestigation();
        saved.setId(1L);
        saved.setStatus(SreInvestigation.InvestigationStatus.PENDING);
        when(investigationRepo.save(any())).thenReturn(saved);

        TriggerInvestigationRequest req = new TriggerInvestigationRequest();
        req.setResourceType(SreInvestigation.ResourceType.K8S_POD);
        req.setResourceId("payments-service-7d9f");
        req.setResourceName("payments-service");
        req.setCloudAccountId(10L);

        SreInvestigation result = service.createInvestigation(1L, req, SreInvestigation.TriggerType.MANUAL);

        assertThat(result.getStatus()).isEqualTo(SreInvestigation.InvestigationStatus.PENDING);
        verify(investigationRepo).save(any(SreInvestigation.class));
    }

    @Test
    void listForClient_delegatesToRepository() {
        when(investigationRepo.findByClientIdOrderByCreatedAtDesc(42L)).thenReturn(List.of());
        service.listForClient(42L);
        verify(investigationRepo).findByClientIdOrderByCreatedAtDesc(42L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd xamops-service && ./mvnw test -Dtest=SreInvestigationServiceTest -q 2>&1 | tail -5
```

Expected: `FAILED` — `SreInvestigationService` not found.

- [ ] **Step 3: Implement SreInvestigationService**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.repository.SreInvestigationRepository;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsCredentials;

import java.time.Instant;
import java.util.*;

@Service
public class SreInvestigationService {

    private static final Logger logger = LoggerFactory.getLogger(SreInvestigationService.class);
    private static final int POLL_INTERVAL_MS = 5000;
    private static final int MAX_POLLS = 60; // 5 min timeout

    private final SreInvestigationRepository investigationRepo;
    private final SreNotificationRepository notificationRepo;
    private final OpenSreClient openSreClient;
    private final CloudAccountRepository cloudAccountRepo;
    private final ObservabilityConfigRepository observabilityConfigRepo;
    private final SreNotificationService notificationService;
    private final AwsClientProvider awsClientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SreInvestigationService(SreInvestigationRepository investigationRepo,
                                   SreNotificationRepository notificationRepo,
                                   OpenSreClient openSreClient,
                                   CloudAccountRepository cloudAccountRepo,
                                   ObservabilityConfigRepository observabilityConfigRepo,
                                   SreNotificationService notificationService,
                                   AwsClientProvider awsClientProvider) {
        this.investigationRepo = investigationRepo;
        this.notificationRepo = notificationRepo;
        this.openSreClient = openSreClient;
        this.cloudAccountRepo = cloudAccountRepo;
        this.observabilityConfigRepo = observabilityConfigRepo;
        this.notificationService = notificationService;
        this.awsClientProvider = awsClientProvider;
    }

    @Transactional
    public SreInvestigation createInvestigation(Long clientId,
                                                TriggerInvestigationRequest req,
                                                SreInvestigation.TriggerType triggerType) {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(clientId);
        inv.setCloudAccountId(req.getCloudAccountId());
        inv.setTriggerType(triggerType);
        inv.setResourceType(req.getResourceType());
        inv.setResourceId(req.getResourceId());
        inv.setResourceName(req.getResourceName());
        inv.setAlertPayload(req.getAlertPayload());
        inv.setStatus(SreInvestigation.InvestigationStatus.PENDING);
        SreInvestigation saved = investigationRepo.save(inv);
        runInvestigationAsync(saved.getId(), clientId);
        return saved;
    }

    public List<SreInvestigation> listForClient(Long clientId) {
        return investigationRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    public Optional<SreInvestigation> getById(Long id, Long clientId) {
        return investigationRepo.findById(id)
            .filter(inv -> inv.getClientId().equals(clientId));
    }

    @Async
    public void runInvestigationAsync(Long investigationId, Long clientId) {
        SreInvestigation inv = investigationRepo.findById(investigationId).orElse(null);
        if (inv == null) return;

        inv.setStatus(SreInvestigation.InvestigationStatus.RUNNING);
        inv.setStartedAt(Instant.now());
        investigationRepo.save(inv);

        try {
            Map<String, Object> input = buildOpenSreInput(inv);
            String threadId = openSreClient.createThread();
            inv.setOpensreThreadId(threadId);
            investigationRepo.save(inv);

            openSreClient.startRun(threadId, input);

            int polls = 0;
            while (polls < MAX_POLLS) {
                Thread.sleep(POLL_INTERVAL_MS);
                polls++;

                if (openSreClient.isCompleted(threadId)) {
                    Map<String, Object> values = openSreClient.getValues(threadId);
                    storeReport(inv, values);
                    inv.setStatus(SreInvestigation.InvestigationStatus.COMPLETED);
                    inv.setCompletedAt(Instant.now());
                    investigationRepo.save(inv);
                    notificationService.notifyComplete(inv, clientId);
                    return;
                }

                if (openSreClient.isFailed(threadId)) {
                    throw new RuntimeException("OpenSRE investigation failed");
                }
            }
            throw new RuntimeException("Investigation timed out after 5 minutes");

        } catch (Exception e) {
            logger.error("SRE investigation {} failed: {}", investigationId, e.getMessage());
            inv.setStatus(SreInvestigation.InvestigationStatus.FAILED);
            inv.setErrorMessage(e.getMessage());
            inv.setCompletedAt(Instant.now());
            investigationRepo.save(inv);
            notificationService.notifyFailed(inv, clientId);
        }
    }

    @SuppressWarnings("unchecked")
    private void storeReport(SreInvestigation inv, Map<String, Object> values) {
        inv.setReportSummary((String) values.getOrDefault("summary", ""));
        inv.setReportRootCause((String) values.getOrDefault("root_cause", ""));
        inv.setReportFullMarkdown((String) values.getOrDefault("report", ""));

        Object recs = values.get("investigation_recommendations");
        if (recs instanceof List) {
            try {
                inv.setReportRecommendations(objectMapper.writeValueAsString(recs));
            } catch (Exception ignored) {}
        }
    }

    private Map<String, Object> buildOpenSreInput(SreInvestigation inv) {
        Map<String, Object> input = new HashMap<>();
        input.put("mode", "investigation");
        input.put("alert_name", inv.getResourceName() + " " + inv.getResourceType().name());
        input.put("pipeline_name", resolvePipelineName(inv.getResourceType()));
        input.put("severity", "critical");
        input.put("raw_alert", Map.of(
            "resourceType", inv.getResourceType().name(),
            "resourceId", inv.getResourceId(),
            "resourceName", inv.getResourceName() != null ? inv.getResourceName() : inv.getResourceId()
        ));

        Map<String, Object> integrations = new HashMap<>();
        buildObservabilityIntegration(inv.getClientId(), integrations);
        if (inv.getCloudAccountId() != null) {
            buildCloudIntegration(inv.getCloudAccountId(), inv.getResourceType(), integrations);
        }
        input.put("resolved_integrations", integrations);
        return input;
    }

    private String resolvePipelineName(SreInvestigation.ResourceType resourceType) {
        return switch (resourceType) {
            case K8S_POD -> "kubernetes";
            case EC2, RDS -> "aws";
            case AZURE_VM -> "azure";
            case GCP_VM -> "gcp";
        };
    }

    private void buildObservabilityIntegration(Long clientId, Map<String, Object> integrations) {
        observabilityConfigRepo.findByClientId(clientId).ifPresent(obs -> {
            Map<String, Object> oo = new HashMap<>();
            oo.put("base_url", System.getenv().getOrDefault("OPENOBSERVE_EXTERNAL_URL", "http://openobserve:5080"));
            oo.put("org", obs.getO2OrgId());
            oo.put("api_token", obs.getO2ApiKey());
            oo.put("connection_verified", true);
            integrations.put("openobserve", oo);
        });
    }

    private void buildCloudIntegration(Long cloudAccountId,
                                       SreInvestigation.ResourceType resourceType,
                                       Map<String, Object> integrations) {
        cloudAccountRepo.findById(cloudAccountId).ifPresent(account -> {
            if ("AWS".equals(account.getProvider())) {
                buildAwsIntegration(account, resourceType, integrations);
            } else if ("AZURE".equals(account.getProvider())) {
                buildAzureIntegration(account, integrations);
            }
        });
    }

    private void buildAwsIntegration(CloudAccount account,
                                     SreInvestigation.ResourceType resourceType,
                                     Map<String, Object> integrations) {
        try {
            AwsCredentials creds = awsClientProvider.getCredentialsProvider(account).resolveCredentials();
            String region = account.getRegion() != null ? account.getRegion() : "us-east-1";

            if (resourceType == SreInvestigation.ResourceType.K8S_POD) {
                Map<String, Object> eks = new HashMap<>();
                eks.put("aws_access_key_id", creds.accessKeyId());
                eks.put("aws_secret_access_key", creds.secretAccessKey());
                eks.put("region", region);
                integrations.put("eks", eks);
            }

            Map<String, Object> cw = new HashMap<>();
            cw.put("aws_access_key_id", creds.accessKeyId());
            cw.put("aws_secret_access_key", creds.secretAccessKey());
            cw.put("region", region);
            integrations.put("cloudwatch", cw);
        } catch (Exception e) {
            logger.warn("Could not resolve AWS credentials for account {}: {}", account.getId(), e.getMessage());
        }
    }

    private void buildAzureIntegration(CloudAccount account, Map<String, Object> integrations) {
        if (account.getAzureSubscriptionId() != null) {
            Map<String, Object> azure = new HashMap<>();
            azure.put("subscription_id", account.getAzureSubscriptionId());
            azure.put("tenant_id", account.getAzureTenantId());
            azure.put("client_id", account.getAzureClientId());
            integrations.put("azure_monitor", azure);
        }
    }
}
```

- [ ] **Step 4: Add `getRegion()` to CloudAccount if missing**

Check `CloudAccount.java` — if there's no `region` field, add it:
```java
@Column
private String region;
```

- [ ] **Step 5: Run tests**

```bash
cd xamops-service && ./mvnw test -Dtest=SreInvestigationServiceTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/SreInvestigationServiceTest.java
git commit -m "feat: add SreInvestigationService with async OpenSRE polling"
```

---

## Task 8: SreNotificationService

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreNotificationService.java`

- [ ] **Step 1: Write the failing test**

```java
// xamops-service/src/test/java/com/xammer/cloud/service/sre/SreNotificationServiceTest.java
package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.repository.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SreNotificationServiceTest {

    private SreNotificationRepository notificationRepo;
    private EmailService emailService;
    private ClientRepository clientRepo;
    private SreNotificationService service;

    @BeforeEach
    void setUp() {
        notificationRepo = mock(SreNotificationRepository.class);
        emailService = mock(EmailService.class);
        clientRepo = mock(ClientRepository.class);
        service = new SreNotificationService(notificationRepo, emailService, clientRepo);
    }

    @Test
    void notifyComplete_savesNotificationAndSendsEmail() {
        Client client = new Client("test-client");
        client.setEmail("user@example.com");
        when(clientRepo.findById(1L)).thenReturn(Optional.of(client));

        SreInvestigation inv = new SreInvestigation();
        inv.setId(10L);
        inv.setResourceName("payments-service");
        inv.setResourceType(SreInvestigation.ResourceType.K8S_POD);
        inv.setReportSummary("OOMKilled due to memory limit");

        service.notifyComplete(inv, 1L);

        verify(notificationRepo).save(any(SreNotification.class));
        verify(emailService).sendHtmlEmail(eq("user@example.com"), contains("payments-service"), anyString());
    }

    @Test
    void notifyFailed_savesNotificationWithoutEmail() {
        Client client = new Client("test-client");
        client.setEmail("user@example.com");
        when(clientRepo.findById(1L)).thenReturn(Optional.of(client));

        SreInvestigation inv = new SreInvestigation();
        inv.setId(11L);
        inv.setResourceName("api-service");
        inv.setResourceType(SreInvestigation.ResourceType.EC2);
        inv.setErrorMessage("Timed out");

        service.notifyFailed(inv, 1L);

        verify(notificationRepo).save(any(SreNotification.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd xamops-service && ./mvnw test -Dtest=SreNotificationServiceTest -q 2>&1 | tail -5
```

Expected: `FAILED`

- [ ] **Step 3: Implement SreNotificationService**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/SreNotificationService.java
package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SreNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(SreNotificationService.class);

    private final SreNotificationRepository notificationRepo;
    private final EmailService emailService;
    private final ClientRepository clientRepo;

    public SreNotificationService(SreNotificationRepository notificationRepo,
                                  EmailService emailService,
                                  ClientRepository clientRepo) {
        this.notificationRepo = notificationRepo;
        this.emailService = emailService;
        this.clientRepo = clientRepo;
    }

    public void notifyComplete(SreInvestigation inv, Long clientId) {
        SreNotification notification = new SreNotification();
        notification.setClientId(clientId);
        notification.setInvestigationId(inv.getId());
        notification.setTitle("Investigation Complete: " + inv.getResourceName());
        notification.setMessage(inv.getReportSummary() != null ? inv.getReportSummary() : "Root cause analysis is ready.");
        notificationRepo.save(notification);

        clientRepo.findById(clientId).ifPresent(client -> {
            if (client.getEmail() != null && !client.getEmail().isBlank()) {
                try {
                    emailService.sendHtmlEmail(
                        client.getEmail(),
                        "SRE Investigation Complete: " + inv.getResourceName(),
                        buildCompleteEmailHtml(inv)
                    );
                } catch (Exception e) {
                    logger.warn("Failed to send SRE notification email to {}: {}", client.getEmail(), e.getMessage());
                }
            }
        });
    }

    public void notifyFailed(SreInvestigation inv, Long clientId) {
        SreNotification notification = new SreNotification();
        notification.setClientId(clientId);
        notification.setInvestigationId(inv.getId());
        notification.setTitle("Investigation Failed: " + inv.getResourceName());
        notification.setMessage("Investigation could not complete: " + inv.getErrorMessage());
        notificationRepo.save(notification);
    }

    private String buildCompleteEmailHtml(SreInvestigation inv) {
        String summary = inv.getReportSummary() != null ? inv.getReportSummary() : "See full report for details.";
        String rootCause = inv.getReportRootCause() != null ? inv.getReportRootCause() : "N/A";
        return """
            <html><body style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;padding:20px;">
            <h2 style="color:#3b82f6;">SRE Investigation Complete</h2>
            <p><strong>Resource:</strong> %s (%s)</p>
            <h3>Summary</h3>
            <p>%s</p>
            <h3>Root Cause</h3>
            <p>%s</p>
            <p><a href="%s/sre/investigations/%d" style="background:#3b82f6;color:white;padding:10px 20px;border-radius:6px;text-decoration:none;">View Full Report</a></p>
            </body></html>
            """.formatted(
                inv.getResourceName(), inv.getResourceType().name(),
                summary, rootCause,
                System.getenv().getOrDefault("XAMOPS_BASE_URL", "https://app.xamops.com"),
                inv.getId()
            );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd xamops-service && ./mvnw test -Dtest=SreNotificationServiceTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/SreNotificationService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/SreNotificationServiceTest.java
git commit -m "feat: add SreNotificationService for in-app and email notifications"
```

---

## Task 9: SreAlertListener — Auto-Trigger

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreAlertListener.java`

- [ ] **Step 1: Find existing alert webhook/ingestion entry point**

```bash
grep -rn "GrafanaWebhookDto\|AlertDto\|webhook\|alertName\|alert_name" \
  xamops-service/src/main/java/com/xammer/cloud/controller/ | grep -v ".class" | head -10
```

Note the controller that receives incoming alerts — this is where SreAlertListener needs to hook in.

- [ ] **Step 2: Create SreAlertListener as a Spring Event listener**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/SreAlertListener.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Listens for SreAlertEvent and auto-triggers an investigation for
 * HIGH/CRITICAL alerts on supported resource types.
 */
@Component
public class SreAlertListener {

    private static final Logger logger = LoggerFactory.getLogger(SreAlertListener.class);
    private static final Set<String> AUTO_TRIGGER_SEVERITIES = Set.of("CRITICAL", "HIGH", "critical", "high");

    private final SreInvestigationService investigationService;
    private final CloudAccountRepository cloudAccountRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SreAlertListener(SreInvestigationService investigationService,
                            CloudAccountRepository cloudAccountRepo) {
        this.investigationService = investigationService;
        this.cloudAccountRepo = cloudAccountRepo;
    }

    @Async
    @EventListener
    public void onAlert(SreAlertEvent event) {
        if (!AUTO_TRIGGER_SEVERITIES.contains(event.getSeverity())) {
            return;
        }
        logger.info("Auto-triggering SRE investigation for alert: {} client: {}",
            event.getResourceId(), event.getClientId());

        TriggerInvestigationRequest req = new TriggerInvestigationRequest();
        req.setResourceType(event.getResourceType());
        req.setResourceId(event.getResourceId());
        req.setResourceName(event.getResourceName());
        req.setCloudAccountId(event.getCloudAccountId());
        req.setAlertPayload(event.getAlertPayload());

        investigationService.createInvestigation(
            event.getClientId(), req, SreInvestigation.TriggerType.AUTO
        );
    }
}
```

- [ ] **Step 3: Create SreAlertEvent**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/SreAlertEvent.java
package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class SreAlertEvent extends ApplicationEvent {

    private final Long clientId;
    private final Long cloudAccountId;
    private final SreInvestigation.ResourceType resourceType;
    private final String resourceId;
    private final String resourceName;
    private final String severity;
    private final String alertPayload;

    public SreAlertEvent(Object source, Long clientId, Long cloudAccountId,
                         SreInvestigation.ResourceType resourceType,
                         String resourceId, String resourceName,
                         String severity, String alertPayload) {
        super(source);
        this.clientId = clientId;
        this.cloudAccountId = cloudAccountId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.severity = severity;
        this.alertPayload = alertPayload;
    }
}
```

- [ ] **Step 4: Publish event from existing Grafana/alert webhook controller**

Open the controller that handles incoming alerts (found in Step 1). Inject `ApplicationEventPublisher` and publish `SreAlertEvent` when a critical alert arrives. Example — in the relevant method add:

```java
// Inject in constructor:
private final ApplicationEventPublisher eventPublisher;

// In alert handler method, after processing alert:
if ("CRITICAL".equalsIgnoreCase(alert.getSeverity()) || "HIGH".equalsIgnoreCase(alert.getSeverity())) {
    SreInvestigation.ResourceType resourceType = resolveResourceType(alert);
    if (resourceType != null) {
        eventPublisher.publishEvent(new SreAlertEvent(
            this, clientId, cloudAccountId, resourceType,
            alert.getResourceId(), alert.getResourceName(),
            alert.getSeverity(), objectMapper.writeValueAsString(alert)
        ));
    }
}
```

Add a private helper `resolveResourceType` that maps alert labels/annotations to `SreInvestigation.ResourceType` — return `null` for unsupported types (no investigation triggered).

- [ ] **Step 5: Compile**

```bash
cd xamops-service && ./mvnw compile -q 2>&1 | grep -E "ERROR|error:" | head -10
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/SreAlertListener.java \
        xamops-service/src/main/java/com/xammer/cloud/service/sre/SreAlertEvent.java
git commit -m "feat: add SreAlertListener for auto-triggered investigations"
```

---

## Task 10: SreInvestigationController — REST API

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java`

- [ ] **Step 1: Create the controller**

```java
// xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java
package com.xammer.cloud.controller.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.dto.sre.SreInvestigationDto;
import com.xammer.cloud.dto.sre.SreNotificationDto;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.sre.SreInvestigationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sre")
public class SreInvestigationController {

    private static final Logger logger = LoggerFactory.getLogger(SreInvestigationController.class);

    private final SreInvestigationService investigationService;
    private final SreNotificationRepository notificationRepo;

    public SreInvestigationController(SreInvestigationService investigationService,
                                      SreNotificationRepository notificationRepo) {
        this.investigationService = investigationService;
        this.notificationRepo = notificationRepo;
    }

    @PostMapping("/investigations")
    public ResponseEntity<SreInvestigationDto> trigger(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody TriggerInvestigationRequest req) {
        try {
            Long clientId = userDetails.getClientId();
            SreInvestigation inv = investigationService.createInvestigation(
                clientId, req, SreInvestigation.TriggerType.MANUAL
            );
            return ResponseEntity.ok(SreInvestigationDto.from(inv));
        } catch (Exception e) {
            logger.error("Error triggering investigation: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/investigations")
    public ResponseEntity<List<SreInvestigationDto>> list(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            List<SreInvestigationDto> dtos = investigationService.listForClient(clientId)
                .stream().map(SreInvestigationDto::from).collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            logger.error("Error listing investigations: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/investigations/{id}")
    public ResponseEntity<SreInvestigationDto> getById(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            Long clientId = userDetails.getClientId();
            return investigationService.getById(id, clientId)
                .map(inv -> ResponseEntity.ok(SreInvestigationDto.from(inv)))
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error fetching investigation {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/investigations/{id}/status")
    public ResponseEntity<Map<String, String>> getStatus(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            Long clientId = userDetails.getClientId();
            return investigationService.getById(id, clientId)
                .map(inv -> ResponseEntity.ok(Map.of("status", inv.getStatus().name())))
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<SreNotificationDto>> getNotifications(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            List<SreNotificationDto> dtos = notificationRepo
                .findByClientIdAndReadFalseOrderByCreatedAtDesc(clientId)
                .stream().map(SreNotificationDto::from).collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/notifications/count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long clientId = userDetails.getClientId();
            long count = notificationRepo.countByClientIdAndReadFalse(clientId);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @PathVariable Long id) {
        try {
            notificationRepo.findById(id).ifPresent(n -> {
                if (n.getClientId().equals(userDetails.getClientId())) {
                    n.setRead(true);
                    notificationRepo.save(n);
                }
            });
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
```

- [ ] **Step 2: Compile and run existing tests**

```bash
cd xamops-service && ./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — existing tests still pass.

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java
git commit -m "feat: add SreInvestigationController with REST endpoints"
```

---

## Task 11: Frontend Hook — useSreInvestigation

**Files:**
- Create: `frontend-app/src/hooks/useSreInvestigation.ts`

- [ ] **Step 1: Create the hook**

```typescript
// frontend-app/src/hooks/useSreInvestigation.ts
import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';

export type InvestigationStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type ResourceType = 'K8S_POD' | 'EC2' | 'RDS' | 'AZURE_VM' | 'GCP_VM';
export type TriggerType = 'AUTO' | 'MANUAL';

export interface SreInvestigation {
  id: number;
  clientId: number;
  cloudAccountId?: number;
  triggerType: TriggerType;
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  status: InvestigationStatus;
  reportSummary?: string;
  reportRootCause?: string;
  reportRecommendations?: string[];
  reportFullMarkdown?: string;
  errorMessage?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface TriggerInvestigationRequest {
  cloudAccountId?: number;
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  alertPayload?: string;
}

export interface SreNotification {
  id: number;
  investigationId: number;
  title: string;
  message?: string;
  read: boolean;
  createdAt: string;
}

export function useSreInvestigation() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerInvestigation = useCallback(async (
    req: TriggerInvestigationRequest
  ): Promise<SreInvestigation | null> => {
    setLoading(true);
    setError(null);
    try {
      const res = await apiClient.post<SreInvestigation>('/api/sre/investigations', req);
      return res.data;
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to start investigation');
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  const listInvestigations = useCallback(async (): Promise<SreInvestigation[]> => {
    const res = await apiClient.get<SreInvestigation[]>('/api/sre/investigations');
    return res.data;
  }, []);

  const getInvestigation = useCallback(async (id: number): Promise<SreInvestigation | null> => {
    try {
      const res = await apiClient.get<SreInvestigation>(`/api/sre/investigations/${id}`);
      return res.data;
    } catch {
      return null;
    }
  }, []);

  const pollStatus = useCallback(async (id: number): Promise<InvestigationStatus> => {
    const res = await apiClient.get<{ status: InvestigationStatus }>(
      `/api/sre/investigations/${id}/status`
    );
    return res.data.status;
  }, []);

  const getNotifications = useCallback(async (): Promise<SreNotification[]> => {
    const res = await apiClient.get<SreNotification[]>('/api/sre/notifications');
    return res.data;
  }, []);

  const getUnreadCount = useCallback(async (): Promise<number> => {
    const res = await apiClient.get<{ count: number }>('/api/sre/notifications/count');
    return res.data.count;
  }, []);

  const markRead = useCallback(async (notificationId: number): Promise<void> => {
    await apiClient.put(`/api/sre/notifications/${notificationId}/read`);
  }, []);

  return {
    loading,
    error,
    triggerInvestigation,
    listInvestigations,
    getInvestigation,
    pollStatus,
    getNotifications,
    getUnreadCount,
    markRead,
  };
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep -i "error" | head -10
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/hooks/useSreInvestigation.ts
git commit -m "feat: add useSreInvestigation hook"
```

---

## Task 12: Frontend — SreInvestigationsPage (List)

**Files:**
- Create: `frontend-app/src/pages/SreInvestigationsPage.tsx`

- [ ] **Step 1: Create the page**

```tsx
// frontend-app/src/pages/SreInvestigationsPage.tsx
import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSreInvestigation, SreInvestigation } from '../hooks/useSreInvestigation';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'text-yellow-400 bg-yellow-900/30 border-yellow-700',
  RUNNING: 'text-yellow-400 bg-yellow-900/30 border-yellow-700',
  COMPLETED: 'text-green-400 bg-green-900/30 border-green-700',
  FAILED: 'text-red-400 bg-red-900/30 border-red-700',
};

const STATUS_DOTS: Record<string, string> = {
  PENDING: 'bg-yellow-400 animate-pulse',
  RUNNING: 'bg-yellow-400 animate-pulse',
  COMPLETED: 'bg-green-400',
  FAILED: 'bg-red-400',
};

export default function SreInvestigationsPage() {
  const navigate = useNavigate();
  const { listInvestigations } = useSreInvestigation();
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<string>('ALL');

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      const data = await listInvestigations();
      setInvestigations(data);
      setLoading(false);
    };
    load();
    const interval = setInterval(load, 10000);
    return () => clearInterval(interval);
  }, [listInvestigations]);

  const filtered = filter === 'ALL'
    ? investigations
    : investigations.filter(i => i.status === filter);

  const counts = {
    ALL: investigations.length,
    RUNNING: investigations.filter(i => i.status === 'RUNNING' || i.status === 'PENDING').length,
    COMPLETED: investigations.filter(i => i.status === 'COMPLETED').length,
    FAILED: investigations.filter(i => i.status === 'FAILED').length,
  };

  return (
    <div className="p-6">
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="text-2xl font-bold text-white">🔍 SRE Investigations</h1>
          <p className="text-slate-400 text-sm mt-1">AI-powered root cause analysis for your infrastructure</p>
        </div>
      </div>

      <div className="flex gap-2 mb-4">
        {(['ALL', 'RUNNING', 'COMPLETED', 'FAILED'] as const).map(f => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
              filter === f
                ? 'bg-blue-900 text-blue-300 border border-blue-600'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {f === 'ALL' ? 'All' : f.charAt(0) + f.slice(1).toLowerCase()} ({counts[f]})
          </button>
        ))}
      </div>

      <div className="bg-slate-900 rounded-xl border border-slate-700 overflow-hidden">
        <div className="grid grid-cols-[2fr_1fr_1fr_1fr_1fr] px-6 py-3 text-xs text-slate-500 uppercase tracking-wider border-b border-slate-700">
          <span>Resource</span>
          <span>Type</span>
          <span>Trigger</span>
          <span>Status</span>
          <span>Time</span>
        </div>

        {loading ? (
          <div className="p-8 text-center text-slate-400">Loading investigations...</div>
        ) : filtered.length === 0 ? (
          <div className="p-8 text-center text-slate-400">No investigations found.</div>
        ) : (
          filtered.map(inv => (
            <div
              key={inv.id}
              onClick={() => inv.status === 'COMPLETED' && navigate(`/sre/investigations/${inv.id}`)}
              className={`grid grid-cols-[2fr_1fr_1fr_1fr_1fr] px-6 py-4 border-b border-slate-800 items-center
                ${inv.status === 'COMPLETED' ? 'cursor-pointer hover:bg-slate-800' : ''}`}
            >
              <div>
                <div className="text-white font-medium text-sm">{inv.resourceId}</div>
                {inv.resourceName && <div className="text-slate-400 text-xs mt-0.5">{inv.resourceName}</div>}
              </div>
              <span className="text-blue-400 text-sm">{inv.resourceType.replace('_', ' ')}</span>
              <span className="text-slate-400 text-sm">
                {inv.triggerType === 'AUTO' ? '🔔 Auto' : '👆 Manual'}
              </span>
              <span className={`inline-flex items-center gap-1.5 text-sm border rounded-full px-2.5 py-0.5 w-fit ${STATUS_COLORS[inv.status]}`}>
                <span className={`w-2 h-2 rounded-full ${STATUS_DOTS[inv.status]}`} />
                {inv.status.charAt(0) + inv.status.slice(1).toLowerCase()}
              </span>
              <span className="text-slate-400 text-sm">
                {new Date(inv.createdAt).toLocaleString()}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep "error" | head -10
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/pages/SreInvestigationsPage.tsx
git commit -m "feat: add SreInvestigationsPage with live-polling list"
```

---

## Task 13: Frontend — SreInvestigationDetailPage (Report)

**Files:**
- Create: `frontend-app/src/pages/SreInvestigationDetailPage.tsx`

- [ ] **Step 1: Create the detail page**

```tsx
// frontend-app/src/pages/SreInvestigationDetailPage.tsx
import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useSreInvestigation, SreInvestigation } from '../hooks/useSreInvestigation';
import ReactMarkdown from 'react-markdown';

export default function SreInvestigationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { getInvestigation, pollStatus } = useSreInvestigation();
  const [inv, setInv] = useState<SreInvestigation | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    const load = async () => {
      const data = await getInvestigation(Number(id));
      setInv(data);
      setLoading(false);
    };
    load();

    const interval = setInterval(async () => {
      const status = await pollStatus(Number(id));
      if (status === 'COMPLETED' || status === 'FAILED') {
        const data = await getInvestigation(Number(id));
        setInv(data);
        clearInterval(interval);
      }
    }, 5000);

    return () => clearInterval(interval);
  }, [id, getInvestigation, pollStatus]);

  if (loading) return <div className="p-8 text-slate-400">Loading investigation...</div>;
  if (!inv) return <div className="p-8 text-red-400">Investigation not found.</div>;

  const isRunning = inv.status === 'RUNNING' || inv.status === 'PENDING';

  return (
    <div className="p-6 max-w-4xl">
      <button onClick={() => navigate('/sre/investigations')}
        className="text-slate-400 hover:text-white text-sm mb-4 flex items-center gap-1">
        ← Back to Investigations
      </button>

      <div className="flex justify-between items-start mb-6">
        <div>
          <div className="flex items-center gap-3 mb-2">
            <span className="bg-red-900 text-red-300 text-xs font-bold px-2.5 py-1 rounded">
              🔴 {inv.resourceType.replace('_', ' ')}
            </span>
            <h1 className="text-xl font-bold text-white">{inv.resourceId}</h1>
          </div>
          <div className="text-slate-400 text-sm">
            {inv.triggerType === 'AUTO' ? '🔔 Auto-triggered' : '👆 Manual'} ·{' '}
            {inv.completedAt
              ? `Completed ${new Date(inv.completedAt).toLocaleString()}`
              : `Started ${new Date(inv.createdAt).toLocaleString()}`}
          </div>
        </div>
        {inv.status === 'COMPLETED' && (
          <button
            onClick={() => { setLoading(true); getInvestigation(Number(id)).then(d => { setInv(d); setLoading(false); }); }}
            className="bg-slate-700 text-slate-300 border border-slate-600 rounded-lg px-4 py-2 text-sm hover:bg-slate-600"
          >
            🔄 Re-run
          </button>
        )}
      </div>

      {isRunning && (
        <div className="bg-yellow-900/20 border border-yellow-700 rounded-xl p-6 text-center mb-6">
          <div className="text-yellow-400 text-lg font-semibold mb-2">⏳ Investigation in progress...</div>
          <div className="text-slate-400 text-sm">OpenSRE is gathering evidence. This usually takes 1-3 minutes.</div>
        </div>
      )}

      {inv.status === 'FAILED' && (
        <div className="bg-red-900/20 border border-red-700 rounded-xl p-6 mb-6">
          <div className="text-red-400 font-semibold">Investigation Failed</div>
          <div className="text-slate-400 text-sm mt-1">{inv.errorMessage}</div>
        </div>
      )}

      {inv.status === 'COMPLETED' && (
        <div className="flex flex-col gap-5">
          {inv.reportSummary && (
            <div className="bg-blue-900/20 border border-blue-700 rounded-xl p-5">
              <div className="text-blue-400 text-xs font-bold uppercase tracking-wider mb-3">📋 Summary</div>
              <p className="text-slate-200 text-sm leading-relaxed">{inv.reportSummary}</p>
            </div>
          )}

          {inv.reportRootCause && (
            <div className="bg-red-900/20 border border-red-800 rounded-xl p-5">
              <div className="text-red-400 text-xs font-bold uppercase tracking-wider mb-3">🔍 Root Cause</div>
              <p className="text-slate-200 text-sm leading-relaxed">{inv.reportRootCause}</p>
            </div>
          )}

          {inv.reportRecommendations && inv.reportRecommendations.length > 0 && (
            <div className="bg-green-900/20 border border-green-800 rounded-xl p-5">
              <div className="text-green-400 text-xs font-bold uppercase tracking-wider mb-3">✅ Recommendations</div>
              <div className="flex flex-col gap-3">
                {inv.reportRecommendations.map((rec, i) => (
                  <div key={i} className="flex gap-3 items-start">
                    <span className="bg-green-900 text-green-400 rounded px-2 py-0.5 text-xs font-bold shrink-0">{i + 1}</span>
                    <span className="text-slate-200 text-sm">{rec}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {inv.reportFullMarkdown && (
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
              <div className="text-slate-400 text-xs font-bold uppercase tracking-wider mb-3">📄 Full Report</div>
              <div className="prose prose-invert prose-sm max-w-none text-slate-300">
                <ReactMarkdown>{inv.reportFullMarkdown}</ReactMarkdown>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Install react-markdown if not already present**

```bash
cd frontend-app && npm list react-markdown 2>/dev/null | grep react-markdown || npm install react-markdown
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep "error" | head -10
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/pages/SreInvestigationDetailPage.tsx
git commit -m "feat: add SreInvestigationDetailPage with report rendering"
```

---

## Task 14: Frontend — Investigate Button on Existing Pages

**Files:**
- Create: `frontend-app/src/components/SreInvestigateButton.tsx`
- Modify: `frontend-app/src/pages/AlertsPage.tsx`
- Modify: `frontend-app/src/pages/CloudK8sPage.tsx`

- [ ] **Step 1: Create reusable InvestigateButton component**

```tsx
// frontend-app/src/components/SreInvestigateButton.tsx
import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSreInvestigation, ResourceType, TriggerInvestigationRequest } from '../hooks/useSreInvestigation';

interface Props {
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  cloudAccountId?: number;
  alertPayload?: string;
}

export default function SreInvestigateButton({
  resourceType, resourceId, resourceName, cloudAccountId, alertPayload
}: Props) {
  const navigate = useNavigate();
  const { triggerInvestigation, listInvestigations } = useSreInvestigation();
  const [investigationId, setInvestigationId] = useState<number | null>(null);
  const [status, setStatus] = useState<'idle' | 'running' | 'completed'>('idle');

  useEffect(() => {
    const checkExisting = async () => {
      const all = await listInvestigations();
      const existing = all.find(i =>
        i.resourceId === resourceId &&
        (i.status === 'RUNNING' || i.status === 'PENDING')
      );
      if (existing) { setInvestigationId(existing.id); setStatus('running'); return; }
      const completed = all.find(i => i.resourceId === resourceId && i.status === 'COMPLETED');
      if (completed) { setInvestigationId(completed.id); setStatus('completed'); }
    };
    checkExisting();
  }, [resourceId, listInvestigations]);

  const handleClick = async () => {
    if (status === 'running') return;
    if (status === 'completed' && investigationId) {
      navigate(`/sre/investigations/${investigationId}`);
      return;
    }
    const req: TriggerInvestigationRequest = {
      resourceType, resourceId, resourceName, cloudAccountId, alertPayload,
    };
    const inv = await triggerInvestigation(req);
    if (inv) { setInvestigationId(inv.id); setStatus('running'); }
  };

  if (status === 'running') {
    return (
      <button disabled className="bg-slate-800 text-yellow-400 border border-yellow-800 rounded-md px-3 py-1.5 text-xs font-semibold whitespace-nowrap">
        ⏳ Investigating…
      </button>
    );
  }
  if (status === 'completed') {
    return (
      <button onClick={handleClick} className="bg-green-900 text-green-400 border border-green-700 rounded-md px-3 py-1.5 text-xs font-semibold whitespace-nowrap hover:bg-green-800">
        ✅ View Report
      </button>
    );
  }
  return (
    <button onClick={handleClick} className="bg-blue-600 text-white rounded-md px-3 py-1.5 text-xs font-semibold whitespace-nowrap hover:bg-blue-500">
      🔍 Investigate
    </button>
  );
}
```

- [ ] **Step 2: Add Investigate button to AlertsPage**

Open `frontend-app/src/pages/AlertsPage.tsx`. Find the table row where each alert is rendered. Add a new column header `"Action"` and in each row, add:

```tsx
import SreInvestigateButton from '../components/SreInvestigateButton';

// In each alert row, add this cell:
<td>
  <SreInvestigateButton
    resourceType={resolveResourceType(alert)}
    resourceId={alert.resourceId || alert.name}
    resourceName={alert.name}
    cloudAccountId={alert.cloudAccountId}
    alertPayload={JSON.stringify(alert)}
  />
</td>
```

Add a helper `resolveResourceType` in the same file:
```tsx
function resolveResourceType(alert: any): ResourceType {
  const name = (alert.name || alert.alertName || '').toLowerCase();
  if (name.includes('pod') || name.includes('k8s') || name.includes('kubernetes')) return 'K8S_POD';
  if (name.includes('ec2') || name.includes('instance')) return 'EC2';
  if (name.includes('rds') || name.includes('database')) return 'RDS';
  if (name.includes('azure') || name.includes('vm')) return 'AZURE_VM';
  return 'EC2';
}
```

- [ ] **Step 3: Add Investigate button to CloudK8sPage**

Open `frontend-app/src/pages/CloudK8sPage.tsx`. Find where pods are listed. In each pod row, add:

```tsx
<SreInvestigateButton
  resourceType="K8S_POD"
  resourceId={pod.name}
  resourceName={pod.name}
  cloudAccountId={selectedAccount?.id}
/>
```

- [ ] **Step 4: Add Investigate button to GcpAlertsPage and AzureAlertsPage**

Repeat the same pattern as Step 2 for `GcpAlertsPage.tsx` and `AzureAlertsPage.tsx`.

- [ ] **Step 5: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep "error" | head -10
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/components/SreInvestigateButton.tsx \
        frontend-app/src/pages/AlertsPage.tsx \
        frontend-app/src/pages/CloudK8sPage.tsx \
        frontend-app/src/pages/GcpAlertsPage.tsx \
        frontend-app/src/pages/AzureAlertsPage.tsx
git commit -m "feat: add SreInvestigateButton to alerts and K8s pages"
```

---

## Task 15: Frontend — Routes and Sidebar Navigation

**Files:**
- Modify: `frontend-app/src/App.tsx`

- [ ] **Step 1: Add routes to App.tsx**

Open `frontend-app/src/App.tsx`. Find where other page routes are defined and add:

```tsx
import SreInvestigationsPage from './pages/SreInvestigationsPage';
import SreInvestigationDetailPage from './pages/SreInvestigationDetailPage';

// Inside the router/switch, add:
<Route path="/sre/investigations" element={<SreInvestigationsPage />} />
<Route path="/sre/investigations/:id" element={<SreInvestigationDetailPage />} />
```

- [ ] **Step 2: Add SRE entry to sidebar**

Find where sidebar navigation items are defined in `App.tsx` (or the sidebar component if separate). Add an entry:

```tsx
{
  label: 'SRE Investigations',
  path: '/sre/investigations',
  icon: '🔍',
}
```

Place it near the Alerts/Observability section.

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep "error" | head -10
```

Expected: no errors.

- [ ] **Step 4: Start dev server and manually verify**

```bash
cd frontend-app && npm start
```

Navigate to `http://localhost:3000/sre/investigations` — should see the investigations list page.
Navigate to a K8s or Alerts page — should see "Investigate" button on each row.

- [ ] **Step 5: Commit**

```bash
git add frontend-app/src/App.tsx
git commit -m "feat: add SRE investigations routes and sidebar navigation"
```

---

## Task 16: End-to-End Smoke Test

- [ ] **Step 1: Start OpenSRE with Gemini key**

```bash
cd opensre
cp .env.example .env
# Edit .env and set GEMINI_API_KEY=<your key from aistudio.google.com>
docker-compose -f docker-compose.opensre.yml up -d
```

- [ ] **Step 2: Verify OpenSRE is healthy**

```bash
curl http://localhost:2024/ok
```

Expected: `{"ok":true,...}`

- [ ] **Step 3: Start XamOps backend**

```bash
cd xamops-service && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

- [ ] **Step 4: Manually trigger an investigation via API**

```bash
curl -X POST http://localhost:8080/api/sre/investigations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-dev-token>" \
  -d '{
    "resourceType": "K8S_POD",
    "resourceId": "test-pod-abc",
    "resourceName": "test-service",
    "cloudAccountId": 1
  }'
```

Expected: `{"id":1,"status":"PENDING",...}`

- [ ] **Step 5: Poll status until completed**

```bash
# Run every 5s until COMPLETED
watch -n5 curl -s http://localhost:8080/api/sre/investigations/1/status \
  -H "Authorization: Bearer <your-dev-token>"
```

Expected: transitions `PENDING → RUNNING → COMPLETED`

- [ ] **Step 6: Fetch completed report**

```bash
curl http://localhost:8080/api/sre/investigations/1 \
  -H "Authorization: Bearer <your-dev-token>" | python3 -m json.tool
```

Expected: `reportSummary`, `reportRootCause`, `reportRecommendations` are populated.

- [ ] **Step 7: Verify in-app notification created**

```bash
curl http://localhost:8080/api/sre/notifications/count \
  -H "Authorization: Bearer <your-dev-token>"
```

Expected: `{"count":1}`

- [ ] **Step 8: Final commit**

```bash
git add -A
git commit -m "feat: SRE investigation feature — complete implementation"
```

---

## Summary

| Task | What it delivers |
|------|----------------|
| 1 | OpenSRE Docker sidecar configured with Gemini |
| 2 | DB migration — 2 new tables |
| 3 | JPA entities |
| 4 | Repositories with queries |
| 5 | DTOs |
| 6 | OpenSreClient — LangGraph HTTP client |
| 7 | SreInvestigationService — async investigation pipeline |
| 8 | SreNotificationService — email + in-app |
| 9 | SreAlertListener — auto-trigger |
| 10 | REST controller — 7 endpoints |
| 11 | useSreInvestigation React hook |
| 12 | Investigations list page |
| 13 | Investigation detail/report page |
| 14 | Investigate button on existing pages |
| 15 | Routes + sidebar |
| 16 | E2E smoke test |
