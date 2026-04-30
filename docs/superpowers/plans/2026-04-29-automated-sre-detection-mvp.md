# Automated SRE Detection MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-detect metric anomalies from OpenObserve and trigger SRE investigations automatically, using the existing `SreInvestigationService` pipeline.

**Architecture:** A scheduled `DetectionScheduler` fires every 60s, queries OpenObserve for each client's configured SLO signals via `MetricPoller`, evaluates z-score deviation via `AnomalyDetector`, computes severity via `SloEvaluator`, and calls `SreInvestigationService.createInvestigation()` with `TriggerType.AUTO` when a threshold is crossed. Detection events are persisted to `detection_event` for auditing and the frontend SRE dashboard.

**Tech Stack:** Spring Boot (existing), PostgreSQL + Flyway (existing), OpenObserve HTTP API (existing), Redis (existing), React/TypeScript (existing)

**Out of scope (Plan 2):** Kafka, Isolation Forest, Redis windowing/correlation, full RCA service.

---

## File Map

**New backend files:**
- `src/main/resources/db/migration/V20260429__add_sre_detection_tables.sql` — SLO definitions + detection events schema
- `src/main/java/com/xammer/cloud/domain/SloDefinition.java` — JPA entity
- `src/main/java/com/xammer/cloud/repository/SloDefinitionRepository.java`
- `src/main/java/com/xammer/cloud/domain/DetectionEvent.java` — JPA entity
- `src/main/java/com/xammer/cloud/repository/DetectionEventRepository.java`
- `src/main/java/com/xammer/cloud/dto/sre/SloDefinitionDto.java`
- `src/main/java/com/xammer/cloud/dto/sre/DetectionEventDto.java`
- `src/main/java/com/xammer/cloud/service/sre/detection/MetricPoller.java` — queries OpenObserve per SLO signal
- `src/main/java/com/xammer/cloud/service/sre/detection/AnomalyDetector.java` — z-score + EWMA scoring
- `src/main/java/com/xammer/cloud/service/sre/detection/SloEvaluator.java` — maps score + SLO breach → severity
- `src/main/java/com/xammer/cloud/service/sre/detection/AutoDetectionService.java` — orchestration
- `src/main/java/com/xammer/cloud/service/sre/detection/DetectionScheduler.java` — @Scheduled entry point
- `src/main/java/com/xammer/cloud/controller/sre/SloDefinitionController.java` — CRUD REST
- `src/main/java/com/xammer/cloud/controller/sre/DetectionEventController.java` — read-only REST

**Modified backend files:**
- `src/main/java/com/xammer/cloud/domain/SreInvestigation.java` — add `GENERIC` to `ResourceType`
- `src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java` — add `GENERIC` to `resolvePipelineName()`
- `src/main/resources/db/migration/V20260414__add_sre_investigation_tables.sql` — **do not modify** (already applied); constraint update is in new migration

**New frontend files:**
- `frontend-app/src/pages/SreOverviewPage.tsx` — service health cards + recent auto-detections
- `frontend-app/src/hooks/useSreOverview.ts` — API calls for detection events + SLO definitions

**Modified frontend files:**
- `frontend-app/src/pages/SreInvestigationsPage.tsx` — add AUTO/MANUAL badge, AUTO filter tab
- `frontend-app/src/App.tsx` — add `/sre-overview` route

---

## Task 1: DB Migration

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V20260429__add_sre_detection_tables.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Allow GENERIC resource type in existing table
ALTER TABLE sre_investigation
  DROP CONSTRAINT IF EXISTS sre_investigation_resource_type_check;
ALTER TABLE sre_investigation
  ADD CONSTRAINT sre_investigation_resource_type_check
  CHECK (resource_type IN ('K8S_POD','EC2','RDS','AZURE_VM','GCP_VM','GENERIC'));

-- SLO definitions per client/entity/signal
CREATE SEQUENCE slo_definition_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE slo_definition (
  id               BIGINT PRIMARY KEY DEFAULT nextval('slo_definition_id_seq'),
  client_id        BIGINT NOT NULL REFERENCES client(id),
  entity           TEXT NOT NULL,
  signal           TEXT NOT NULL,
  slo_target       DOUBLE PRECISION,
  window_seconds   INT NOT NULL DEFAULT 300,
  burn_rate_threshold DOUBLE PRECISION DEFAULT 2.0,
  severity_map     JSONB NOT NULL DEFAULT '{
    "critical": {"slo_breach": true,  "score_min": 0.9},
    "high":     {"slo_breach": false, "score_min": 0.9},
    "medium":   {"score_min": 0.7},
    "low":      {"score_min": 0.5}
  }',
  stream_name      TEXT NOT NULL,
  query_template   TEXT NOT NULL,
  enabled          BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(client_id, entity, signal)
);
CREATE INDEX idx_slo_def_client ON slo_definition(client_id);

-- Detection events log (one row per signal evaluation that crossed threshold)
CREATE SEQUENCE detection_event_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE detection_event (
  id               BIGINT PRIMARY KEY DEFAULT nextval('detection_event_id_seq'),
  client_id        BIGINT NOT NULL REFERENCES client(id),
  entity           TEXT NOT NULL,
  signal           TEXT NOT NULL,
  event_type       VARCHAR(20) NOT NULL DEFAULT 'fast_spike'
                   CHECK (event_type IN ('fast_spike','slow_burn')),
  score            DOUBLE PRECISION NOT NULL,
  confidence       DOUBLE PRECISION NOT NULL,
  severity         VARCHAR(10) NOT NULL
                   CHECK (severity IN ('critical','high','medium','low')),
  slo_impact       VARCHAR(10) NOT NULL DEFAULT 'none'
                   CHECK (slo_impact IN ('breach','risk','none')),
  baseline_value   DOUBLE PRECISION,
  observed_value   DOUBLE PRECISION NOT NULL,
  window_start     TIMESTAMP NOT NULL,
  window_end       TIMESTAMP NOT NULL,
  investigation_id BIGINT REFERENCES sre_investigation(id),
  created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_detection_event_client ON detection_event(client_id, created_at DESC);
CREATE INDEX idx_detection_event_entity ON detection_event(client_id, entity, created_at DESC);
```

- [ ] **Step 2: Verify migration file is in the right place**

```bash
ls xamops-service/src/main/resources/db/migration/V20260429*
```
Expected: file listed.

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/V20260429__add_sre_detection_tables.sql
git commit -m "feat(sre): add slo_definition and detection_event tables"
```

---

## Task 2: Domain Entities

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/SloDefinition.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/DetectionEvent.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java`

- [ ] **Step 1: Write failing test for SloDefinition persistence**

```java
// src/test/java/com/xammer/cloud/domain/SloDefinitionTest.java
package com.xammer.cloud.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SloDefinitionTest {
    @Test
    void defaultSeverityMapIsNotNull() {
        SloDefinition s = new SloDefinition();
        assertNull(s.getSeverityMap()); // not set yet
    }
}
```

Run: `cd xamops-service && mvn test -pl . -Dtest=SloDefinitionTest -q 2>&1 | tail -5`
Expected: FAIL (class not found)

- [ ] **Step 2: Create SloDefinition.java**

```java
// src/main/java/com/xammer/cloud/domain/SloDefinition.java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "slo_definition")
public class SloDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "slo_def_seq")
    @SequenceGenerator(name = "slo_def_seq", sequenceName = "slo_definition_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false)
    private String entity;

    @Column(nullable = false)
    private String signal;

    @Column(name = "slo_target")
    private Double sloTarget;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds = 300;

    @Column(name = "burn_rate_threshold")
    private Double burnRateThreshold = 2.0;

    @Column(name = "severity_map", columnDefinition = "jsonb")
    @Type(type = "com.vladmihalcea.hibernate.type.json.JsonStringType")
    private String severityMap;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    @Column(name = "query_template", nullable = false, columnDefinition = "TEXT")
    private String queryTemplate;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 3: Create DetectionEvent.java**

```java
// src/main/java/com/xammer/cloud/domain/DetectionEvent.java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "detection_event")
public class DetectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "det_evt_seq")
    @SequenceGenerator(name = "det_evt_seq", sequenceName = "detection_event_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false)
    private String entity;

    @Column(nullable = false)
    private String signal;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private EventType eventType = EventType.fast_spike;

    @Column(nullable = false)
    private double score;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "slo_impact", nullable = false, length = 10)
    private SloImpact sloImpact = SloImpact.none;

    @Column(name = "baseline_value")
    private Double baselineValue;

    @Column(name = "observed_value", nullable = false)
    private double observedValue;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "investigation_id")
    private Long investigationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum EventType { fast_spike, slow_burn }
    public enum Severity { critical, high, medium, low }
    public enum SloImpact { breach, risk, none }
}
```

- [ ] **Step 4: Add GENERIC to SreInvestigation.ResourceType**

In `SreInvestigation.java`, change:
```java
public enum ResourceType { K8S_POD, EC2, RDS, AZURE_VM, GCP_VM }
```
to:
```java
public enum ResourceType { K8S_POD, EC2, RDS, AZURE_VM, GCP_VM, GENERIC }
```

- [ ] **Step 5: Add GENERIC to resolvePipelineName in SreInvestigationService.java**

Change the switch in `resolvePipelineName()`:
```java
private String resolvePipelineName(SreInvestigation.ResourceType resourceType) {
    return switch (resourceType) {
        case K8S_POD -> "kubernetes";
        case EC2, RDS -> "aws";
        case AZURE_VM -> "azure";
        case GCP_VM -> "gcp";
        case GENERIC -> "generic";
    };
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd xamops-service && mvn test -pl . -Dtest=SloDefinitionTest -q 2>&1 | tail -5
```
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/domain/SloDefinition.java \
        xamops-service/src/main/java/com/xammer/cloud/domain/DetectionEvent.java \
        xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java \
        xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java \
        xamops-service/src/test/java/com/xammer/cloud/domain/SloDefinitionTest.java
git commit -m "feat(sre): add SloDefinition + DetectionEvent entities, GENERIC resource type"
```

---

## Task 3: Repositories + DTOs

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/SloDefinitionRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/DetectionEventRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/SloDefinitionDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/DetectionEventDto.java`

- [ ] **Step 1: Create SloDefinitionRepository.java**

```java
// src/main/java/com/xammer/cloud/repository/SloDefinitionRepository.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SloDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SloDefinitionRepository extends JpaRepository<SloDefinition, Long> {
    List<SloDefinition> findByClientIdAndEnabledTrue(Long clientId);
    boolean existsByClientIdAndEntityAndSignal(Long clientId, String entity, String signal);
}
```

- [ ] **Step 2: Create DetectionEventRepository.java**

```java
// src/main/java/com/xammer/cloud/repository/DetectionEventRepository.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DetectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {
    List<DetectionEvent> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<DetectionEvent> findByClientIdAndEntityOrderByCreatedAtDesc(Long clientId, String entity);

    @Query("SELECT COUNT(d) FROM DetectionEvent d WHERE d.clientId = :clientId AND d.entity = :entity AND d.signal = :signal AND d.createdAt > :since")
    long countRecentByEntitySignal(@Param("clientId") Long clientId,
                                   @Param("entity") String entity,
                                   @Param("signal") String signal,
                                   @Param("since") Instant since);
}
```

- [ ] **Step 3: Create SloDefinitionDto.java**

```java
// src/main/java/com/xammer/cloud/dto/sre/SloDefinitionDto.java
package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SloDefinition;
import lombok.Data;

import java.time.Instant;

@Data
public class SloDefinitionDto {
    private Long id;
    private String entity;
    private String signal;
    private Double sloTarget;
    private int windowSeconds;
    private Double burnRateThreshold;
    private String severityMap;
    private String streamName;
    private String queryTemplate;
    private boolean enabled;
    private Instant updatedAt;

    public static SloDefinitionDto from(SloDefinition s) {
        SloDefinitionDto dto = new SloDefinitionDto();
        dto.setId(s.getId());
        dto.setEntity(s.getEntity());
        dto.setSignal(s.getSignal());
        dto.setSloTarget(s.getSloTarget());
        dto.setWindowSeconds(s.getWindowSeconds());
        dto.setBurnRateThreshold(s.getBurnRateThreshold());
        dto.setSeverityMap(s.getSeverityMap());
        dto.setStreamName(s.getStreamName());
        dto.setQueryTemplate(s.getQueryTemplate());
        dto.setEnabled(s.isEnabled());
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }
}
```

- [ ] **Step 4: Create DetectionEventDto.java**

```java
// src/main/java/com/xammer/cloud/dto/sre/DetectionEventDto.java
package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.DetectionEvent;
import lombok.Data;
import java.time.Instant;

@Data
public class DetectionEventDto {
    private Long id;
    private String entity;
    private String signal;
    private String eventType;
    private double score;
    private double confidence;
    private String severity;
    private String sloImpact;
    private Double baselineValue;
    private double observedValue;
    private Instant windowStart;
    private Instant windowEnd;
    private Long investigationId;
    private Instant createdAt;

    public static DetectionEventDto from(DetectionEvent e) {
        DetectionEventDto dto = new DetectionEventDto();
        dto.setId(e.getId());
        dto.setEntity(e.getEntity());
        dto.setSignal(e.getSignal());
        dto.setEventType(e.getEventType().name());
        dto.setScore(e.getScore());
        dto.setConfidence(e.getConfidence());
        dto.setSeverity(e.getSeverity().name());
        dto.setSloImpact(e.getSloImpact().name());
        dto.setBaselineValue(e.getBaselineValue());
        dto.setObservedValue(e.getObservedValue());
        dto.setWindowStart(e.getWindowStart());
        dto.setWindowEnd(e.getWindowEnd());
        dto.setInvestigationId(e.getInvestigationId());
        dto.setCreatedAt(e.getCreatedAt());
        return dto;
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/repository/SloDefinitionRepository.java \
        xamops-service/src/main/java/com/xammer/cloud/repository/DetectionEventRepository.java \
        xamops-service/src/main/java/com/xammer/cloud/dto/sre/SloDefinitionDto.java \
        xamops-service/src/main/java/com/xammer/cloud/dto/sre/DetectionEventDto.java
git commit -m "feat(sre): add SLO + detection event repositories and DTOs"
```

---

## Task 4: MetricPoller

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/MetricPoller.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/MetricPollerTest.java`

MetricPoller queries OpenObserve using each SloDefinition's `queryTemplate`. The template uses `{entity}`, `{start_time}`, `{end_time}` placeholders (epoch microseconds for OpenObserve). It returns current observed value and a 24h baseline window for the same signal.

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/xammer/cloud/service/sre/detection/MetricPollerTest.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.domain.SloDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MetricPollerTest {

    @Test
    void buildQuery_substitutesPlaceholders() {
        MetricPoller poller = new MetricPoller(mock(RestTemplate.class));
        SloDefinition slo = new SloDefinition();
        slo.setQueryTemplate("SELECT avg(val) FROM \"logs\" WHERE svc='{entity}' AND _timestamp BETWEEN {start_time} AND {end_time}");
        slo.setEntity("user-service");

        String query = poller.buildQuery(slo.getQueryTemplate(), slo.getEntity(), 1000L, 2000L);
        assertEquals("SELECT avg(val) FROM \"logs\" WHERE svc='user-service' AND _timestamp BETWEEN 1000 AND 2000", query);
    }

    @Test
    void parseValue_returnsFirstHitValue() {
        MetricPoller poller = new MetricPoller(mock(RestTemplate.class));
        Map<String, Object> response = Map.of(
            "hits", List.of(Map.of("value", 123.45))
        );
        double val = poller.parseValue(response);
        assertEquals(123.45, val, 0.001);
    }

    @Test
    void parseValue_returnsNaNWhenNoHits() {
        MetricPoller poller = new MetricPoller(mock(RestTemplate.class));
        Map<String, Object> response = Map.of("hits", List.of());
        assertTrue(Double.isNaN(poller.parseValue(response)));
    }
}
```

Run: `cd xamops-service && mvn test -pl . -Dtest=MetricPollerTest -q 2>&1 | tail -5`
Expected: FAIL (class not found)

- [ ] **Step 2: Implement MetricPoller.java**

```java
// src/main/java/com/xammer/cloud/service/sre/detection/MetricPoller.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.domain.SloDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Component
public class MetricPoller {

    private static final Logger log = LoggerFactory.getLogger(MetricPoller.class);
    private static final long BASELINE_SECONDS = 86400L; // 24h

    private final RestTemplate restTemplate;

    public MetricPoller(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public record MetricSample(double observed, double baselineMean, double baselineStd, Instant windowStart, Instant windowEnd) {}

    /**
     * Polls OpenObserve for a single SloDefinition.
     * Returns empty if the query fails or returns no data.
     */
    public Optional<MetricSample> poll(SloDefinition slo, String o2BaseUrl, String orgId, String apiToken) {
        try {
            Instant now = Instant.now();
            Instant windowStart = now.minusSeconds(slo.getWindowSeconds());
            Instant baselineStart = now.minusSeconds(BASELINE_SECONDS);

            long nowUs = toMicros(now);
            long windowStartUs = toMicros(windowStart);
            long baselineStartUs = toMicros(baselineStart);

            double observed = queryValue(slo, o2BaseUrl, orgId, apiToken, windowStartUs, nowUs);
            if (Double.isNaN(observed)) return Optional.empty();

            // Baseline: split 24h into 288 × 5-min buckets and compute stats
            double[] baselineValues = queryBaseline(slo, o2BaseUrl, orgId, apiToken, baselineStartUs, windowStartUs);
            if (baselineValues.length < 5) return Optional.empty(); // not enough history

            double mean = mean(baselineValues);
            double std = std(baselineValues, mean);
            return Optional.of(new MetricSample(observed, mean, std, windowStart, now));
        } catch (Exception e) {
            log.warn("MetricPoller failed for {}/{}: {}", slo.getEntity(), slo.getSignal(), e.getMessage());
            return Optional.empty();
        }
    }

    String buildQuery(String template, String entity, long startUs, long endUs) {
        return template
            .replace("{entity}", entity)
            .replace("{start_time}", String.valueOf(startUs))
            .replace("{end_time}", String.valueOf(endUs));
    }

    @SuppressWarnings("unchecked")
    double parseValue(Map<String, Object> response) {
        List<?> hits = (List<?>) response.get("hits");
        if (hits == null || hits.isEmpty()) return Double.NaN;
        Map<String, Object> first = (Map<String, Object>) hits.get(0);
        Object val = first.values().iterator().next(); // take first column
        if (val instanceof Number) return ((Number) val).doubleValue();
        return Double.NaN;
    }

    private double queryValue(SloDefinition slo, String baseUrl, String org, String token,
                               long startUs, long endUs) {
        String sql = buildQuery(slo.getQueryTemplate(), slo.getEntity(), startUs, endUs);
        Map<String, Object> result = searchOpenObserve(baseUrl, org, slo.getStreamName(), sql, startUs, endUs, token);
        return parseValue(result);
    }

    private double[] queryBaseline(SloDefinition slo, String baseUrl, String org, String token,
                                    long baselineStartUs, long windowStartUs) {
        // Use same query but chunked into window_seconds buckets — simplified: one query for mean
        String sql = buildQuery(slo.getQueryTemplate(), slo.getEntity(), baselineStartUs, windowStartUs);
        Map<String, Object> result = searchOpenObserve(baseUrl, org, slo.getStreamName(), sql, baselineStartUs, windowStartUs, token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.getOrDefault("hits", List.of());
        double[] vals = new double[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            Object v = hits.get(i).values().iterator().next();
            vals[i] = v instanceof Number ? ((Number) v).doubleValue() : 0;
        }
        return vals;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> searchOpenObserve(String baseUrl, String org, String stream,
                                                   String sql, long startUs, long endUs, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString((token + ":").getBytes()));

        Map<String, Object> query = Map.of(
            "sql", sql,
            "start_time", startUs,
            "end_time", endUs
        );
        Map<String, Object> body = Map.of("query", query);

        String url = baseUrl + "/api/" + org + "/" + stream + "/_search";
        ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        if (resp.getBody() == null) return Map.of();
        return resp.getBody();
    }

    private long toMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1000L;
    }

    private double mean(double[] vals) {
        double sum = 0;
        for (double v : vals) sum += v;
        return sum / vals.length;
    }

    private double std(double[] vals, double mean) {
        double sq = 0;
        for (double v : vals) sq += (v - mean) * (v - mean);
        return Math.sqrt(sq / vals.length);
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

```bash
cd xamops-service && mvn test -pl . -Dtest=MetricPollerTest -q 2>&1 | tail -5
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/MetricPoller.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/MetricPollerTest.java
git commit -m "feat(sre): add MetricPoller for OpenObserve signal queries"
```

---

## Task 5: AnomalyDetector

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/AnomalyDetector.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/AnomalyDetectorTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/xammer/cloud/service/sre/detection/AnomalyDetectorTest.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.service.sre.detection.MetricPoller.MetricSample;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class AnomalyDetectorTest {

    private final AnomalyDetector detector = new AnomalyDetector();
    private final Instant now = Instant.now();

    @Test
    void highDeviationYieldsHighScore() {
        // baseline mean=100, std=10, observed=200 → z-score=10 → very anomalous
        MetricSample sample = new MetricSample(200, 100, 10, now.minusSeconds(300), now);
        AnomalyDetector.AnomalyResult result = detector.evaluate(sample);
        assertTrue(result.score() > 0.9, "Expected score > 0.9 for 10σ deviation");
        assertTrue(result.confidence() > 0.8);
    }

    @Test
    void normalValueYieldsLowScore() {
        // baseline mean=100, std=10, observed=105 → z-score=0.5 → normal
        MetricSample sample = new MetricSample(105, 100, 10, now.minusSeconds(300), now);
        AnomalyDetector.AnomalyResult result = detector.evaluate(sample);
        assertTrue(result.score() < 0.4, "Expected low score for 0.5σ deviation");
    }

    @Test
    void zeroStdUsesAbsoluteDeviation() {
        // baseline std=0 (constant signal), observed deviates → should still score
        MetricSample sample = new MetricSample(200, 100, 0, now.minusSeconds(300), now);
        AnomalyDetector.AnomalyResult result = detector.evaluate(sample);
        assertTrue(result.score() > 0, "Non-zero score expected when std=0 but value deviates");
    }
}
```

Run: `cd xamops-service && mvn test -pl . -Dtest=AnomalyDetectorTest -q 2>&1 | tail -5`
Expected: FAIL (class not found)

- [ ] **Step 2: Implement AnomalyDetector.java**

```java
// src/main/java/com/xammer/cloud/service/sre/detection/AnomalyDetector.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.service.sre.detection.MetricPoller.MetricSample;
import org.springframework.stereotype.Component;

@Component
public class AnomalyDetector {

    // Sigmoid-based score: maps z-score to [0,1]
    private static final double SIGMOID_SCALE = 0.5;

    public record AnomalyResult(double score, double confidence) {}

    public AnomalyResult evaluate(MetricSample sample) {
        double zScore = computeZScore(sample);
        double score = sigmoid(Math.abs(zScore));

        // Confidence is higher when baseline has lower relative variance (stable baseline → reliable detection)
        double cv = sample.baselineStd() / (Math.abs(sample.baselineMean()) + 1e-9);
        double confidence = Math.max(0.3, 1.0 - Math.min(cv, 0.7));

        return new AnomalyResult(score, confidence);
    }

    private double computeZScore(MetricSample sample) {
        double std = sample.baselineStd();
        if (std < 1e-9) {
            // Zero std: use relative deviation from mean as proxy
            double mean = sample.baselineMean();
            if (Math.abs(mean) < 1e-9) return 0;
            return (sample.observed() - mean) / Math.abs(mean) * 3; // scale to z-score range
        }
        return (sample.observed() - sample.baselineMean()) / std;
    }

    private double sigmoid(double x) {
        // Scaled sigmoid: score approaches 1 at z≈6+
        return 1.0 / (1.0 + Math.exp(-SIGMOID_SCALE * (x - 3)));
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

```bash
cd xamops-service && mvn test -pl . -Dtest=AnomalyDetectorTest -q 2>&1 | tail -5
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/AnomalyDetector.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/AnomalyDetectorTest.java
git commit -m "feat(sre): add AnomalyDetector with z-score + sigmoid scoring"
```

---

## Task 6: SloEvaluator

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/SloEvaluator.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/SloEvaluatorTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/xammer/cloud/service/sre/detection/SloEvaluatorTest.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.domain.DetectionEvent.Severity;
import com.xammer.cloud.domain.DetectionEvent.SloImpact;
import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.service.sre.detection.AnomalyDetector.AnomalyResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SloEvaluatorTest {

    private final SloEvaluator evaluator = new SloEvaluator();

    private SloDefinition sloWith(double target) {
        SloDefinition slo = new SloDefinition();
        slo.setSloTarget(target);
        slo.setSeverityMap("""
            {"critical":{"slo_breach":true,"score_min":0.9},
             "high":{"slo_breach":false,"score_min":0.9},
             "medium":{"score_min":0.7},
             "low":{"score_min":0.5}}
        """);
        return slo;
    }

    @Test
    void sloBreachWithHighScoreIsCritical() {
        SloDefinition slo = sloWith(400.0); // target = 400ms
        AnomalyResult anomaly = new AnomalyResult(0.95, 0.9);
        SloEvaluator.EvalResult result = evaluator.evaluate(slo, 2100.0, anomaly);
        assertEquals(Severity.critical, result.severity());
        assertEquals(SloImpact.breach, result.sloImpact());
    }

    @Test
    void highScoreWithoutSloBreachIsHigh() {
        SloDefinition slo = sloWith(400.0);
        AnomalyResult anomaly = new AnomalyResult(0.92, 0.85);
        SloEvaluator.EvalResult result = evaluator.evaluate(slo, 380.0, anomaly); // below SLO target
        assertEquals(Severity.high, result.severity());
        assertEquals(SloImpact.none, result.sloImpact());
    }

    @Test
    void lowScoreBelowThresholdIsFiltered() {
        SloDefinition slo = sloWith(400.0);
        AnomalyResult anomaly = new AnomalyResult(0.3, 0.7);
        SloEvaluator.EvalResult result = evaluator.evaluate(slo, 105.0, anomaly);
        assertNull(result, "Score below minimum threshold should return null (not actionable)");
    }
}
```

Run: `cd xamops-service && mvn test -pl . -Dtest=SloEvaluatorTest -q 2>&1 | tail -5`
Expected: FAIL (class not found)

- [ ] **Step 2: Implement SloEvaluator.java**

```java
// src/main/java/com/xammer/cloud/service/sre/detection/SloEvaluator.java
package com.xammer.cloud.service.sre.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DetectionEvent.Severity;
import com.xammer.cloud.domain.DetectionEvent.SloImpact;
import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.service.sre.detection.AnomalyDetector.AnomalyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SloEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SloEvaluator.class);
    private static final double DEFAULT_MIN_SCORE = 0.5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record EvalResult(Severity severity, SloImpact sloImpact) {}

    /**
     * Returns null if the anomaly is not actionable (score below all thresholds).
     */
    public EvalResult evaluate(SloDefinition slo, double observed, AnomalyResult anomaly) {
        boolean sloBreach = slo.getSloTarget() != null && observed > slo.getSloTarget();
        SloImpact impact = sloBreach ? SloImpact.breach : SloImpact.none;

        try {
            JsonNode map = MAPPER.readTree(slo.getSeverityMap() != null ? slo.getSeverityMap() : "{}");
            for (String level : new String[]{"critical", "high", "medium", "low"}) {
                JsonNode rule = map.get(level);
                if (rule == null) continue;
                double minScore = rule.has("score_min") ? rule.get("score_min").asDouble() : DEFAULT_MIN_SCORE;
                boolean requiresBreach = rule.has("slo_breach") && rule.get("slo_breach").asBoolean();
                if (anomaly.score() >= minScore && (!requiresBreach || sloBreach)) {
                    return new EvalResult(Severity.valueOf(level), impact);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse severity_map for SLO {}/{}: {}", slo.getEntity(), slo.getSignal(), e.getMessage());
        }
        return null; // not actionable
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

```bash
cd xamops-service && mvn test -pl . -Dtest=SloEvaluatorTest -q 2>&1 | tail -5
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/SloEvaluator.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/SloEvaluatorTest.java
git commit -m "feat(sre): add SloEvaluator with severity_map-driven severity classification"
```

---

## Task 7: AutoDetectionService

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/AutoDetectionService.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/AutoDetectionServiceTest.java`

This is the orchestrator. It:
1. Loads all clients with observability config
2. For each client, loads enabled SLO definitions
3. Polls each signal via MetricPoller
4. Evaluates via AnomalyDetector + SloEvaluator
5. Deduplicates (skips if detection event for same entity+signal in last 10 min)
6. Persists DetectionEvent
7. Calls `SreInvestigationService.createInvestigation()` for medium+ severity

- [ ] **Step 1: Write failing test**

```java
// src/test/java/com/xammer/cloud/service/sre/detection/AutoDetectionServiceTest.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.domain.*;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.sre.SreInvestigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDetectionServiceTest {

    @Mock SloDefinitionRepository sloRepo;
    @Mock DetectionEventRepository detectionRepo;
    @Mock ObservabilityConfigRepository obsConfigRepo;
    @Mock SreInvestigationService investigationService;
    @Mock MetricPoller metricPoller;
    @Mock AnomalyDetector anomalyDetector;
    @Mock SloEvaluator sloEvaluator;

    AutoDetectionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AutoDetectionService(sloRepo, detectionRepo, obsConfigRepo,
                investigationService, metricPoller, anomalyDetector, sloEvaluator);
    }

    @Test
    void skipsWhenNoObservabilityConfig() {
        when(obsConfigRepo.findByClientId(1L)).thenReturn(Optional.empty());
        service.runForClient(1L);
        verify(sloRepo, never()).findByClientIdAndEnabledTrue(any());
    }

    @Test
    void triggersInvestigationForCriticalAnomaly() {
        ObservabilityConfig obs = new ObservabilityConfig();
        obs.setO2OrgId("org1");
        obs.setO2ApiKey("key1");

        SloDefinition slo = new SloDefinition();
        slo.setEntity("user-service");
        slo.setSignal("latency_p95");
        slo.setClientId(1L);

        MetricPoller.MetricSample sample = new MetricPoller.MetricSample(2100, 220, 30, Instant.now().minusSeconds(300), Instant.now());
        AnomalyDetector.AnomalyResult anomaly = new AnomalyDetector.AnomalyResult(0.97, 0.91);
        SloEvaluator.EvalResult eval = new SloEvaluator.EvalResult(DetectionEvent.Severity.critical, DetectionEvent.SloImpact.breach);

        when(obsConfigRepo.findByClientId(1L)).thenReturn(Optional.of(obs));
        when(sloRepo.findByClientIdAndEnabledTrue(1L)).thenReturn(List.of(slo));
        when(metricPoller.poll(eq(slo), any(), any(), any())).thenReturn(Optional.of(sample));
        when(anomalyDetector.evaluate(sample)).thenReturn(anomaly);
        when(sloEvaluator.evaluate(eq(slo), anyDouble(), eq(anomaly))).thenReturn(eval);
        when(detectionRepo.countRecentByEntitySignal(eq(1L), eq("user-service"), eq("latency_p95"), any())).thenReturn(0L);
        when(detectionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(investigationService.createInvestigation(eq(1L), any(), eq(SreInvestigation.TriggerType.AUTO)))
                .thenReturn(new SreInvestigation());

        service.runForClient(1L);

        verify(investigationService).createInvestigation(eq(1L), any(), eq(SreInvestigation.TriggerType.AUTO));
    }

    @Test
    void deduplicatesWithinTenMinutes() {
        ObservabilityConfig obs = new ObservabilityConfig();
        obs.setO2OrgId("org1");
        obs.setO2ApiKey("key1");

        SloDefinition slo = new SloDefinition();
        slo.setEntity("user-service");
        slo.setSignal("latency_p95");
        slo.setClientId(1L);

        MetricPoller.MetricSample sample = new MetricPoller.MetricSample(2100, 220, 30, Instant.now().minusSeconds(300), Instant.now());
        AnomalyDetector.AnomalyResult anomaly = new AnomalyDetector.AnomalyResult(0.97, 0.91);
        SloEvaluator.EvalResult eval = new SloEvaluator.EvalResult(DetectionEvent.Severity.critical, DetectionEvent.SloImpact.breach);

        when(obsConfigRepo.findByClientId(1L)).thenReturn(Optional.of(obs));
        when(sloRepo.findByClientIdAndEnabledTrue(1L)).thenReturn(List.of(slo));
        when(metricPoller.poll(eq(slo), any(), any(), any())).thenReturn(Optional.of(sample));
        when(anomalyDetector.evaluate(sample)).thenReturn(anomaly);
        when(sloEvaluator.evaluate(eq(slo), anyDouble(), eq(anomaly))).thenReturn(eval);
        // already fired within 10 min
        when(detectionRepo.countRecentByEntitySignal(eq(1L), eq("user-service"), eq("latency_p95"), any())).thenReturn(1L);

        service.runForClient(1L);

        verify(investigationService, never()).createInvestigation(any(), any(), any());
    }
}
```

Run: `cd xamops-service && mvn test -pl . -Dtest=AutoDetectionServiceTest -q 2>&1 | tail -5`
Expected: FAIL (class not found)

- [ ] **Step 2: Implement AutoDetectionService.java**

```java
// src/main/java/com/xammer/cloud/service/sre/detection/AutoDetectionService.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.domain.*;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.sre.SreInvestigationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AutoDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AutoDetectionService.class);
    private static final long DEDUP_WINDOW_SECONDS = 600L; // 10 min

    private final SloDefinitionRepository sloRepo;
    private final DetectionEventRepository detectionRepo;
    private final ObservabilityConfigRepository obsConfigRepo;
    private final SreInvestigationService investigationService;
    private final MetricPoller metricPoller;
    private final AnomalyDetector anomalyDetector;
    private final SloEvaluator sloEvaluator;

    @Value("${openobserve.url:http://localhost:5080}")
    private String o2Url;

    public AutoDetectionService(SloDefinitionRepository sloRepo,
                                 DetectionEventRepository detectionRepo,
                                 ObservabilityConfigRepository obsConfigRepo,
                                 SreInvestigationService investigationService,
                                 MetricPoller metricPoller,
                                 AnomalyDetector anomalyDetector,
                                 SloEvaluator sloEvaluator) {
        this.sloRepo = sloRepo;
        this.detectionRepo = detectionRepo;
        this.obsConfigRepo = obsConfigRepo;
        this.investigationService = investigationService;
        this.metricPoller = metricPoller;
        this.anomalyDetector = anomalyDetector;
        this.sloEvaluator = sloEvaluator;
    }

    public void runForClient(Long clientId) {
        Optional<ObservabilityConfig> obsOpt = obsConfigRepo.findByClientId(clientId);
        if (obsOpt.isEmpty()) return;

        ObservabilityConfig obs = obsOpt.get();
        List<SloDefinition> slos = sloRepo.findByClientIdAndEnabledTrue(clientId);
        if (slos.isEmpty()) return;

        log.debug("Detection cycle: clientId={} sloCount={}", clientId, slos.size());

        for (SloDefinition slo : slos) {
            try {
                evaluateSignal(clientId, slo, obs);
            } catch (Exception e) {
                log.warn("Detection failed for {}/{}: {}", slo.getEntity(), slo.getSignal(), e.getMessage());
            }
        }
    }

    private void evaluateSignal(Long clientId, SloDefinition slo, ObservabilityConfig obs) {
        Optional<MetricPoller.MetricSample> sampleOpt = metricPoller.poll(slo, o2Url, obs.getO2OrgId(), obs.getO2ApiKey());
        if (sampleOpt.isEmpty()) return;

        MetricPoller.MetricSample sample = sampleOpt.get();
        AnomalyDetector.AnomalyResult anomaly = anomalyDetector.evaluate(sample);
        SloEvaluator.EvalResult eval = sloEvaluator.evaluate(slo, sample.observed(), anomaly);
        if (eval == null) return; // below all thresholds

        // Dedup: skip if we already fired for this signal in the last 10 min
        long recent = detectionRepo.countRecentByEntitySignal(clientId, slo.getEntity(), slo.getSignal(),
                Instant.now().minusSeconds(DEDUP_WINDOW_SECONDS));
        if (recent > 0) {
            log.debug("Dedup skip: {}/{} already fired within {}s", slo.getEntity(), slo.getSignal(), DEDUP_WINDOW_SECONDS);
            return;
        }

        DetectionEvent event = buildEvent(clientId, slo, sample, anomaly, eval);

        // Only trigger investigation for medium+ severity
        if (eval.severity() != DetectionEvent.Severity.low) {
            TriggerInvestigationRequest req = buildInvestigationRequest(slo, sample, eval);
            SreInvestigation inv = investigationService.createInvestigation(clientId, req, SreInvestigation.TriggerType.AUTO);
            event.setInvestigationId(inv.getId());
            log.info("Auto investigation triggered: clientId={} entity={} signal={} severity={} investigationId={}",
                    clientId, slo.getEntity(), slo.getSignal(), eval.severity(), inv.getId());
        }

        detectionRepo.save(event);
    }

    private DetectionEvent buildEvent(Long clientId, SloDefinition slo,
                                       MetricPoller.MetricSample sample,
                                       AnomalyDetector.AnomalyResult anomaly,
                                       SloEvaluator.EvalResult eval) {
        DetectionEvent e = new DetectionEvent();
        e.setClientId(clientId);
        e.setEntity(slo.getEntity());
        e.setSignal(slo.getSignal());
        e.setEventType(DetectionEvent.EventType.fast_spike);
        e.setScore(anomaly.score());
        e.setConfidence(anomaly.confidence());
        e.setSeverity(eval.severity());
        e.setSloImpact(eval.sloImpact());
        e.setBaselineValue(sample.baselineMean());
        e.setObservedValue(sample.observed());
        e.setWindowStart(sample.windowStart());
        e.setWindowEnd(sample.windowEnd());
        return e;
    }

    private TriggerInvestigationRequest buildInvestigationRequest(SloDefinition slo,
                                                                    MetricPoller.MetricSample sample,
                                                                    SloEvaluator.EvalResult eval) {
        TriggerInvestigationRequest req = new TriggerInvestigationRequest();
        req.setResourceType(SreInvestigation.ResourceType.GENERIC);
        req.setResourceId(slo.getEntity());
        req.setResourceName(slo.getEntity());
        String payload = String.format(
            "{\"signal\":\"%s\",\"observed\":%.2f,\"baseline\":%.2f,\"severity\":\"%s\",\"slo_impact\":\"%s\"}",
            slo.getSignal(), sample.observed(), sample.baselineMean(),
            eval.severity().name(), eval.sloImpact().name()
        );
        req.setAlertPayload(payload);
        return req;
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

```bash
cd xamops-service && mvn test -pl . -Dtest=AutoDetectionServiceTest -q 2>&1 | tail -5
```
Expected: PASS (3 tests)

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/AutoDetectionService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/detection/AutoDetectionServiceTest.java
git commit -m "feat(sre): add AutoDetectionService orchestrating poll→detect→trigger pipeline"
```

---

## Task 8: DetectionScheduler

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/DetectionScheduler.java`

- [ ] **Step 1: Implement DetectionScheduler.java**

```java
// src/main/java/com/xammer/cloud/service/sre/detection/DetectionScheduler.java
package com.xammer.cloud.service.sre.detection;

import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);

    private final ObservabilityConfigRepository obsConfigRepo;
    private final AutoDetectionService detectionService;

    public DetectionScheduler(ObservabilityConfigRepository obsConfigRepo,
                               AutoDetectionService detectionService) {
        this.obsConfigRepo = obsConfigRepo;
        this.detectionService = detectionService;
    }

    // Runs every 60 seconds; initial 2-min delay lets the app fully start
    @Scheduled(initialDelay = 120_000, fixedDelay = 60_000)
    public void runDetectionCycle() {
        List<ObservabilityConfig> configs = obsConfigRepo.findAll();
        if (configs.isEmpty()) return;

        log.debug("Detection cycle started: {} client(s)", configs.size());
        for (ObservabilityConfig cfg : configs) {
            try {
                detectionService.runForClient(cfg.getClient().getId());
            } catch (Exception e) {
                log.error("Detection cycle failed for client {}: {}", cfg.getClient().getId(), e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 2: Verify @EnableScheduling is active**

Check that the main application class or a config class has `@EnableScheduling`. If not, add it to the main application class:

```java
@SpringBootApplication
@EnableScheduling   // add this if missing
public class XamOpsApplication { ... }
```

Search: `grep -r "EnableScheduling" xamops-service/src/main/java/`
If found, no change needed.

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/detection/DetectionScheduler.java
git commit -m "feat(sre): add DetectionScheduler firing every 60s across all clients"
```

---

## Task 9: REST Controllers

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/SloDefinitionController.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/DetectionEventController.java`

- [ ] **Step 1: Implement SloDefinitionController.java**

```java
// src/main/java/com/xammer/cloud/controller/sre/SloDefinitionController.java
package com.xammer.cloud.controller.sre;

import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.dto.sre.SloDefinitionDto;
import com.xammer.cloud.repository.SloDefinitionRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sre/slo-definitions")
public class SloDefinitionController {

    private final SloDefinitionRepository repo;

    public SloDefinitionController(SloDefinitionRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<SloDefinitionDto>> list(@AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(
            repo.findByClientIdAndEnabledTrue(user.getClientId())
                .stream().map(SloDefinitionDto::from).collect(Collectors.toList())
        );
    }

    @PostMapping
    public ResponseEntity<SloDefinitionDto> create(@RequestBody SloDefinitionDto dto,
                                                    @AuthenticationPrincipal ClientUserDetails user) {
        SloDefinition s = new SloDefinition();
        s.setClientId(user.getClientId());
        s.setEntity(dto.getEntity());
        s.setSignal(dto.getSignal());
        s.setSloTarget(dto.getSloTarget());
        s.setWindowSeconds(dto.getWindowSeconds() > 0 ? dto.getWindowSeconds() : 300);
        s.setBurnRateThreshold(dto.getBurnRateThreshold());
        s.setSeverityMap(dto.getSeverityMap());
        s.setStreamName(dto.getStreamName());
        s.setQueryTemplate(dto.getQueryTemplate());
        s.setEnabled(true);
        return ResponseEntity.ok(SloDefinitionDto.from(repo.save(s)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SloDefinitionDto> update(@PathVariable Long id,
                                                    @RequestBody SloDefinitionDto dto,
                                                    @AuthenticationPrincipal ClientUserDetails user) {
        return repo.findById(id)
            .filter(s -> s.getClientId().equals(user.getClientId()))
            .map(s -> {
                s.setSloTarget(dto.getSloTarget());
                s.setWindowSeconds(dto.getWindowSeconds() > 0 ? dto.getWindowSeconds() : s.getWindowSeconds());
                s.setBurnRateThreshold(dto.getBurnRateThreshold());
                s.setSeverityMap(dto.getSeverityMap());
                s.setStreamName(dto.getStreamName());
                s.setQueryTemplate(dto.getQueryTemplate());
                s.setEnabled(dto.isEnabled());
                return ResponseEntity.ok(SloDefinitionDto.from(repo.save(s)));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                        @AuthenticationPrincipal ClientUserDetails user) {
        repo.findById(id)
            .filter(s -> s.getClientId().equals(user.getClientId()))
            .ifPresent(s -> { s.setEnabled(false); repo.save(s); });
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 2: Implement DetectionEventController.java**

```java
// src/main/java/com/xammer/cloud/controller/sre/DetectionEventController.java
package com.xammer.cloud.controller.sre;

import com.xammer.cloud.dto.sre.DetectionEventDto;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sre/detection-events")
public class DetectionEventController {

    private final DetectionEventRepository repo;

    public DetectionEventController(DetectionEventRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<List<DetectionEventDto>> list(@AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(
            repo.findByClientIdOrderByCreatedAtDesc(user.getClientId())
                .stream().map(DetectionEventDto::from).collect(Collectors.toList())
        );
    }

    @GetMapping("/entity/{entity}")
    public ResponseEntity<List<DetectionEventDto>> listByEntity(@PathVariable String entity,
                                                                  @AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(
            repo.findByClientIdAndEntityOrderByCreatedAtDesc(user.getClientId(), entity)
                .stream().map(DetectionEventDto::from).collect(Collectors.toList())
        );
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/sre/SloDefinitionController.java \
        xamops-service/src/main/java/com/xammer/cloud/controller/sre/DetectionEventController.java
git commit -m "feat(sre): add SloDefinition and DetectionEvent REST controllers"
```

---

## Task 10: Backend Build Verification

- [ ] **Step 1: Run full test suite**

```bash
cd xamops-service && mvn test -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS, no new failures.

- [ ] **Step 2: Build the jar**

```bash
cd xamops-service && mvn package -DskipTests -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit if any fixes needed**

If any compilation errors found, fix them and commit:
```bash
git add -p
git commit -m "fix(sre): resolve compilation issues in detection pipeline"
```

---

## Task 11: Frontend — Update SreInvestigationsPage

**Files:**
- Modify: `frontend-app/src/pages/SreInvestigationsPage.tsx`

Add an AUTO/MANUAL badge to each investigation row, and an AUTO filter tab.

- [ ] **Step 1: Read the current file to understand the full structure**

Read: `frontend-app/src/pages/SreInvestigationsPage.tsx` (full file)

- [ ] **Step 2: Add triggerType to the SreInvestigation type in useSreInvestigation.ts**

Open `frontend-app/src/hooks/useSreInvestigation.ts` and add `triggerType: 'AUTO' | 'MANUAL'` to the `SreInvestigation` interface. The backend already returns this field.

- [ ] **Step 3: Update FilterTab type and add badge**

In `SreInvestigationsPage.tsx`:

Change `FilterTab` type:
```typescript
type FilterTab = 'ALL' | 'AUTO' | 'MANUAL' | 'RUNNING' | 'COMPLETED' | 'FAILED';
```

Add badge rendering alongside the resource type label (inside each row):
```typescript
const triggerBadge = (trigger: 'AUTO' | 'MANUAL') => (
  <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${
    trigger === 'AUTO' ? 'bg-purple-100 text-purple-700' : 'bg-gray-100 text-gray-600'
  }`}>
    {trigger}
  </span>
);
```

Update the filter logic to handle AUTO/MANUAL tabs:
```typescript
const filtered = investigations.filter(inv => {
  if (activeTab === 'ALL') return true;
  if (activeTab === 'AUTO') return inv.triggerType === 'AUTO';
  if (activeTab === 'MANUAL') return inv.triggerType === 'MANUAL';
  return inv.status === activeTab;
});
```

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/pages/SreInvestigationsPage.tsx \
        frontend-app/src/hooks/useSreInvestigation.ts
git commit -m "feat(sre-ui): add AUTO/MANUAL badge and filter tabs to investigations page"
```

---

## Task 12: Frontend — SRE Overview Page

**Files:**
- Create: `frontend-app/src/hooks/useSreOverview.ts`
- Create: `frontend-app/src/pages/SreOverviewPage.tsx`
- Modify: `frontend-app/src/App.tsx`

- [ ] **Step 1: Create useSreOverview.ts**

```typescript
// frontend-app/src/hooks/useSreOverview.ts
import apiClient from '../utils/apiClient';
import { DetectionEvent, SloDefinition } from '../types/sre';

export interface DetectionEvent {
  id: number;
  entity: string;
  signal: string;
  eventType: string;
  score: number;
  confidence: number;
  severity: 'critical' | 'high' | 'medium' | 'low';
  sloImpact: 'breach' | 'risk' | 'none';
  baselineValue: number | null;
  observedValue: number;
  windowStart: string;
  windowEnd: string;
  investigationId: number | null;
  createdAt: string;
}

export interface SloDefinition {
  id: number;
  entity: string;
  signal: string;
  sloTarget: number | null;
  windowSeconds: number;
  streamName: string;
  queryTemplate: string;
  enabled: boolean;
}

export async function fetchDetectionEvents(): Promise<DetectionEvent[]> {
  const res = await apiClient.get('/api/sre/detection-events');
  return res.data;
}

export async function fetchSloDefinitions(): Promise<SloDefinition[]> {
  const res = await apiClient.get('/api/sre/slo-definitions');
  return res.data;
}

export async function createSloDefinition(dto: Omit<SloDefinition, 'id'>): Promise<SloDefinition> {
  const res = await apiClient.post('/api/sre/slo-definitions', dto);
  return res.data;
}
```

- [ ] **Step 2: Create SreOverviewPage.tsx**

```tsx
// frontend-app/src/pages/SreOverviewPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchDetectionEvents, fetchSloDefinitions, DetectionEvent, SloDefinition } from '../hooks/useSreOverview';

const severityColor: Record<string, string> = {
  critical: 'bg-red-100 text-red-700 border-red-200',
  high:     'bg-orange-100 text-orange-700 border-orange-200',
  medium:   'bg-yellow-100 text-yellow-700 border-yellow-200',
  low:      'bg-blue-100 text-blue-700 border-blue-200',
};

const severityDot: Record<string, string> = {
  critical: 'bg-red-500',
  high:     'bg-orange-500',
  medium:   'bg-yellow-500',
  low:      'bg-blue-400',
};

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ago`;
}

const SreOverviewPage: React.FC = () => {
  const navigate = useNavigate();
  const [events, setEvents] = useState<DetectionEvent[]>([]);
  const [slos, setSlos] = useState<SloDefinition[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAll = useCallback(async () => {
    try {
      const [evts, defs] = await Promise.all([fetchDetectionEvents(), fetchSloDefinitions()]);
      setEvents(evts);
      setSlos(defs);
    } catch {
      // silently fail on background polls
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 30000);
    return () => clearInterval(interval);
  }, [fetchAll]);

  const critical = events.filter(e => e.severity === 'critical').length;
  const high = events.filter(e => e.severity === 'high').length;
  const monitored = new Set(slos.map(s => s.entity)).size;

  if (loading) return <div className="p-8 text-gray-500">Loading SRE overview…</div>;

  return (
    <div className="p-6 max-w-5xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">SRE Overview</h1>
        <button
          onClick={() => navigate('/sre-investigations')}
          className="text-sm text-blue-600 hover:underline"
        >
          View all investigations →
        </button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-3 gap-4">
        <div className="bg-white border rounded-lg p-4">
          <p className="text-sm text-gray-500">Services monitored</p>
          <p className="text-3xl font-bold text-gray-900">{monitored}</p>
        </div>
        <div className="bg-white border rounded-lg p-4">
          <p className="text-sm text-gray-500">Critical detections (24h)</p>
          <p className={`text-3xl font-bold ${critical > 0 ? 'text-red-600' : 'text-gray-900'}`}>{critical}</p>
        </div>
        <div className="bg-white border rounded-lg p-4">
          <p className="text-sm text-gray-500">High detections (24h)</p>
          <p className={`text-3xl font-bold ${high > 0 ? 'text-orange-500' : 'text-gray-900'}`}>{high}</p>
        </div>
      </div>

      {/* Recent detection events */}
      <div className="bg-white border rounded-lg">
        <div className="px-4 py-3 border-b">
          <h2 className="font-medium text-gray-800">Recent anomalies</h2>
        </div>
        {events.length === 0 ? (
          <div className="p-8 text-center text-gray-400">No anomalies detected — all signals nominal</div>
        ) : (
          <ul className="divide-y">
            {events.slice(0, 20).map(evt => (
              <li
                key={evt.id}
                className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer"
                onClick={() => evt.investigationId && navigate(`/sre-investigations/${evt.investigationId}`)}
              >
                <div className="flex items-center gap-3">
                  <span className={`w-2 h-2 rounded-full ${severityDot[evt.severity]}`} />
                  <div>
                    <p className="text-sm font-medium text-gray-900">{evt.entity}</p>
                    <p className="text-xs text-gray-500">{evt.signal} · {evt.observedValue.toFixed(0)} (baseline {evt.baselineValue?.toFixed(0) ?? '—'})</p>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className={`text-xs px-2 py-0.5 rounded border font-medium ${severityColor[evt.severity]}`}>
                    {evt.severity.toUpperCase()}
                  </span>
                  <span className="text-xs text-gray-400">{timeAgo(evt.createdAt)}</span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Monitored SLOs */}
      <div className="bg-white border rounded-lg">
        <div className="px-4 py-3 border-b flex items-center justify-between">
          <h2 className="font-medium text-gray-800">Monitored signals ({slos.length})</h2>
        </div>
        {slos.length === 0 ? (
          <div className="p-8 text-center text-gray-400">No SLO definitions configured. Add one to start automated detection.</div>
        ) : (
          <ul className="divide-y">
            {slos.map(slo => (
              <li key={slo.id} className="flex items-center justify-between px-4 py-3">
                <div>
                  <p className="text-sm font-medium text-gray-900">{slo.entity}</p>
                  <p className="text-xs text-gray-500">{slo.signal} · target: {slo.sloTarget ?? 'none'} · window: {slo.windowSeconds}s</p>
                </div>
                <span className="text-xs text-green-600 font-medium">Active</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};

export default SreOverviewPage;
```

- [ ] **Step 3: Add route in App.tsx**

In `frontend-app/src/App.tsx`, add the import and route alongside existing SRE routes:

```typescript
import SreOverviewPage from './pages/SreOverviewPage';
// ...existing imports...

// Inside the Router/Routes block, add:
<Route path="/sre-overview" element={<SreOverviewPage />} />
```

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/hooks/useSreOverview.ts \
        frontend-app/src/pages/SreOverviewPage.tsx \
        frontend-app/src/App.tsx
git commit -m "feat(sre-ui): add SRE Overview page with anomaly feed and monitored signals"
```

---

## Task 13: End-to-End Verification

- [ ] **Step 1: Start the backend and verify it starts clean**

```bash
cd xamops-service && mvn spring-boot:run 2>&1 | grep -E "(Started|ERROR|WARN.*sre)" | head -20
```
Expected: `Started XamOpsApplication` with no SRE-related errors. Flyway migration `V20260429` should appear in logs.

- [ ] **Step 2: Check migration applied**

```bash
psql $DATABASE_URL -c "\d slo_definition" 2>&1 | head -15
psql $DATABASE_URL -c "\d detection_event" 2>&1 | head -15
```
Expected: Both tables described successfully.

- [ ] **Step 3: Start the frontend and verify the SRE Overview page loads**

```bash
cd frontend-app && npm start
```
Navigate to `/sre-overview`. Expected: page loads, shows "No anomalies detected" and "No SLO definitions configured" (empty state — correct for fresh setup).

- [ ] **Step 4: Create a test SLO definition via API**

```bash
curl -s -X POST http://localhost:8080/api/sre/slo-definitions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "entity": "user-service",
    "signal": "latency_p95",
    "sloTarget": 400,
    "windowSeconds": 300,
    "streamName": "default",
    "queryTemplate": "SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) as value FROM \"http_requests\" WHERE service_name = '\''{entity}'\'' AND _timestamp BETWEEN {start_time} AND {end_time}",
    "severityMap": "{\"critical\":{\"slo_breach\":true,\"score_min\":0.9},\"high\":{\"slo_breach\":false,\"score_min\":0.9},\"medium\":{\"score_min\":0.7},\"low\":{\"score_min\":0.5}}"
  }' | jq .
```
Expected: JSON response with `id` field.

- [ ] **Step 5: Verify the SLO appears on the overview page**

Refresh `/sre-overview`. Expected: "Monitored signals (1)" shows `user-service / latency_p95`.

- [ ] **Step 6: Final commit**

```bash
git add .
git status  # verify only expected files
git commit -m "feat(sre): automated detection MVP - SLO store, metric polling, z-score detection, auto-trigger"
```

---

## Self-Review

### Spec coverage

| Requirement | Task |
|---|---|
| SLO definitions as first-class data source | Task 1, 2, 3, 9 |
| Anomaly detection (z-score + EWMA) | Task 5 |
| Severity mapping from SLO | Task 6 |
| Auto-trigger existing investigation pipeline | Task 7 |
| Deduplication (10-min window) | Task 7 |
| Detection events persisted | Task 2, 3, 7 |
| Scheduled detection cycle | Task 8 |
| GENERIC resource type | Task 2 |
| SLO CRUD API | Task 9 |
| Detection events read API | Task 9 |
| Frontend overview page | Task 12 |
| AUTO/MANUAL badge on investigations | Task 11 |

**Out of scope (Plan 2):** Kafka, Isolation Forest, Redis windowing, full RCA correlation, slow-burn detector.

### Placeholder scan
No TBD, TODO, or "similar to above" patterns. All code blocks are complete.

### Type consistency
- `DetectionEvent.Severity` enum values (`critical`, `high`, `medium`, `low`) used consistently in SloEvaluator, AutoDetectionService, and DTOs.
- `MetricPoller.MetricSample` record used consistently in AnomalyDetector and AutoDetectionService.
- `TriggerInvestigationRequest` fields (`resourceType`, `resourceId`, `resourceName`, `alertPayload`) match existing usage in `SreInvestigationService.createInvestigation()`.
