# SRE Automated Detection Pipeline — Design Spec

**Date:** 2026-04-30
**Status:** Approved

---

## Goal

Build an automated detection and RCA pipeline that monitors customer services via OpenObserve, detects anomalies, groups correlated events into incidents, and triggers OpenSRE for AI-driven root cause analysis — all without manual intervention.

---

## Architecture Overview

```
OpenObserve (logs/metrics already flowing)
    ↓
DetectionScheduler — Spring Boot, every 60s
    MetricPoller  → SQL queries per SloDefinition
    AnomalyDetector → z-score on EWMA baseline
    ↓
Kafka: sre.detection.events  (partitioned by entity)
    ↓
RCA Consumer — Spring Boot, partition-local
    Redis: backward sliding window join (ZADD / ZRANGEBYSCORE)
    Incident state check: open / final / closed
    Deduplication (entity + signal + window overlap)
    → Create/update SreInvestigation (preliminary v1)
    → Build pre-LLM context packet (≤ 8k tokens)
    ↓
OpenSRE — Python LangGraph agent
    Idempotent upsert by incident_id + version
    Tools: OpenObserve logs, GitHub commits, GitLab MRs/pipelines
    Async LLM → RCA report
    → PUT /api/sre/investigations/{id}/complete  (v2 patch)
    ↓
PostgreSQL — source of truth
    SreInvestigation: status, version, RCA report
    ↓
SRE Dashboard — live feed, logs tab, detection events
```

---

## Data Model

### New PostgreSQL tables

#### `slo_definition`
Per-client signal config. Defines what to watch and when to fire.

```sql
CREATE TABLE slo_definition (
  id                   BIGSERIAL PRIMARY KEY,
  client_id            BIGINT NOT NULL REFERENCES client(id),
  entity               TEXT NOT NULL,          -- service name / resource id
  signal               TEXT NOT NULL,          -- error_rate | latency_p99 | availability
  stream_name          TEXT NOT NULL,          -- OpenObserve stream
  query_template       TEXT NOT NULL,          -- SQL with {stream} / {entity} placeholders
  window_seconds       INT NOT NULL DEFAULT 300,
  zscore_threshold     DOUBLE PRECISION NOT NULL DEFAULT 3.0,
  cooldown_seconds     INT NOT NULL DEFAULT 600,
  enabled              BOOLEAN NOT NULL DEFAULT TRUE,
  created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
```

#### `detection_event`
Audit log of every anomaly detected, before grouping into an incident.

```sql
CREATE TABLE detection_event (
  id                   BIGSERIAL PRIMARY KEY,
  client_id            BIGINT NOT NULL,
  slo_definition_id    BIGINT REFERENCES slo_definition(id),
  entity               TEXT NOT NULL,
  signal               TEXT NOT NULL,
  score                DOUBLE PRECISION NOT NULL,   -- z-score
  metric_value         DOUBLE PRECISION,
  metric_baseline      DOUBLE PRECISION,
  evidence_logs        TEXT,                        -- top log lines (≤ 50 lines)
  investigation_id     BIGINT REFERENCES sre_investigation(id),
  kafka_offset         BIGINT,
  detected_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

#### `sre_investigation` additions (migration)
Add incident lifecycle fields to the existing table.

```sql
ALTER TABLE sre_investigation
  ADD COLUMN IF NOT EXISTS incident_id   TEXT,          -- stable ID across versions
  ADD COLUMN IF NOT EXISTS version       INT NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS incident_status TEXT NOT NULL DEFAULT 'open'
    CHECK (incident_status IN ('open','final','closed')),
  ADD COLUMN IF NOT EXISTS closed_at     TIMESTAMP;
```

### Redis structures

| Key | Type | Purpose |
|-----|------|---------|
| `events:{entity}` | Sorted Set | ZADD with score=epoch. ZRANGEBYSCORE for backward window join |
| `incident:open:{entity}` | String | Current open incident_id for entity, with TTL = MAX_JOIN_WINDOW |
| `dedup:{entity}:{signal}:{bucket}` | String | Short TTL key for pre-ingest deduplication |
| `cooldown:{client_id}:{entity}:{signal}` | String | TTL = cooldown_seconds, prevents duplicate alerts |

---

## Kafka

### Topic: `sre.detection.events`

- **Partitioned by entity** (hash of `client_id:entity`) — guarantees partition-local processing; all events for a service go to the same consumer thread
- **Compaction enabled** — key = `incident_id`; both preliminary and patch messages are visible to consumers
- **Message schema:**

```json
{
  "incident_id": "inc-{client_id}-{entity}-{epoch_ms}",
  "version": 1,
  "status": "preliminary",
  "updated_at": 1714385520,
  "client_id": 42,
  "entity": "user-service",
  "resource_type": "GENERIC",
  "signal": "error_rate",
  "score": 4.2,
  "metric_summary": {
    "metric": "error_rate",
    "baseline": 0.8,
    "peak": 12.4,
    "change_pct": 1450,
    "duration_seconds": 300
  },
  "evidence_log_lines": ["ERROR NullPointerException at ...", "..."],
  "github_repo": "org/user-service",
  "gitlab_project": null,
  "investigation_id": 101
}
```

**Version 2 patch** (sent by OpenSRE after LLM RCA):
```json
{
  "incident_id": "inc-42-user-service-1714385520",
  "version": 2,
  "status": "final",
  "updated_at": 1714385640
}
```

### Idempotency contract (mandatory for all consumers)

1. **UPSERT by `incident_id`** — never append, always overwrite
2. **Version guard:** if `incoming.version < stored.version` → drop silently
3. **Alert only when:**
   - `status = preliminary` AND not already alerted for this `incident_id`
   - OR `severity` escalated in a later version

---

## Detection Pipeline (Spring Boot)

### `SloDefinition` entity + `SloDefinitionRepository`
JPA entity mapping `slo_definition` table. Repository with `findByClientIdAndEnabledTrue()`.

### `MetricPoller`
For each active `SloDefinition`, builds the SQL query from `query_template`, calls `OpenObserveService.searchLogs()`, and extracts the scalar metric value.

### `AnomalyDetector`
Computes z-score against EWMA baseline stored in Redis:
- Key: `ewma:{client_id}:{entity}:{signal}`
- Updates baseline on every poll (α = 0.1 for EWMA)
- Returns score and baseline for context packet

### `AutoDetectionService`
Orchestrates one detection cycle:
1. Load all enabled `SloDefinition` records for all clients
2. For each: poll metric → compute z-score
3. Check cooldown Redis key — skip if present
4. Check dedup key — drop if identical signal+entity within 30s bucket
5. Persist `DetectionEvent`
6. Set cooldown key (TTL = cooldown_seconds)
7. Publish to Kafka `sre.detection.events`

### `DetectionScheduler`
`@Scheduled(fixedDelay = 60_000)` entry point calling `AutoDetectionService.runCycle()`.

---

## RCA Consumer (Spring Boot)

### Sliding window join (backward-only)

At event time `T` for entity `E`:
1. `ZADD events:{E} T event_id`
2. `neighbors = ZRANGEBYSCORE events:{E} (T - window_seconds) T`
3. Filter neighbors by signal proximity (same entity, any signal)
4. Check `incident:open:{E}` key:
   - If exists and `T - incident_start_ts <= MAX_JOIN_WINDOW (10 min)` → attach to open incident
   - If exists but past grace → close old, create new
   - If not exists → create new incident

### Incident lifecycle

```
OPEN  → events being attached, window still active
FINAL → LLM RCA complete, no new events expected
CLOSED → explicitly closed (resolved verdict or timeout)
```

Reopen grace: if `status = final` and `(now - closed_at) < REOPEN_GRACE (2 min)` → reopen and attach. Otherwise create new.

### `SreInvestigationService` changes

- `createFromDetection(KafkaDetectionEvent)` → creates `SreInvestigation` with `triggerType=AUTO`, `incident_id`, `version=1`, `incident_status=open`
- `patchFromRca(String incidentId, int version, RcaResult)` → idempotent UPSERT: update fields only if `version > stored.version`

---

## Pre-LLM Context Reduction (Spring Boot, before calling OpenSRE)

Runs after preliminary incident is created, before dispatching to OpenSRE. Hard cap: **8k tokens**.

### A) Log selection
- Query OpenObserve for last 5 min of logs for the entity
- Cluster by log template (strip variable parts)
- Rank by: frequency spike + error severity
- Select top 20–50 lines

### B) Metric summarization
Never send raw time series. Send structured summary only:
```json
{
  "metric": "latency_p99",
  "baseline": 220,
  "peak": 2100,
  "change_pct": 855,
  "duration_seconds": 300
}
```

### C) Change event scoping
Query stored GitHub/GitLab config for the entity's repo. Include only:
- Most recent 1–2 commits/MRs within ±10 min of detection time
- Pipeline status for the most recent run

### D) Topology scope
Limit to 1–2 hop neighborhood (parent + direct children of the impacted service). Defined in `SloDefinition.topology_context` (optional JSON field).

### E) Token budget enforcement
Estimate tokens (chars / 4). If > 8k tokens:
- Truncate log lines first (reduce to top 10)
- If still > 8k → skip LLM, write heuristic-only RCA from metric summary

---

## OpenSRE Integration

### Kafka consumer (Python, `aiokafka` background task in FastAPI lifespan)

Listens on `sre.detection.events`. On message:
1. Check `incident_id` in local state — UPSERT, version guard
2. If `status = preliminary` and not yet processed → trigger `_execute_investigation()`
3. Investigation payload built from Kafka message (logs, metric summary, git config)
4. On completion → POST result to `PUT /api/sre/investigations/{investigation_id}/complete`
5. Publish patch message to `sre.detection.events` with `version=2, status=final`

### GitHub / GitLab activation
The `github_repo` and `gitlab_project` fields in the Kafka message are passed into the investigation `sources` dict. OpenSRE's existing tools activate automatically:
- `GitHubCommitsTool` — recent commits for the repo
- `GitLabMRsTool` + `GitLabPipelinesTool` — recent MRs and pipeline runs
- `GitDeployTimelineTool` — deploy timeline correlation

### `PUT /api/sre/investigations/{id}/complete` (new Spring Boot endpoint)
Accepts RCA result from OpenSRE. Idempotent — version guard applied before update.

```json
{
  "incident_id": "inc-42-user-service-1714385520",
  "version": 2,
  "report_summary": "...",
  "report_root_cause": "...",
  "report_recommendations": ["..."],
  "report_full_markdown": "...",
  "is_noise": false
}
```

---

## Frontend Changes

### 1. Dashboard — Detection Events feed
New section below stats cards: "Auto-Detected" feed showing last 10 detection events with entity, signal, z-score, and link to investigation. Polls `GET /api/sre/detection/events` every 15s.

### 2. Investigation Detail — Logs tab
New "Logs" tab alongside the report. Embeds a scoped version of the existing `LogsExplorer` component, pre-filtered to:
- Stream: from `SloDefinition.stream_name`
- Keyword filter: `resourceId` (entity name)
- Time range: `createdAt - 30min` to `completedAt + 5min`

No new API needed — uses existing `/api/observability/logs`.

### 3. Setup Wizard — real verification
Step 3 "Check Connection" calls `GET /api/sre/detection/verify?stream={stream}&entity={entity}`. Spring Boot queries OpenObserve to confirm the stream has data in the last 24h. Returns `{ connected: true/false, lastEventAt: "..." }`.

---

## Out of Scope (Plan 2)

- Isolation Forest ML model (EWMA z-score is sufficient for MVP)
- SLO definition CRUD UI (managed via API for now)
- Multi-hop topology correlation beyond 2 hops
- Alert routing (PagerDuty / Slack notifications)

---

## Implementation Order

1. DB migration (slo_definition, detection_event, sre_investigation additions)
2. Spring Boot: Kafka producer setup + DetectionScheduler + MetricPoller + AnomalyDetector
3. Spring Boot: RCA consumer + Redis sliding window + incident lifecycle
4. Spring Boot: Pre-LLM context reduction + `/complete` callback endpoint
5. OpenSRE: `aiokafka` consumer + idempotent processing + git source injection
6. Frontend: Detection events feed + Logs tab + wizard verification
