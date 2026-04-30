# SRE Automated Detection Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an end-to-end automated anomaly detection pipeline: Spring Boot polls OpenObserve every 60s, detects z-score anomalies, publishes to Kafka, an RCA consumer creates incidents, and OpenSRE's aiokafka consumer runs LLM root cause analysis and posts the result back.

**Architecture:** AutoDetectionService creates a SreInvestigation row and publishes a preliminary Kafka message (including `investigation_id`). The RCA Consumer handles Redis sliding-window grouping and incident lifecycle. OpenSRE's aiokafka background task reads the same topic, runs the LangGraph investigation, and calls `PUT /api/sre/investigations/{id}/complete`. Frontend gains a detection-events feed, a Logs tab in investigation detail, and a real verify step in the setup wizard.

**Tech Stack:** Spring Boot 3 / spring-kafka / spring-data-redis, PostgreSQL Flyway, Redis sorted sets, Kafka (compacted, partitioned by entity), Python FastAPI + aiokafka, React 18 / TypeScript / Tailwind

---

## File Map

### Spring Boot (`xamops-service/src/main/java/com/xammer/cloud/`)
| Action | Path |
|--------|------|
| Create | `resources/db/migration/V20260430__sre_detection_pipeline.sql` |
| Create | `domain/SloDefinition.java` |
| Create | `domain/DetectionEvent.java` |
| Modify | `domain/SreInvestigation.java` — add incidentId, version, incidentStatus, closedAt |
| Create | `repository/SloDefinitionRepository.java` |
| Create | `repository/DetectionEventRepository.java` |
| Modify | `repository/SreInvestigationRepository.java` — add findByIncidentId |
| Create | `dto/sre/KafkaDetectionEvent.java` |
| Create | `dto/sre/RcaCompleteRequest.java` |
| Create | `dto/sre/DetectionEventDto.java` |
| Modify | `pom.xml` — add spring-kafka |
| Create | `config/KafkaConfig.java` |
| Create | `service/sre/MetricPoller.java` |
| Create | `service/sre/AnomalyDetector.java` |
| Create | `service/sre/AutoDetectionService.java` |
| Create | `service/sre/DetectionScheduler.java` |
| Create | `service/sre/SreDetectionConsumer.java` |
| Modify | `service/sre/SreInvestigationService.java` — add patchFromRca() |
| Modify | `controller/sre/SreInvestigationController.java` — add /complete + /detection endpoints |
| Modify | `resources/application.properties` — add Kafka + detection config |

### OpenSRE (`opensre/opensre-source/`)
| Action | Path |
|--------|------|
| Modify | `pyproject.toml` — add aiokafka optional dep |
| Create | `app/detection/__init__.py` |
| Create | `app/detection/consumer.py` |
| Modify | `app/remote/server.py` — wire consumer into lifespan |

### Frontend (`frontend-app/src/`)
| Action | Path |
|--------|------|
| Modify | `hooks/useSreInvestigation.ts` — add DetectionEvent type + listDetectionEvents() |
| Modify | `pages/SreDashboardPage.tsx` — detection events feed below stats |
| Modify | `pages/SreInvestigationDetailPage.tsx` — add Logs tab |
| Modify | `pages/SreSetupPage.tsx` — replace mock verify with real API call |

---

## Task 1: DB Migration

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V20260430__sre_detection_pipeline.sql`

- [ ] **Step 1: Create migration file**

```sql
-- slo_definition: per-client signal config
CREATE TABLE slo_definition (
  id                  BIGSERIAL PRIMARY KEY,
  client_id           BIGINT NOT NULL REFERENCES client(id),
  entity              TEXT NOT NULL,
  signal              TEXT NOT NULL,
  stream_name         TEXT NOT NULL,
  query_template      TEXT NOT NULL,
  window_seconds      INT NOT NULL DEFAULT 300,
  zscore_threshold    DOUBLE PRECISION NOT NULL DEFAULT 3.0,
  cooldown_seconds    INT NOT NULL DEFAULT 600,
  enabled             BOOLEAN NOT NULL DEFAULT TRUE,
  created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- detection_event: audit log of every anomaly before grouping
CREATE TABLE detection_event (
  id                  BIGSERIAL PRIMARY KEY,
  client_id           BIGINT NOT NULL,
  slo_definition_id   BIGINT REFERENCES slo_definition(id),
  entity              TEXT NOT NULL,
  signal              TEXT NOT NULL,
  score               DOUBLE PRECISION NOT NULL,
  metric_value        DOUBLE PRECISION,
  metric_baseline     DOUBLE PRECISION,
  evidence_logs       TEXT,
  investigation_id    BIGINT REFERENCES sre_investigation(id),
  kafka_offset        BIGINT,
  detected_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- add incident lifecycle fields to existing sre_investigation
ALTER TABLE sre_investigation
  ADD COLUMN IF NOT EXISTS incident_id      TEXT,
  ADD COLUMN IF NOT EXISTS version          INT NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS incident_status  TEXT NOT NULL DEFAULT 'open'
    CHECK (incident_status IN ('open','final','closed')),
  ADD COLUMN IF NOT EXISTS closed_at        TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_slo_definition_enabled ON slo_definition(enabled) WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_detection_event_client ON detection_event(client_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_sre_investigation_incident ON sre_investigation(incident_id);
```

- [ ] **Step 2: Verify migration applies**

```bash
cd xamops-service && mvn flyway:migrate -Dflyway.url=... 2>&1 | grep -E "Migrating|Success|ERROR"
```
Expected: `Migrating schema "public" to version V20260430` then `Successfully applied 1 migration`.
If the full DB isn't available locally, just check the file compiles by running: `mvn validate`

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/V20260430__sre_detection_pipeline.sql
git commit -m "db: add slo_definition, detection_event tables and sre_investigation incident columns"
```

---

## Task 2: Domain Entities + Repositories

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/SloDefinition.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/DetectionEvent.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/SloDefinitionRepository.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/DetectionEventRepository.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/repository/SreInvestigationRepository.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/AnomalyDetectorTest.java` (written in Task 4)

- [ ] **Step 1: Create SloDefinition entity**

```java
// xamops-service/src/main/java/com/xammer/cloud/domain/SloDefinition.java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
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

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    @Column(name = "query_template", nullable = false, columnDefinition = "TEXT")
    private String queryTemplate;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds = 300;

    @Column(name = "zscore_threshold", nullable = false)
    private double zscoreThreshold = 3.0;

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds = 600;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 2: Create DetectionEvent entity**

```java
// xamops-service/src/main/java/com/xammer/cloud/domain/DetectionEvent.java
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

    @Column(name = "slo_definition_id")
    private Long sloDefinitionId;

    @Column(nullable = false)
    private String entity;

    @Column(nullable = false)
    private String signal;

    @Column(nullable = false)
    private double score;

    @Column(name = "metric_value")
    private Double metricValue;

    @Column(name = "metric_baseline")
    private Double metricBaseline;

    @Column(name = "evidence_logs", columnDefinition = "TEXT")
    private String evidenceLogs;

    @Column(name = "investigation_id")
    private Long investigationId;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();
}
```

- [ ] **Step 3: Add incident fields to SreInvestigation**

Open `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java`.
After the `verdictNote` field (before the enum definitions at the bottom), add:

```java
    @Column(name = "incident_id")
    private String incidentId;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_status", nullable = false, length = 10)
    private IncidentStatus incidentStatus = IncidentStatus.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;
```

Inside the same file, add the enum after the existing `Verdict` enum:

```java
    public enum IncidentStatus {
        OPEN, FINAL, CLOSED
    }
```

- [ ] **Step 4: Create SloDefinitionRepository**

```java
// xamops-service/src/main/java/com/xammer/cloud/repository/SloDefinitionRepository.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SloDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SloDefinitionRepository extends JpaRepository<SloDefinition, Long> {
    List<SloDefinition> findByEnabledTrue();
}
```

- [ ] **Step 5: Create DetectionEventRepository**

```java
// xamops-service/src/main/java/com/xammer/cloud/repository/DetectionEventRepository.java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DetectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {
    List<DetectionEvent> findTop10ByClientIdOrderByDetectedAtDesc(Long clientId);
}
```

- [ ] **Step 6: Add findByIncidentId to SreInvestigationRepository**

Open `xamops-service/src/main/java/com/xammer/cloud/repository/SreInvestigationRepository.java` and add:

```java
import java.util.Optional;
...
Optional<SreInvestigation> findByIncidentId(String incidentId);
```

- [ ] **Step 7: Compile check**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/domain/ \
        xamops-service/src/main/java/com/xammer/cloud/repository/
git commit -m "feat(sre): add SloDefinition, DetectionEvent entities and incident fields on SreInvestigation"
```

---

## Task 3: DTOs + Kafka Config

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/KafkaDetectionEvent.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/RcaCompleteRequest.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/DetectionEventDto.java`
- Modify: `xamops-service/pom.xml` — add spring-kafka
- Create: `xamops-service/src/main/java/com/xammer/cloud/config/KafkaConfig.java`
- Modify: `xamops-service/src/main/resources/application.properties`

- [ ] **Step 1: Add spring-kafka to pom.xml**

In `xamops-service/pom.xml`, find the `<dependencies>` block and add after the last spring-boot starter:

```xml
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
```

- [ ] **Step 2: Add detection config to application.properties**

Append to `xamops-service/src/main/resources/application.properties`:

```properties
# SRE Detection Pipeline
sre.kafka.bootstrap-servers=localhost:9092
sre.kafka.topic=sre.detection.events
sre.detection.max-join-window-seconds=600
sre.detection.reopen-grace-seconds=120
```

- [ ] **Step 3: Create KafkaConfig**

```java
// xamops-service/src/main/java/com/xammer/cloud/config/KafkaConfig.java
package com.xammer.cloud.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${sre.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public KafkaTemplate<String, String> sreKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> sreKafkaListenerContainerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "sre-rca-consumer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
```

- [ ] **Step 4: Create KafkaDetectionEvent DTO**

```java
// xamops-service/src/main/java/com/xammer/cloud/dto/sre/KafkaDetectionEvent.java
package com.xammer.cloud.dto.sre;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KafkaDetectionEvent {

    @JsonProperty("incident_id")
    private String incidentId;

    @JsonProperty("version")
    private int version = 1;

    @JsonProperty("status")
    private String status; // "preliminary" | "final"

    @JsonProperty("updated_at")
    private long updatedAt;

    @JsonProperty("client_id")
    private Long clientId;

    @JsonProperty("entity")
    private String entity;

    @JsonProperty("resource_type")
    private String resourceType;

    @JsonProperty("signal")
    private String signal;

    @JsonProperty("score")
    private double score;

    @JsonProperty("metric_summary")
    private Map<String, Object> metricSummary;

    @JsonProperty("evidence_log_lines")
    private List<String> evidenceLogLines;

    @JsonProperty("github_repo")
    private String githubRepo;

    @JsonProperty("gitlab_project")
    private String gitlabProject;

    @JsonProperty("investigation_id")
    private Long investigationId;
}
```

- [ ] **Step 5: Create RcaCompleteRequest DTO**

```java
// xamops-service/src/main/java/com/xammer/cloud/dto/sre/RcaCompleteRequest.java
package com.xammer.cloud.dto.sre;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RcaCompleteRequest {

    @JsonProperty("incident_id")
    private String incidentId;

    @JsonProperty("version")
    private int version;

    @JsonProperty("report_summary")
    private String reportSummary;

    @JsonProperty("report_root_cause")
    private String reportRootCause;

    @JsonProperty("report_recommendations")
    private List<String> reportRecommendations;

    @JsonProperty("report_full_markdown")
    private String reportFullMarkdown;

    @JsonProperty("is_noise")
    private boolean isNoise;
}
```

- [ ] **Step 6: Create DetectionEventDto**

```java
// xamops-service/src/main/java/com/xammer/cloud/dto/sre/DetectionEventDto.java
package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.DetectionEvent;
import java.time.Instant;

public record DetectionEventDto(
    Long id,
    String entity,
    String signal,
    double score,
    Double metricValue,
    Double metricBaseline,
    Long investigationId,
    Instant detectedAt
) {
    public static DetectionEventDto from(DetectionEvent e) {
        return new DetectionEventDto(
            e.getId(), e.getEntity(), e.getSignal(), e.getScore(),
            e.getMetricValue(), e.getMetricBaseline(),
            e.getInvestigationId(), e.getDetectedAt()
        );
    }
}
```

- [ ] **Step 7: Compile check**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add xamops-service/pom.xml \
        xamops-service/src/main/java/com/xammer/cloud/config/KafkaConfig.java \
        xamops-service/src/main/java/com/xammer/cloud/dto/sre/ \
        xamops-service/src/main/resources/application.properties
git commit -m "feat(sre): add Kafka config, detection DTOs"
```

---

## Task 4: MetricPoller + AnomalyDetector

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/MetricPoller.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/AnomalyDetector.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/AnomalyDetectorTest.java`

- [ ] **Step 1: Write failing AnomalyDetector test**

```java
// xamops-service/src/test/java/com/xammer/cloud/service/sre/AnomalyDetectorTest.java
package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SloDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnomalyDetectorTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        detector = new AnomalyDetector(redis);
    }

    @Test
    void firstObservation_zScoreIsZero() {
        when(ops.get(anyString())).thenReturn(null); // no prior state
        SloDefinition slo = slo(42L, "payments", "error_rate");

        AnomalyDetector.AnomalyResult result = detector.detect(slo, 5.0);

        assertThat(result.zScore()).isEqualTo(0.0);
        verify(ops).set(anyString(), anyString()); // state saved
    }

    @Test
    void clearSpike_returnsHighZScore() throws Exception {
        // Seed a stable baseline: mean=1.0, variance=0.1, count=50
        String stateJson = "{\"mean\":1.0,\"variance\":0.1,\"count\":50}";
        when(ops.get(anyString())).thenReturn(stateJson);
        SloDefinition slo = slo(42L, "payments", "error_rate");

        AnomalyDetector.AnomalyResult result = detector.detect(slo, 10.0);

        assertThat(result.zScore()).isGreaterThan(3.0);
        assertThat(result.baseline()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.5));
    }

    private SloDefinition slo(Long clientId, String entity, String signal) {
        SloDefinition s = new SloDefinition();
        s.setClientId(clientId);
        s.setEntity(entity);
        s.setSignal(signal);
        return s;
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd xamops-service && mvn test -pl . -Dtest=AnomalyDetectorTest -q 2>&1 | tail -5
```
Expected: `FAILURES` or compile error (class not found).

- [ ] **Step 3: Create AnomalyDetector**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/AnomalyDetector.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SloDefinition;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);
    private static final double ALPHA = 0.1;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnomalyDetector(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record AnomalyResult(double zScore, double baseline) {}

    public AnomalyResult detect(SloDefinition slo, double metricValue) {
        String key = "ewma:" + slo.getClientId() + ":" + slo.getEntity() + ":" + slo.getSignal();
        EwmaState state = loadState(key);

        double zScore = 0.0;
        if (state.count > 0 && state.variance > 1e-9) {
            zScore = (metricValue - state.mean) / Math.sqrt(state.variance);
        }

        double diff = metricValue - state.mean;
        state.mean = state.mean + ALPHA * diff;
        state.variance = (1 - ALPHA) * (state.variance + ALPHA * diff * diff);
        state.count++;
        saveState(key, state);

        return new AnomalyResult(zScore, state.mean);
    }

    private EwmaState loadState(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) return objectMapper.readValue(json, EwmaState.class);
        } catch (Exception e) {
            log.warn("Could not load EWMA state for key {}: {}", key, e.getMessage());
        }
        return new EwmaState(0.0, 1.0, 0);
    }

    private void saveState(String key, EwmaState state) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(state));
        } catch (Exception e) {
            log.warn("Could not save EWMA state for key {}: {}", key, e.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class EwmaState {
        double mean;
        double variance;
        long count;
    }
}
```

- [ ] **Step 4: Create MetricPoller**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/MetricPoller.java
package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.service.OpenObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MetricPoller {

    private static final Logger log = LoggerFactory.getLogger(MetricPoller.class);

    private final OpenObserveService openObserveService;
    private final ObservabilityConfigRepository obsConfigRepo;

    public MetricPoller(OpenObserveService openObserveService,
                        ObservabilityConfigRepository obsConfigRepo) {
        this.openObserveService = openObserveService;
        this.obsConfigRepo = obsConfigRepo;
    }

    public Optional<Double> poll(SloDefinition slo) {
        var obsConfig = obsConfigRepo.findByClientId(slo.getClientId()).orElse(null);
        if (obsConfig == null) return Optional.empty();

        String sql = slo.getQueryTemplate()
            .replace("{stream}", slo.getStreamName())
            .replace("{entity}", slo.getEntity());

        long nowSec = System.currentTimeMillis() / 1000;
        Map<String, Object> params = new HashMap<>();
        params.put("providerAccountId", obsConfig.getO2OrgId());
        params.put("stream", slo.getStreamName());
        params.put("rawSql", sql);
        params.put("startTime", nowSec - slo.getWindowSeconds());
        params.put("endTime", nowSec);
        params.put("size", 1);

        try {
            Map<String, Object> result = openObserveService.searchLogs(slo.getClientId(), params);
            return extractScalar(result);
        } catch (Exception e) {
            log.warn("MetricPoller failed for slo {} entity={}: {}", slo.getId(), slo.getEntity(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Double> extractScalar(Map<String, Object> result) {
        Object hits = result.get("hits");
        if (!(hits instanceof List<?> list) || list.isEmpty()) return Optional.empty();
        if (!(list.get(0) instanceof Map<?, ?> row)) return Optional.empty();
        for (String key : List.of("value", "count", "metric_value", "result", "_count")) {
            Object v = row.get(key);
            if (v instanceof Number n) return Optional.of(n.doubleValue());
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Run tests — expect pass**

```bash
cd xamops-service && mvn test -Dtest=AnomalyDetectorTest -q 2>&1 | tail -5
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/AnomalyDetector.java \
        xamops-service/src/main/java/com/xammer/cloud/service/sre/MetricPoller.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/AnomalyDetectorTest.java
git commit -m "feat(sre): add MetricPoller and AnomalyDetector (EWMA z-score)"
```

---

## Task 5: AutoDetectionService + DetectionScheduler

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/AutoDetectionService.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/DetectionScheduler.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/sre/AutoDetectionServiceTest.java`

- [ ] **Step 1: Write failing AutoDetectionService test**

```java
// xamops-service/src/test/java/com/xammer/cloud/service/sre/AutoDetectionServiceTest.java
package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.DetectionEvent;
import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.repository.SloDefinitionRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.service.OpenObserveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDetectionServiceTest {

    private SloDefinitionRepository sloRepo;
    private DetectionEventRepository detectionRepo;
    private MetricPoller metricPoller;
    private AnomalyDetector anomalyDetector;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private KafkaTemplate<String, String> kafkaTemplate;
    private SreInvestigationService investigationService;
    private OpenObserveService openObserveService;
    private ObservabilityConfigRepository obsConfigRepo;
    private AutoDetectionService service;

    @BeforeEach
    void setUp() {
        sloRepo = mock(SloDefinitionRepository.class);
        detectionRepo = mock(DetectionEventRepository.class);
        metricPoller = mock(MetricPoller.class);
        anomalyDetector = mock(AnomalyDetector.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        investigationService = mock(SreInvestigationService.class);
        openObserveService = mock(OpenObserveService.class);
        obsConfigRepo = mock(ObservabilityConfigRepository.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new AutoDetectionService(sloRepo, detectionRepo, metricPoller,
            anomalyDetector, redis, kafkaTemplate, investigationService,
            openObserveService, obsConfigRepo, "sre.detection.events");
    }

    @Test
    void runCycle_anomalyDetected_publishesToKafka() {
        SloDefinition slo = new SloDefinition();
        slo.setId(1L);
        slo.setClientId(42L);
        slo.setEntity("payments");
        slo.setSignal("error_rate");
        slo.setStreamName("k8s-logs");
        slo.setQueryTemplate("SELECT COUNT(*) as count FROM {stream} WHERE service='{entity}'");
        slo.setZscoreThreshold(3.0);
        slo.setCooldownSeconds(600);

        when(sloRepo.findByEnabledTrue()).thenReturn(List.of(slo));
        when(metricPoller.poll(slo)).thenReturn(Optional.of(12.4));
        when(anomalyDetector.detect(slo, 12.4)).thenReturn(new AnomalyDetector.AnomalyResult(4.2, 0.8));
        when(redis.hasKey(anyString())).thenReturn(false); // no cooldown, no dedup

        DetectionEvent saved = new DetectionEvent();
        saved.setId(10L);
        when(detectionRepo.save(any())).thenReturn(saved);

        SreInvestigation inv = new SreInvestigation();
        inv.setId(101L);
        when(investigationService.createAutoInvestigation(any(), any(), any())).thenReturn(inv);

        service.runCycle();

        verify(kafkaTemplate).send(eq("sre.detection.events"), anyString(), anyString());
        verify(detectionRepo).save(any(DetectionEvent.class));
    }

    @Test
    void runCycle_cooldownActive_doesNotPublish() {
        SloDefinition slo = new SloDefinition();
        slo.setId(1L);
        slo.setClientId(42L);
        slo.setEntity("payments");
        slo.setSignal("error_rate");
        slo.setZscoreThreshold(3.0);
        slo.setCooldownSeconds(600);

        when(sloRepo.findByEnabledTrue()).thenReturn(List.of(slo));
        when(metricPoller.poll(slo)).thenReturn(Optional.of(12.4));
        when(anomalyDetector.detect(slo, 12.4)).thenReturn(new AnomalyDetector.AnomalyResult(4.2, 0.8));
        when(redis.hasKey(contains("cooldown"))).thenReturn(true); // cooldown active

        service.runCycle();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd xamops-service && mvn test -Dtest=AutoDetectionServiceTest -q 2>&1 | tail -5
```
Expected: compile error (class not found).

- [ ] **Step 3: Create AutoDetectionService**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/AutoDetectionService.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DetectionEvent;
import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.KafkaDetectionEvent;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.repository.SloDefinitionRepository;
import com.xammer.cloud.service.OpenObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class AutoDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AutoDetectionService.class);
    private static final long DEDUP_BUCKET_SECONDS = 30;

    private final SloDefinitionRepository sloRepo;
    private final DetectionEventRepository detectionRepo;
    private final MetricPoller metricPoller;
    private final AnomalyDetector anomalyDetector;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SreInvestigationService investigationService;
    private final OpenObserveService openObserveService;
    private final ObservabilityConfigRepository obsConfigRepo;
    private final String kafkaTopic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AutoDetectionService(SloDefinitionRepository sloRepo,
                                DetectionEventRepository detectionRepo,
                                MetricPoller metricPoller,
                                AnomalyDetector anomalyDetector,
                                StringRedisTemplate redis,
                                KafkaTemplate<String, String> kafkaTemplate,
                                SreInvestigationService investigationService,
                                OpenObserveService openObserveService,
                                ObservabilityConfigRepository obsConfigRepo,
                                @Value("${sre.kafka.topic:sre.detection.events}") String kafkaTopic) {
        this.sloRepo = sloRepo;
        this.detectionRepo = detectionRepo;
        this.metricPoller = metricPoller;
        this.anomalyDetector = anomalyDetector;
        this.redis = redis;
        this.kafkaTemplate = kafkaTemplate;
        this.investigationService = investigationService;
        this.openObserveService = openObserveService;
        this.obsConfigRepo = obsConfigRepo;
        this.kafkaTopic = kafkaTopic;
    }

    public void runCycle() {
        List<SloDefinition> definitions = sloRepo.findByEnabledTrue();
        for (SloDefinition slo : definitions) {
            try {
                processSlo(slo);
            } catch (Exception e) {
                log.error("Detection error for slo={} entity={}: {}", slo.getId(), slo.getEntity(), e.getMessage());
            }
        }
    }

    private void processSlo(SloDefinition slo) {
        Optional<Double> metricOpt = metricPoller.poll(slo);
        if (metricOpt.isEmpty()) return;

        double metric = metricOpt.get();
        AnomalyDetector.AnomalyResult anomaly = anomalyDetector.detect(slo, metric);
        if (anomaly.zScore() < slo.getZscoreThreshold()) return;

        String cooldownKey = "cooldown:" + slo.getClientId() + ":" + slo.getEntity() + ":" + slo.getSignal();
        if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) return;

        long bucket = System.currentTimeMillis() / (DEDUP_BUCKET_SECONDS * 1000);
        String dedupKey = "dedup:" + slo.getEntity() + ":" + slo.getSignal() + ":" + bucket;
        if (Boolean.TRUE.equals(redis.hasKey(dedupKey))) return;

        String evidenceLogs = collectEvidenceLogs(slo);

        DetectionEvent event = new DetectionEvent();
        event.setClientId(slo.getClientId());
        event.setSloDefinitionId(slo.getId());
        event.setEntity(slo.getEntity());
        event.setSignal(slo.getSignal());
        event.setScore(anomaly.zScore());
        event.setMetricValue(metric);
        event.setMetricBaseline(anomaly.baseline());
        event.setEvidenceLogs(evidenceLogs);
        DetectionEvent saved = detectionRepo.save(event);

        redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(slo.getCooldownSeconds()));
        redis.opsForValue().set(dedupKey, "1", Duration.ofSeconds(DEDUP_BUCKET_SECONDS));

        String incidentId = "inc-" + slo.getClientId() + "-" + slo.getEntity() + "-" + System.currentTimeMillis();
        SreInvestigation inv = investigationService.createAutoInvestigation(
            slo.getClientId(), slo, incidentId);

        saved.setInvestigationId(inv.getId());
        detectionRepo.save(saved);

        publishKafkaEvent(slo, anomaly, metric, incidentId, inv.getId(), evidenceLogs);
    }

    private String collectEvidenceLogs(SloDefinition slo) {
        try {
            var obsConfig = obsConfigRepo.findByClientId(slo.getClientId()).orElse(null);
            if (obsConfig == null) return "";
            long nowSec = System.currentTimeMillis() / 1000;
            Map<String, Object> params = new HashMap<>();
            params.put("providerAccountId", obsConfig.getO2OrgId());
            params.put("stream", slo.getStreamName());
            params.put("keyword", slo.getEntity());
            params.put("startTime", nowSec - 300);
            params.put("endTime", nowSec);
            params.put("size", 50);
            Map<String, Object> result = openObserveService.searchLogs(slo.getClientId(), params);
            Object hits = result.get("hits");
            if (!(hits instanceof List<?> list)) return "";
            List<String> lines = new ArrayList<>();
            for (Object hit : list) {
                if (hit instanceof Map<?, ?> row) {
                    Object msg = row.getOrDefault("message", row.getOrDefault("log", ""));
                    lines.add(String.valueOf(msg));
                    if (lines.size() >= 50) break;
                }
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("Could not collect evidence logs for {}: {}", slo.getEntity(), e.getMessage());
            return "";
        }
    }

    private void publishKafkaEvent(SloDefinition slo, AnomalyDetector.AnomalyResult anomaly,
                                   double metric, String incidentId, Long investigationId,
                                   String evidenceLogs) {
        try {
            KafkaDetectionEvent msg = new KafkaDetectionEvent();
            msg.setIncidentId(incidentId);
            msg.setVersion(1);
            msg.setStatus("preliminary");
            msg.setUpdatedAt(System.currentTimeMillis() / 1000);
            msg.setClientId(slo.getClientId());
            msg.setEntity(slo.getEntity());
            msg.setResourceType("GENERIC");
            msg.setSignal(slo.getSignal());
            msg.setScore(anomaly.zScore());
            msg.setMetricSummary(Map.of(
                "metric", slo.getSignal(),
                "baseline", anomaly.baseline(),
                "peak", metric,
                "change_pct", anomaly.baseline() > 0 ? Math.round((metric - anomaly.baseline()) / anomaly.baseline() * 100) : 0
            ));
            if (evidenceLogs != null && !evidenceLogs.isBlank()) {
                msg.setEvidenceLogLines(List.of(evidenceLogs.split("\n", 50)));
            }
            msg.setInvestigationId(investigationId);

            String json = objectMapper.writeValueAsString(msg);
            String partitionKey = slo.getClientId() + ":" + slo.getEntity();
            kafkaTemplate.send(kafkaTopic, partitionKey, json);
        } catch (Exception e) {
            log.error("Failed to publish Kafka event for entity={}: {}", slo.getEntity(), e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Add createAutoInvestigation to SreInvestigationService**

Open `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java` and add this method after `createInvestigation`:

```java
    public SreInvestigation createAutoInvestigation(Long clientId, SloDefinition slo, String incidentId) {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(clientId);
        inv.setTriggerType(SreInvestigation.TriggerType.AUTO);
        inv.setResourceType(SreInvestigation.ResourceType.GENERIC);
        inv.setResourceId(slo.getEntity());
        inv.setResourceName(slo.getEntity());
        inv.setIncidentId(incidentId);
        inv.setIncidentStatus(SreInvestigation.IncidentStatus.OPEN);
        inv.setStatus(SreInvestigation.InvestigationStatus.PENDING);
        return investigationRepo.save(inv);
    }
```

Also add import at top of file:
```java
import com.xammer.cloud.domain.SloDefinition;
```

- [ ] **Step 5: Create DetectionScheduler**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/DetectionScheduler.java
package com.xammer.cloud.service.sre;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);
    private final AutoDetectionService autoDetectionService;

    public DetectionScheduler(AutoDetectionService autoDetectionService) {
        this.autoDetectionService = autoDetectionService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void runDetectionCycle() {
        log.debug("SRE detection cycle starting");
        autoDetectionService.runCycle();
    }
}
```

- [ ] **Step 6: Run tests**

```bash
cd xamops-service && mvn test -Dtest=AutoDetectionServiceTest -q 2>&1 | tail -5
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: Compile full project**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/AutoDetectionService.java \
        xamops-service/src/main/java/com/xammer/cloud/service/sre/DetectionScheduler.java \
        xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/sre/AutoDetectionServiceTest.java
git commit -m "feat(sre): add AutoDetectionService, DetectionScheduler, and createAutoInvestigation"
```

---

## Task 6: Kafka RCA Consumer + Redis Sliding Window

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreDetectionConsumer.java`

- [ ] **Step 1: Create SreDetectionConsumer**

```java
// xamops-service/src/main/java/com/xammer/cloud/service/sre/SreDetectionConsumer.java
package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.KafkaDetectionEvent;
import com.xammer.cloud.repository.SreInvestigationRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Service
public class SreDetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SreDetectionConsumer.class);

    @Value("${sre.detection.max-join-window-seconds:600}")
    private int maxJoinWindowSeconds;

    @Value("${sre.detection.reopen-grace-seconds:120}")
    private int reopenGraceSeconds;

    private final SreInvestigationRepository investigationRepo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SreDetectionConsumer(SreInvestigationRepository investigationRepo,
                                StringRedisTemplate redis) {
        this.investigationRepo = investigationRepo;
        this.redis = redis;
    }

    @KafkaListener(
        topics = "${sre.kafka.topic:sre.detection.events}",
        containerFactory = "sreKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            KafkaDetectionEvent event = objectMapper.readValue(record.value(), KafkaDetectionEvent.class);
            if ("final".equals(event.getStatus())) {
                handleFinalEvent(event);
            } else if ("preliminary".equals(event.getStatus())) {
                handlePreliminaryEvent(event, record.offset());
            }
        } catch (Exception e) {
            log.error("RCA consumer failed on offset={}: {}", record.offset(), e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }

    private void handleFinalEvent(KafkaDetectionEvent event) {
        investigationRepo.findByIncidentId(event.getIncidentId()).ifPresent(inv -> {
            if (event.getVersion() <= inv.getVersion()) return; // version guard
            inv.setIncidentStatus(SreInvestigation.IncidentStatus.FINAL);
            inv.setVersion(event.getVersion());
            investigationRepo.save(inv);
            log.info("Incident {} marked FINAL", event.getIncidentId());
        });
    }

    private void handlePreliminaryEvent(KafkaDetectionEvent event, long kafkaOffset) {
        long nowEpoch = System.currentTimeMillis() / 1000;
        String eventsKey = "events:" + event.getEntity();

        // Add to sorted set for sliding window audit
        redis.opsForZSet().add(eventsKey, event.getIncidentId(), nowEpoch);
        redis.expire(eventsKey, Duration.ofSeconds(maxJoinWindowSeconds * 2L));

        // Trim entries older than the window
        redis.opsForZSet().removeRangeByScore(eventsKey, 0, nowEpoch - maxJoinWindowSeconds);

        // Check for existing open incident
        String openKey = "incident:open:" + event.getEntity();
        String existingIncidentId = redis.opsForValue().get(openKey);

        if (existingIncidentId != null && !existingIncidentId.equals(event.getIncidentId())) {
            // Attach this detection event to the existing open incident
            investigationRepo.findByIncidentId(existingIncidentId).ifPresent(existing -> {
                if (existing.getIncidentStatus() == SreInvestigation.IncidentStatus.OPEN) {
                    log.info("Attaching detection to existing incident {} for entity {}",
                        existingIncidentId, event.getEntity());
                    // The existing investigation will handle this via its own Kafka message processing
                    // Mark the new investigation (created by AutoDetectionService) as closed
                    if (event.getInvestigationId() != null) {
                        investigationRepo.findById(event.getInvestigationId()).ifPresent(dup -> {
                            dup.setIncidentStatus(SreInvestigation.IncidentStatus.CLOSED);
                            dup.setClosedAt(Instant.now());
                            investigationRepo.save(dup);
                        });
                    }
                    return;
                }
            });
        }

        // Register this incident as the open one for the entity
        redis.opsForValue().set(openKey, event.getIncidentId(),
            Duration.ofSeconds(maxJoinWindowSeconds));

        // Update the investigation created by AutoDetectionService
        if (event.getInvestigationId() != null) {
            investigationRepo.findById(event.getInvestigationId()).ifPresent(inv -> {
                if (inv.getIncidentId() == null) {
                    inv.setIncidentId(event.getIncidentId());
                }
                inv.setIncidentStatus(SreInvestigation.IncidentStatus.OPEN);
                investigationRepo.save(inv);
            });
        }

        log.info("Preliminary event processed: entity={} incidentId={} score={}",
            event.getEntity(), event.getIncidentId(), event.getScore());
    }
}
```

- [ ] **Step 2: Compile check**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/SreDetectionConsumer.java
git commit -m "feat(sre): add Kafka RCA consumer with Redis sliding window and incident lifecycle"
```

---

## Task 7: /complete Endpoint + Detection REST Endpoints

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreDetectionController.java`

- [ ] **Step 1: Add patchFromRca to SreInvestigationService**

In `SreInvestigationService.java`, add these imports and method:

```java
import com.xammer.cloud.dto.sre.RcaCompleteRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
```

Add method after `createAutoInvestigation`:

```java
    public void patchFromRca(Long investigationId, RcaCompleteRequest req) {
        investigationRepo.findById(investigationId).ifPresent(inv -> {
            if (req.getVersion() <= inv.getVersion()) {
                logger.info("SRE [{}] RCA patch dropped: incoming version {} <= stored {}", 
                    investigationId, req.getVersion(), inv.getVersion());
                return;
            }
            inv.setVersion(req.getVersion());
            inv.setReportSummary(req.getReportSummary());
            inv.setReportRootCause(req.getReportRootCause());
            inv.setReportFullMarkdown(req.getReportFullMarkdown());
            if (req.getReportRecommendations() != null) {
                try {
                    inv.setReportRecommendations(objectMapper.writeValueAsString(req.getReportRecommendations()));
                } catch (JsonProcessingException ignored) {}
            }
            inv.setStatus(req.isNoise() ? InvestigationStatus.FAILED : InvestigationStatus.COMPLETED);
            inv.setIncidentStatus(SreInvestigation.IncidentStatus.FINAL);
            inv.setCompletedAt(Instant.now());
            investigationRepo.save(inv);
            notificationService.notifyComplete(inv, inv.getClientId());
            logger.info("SRE [{}] RCA complete, incidentId={}", investigationId, inv.getIncidentId());
        });
    }
```

- [ ] **Step 2: Add /complete endpoint to SreInvestigationController**

In `SreInvestigationController.java`, add import and endpoint:

```java
import com.xammer.cloud.dto.sre.RcaCompleteRequest;
```

```java
    @PutMapping("/investigations/{id}/complete")
    public ResponseEntity<Void> completeInvestigation(
            @PathVariable Long id,
            @RequestBody RcaCompleteRequest req) {
        investigationService.patchFromRca(id, req);
        return ResponseEntity.ok().build();
    }
```

Note: this endpoint is called by OpenSRE (internal service-to-service), not by the browser, so no `@AuthenticationPrincipal` guard is needed. If you want to add a shared secret check, use a `X-Internal-Token` header verified against `${sre.internal.token}` property.

- [ ] **Step 3: Create SreDetectionController**

```java
// xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreDetectionController.java
package com.xammer.cloud.controller.sre;

import com.xammer.cloud.dto.sre.DetectionEventDto;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.OpenObserveService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sre/detection")
public class SreDetectionController {

    private final DetectionEventRepository detectionRepo;
    private final ObservabilityConfigRepository obsConfigRepo;
    private final OpenObserveService openObserveService;

    public SreDetectionController(DetectionEventRepository detectionRepo,
                                  ObservabilityConfigRepository obsConfigRepo,
                                  OpenObserveService openObserveService) {
        this.detectionRepo = detectionRepo;
        this.obsConfigRepo = obsConfigRepo;
        this.openObserveService = openObserveService;
    }

    @GetMapping("/events")
    public ResponseEntity<List<DetectionEventDto>> getRecentEvents(
            @AuthenticationPrincipal ClientUserDetails user) {
        List<DetectionEventDto> dtos = detectionRepo
            .findTop10ByClientIdOrderByDetectedAtDesc(user.getClientId())
            .stream().map(DetectionEventDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyStream(
            @RequestParam String stream,
            @RequestParam String entity,
            @AuthenticationPrincipal ClientUserDetails user) {
        try {
            var obsConfig = obsConfigRepo.findByClientId(user.getClientId()).orElse(null);
            if (obsConfig == null) {
                return ResponseEntity.ok(Map.of("connected", false, "lastEventAt", null));
            }
            long nowSec = System.currentTimeMillis() / 1000;
            Map<String, Object> params = Map.of(
                "providerAccountId", obsConfig.getO2OrgId(),
                "stream", stream,
                "keyword", entity,
                "startTime", nowSec - 86400,
                "endTime", nowSec,
                "size", 1
            );
            Map<String, Object> result = openObserveService.searchLogs(user.getClientId(), params);
            Object hits = result.get("hits");
            boolean connected = hits instanceof List<?> list && !list.isEmpty();
            String lastEventAt = null;
            if (connected && ((List<?>) hits).get(0) instanceof Map<?, ?> row) {
                Object ts = row.getOrDefault("_timestamp", row.getOrDefault("timestamp", null));
                if (ts != null) lastEventAt = String.valueOf(ts);
            }
            return ResponseEntity.ok(Map.of("connected", connected,
                "lastEventAt", lastEventAt != null ? lastEventAt : ""));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("connected", false, "lastEventAt", null));
        }
    }
}
```

- [ ] **Step 4: Compile check**

```bash
cd xamops-service && mvn compile -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java \
        xamops-service/src/main/java/com/xammer/cloud/controller/sre/
git commit -m "feat(sre): add /complete callback endpoint and detection events/verify REST endpoints"
```

---

## Task 8: OpenSRE aiokafka Consumer

**Files:**
- Modify: `opensre/opensre-source/pyproject.toml`
- Create: `opensre/opensre-source/app/detection/__init__.py`
- Create: `opensre/opensre-source/app/detection/consumer.py`
- Modify: `opensre/opensre-source/app/remote/server.py`

- [ ] **Step 1: Add aiokafka to pyproject.toml**

In `opensre/opensre-source/pyproject.toml`, find the `[project.optional-dependencies]` section and add:

```toml
detection = [
    "aiokafka>=0.10.0",
    "httpx>=0.27.0",
]
```

Also add `"httpx>=0.27.0"` to the main `dependencies` list if not already present (it is — check line 35: `"httpx>=0.27.0"` already exists).

So just add the `detection` optional dep block.

- [ ] **Step 2: Install aiokafka**

```bash
cd opensre/opensre-source && pip install aiokafka>=0.10.0
```
Expected: `Successfully installed aiokafka-...`

- [ ] **Step 3: Create app/detection/__init__.py**

```python
# opensre/opensre-source/app/detection/__init__.py
```
(Empty file — just creates the package)

- [ ] **Step 4: Create app/detection/consumer.py**

```python
# opensre/opensre-source/app/detection/consumer.py
"""aiokafka consumer for SRE detection events — runs as background task in FastAPI lifespan."""
from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
_TOPIC = os.getenv("SRE_KAFKA_TOPIC", "sre.detection.events")
_GROUP_ID = "opensre-detection-consumer"
_XAMOPS_URL = os.getenv("XAMOPS_BASE_URL", "http://localhost:8080")
_XAMOPS_API_KEY = os.getenv("XAMOPS_INTERNAL_TOKEN", "")

# In-memory idempotency state: incident_id -> version processed
_processed: dict[str, int] = {}


async def _run_investigation_for_event(event: dict[str, Any]) -> None:
    """Run LangGraph investigation and post result back to Spring Boot."""
    incident_id: str = event.get("incident_id", "")
    investigation_id: int | None = event.get("investigation_id")
    entity: str = event.get("entity", "unknown")
    signal: str = event.get("signal", "unknown")
    metric_summary: dict[str, Any] = event.get("metric_summary", {})
    evidence_log_lines: list[str] = event.get("evidence_log_lines", [])
    github_repo: str | None = event.get("github_repo")
    gitlab_project: str | None = event.get("gitlab_project")
    client_id: int | None = event.get("client_id")

    logger.info("[detection] running investigation for incidentId=%s entity=%s", incident_id, entity)

    # Build investigation input matching run_investigation() signature
    raw_alert: dict[str, Any] = {
        "resourceId": entity,
        "resourceType": event.get("resource_type", "GENERIC"),
        "alert_name": f"{entity} {signal} anomaly",
        "signal": signal,
        "score": event.get("score", 0),
        "metric_summary": metric_summary,
        "evidence_log_lines": evidence_log_lines[:50],
        "incident_id": incident_id,
        "client_id": client_id,
    }

    sources: dict[str, Any] = {}
    if github_repo:
        sources["github_repo"] = github_repo
    if gitlab_project:
        sources["gitlab_project"] = gitlab_project

    resolved_integrations: dict[str, Any] = sources if sources else {}

    try:
        from app.entrypoints.sdk import run_investigation  # lazy import

        result = await asyncio.to_thread(
            run_investigation,
            alert_name=f"{entity} {signal} anomaly",
            pipeline_name="generic",
            severity="critical",
            raw_alert=raw_alert,
            resolved_integrations=resolved_integrations if resolved_integrations else None,
        )
    except Exception:
        logger.exception("[detection] investigation failed for incidentId=%s", incident_id)
        return

    # Post result back to Spring Boot
    if investigation_id is None:
        logger.warning("[detection] no investigation_id in Kafka message for incidentId=%s — cannot call /complete", incident_id)
        return

    payload = {
        "incident_id": incident_id,
        "version": 2,
        "report_summary": result.get("summary", ""),
        "report_root_cause": result.get("root_cause", ""),
        "report_recommendations": result.get("investigation_recommendations", []),
        "report_full_markdown": result.get("report", ""),
        "is_noise": bool(result.get("is_noise", False)),
    }

    url = f"{_XAMOPS_URL}/api/sre/investigations/{investigation_id}/complete"
    headers = {"Content-Type": "application/json"}
    if _XAMOPS_API_KEY:
        headers["X-Internal-Token"] = _XAMOPS_API_KEY

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.put(url, json=payload, headers=headers)
            if resp.status_code not in (200, 204):
                logger.warning("[detection] /complete returned %s for id=%s", resp.status_code, investigation_id)
            else:
                logger.info("[detection] /complete success for investigationId=%s incidentId=%s", investigation_id, incident_id)
    except Exception:
        logger.exception("[detection] failed to POST /complete for investigationId=%s", investigation_id)


async def run_detection_consumer() -> None:
    """Consume sre.detection.events and trigger investigations for preliminary events."""
    try:
        from aiokafka import AIOKafkaConsumer  # optional dependency
    except ImportError:
        logger.warning("[detection] aiokafka not installed — detection consumer disabled. Install with: pip install aiokafka")
        return

    consumer = AIOKafkaConsumer(
        _TOPIC,
        bootstrap_servers=_BOOTSTRAP_SERVERS,
        group_id=_GROUP_ID,
        auto_offset_reset="latest",
        enable_auto_commit=True,
        value_deserializer=lambda b: json.loads(b.decode("utf-8")),
    )

    try:
        await consumer.start()
        logger.info("[detection] consumer started on topic=%s group=%s", _TOPIC, _GROUP_ID)
        async for msg in consumer:
            event: dict[str, Any] = msg.value
            incident_id: str = event.get("incident_id", "")
            version: int = event.get("version", 1)
            status: str = event.get("status", "")

            if status != "preliminary":
                continue

            stored_version = _processed.get(incident_id, 0)
            if version <= stored_version:
                logger.debug("[detection] dropping duplicate incidentId=%s version=%d", incident_id, version)
                continue

            _processed[incident_id] = version
            asyncio.create_task(
                _run_investigation_for_event(event),
                name=f"opensre-investigation-{incident_id}",
            )
    except asyncio.CancelledError:
        logger.info("[detection] consumer cancelled — shutting down")
    finally:
        await consumer.stop()
```

- [ ] **Step 5: Wire consumer into server.py lifespan**

Open `opensre/opensre-source/app/remote/server.py`.

Find the `_lifespan` function (around line 90). The current function is:

```python
@asynccontextmanager
async def _lifespan(_app: FastAPI) -> AsyncIterator[None]:
    INVESTIGATIONS_DIR.mkdir(parents=True, exist_ok=True)
    _refresh_instance_metadata()

    poller_task: asyncio.Task[None] | None = None
    poller = VercelPoller(investigations_dir=INVESTIGATIONS_DIR)
    if poller.is_enabled:
        poller_task = asyncio.create_task(
            poller.run_forever(_handle_polled_candidate),
            name="vercel-poller",
        )

    try:
        yield
    finally:
        if poller_task is not None:
            poller_task.cancel()
            with suppress(asyncio.CancelledError):
                await poller_task
```

Replace it with:

```python
@asynccontextmanager
async def _lifespan(_app: FastAPI) -> AsyncIterator[None]:
    INVESTIGATIONS_DIR.mkdir(parents=True, exist_ok=True)
    _refresh_instance_metadata()

    poller_task: asyncio.Task[None] | None = None
    poller = VercelPoller(investigations_dir=INVESTIGATIONS_DIR)
    if poller.is_enabled:
        poller_task = asyncio.create_task(
            poller.run_forever(_handle_polled_candidate),
            name="vercel-poller",
        )

    from app.detection.consumer import run_detection_consumer
    detection_task = asyncio.create_task(run_detection_consumer(), name="sre-detection-consumer")

    try:
        yield
    finally:
        detection_task.cancel()
        with suppress(asyncio.CancelledError):
            await detection_task
        if poller_task is not None:
            poller_task.cancel()
            with suppress(asyncio.CancelledError):
                await poller_task
```

- [ ] **Step 6: Lint check**

```bash
cd opensre/opensre-source && ruff check app/detection/ app/remote/server.py --fix
```
Expected: no errors (or only auto-fixed).

- [ ] **Step 7: Type check**

```bash
cd opensre/opensre-source && mypy app/detection/ --ignore-missing-imports 2>&1 | tail -10
```
Expected: `Success: no issues found` or only import-related warnings.

- [ ] **Step 8: Commit**

```bash
git add opensre/opensre-source/pyproject.toml \
        opensre/opensre-source/app/detection/ \
        opensre/opensre-source/app/remote/server.py
git commit -m "feat(opensre): add aiokafka detection consumer — runs LLM investigation on preliminary Kafka events"
```

---

## Task 9: Frontend — Detection Events Feed on Dashboard

**Files:**
- Modify: `frontend-app/src/hooks/useSreInvestigation.ts`
- Modify: `frontend-app/src/pages/SreDashboardPage.tsx`

- [ ] **Step 1: Add DetectionEvent type and listDetectionEvents to useSreInvestigation.ts**

Open `frontend-app/src/hooks/useSreInvestigation.ts` and add after the existing exports:

```typescript
export interface DetectionEvent {
  id: number;
  entity: string;
  signal: string;
  score: number;
  metricValue?: number;
  metricBaseline?: number;
  investigationId?: number;
  detectedAt: string;
}

export const listDetectionEvents = async (): Promise<DetectionEvent[]> => {
  const res = await apiClient.get('/api/sre/detection/events');
  return res.data;
};
```

- [ ] **Step 2: Add detection feed to SreDashboardPage.tsx**

Open `frontend-app/src/pages/SreDashboardPage.tsx`.

Add `listDetectionEvents, DetectionEvent` to the import from `../hooks/useSreInvestigation`:

```typescript
import {
  listInvestigations, getSreStats, listDetectionEvents,
  SreInvestigation, SreStats, DetectionEvent
} from '../hooks/useSreInvestigation';
```

Add state and fetch in the component (after `const [stats, setStats] = useState<SreStats | null>(null);`):

```typescript
  const [detectionEvents, setDetectionEvents] = useState<DetectionEvent[]>([]);
```

Update `fetchAll` to also fetch detection events:

```typescript
  const fetchAll = useCallback(async () => {
    try {
      const [invs, s, detEvts] = await Promise.all([
        listInvestigations(),
        getSreStats(),
        listDetectionEvents(),
      ]);
      setInvestigations(invs);
      setStats(s);
      setDetectionEvents(detEvts);
    } catch {
      // silently fail on background polls
    } finally {
      setLoading(false);
    }
  }, []);
```

Add the detection events feed section inside the return, after the stats cards `</div>` and before the active incidents section:

```typescript
        {/* Auto-Detected Events feed */}
        {detectionEvents.length > 0 && (
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-gray-100">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-violet-500" />
                <h2 className="text-sm font-semibold text-gray-700">Auto-Detected Anomalies</h2>
              </div>
              <span className="text-xs text-gray-400">last 10</span>
            </div>
            <ul className="divide-y divide-gray-100">
              {detectionEvents.map(evt => (
                <li
                  key={evt.id}
                  onClick={() => evt.investigationId && navigate(`/sre/investigations/${evt.investigationId}`)}
                  className={`flex items-center justify-between px-5 py-3 ${evt.investigationId ? 'cursor-pointer hover:bg-gray-50' : ''} transition-colors`}
                >
                  <div className="flex items-center gap-3">
                    <div className="w-7 h-7 rounded-lg bg-violet-100 flex items-center justify-center">
                      <svg className="w-3.5 h-3.5 text-violet-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                      </svg>
                    </div>
                    <div>
                      <p className="text-sm font-medium text-gray-800">{evt.entity}</p>
                      <p className="text-xs text-gray-400">{evt.signal} · z-score {evt.score.toFixed(1)}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    {evt.metricValue != null && evt.metricBaseline != null && (
                      <span className="text-xs text-red-600 font-medium">
                        {evt.metricValue.toFixed(1)} <span className="text-gray-400">vs</span> {evt.metricBaseline.toFixed(1)}
                      </span>
                    )}
                    <span className="text-xs text-gray-400">{timeAgo(evt.detectedAt)}</span>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}
```

- [ ] **Step 3: TypeScript check**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep -E "error TS|SreDashboard|useSreInvest"
```
Expected: no errors on the modified files.

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/hooks/useSreInvestigation.ts \
        frontend-app/src/pages/SreDashboardPage.tsx
git commit -m "feat(frontend): add auto-detected anomalies feed to SRE dashboard"
```

---

## Task 10: Frontend — Logs Tab in Investigation Detail

**Files:**
- Modify: `frontend-app/src/pages/SreInvestigationDetailPage.tsx`

- [ ] **Step 1: Add Logs tab to SreInvestigationDetailPage**

Open `frontend-app/src/pages/SreInvestigationDetailPage.tsx`.

Add these imports at the top of the file:

```typescript
import LogsExplorer from '../components/Observability/LogsExplorer';
import { useObservabilityData } from '../hooks/useObservabilityData';
```

Inside the component, add streams fetch. Find where other state/hooks are declared and add:

```typescript
  const { streams, fetchStreams } = useObservabilityData();
  useEffect(() => { fetchStreams(); }, [fetchStreams]);
```

Find the tab selector (the section with buttons for "Report", etc.). Add a "Logs" tab button alongside the existing ones. The existing tab area typically looks like a div with tab buttons. Add "Logs" as an option:

```typescript
  const [activeTab, setActiveTab] = useState<'report' | 'logs'>('report');
```

In the tab button area (find the existing tab switcher in the JSX and add):

```typescript
              <button
                onClick={() => setActiveTab('logs')}
                className={`px-4 py-2 text-sm font-medium rounded-lg transition-colors ${
                  activeTab === 'logs'
                    ? 'bg-indigo-600 text-white'
                    : 'text-gray-500 hover:text-gray-800 bg-white border border-gray-200'
                }`}
              >
                Logs
              </button>
```

In the tab content area, after the report content, add:

```typescript
              {activeTab === 'logs' && (
                <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" style={{ minHeight: '500px' }}>
                  <LogsExplorer streams={streams} />
                </div>
              )}
```

**Note:** The exact insertion points depend on the current file structure. Read the file first, find the tab switcher area, and insert appropriately. The component uses `useTabState` or local `useState` for tab tracking.

- [ ] **Step 2: TypeScript check**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep -E "error TS|InvestigationDetail"
```
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/pages/SreInvestigationDetailPage.tsx
git commit -m "feat(frontend): add Logs tab to investigation detail using LogsExplorer"
```

---

## Task 11: Frontend — Real Verify Step in Setup Wizard

**Files:**
- Modify: `frontend-app/src/pages/SreSetupPage.tsx`
- Modify: `frontend-app/src/hooks/useSreInvestigation.ts` (add verifyStream)

- [ ] **Step 1: Add verifyStream to useSreInvestigation.ts**

```typescript
export const verifyDetectionStream = async (
  stream: string,
  entity: string
): Promise<{ connected: boolean; lastEventAt: string | null }> => {
  const res = await apiClient.get('/api/sre/detection/verify', {
    params: { stream, entity },
  });
  return res.data;
};
```

- [ ] **Step 2: Update SreSetupPage handleVerify**

Open `frontend-app/src/pages/SreSetupPage.tsx`.

Add import:
```typescript
import { verifyDetectionStream } from '../hooks/useSreInvestigation';
```

Find the existing `handleVerify` function:
```typescript
const handleVerify = async () => {
  setVerifying(true);
  await new Promise(r => setTimeout(r, 2000));
  setVerified(true);
  setVerifying(false);
};
```

Replace with:
```typescript
const handleVerify = async () => {
  setVerifying(true);
  try {
    const result = await verifyDetectionStream('default', 'health-check');
    setVerified(result.connected);
    if (!result.connected) {
      setVerifyError('No signals detected in the last 24h. Make sure your agent is sending data.');
    }
  } catch {
    setVerified(false);
    setVerifyError('Could not reach the verification endpoint. Check your network connection.');
  } finally {
    setVerifying(false);
  }
};
```

Add state for verifyError near the top of the component (alongside existing state):
```typescript
const [verifyError, setVerifyError] = useState('');
```

In the verify step JSX, add an error display after the "Check Connection" button area (in the `!verified && !verifying` branch):
```typescript
              {verifyError && (
                <p className="text-xs text-red-500 mt-2">{verifyError}</p>
              )}
```

Also reset verifyError when the verify button is clicked (add `setVerifyError('')` at the start of handleVerify).

- [ ] **Step 3: TypeScript check**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep -E "error TS|SreSetup"
```
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/hooks/useSreInvestigation.ts \
        frontend-app/src/pages/SreSetupPage.tsx
git commit -m "feat(frontend): replace mock verify with real OpenObserve stream verification"
```

---

## Task 12: Final Compile + Build Verification

- [ ] **Step 1: Full Spring Boot build**

```bash
cd xamops-service && mvn package -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 2: Run all SRE tests**

```bash
cd xamops-service && mvn test -Dtest="AnomalyDetectorTest,AutoDetectionServiceTest,SreInvestigationServiceTest,OpenSreClientTest" -q 2>&1 | tail -10
```
Expected: all tests pass, 0 failures.

- [ ] **Step 3: Full frontend TypeScript check**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | grep "error TS" | head -20
```
Expected: no errors.

- [ ] **Step 4: OpenSRE lint + typecheck**

```bash
cd opensre/opensre-source && make lint && make typecheck 2>&1 | tail -10
```
Expected: exit 0 / no errors.

- [ ] **Step 5: Commit final verification**

```bash
git add -A
git commit -m "chore: final SRE detection pipeline — all builds pass"
```
