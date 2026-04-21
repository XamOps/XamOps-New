# SRE Investigation Feature — Design Spec
**Date:** 2026-04-21  
**Status:** Approved  
**Author:** Aditya Mehta

---

## 1. Overview

Add an AI-powered SRE investigation feature to XamOps that helps customers identify why their live applications failed. When an alert fires (or a customer manually requests it), XamOps triggers an investigation via OpenSRE, which uses AI agents to gather evidence across Kubernetes, cloud VMs, and logs, then generates a structured root cause report with fix recommendations.

---

## 2. Goals

- Customers can understand **why** a K8s pod crashed or a cloud VM/service failed — directly inside XamOps
- Investigations trigger **automatically** when alerts fire and **manually** via an "Investigate" button on any resource
- Reports include: plain-English summary, event timeline, root cause, and numbered fix recommendations
- Customers are notified via **in-app bell** and **email** when an investigation completes
- **Zero new LLM cost** — uses Gemini 2.0 Flash free tier (1,500 req/day)

---

## 3. Architecture

### Systems Involved

| System | Role |
|--------|------|
| **XamOps (Spring Boot)** | UI, auth, triggers, credential passing, notifications, report storage, investigation history |
| **OpenSRE (Docker sidecar)** | AI investigation engine — multi-step LangGraph agent pipeline |
| **Gemini 2.0 Flash** | LLM for reasoning and tool-call decisions (free tier via Google AI Studio) |
| **OpenObserve** | Log storage — already running; OpenSRE queries it directly |
| **AWS / Azure / GCP** | Customer cloud infra — OpenSRE queries EKS, CloudWatch, Azure Monitor |

### Data Flow

```
Alert fires / Manual click
        ↓
XamOps: SreAlertListener / SreInvestigationController
        ↓ creates SreInvestigation (PENDING)
        ↓ @Async worker
        ↓ fetches credentials from CloudAccount + ObservabilityConfig DB
        ↓ POST /threads + POST /threads/{id}/runs → OpenSRE LangGraph API
        ↓
OpenSRE pipeline:
  extract_alert → resolve_integrations → plan_actions
  → investigate (EKS/CloudWatch/Azure/OpenObserve tools)
  → diagnose_root_cause → publish_findings
        ↓ structured JSON report
XamOps: stores report, status = COMPLETED
        ↓
  EmailService → investigation complete email
  SreNotification → in-app bell
```

### OpenSRE Deployment

- Docker image: `langchain/langgraph-api:3.11` (as per OpenSRE Dockerfile)
- Port: 2024
- LangGraph REST API surface used:
  - `POST /threads` — create investigation thread
  - `POST /threads/{id}/runs` — start investigation with alert payload
  - `GET /threads/{id}/state` — poll for result
- Environment variables required:
  ```
  LLM_PROVIDER=gemini
  GEMINI_API_KEY=<free key from aistudio.google.com>
  OPENSRE_DISABLE_KEYRING=1
  ```

---

## 4. Data Model

### `sre_investigation` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT PK | auto-increment |
| `client_id` | BIGINT FK | → client table |
| `cloud_account_id` | BIGINT FK | → cloud_account table |
| `trigger_type` | ENUM | `AUTO`, `MANUAL` |
| `resource_type` | ENUM | `K8S_POD`, `EC2`, `RDS`, `AZURE_VM`, `GCP_VM` |
| `resource_id` | VARCHAR | pod name / instance ID |
| `resource_name` | VARCHAR | human-friendly display name |
| `status` | ENUM | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED` |
| `alert_payload` | TEXT (JSON) | raw alert that triggered investigation |
| `report_summary` | TEXT | 2-3 sentence plain-English summary (AI-generated) |
| `report_root_cause` | TEXT | root cause explanation (AI-generated) |
| `report_timeline` | TEXT (JSON) | array of `{time, event}` objects (AI-generated) |
| `report_recommendations` | TEXT (JSON) | array of fix step strings (AI-generated) |
| `opensre_thread_id` | VARCHAR | LangGraph thread ID for polling |
| `error_message` | TEXT | populated if status = FAILED |
| `created_at` | TIMESTAMP | when investigation was requested |
| `started_at` | TIMESTAMP | when OpenSRE started processing |
| `completed_at` | TIMESTAMP | when report was stored |

### `sre_notification` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT PK | auto-increment |
| `client_id` | BIGINT FK | → client table |
| `investigation_id` | BIGINT FK | → sre_investigation table |
| `title` | VARCHAR | notification title |
| `message` | TEXT | short description |
| `is_read` | BOOLEAN | default false |
| `created_at` | TIMESTAMP | |

---

## 5. Backend Components

### New Files

| File | Purpose |
|------|---------|
| `domain/SreInvestigation.java` | JPA entity for `sre_investigation` |
| `domain/SreNotification.java` | JPA entity for `sre_notification` |
| `repository/SreInvestigationRepository.java` | JPA repository |
| `repository/SreNotificationRepository.java` | JPA repository |
| `dto/sre/SreInvestigationDto.java` | API response DTO |
| `dto/sre/TriggerInvestigationRequest.java` | API request DTO |
| `service/sre/SreInvestigationService.java` | Core service — creates jobs, calls OpenSRE, stores reports |
| `service/sre/OpenSreClient.java` | HTTP client for OpenSRE LangGraph API |
| `service/sre/SreAlertListener.java` | Listens to incoming alerts, auto-triggers investigations |
| `service/sre/SreNotificationService.java` | Creates in-app notifications + sends email |
| `controller/sre/SreInvestigationController.java` | REST endpoints |

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/sre/investigations` | Create investigation (manual trigger) |
| `GET` | `/api/sre/investigations` | List all investigations for client |
| `GET` | `/api/sre/investigations/{id}` | Get investigation with full report |
| `GET` | `/api/sre/investigations/{id}/status` | Poll status (PENDING/RUNNING/COMPLETED/FAILED) |
| `GET` | `/api/sre/notifications` | Get unread in-app notifications |
| `PUT` | `/api/sre/notifications/{id}/read` | Mark notification as read |

### Investigation Flow (async)

1. `POST /api/sre/investigations` → `SreInvestigationService.triggerInvestigation()` → saves `SreInvestigation` with `status=PENDING`
2. `@Async` worker: fetches `CloudAccount` + `ObservabilityConfig` credentials → `status=RUNNING`
3. `OpenSreClient.createThread()` → `OpenSreClient.startRun(threadId, payload)`
4. Poll loop: `OpenSreClient.getState(threadId)` every 5 seconds until `status=success`
5. Parse report JSON → store in `SreInvestigation` fields → `status=COMPLETED`
6. `SreNotificationService.notifyComplete()` → creates `SreNotification` + sends email via `EmailService`

### Auto-trigger

`SreAlertListener` hooks into the existing alert ingestion pipeline. When an alert arrives with severity `CRITICAL` or `HIGH` for a supported resource type (`K8S_POD`, `EC2`, `AZURE_VM`, `GCP_VM`), it calls `SreInvestigationService.triggerInvestigation()` automatically.

---

## 6. Frontend Components

### New Pages

| File | Route | Description |
|------|-------|-------------|
| `pages/SreInvestigationsPage.tsx` | `/sre/investigations` | List of all investigations with status badges |
| `pages/SreInvestigationDetailPage.tsx` | `/sre/investigations/:id` | Full report: summary, timeline, root cause, recommendations |

### Modified Pages (add Investigate button)

- `pages/AlertsPage.tsx`
- `pages/GcpAlertsPage.tsx`
- `pages/AzureAlertsPage.tsx`
- `pages/CloudK8sPage.tsx`
- `pages/EksDetailsPage.tsx`
- `pages/GkeDetailsPage.tsx`
- `pages/AksDetailsPage.tsx`

### Button States

| State | Appearance | Action |
|-------|-----------|--------|
| Default | Blue "🔍 Investigate" | POST to create investigation |
| Running | Yellow "⏳ Investigating…" | disabled, investigation in progress |
| Completed | Green "✅ View Report" | navigate to `/sre/investigations/{id}` |

### New Hook

`hooks/useSreInvestigation.ts` — manages investigation trigger, status polling, and report fetching.

### Sidebar Navigation

Add "SRE Investigations" entry to the main sidebar navigation in `App.tsx`.

---

## 7. OpenSRE Payload Format

XamOps sends this payload to OpenSRE per investigation:

```json
{
  "alert_name": "payments-service OOMKilled",
  "pipeline_name": "kubernetes",
  "severity": "critical",
  "raw_alert": {
    "resourceType": "K8S_POD",
    "resourceId": "payments-service-7d9f",
    "namespace": "production",
    "clusterName": "prod-cluster"
  },
  "integrations": {
    "eks": {
      "cluster_name": "prod-cluster",
      "aws_access_key_id": "...",
      "aws_secret_access_key": "...",
      "region": "us-east-1"
    },
    "openobserve": {
      "base_url": "http://openobserve:5080",
      "username": "...",
      "password": "...",
      "org": "client-org-id"
    }
  }
}
```

Credentials come from `CloudAccount` and `ObservabilityConfig` already stored in XamOps DB.

---

## 8. Notifications

### In-app Bell
- `SreNotification` row created on investigation completion
- Frontend polls `GET /api/sre/notifications` for unread count
- Bell icon in navbar shows badge count
- Clicking notification navigates to `/sre/investigations/{id}`

### Email
- Reuses existing `EmailService`
- Subject: `"SRE Investigation Complete: {resourceName}"`
- Body: summary + root cause + top 3 recommendations + link to full report

---

## 9. Error Handling

| Scenario | Handling |
|----------|---------|
| OpenSRE unreachable | status=FAILED, error_message="OpenSRE service unavailable", notify customer |
| Investigation times out (>5 min) | status=FAILED, error_message="Investigation timed out" |
| Gemini rate limit hit | OpenSRE retries internally; XamOps waits up to 5 min |
| Missing cloud credentials | status=FAILED, error_message="Missing credentials for {provider}" |
| OpenSRE returns partial report | Store what's available, mark COMPLETED with warning |

---

## 10. Cost

| Component | Cost |
|-----------|------|
| OpenSRE | Free (open source) |
| Docker container (OpenSRE) | Runs on existing server — no extra infrastructure cost |
| Gemini 2.0 Flash | Free tier: 1,500 requests/day. Each investigation = 1 request |
| DB storage (reports) | Negligible |
| **Total** | **$0** |

---

## 11. Out of Scope (v1)

- Auto-remediation (executing fixes automatically)
- Conversational follow-up ("chat" with the investigation)
- Custom investigation pipelines per customer
- SLA tracking or MTTR metrics
- Slack/PagerDuty notifications (email + in-app only for v1)
