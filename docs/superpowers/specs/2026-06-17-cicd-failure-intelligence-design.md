# CI/CD Failure Intelligence & Pipeline Health Design

**Date:** 2026-06-17
**Feature area:** CI/CD Pipelines (XamOps)
**Personas:** Developers (debug fast) + Team Leads/Managers (cross-repo health)

---

## Problem

The existing CI/CD feature shows run status and aggregate metrics but gives no context on *why* a pipeline failed. Developers must leave XamOps and dig through raw GitHub/Jenkins logs. Managers have no at-a-glance health view across all connected repos and servers.

---

## Goals

1. Surface structured failure diagnosis (category + error snippets + diff vs last good run) without requiring AI
2. Alert users via email and Slack when a pipeline fails
3. Give managers a pipeline health scorecard across all connected repos/servers
4. Keep the design AI-ready — the on-demand analysis path can call an LLM later without touching the sync path

---

## Architecture: Hybrid Sync + On-Demand

**At sync time (every 15 min, `CicdSyncScheduler`):**
- Infer `failureCategory` from the failed job/step name — no log fetch, uses data already returned by the Jobs API
- Fire notification on status flip (non-failure → failure), set `notificationSent = true` to dedup

**On-demand (user opens failure detail):**
- Fetch logs for the failed job only (not the whole run)
- Parse with regex patterns to extract top error snippets
- Fetch last successful run for the same workflow; return commit diff and changed-file count

This split avoids fetching 1–50 MB log files on every sync cycle while keeping page loads instant for the scorecard and category badges.

---

## Data Model

### `cicd_run_record` additions (Flyway migration)

| Column | Type | Notes |
|---|---|---|
| `failure_category` | `VARCHAR(30)` | `TEST_FAILURE`, `BUILD_ERROR`, `DEPLOY_FAILURE`, `INFRA_ERROR`, `UNKNOWN` |
| `failed_stage_name` | `VARCHAR(255)` | Name of the first failed job/stage |
| `notification_sent` | `BOOLEAN DEFAULT FALSE` | Dedup flag — set to true after first alert fires |

No existing columns change. Null-safe: all three columns are nullable for historical records.

### New table: `cicd_notification_config`

| Column | Type |
|---|---|
| `id` | `BIGINT PK` |
| `user_id` | `BIGINT FK → users` |
| `email_enabled` | `BOOLEAN DEFAULT TRUE` |
| `slack_webhook_url` | `VARCHAR(500)` |
| `created_at` | `TIMESTAMP` |
| `updated_at` | `TIMESTAMP` |

One row per user. Upsert on save.

---

## Backend

### 1. Failure Categorization (sync time)

`CicdSyncScheduler` — after saving a `CicdRunRecord` with status `failure`, call a new `FailureCategoryResolver`:

```
job name contains "test" / "spec"     → TEST_FAILURE
job name contains "build" / "compile" → BUILD_ERROR
job name contains "deploy" / "release"→ DEPLOY_FAILURE
Jenkins status = UNSTABLE              → TEST_FAILURE
anything else                          → UNKNOWN
```

Matching is case-insensitive. `failedStageName` is set to the first job/step with a failed conclusion.

### 2. Notification Trigger (sync time)

In `CicdSyncScheduler.upsertRun()`:
- If new status is `failure` AND previous status was not `failure` AND `notificationSent = false`:
  - Call `CicdNotificationService.notifyFailure(record, userConfig)`
  - Set `notificationSent = true`, save

`CicdNotificationService`:
- **Email:** uses existing email infrastructure. Subject: `[XamOps] Pipeline failed: {workflowName} in {repo}`. Body includes: category, failed stage, run URL, timestamp.
- **Slack:** HTTP POST to `slackWebhookUrl` with a simple JSON payload (repo, workflow, category, failed stage, run URL). No Slack SDK needed — plain `RestTemplate` POST.

### 3. On-Demand Failure Analysis

New service: `FailureAnalysisService`

**Log parsing:**
- Fetch logs for the failed job only via existing GitHub/Jenkins log endpoints
- Apply regex patterns in priority order:

| Pattern | Category hint |
|---|---|
| `Exception:`, `at com\.`, `Caused by:` | Java exception |
| `BUILD FAILURE`, `Tests run:.*Failures:` | Maven/Gradle |
| `npm ERR!`, `AssertionError` | Node/JS |
| `Error:`, `FAILED`, `exit code [^0]` | Generic |

- Return top 3 matched snippets: each snippet is 5 lines of context around the match, with the matching line index noted for frontend highlighting
- If no pattern matches, return last 20 lines of the log as fallback

**Diff summary:**
- Query `CicdRunRecord` for the most recent `success` run of the same `workflowName` + `userId`
- GitHub: call `GET /repos/{owner}/{repo}/compare/{base_sha}...{head_sha}` to get commits and files
- Jenkins: use the build's `changeSet` from the Jenkins API
- Return: list of commits (hash, message, author), total files changed count, list of changed file paths

### 4. New Endpoints

```
GET  /api/cicd/{provider}/runs/{owner}/{repo}/{runId}/failure-analysis
GET  /api/cicd/{provider}/runs/{owner}/{repo}/{runId}/diff-summary
GET  /api/cicd/notification-config
POST /api/cicd/notification-config
```

`{provider}` = `github` or `jenkins`. Jenkins endpoints use `jobName` + `buildNumber` path params instead of `owner/repo/runId`.

---

## Frontend

### New "Health" Tab

Added to `CICDPipelinesPage` as a third tab alongside GitHub and Jenkins.

**`CicdHealthScorecard` component:**
- Responsive grid of cards, one per connected repo/Jenkins server
- Each card: repo/server name, success rate %, last run status badge (color-coded), 7-day sparkline (reuses existing `CicdTrendCharts` data already in store), time since last run ("2h ago")
- Card click navigates to that repo's run list in the GitHub or Jenkins tab
- Empty state: "Connect a GitHub repo or Jenkins server to see health scores"

### Enhanced Failure Detail Dialog

Existing `DialogRoot` in `CICDPipelinesPage` gains a tab strip inside:

**Overview tab** (existing content, unchanged):
- Stage visualization + run history

**Failure Analysis tab** (new):
- Loads only when this tab is clicked — no fetch on dialog open
- Top: `failureCategory` badge + `failedStageName` (from sync data, instant)
- Below: "Why it failed" — top 3 error snippets, each in a monospace code block with the trigger line highlighted
- Skeleton loader while fetching; "No parseable error found — view full logs below" graceful fallback
- "View full logs" button links to existing `CicdLogViewer`

**Diff tab** (new):
- Loads only when this tab is clicked
- Commit list: short hash, message, author, timestamp
- Files changed count + collapsible file path list
- "No previous successful run to compare against" empty state

### Alert Settings

Added to the existing account manager / add-account flow as a new `CicdNotificationSettings` section:
- Email toggle (pre-filled with account email from user profile)
- Slack webhook URL text input with a "Test" button that fires a test POST
- Saved via `POST /api/cicd/notification-config`

---

## New Components

| Component | Location |
|---|---|
| `CicdHealthScorecard` | `components/CICDPipelines/CicdHealthScorecard.tsx` |
| `CicdFailureDetail` | `components/CICDPipelines/CicdFailureDetail.tsx` |
| `CicdDiffSummary` | `components/CICDPipelines/CicdDiffSummary.tsx` |
| `CicdNotificationSettings` | `components/CICDPipelines/CicdNotificationSettings.tsx` |

All follow existing XamOps patterns: `apiClient` for requests, XamOps badge/skeleton/card components, no new libraries.

---

## Error Handling

- Log fetch fails → show "Could not load failure analysis" with retry button; do not block dialog open
- Slack webhook unreachable → log warning, do not fail the sync cycle
- Email send fails → log error, do not set `notificationSent = true` (will retry on next sync)
- `diff-summary` endpoint finds no previous success → return empty response, frontend shows empty state

---

## Out of Scope

- AI-powered log summarization (designed to be added later by replacing the regex path in `FailureAnalysisService`)
- Per-pipeline notification granularity (all or nothing per user for now)
- Jenkins multi-server drill-down (existing limitation; tracked separately)
- Mobile push notifications
