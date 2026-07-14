# Chatbot Feature/Tab Auto-Answer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a XamOps user asks the support chat widget a question that matches a known sidebar feature/tab, the bot answers instantly with a description of what that feature does, instead of silently queuing it for a human agent.

**Architecture:** A new `com.xammer.cloud.service.faq` package holds a hardcoded catalog of ~40 feature entries (one per `xamopsTabs.ts` tab key) and a deterministic keyword/label matcher — no external AI call. `ChatbotController.chat()` runs the incoming message through the matcher first: a match auto-answers (and marks that thread item as already-resolved so it doesn't show up as "unread" in the BillOps admin inbox); no match falls through to the existing human-support flow, completely unchanged. The frontend widget needs no changes — it already renders any `isUserMessage=false` message with bot styling and already re-fetches history right after sending.

**Tech Stack:** Java 17, Spring Boot, JUnit 5 + AssertJ + Mockito (plain `mock()`/`when()`/`verify()`, no `@Mock`/`MockitoExtension` — see `AgentIngestServiceTest.java` for the house style), JPA/Hibernate.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-14-chatbot-feature-faq-design.md` — this plan implements that spec exactly; do not deviate from its scoring rules (label match = +3, keyword match = +1 each, minimum score = 2, ties = no match) or its status semantics table without checking back with the user.
- No frontend changes in this plan — the design confirmed the existing widget/hook already handles this correctly.
- `SupportChatService.sendUserMessage(String, String)`'s public signature and external behavior (default `status="UNREAD"`) must not change — it's the fallback path for every unmatched message today and must remain a pure regression-free no-op change.
- All new Java files go under `com.xammer.cloud.service.faq` (package convention mirrors existing `service/gcp`, `service/azure`, `service/sre` subpackages).
- Test style: JUnit 5, `assertThat` from AssertJ, `org.mockito.Mockito.*` static imports, no Spring test context (plain `new Service(mock(...), mock(...))` construction) — matches `MaintenanceServiceTest.java` / `AgentIngestServiceTest.java`.

---

### Task 1: `FeatureFaqEntry` record and `FeatureFaqCatalog`

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqEntry.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqCatalog.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/faq/FeatureFaqCatalogTest.java`

**Interfaces:**
- Produces: `FeatureFaqEntry(String tabKey, String moduleLabel, String tabLabel, List<String> keywords, String description)` record with accessors `tabKey()`, `moduleLabel()`, `tabLabel()`, `keywords()`, `description()`.
- Produces: `FeatureFaqCatalog.ENTRIES` — `public static final List<FeatureFaqEntry>`, consumed by `FeatureFaqMatcher` in Task 2.

- [ ] **Step 1: Write the failing test**

Create `xamops-service/src/test/java/com/xammer/cloud/service/faq/FeatureFaqCatalogTest.java`:

```java
package com.xammer.cloud.service.faq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFaqCatalogTest {

    // Mirrors frontend-app/src/constants/xamopsTabs.ts ALL_XAMOPS_TAB_KEYS.
    // Kept as a literal list (not shared code — frontend TS and backend Java
    // can't share constants) so this test fails loudly if the two drift.
    private static final List<String> EXPECTED_TAB_KEYS = List.of(
            "account-dashboard",
            "finops-cost", "finops-reservations", "finops-reports", "finops-waste",
            "finops-rightsizing", "finops-disk-rightsizing", "finops-cloudsitter",
            "finops-flex-compute", "finops-autospotting",
            "cloudops-cloudk8s", "cloudops-code-quality", "cloudops-cicd",
            "cloudops-devops-box", "cloudops-aiops", "cloudops-dbops",
            "secops-security-center", "secops-xamsecure", "secops-compliance",
            "secops-performance", "secops-alerts",
            "obs-overview", "obs-logs", "obs-metrics", "obs-traces", "obs-alerts",
            "obs-setup", "obs-grafana",
            "sre-dashboard", "sre-investigations", "sre-setup",
            "mediaops-overview", "mediaops-transcode", "mediaops-streams",
            "mediaops-storage", "mediaops-cost-savings",
            "licenses-marketops", "licenses-tools",
            "xam-tickets-technical", "xam-tickets-account-billing"
    );

    @Test
    void catalog_coversEveryExpectedTabKey() {
        Set<String> actualKeys = FeatureFaqCatalog.ENTRIES.stream()
                .map(FeatureFaqEntry::tabKey)
                .collect(Collectors.toSet());

        assertThat(actualKeys).containsExactlyInAnyOrderElementsOf(EXPECTED_TAB_KEYS);
    }

    @Test
    void catalog_hasNoDuplicateTabKeys() {
        List<String> keys = FeatureFaqCatalog.ENTRIES.stream()
                .map(FeatureFaqEntry::tabKey)
                .toList();

        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void catalog_everyEntryHasNonBlankDescriptionAndAtLeastThreeKeywords() {
        for (FeatureFaqEntry entry : FeatureFaqCatalog.ENTRIES) {
            assertThat(entry.description())
                    .as("description for %s", entry.tabKey())
                    .isNotBlank();
            assertThat(entry.keywords())
                    .as("keywords for %s", entry.tabKey())
                    .hasSizeGreaterThanOrEqualTo(3);
            assertThat(entry.moduleLabel()).as("moduleLabel for %s", entry.tabKey()).isNotBlank();
            assertThat(entry.tabLabel()).as("tabLabel for %s", entry.tabKey()).isNotBlank();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=FeatureFaqCatalogTest`
Expected: FAIL — compilation error, `com.xammer.cloud.service.faq` package / `FeatureFaqEntry` / `FeatureFaqCatalog` do not exist yet.

- [ ] **Step 3: Write the implementation**

Create `xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqEntry.java`:

```java
package com.xammer.cloud.service.faq;

import java.util.List;

/**
 * One entry in the chatbot's feature/tab FAQ knowledge base.
 * tabKey mirrors the keys in frontend-app/src/constants/xamopsTabs.ts.
 */
public record FeatureFaqEntry(
        String tabKey,
        String moduleLabel,
        String tabLabel,
        List<String> keywords,
        String description
) {
}
```

Create `xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqCatalog.java`:

```java
package com.xammer.cloud.service.faq;

import java.util.List;

/**
 * Hardcoded knowledge base for the chatbot's feature/tab auto-answer.
 * One entry per tab key in frontend-app/src/constants/xamopsTabs.ts (XAMOPS_MODULES).
 * Descriptions are sourced from each page's own header/subtitle copy — see
 * docs/superpowers/specs/2026-07-14-chatbot-feature-faq-design.md for sourcing notes.
 */
public final class FeatureFaqCatalog {

    private FeatureFaqCatalog() {
    }

    public static final List<FeatureFaqEntry> ENTRIES = List.of(

            new FeatureFaqEntry("account-dashboard", "Dashboard", "Account Dashboard",
                    List.of("dashboard", "overview", "resource inventory", "kpi", "cost summary", "home"),
                    "The Account Dashboard is your home page for a selected cloud account — real-time " +
                    "resource inventory, cost summaries and trends by service and region, and key KPI " +
                    "cards, for AWS, GCP, or Azure."),

            new FeatureFaqEntry("finops-cost", "FinOps", "Cost Management",
                    List.of("cost", "spend", "billing", "breakdown", "forecast", "budget", "trend"),
                    "Cost Management shows KPI cards for total cost, cost categories, and top spenders, " +
                    "a cost breakdown chart and table, a 6-month cost trend, an ML-powered cost forecast, " +
                    "and lets you create and track budgets."),

            new FeatureFaqEntry("finops-reservations", "FinOps", "Reservations",
                    List.of("reserved instances", "ri", "savings plans", "utilization", "coverage", "purchase recommendation"),
                    "Reservations tracks your Reserved Instance inventory and utilization, monitors " +
                    "Savings Plans, shows historical utilization and coverage trends, and surfaces " +
                    "purchase recommendations."),

            new FeatureFaqEntry("finops-reports", "FinOps", "FinOps Reports",
                    List.of("finops report", "budget", "tagging compliance", "anomaly", "scheduled report", "mtd spend"),
                    "FinOps Reports combines month-to-date spend, forecast, and savings KPIs with budget " +
                    "management, scheduled email reports, cost breakdowns by service and region, " +
                    "tagging-compliance tracking, rightsizing recommendations, waste detection, and cost " +
                    "anomaly alerts in one place."),

            new FeatureFaqEntry("finops-waste", "FinOps", "Waste Management",
                    List.of("waste", "idle", "orphaned", "unused resources", "scan", "delete"),
                    "Waste Management scans your resources to detect idle and orphaned cloud resources, " +
                    "groups them by service with monthly potential-savings totals, and lets you delete " +
                    "flagged resources directly."),

            new FeatureFaqEntry("finops-rightsizing", "FinOps", "Rightsizing",
                    List.of("rightsizing", "compute", "ec2", "instance type", "resize", "downsize", "upsize"),
                    "Rightsizing analyzes your compute instances and shows both cloud-native and XamOps " +
                    "recommendations for resizing under- or over-provisioned instances, with a one-click " +
                    "apply action."),

            new FeatureFaqEntry("finops-disk-rightsizing", "FinOps", "Disk Rightsizing",
                    List.of("ebs", "disk", "volume", "rightsizing", "resize", "shrink", "expand", "unattached", "gp2", "gp3"),
                    "Disk Rightsizing analyzes your EBS, Persistent Disk, or Managed Disk volumes for " +
                    "over- or under-provisioning, recommends shrinking, expanding, or upgrading volume " +
                    "types (like gp2 to gp3), flags unattached volumes, and estimates the ROI of each " +
                    "change — all with zero downtime."),

            new FeatureFaqEntry("finops-cloudsitter", "FinOps", "CloudSitter",
                    List.of("cloudsitter", "schedule", "start stop", "ec2", "timezone", "policy"),
                    "CloudSitter schedules your EC2 instances to start and stop on a weekly, " +
                    "timezone-aware grid, so you stop paying for compute outside business hours, and " +
                    "estimates the savings from each schedule."),

            new FeatureFaqEntry("finops-flex-compute", "FinOps", "Flex Compute",
                    List.of("flex compute", "capacity reservation", "ec2", "cloudidr", "scheduled capacity"),
                    "Flex Compute manages scheduled EC2 capacity reservations through a CloudiDR " +
                    "integration, which you connect and authenticate from this page."),

            new FeatureFaqEntry("finops-autospotting", "FinOps", "AutoSpotting",
                    List.of("autospotting", "spot instances", "asg", "auto scaling group", "mig", "vmss"),
                    "AutoSpotting automates Spot Instance usage inside your Auto Scaling Groups (or GCP " +
                    "MIGs / Azure VMSS), replacing on-demand capacity with spot capacity for up to " +
                    "around 70% compute savings, with per-group enable/disable toggles and a " +
                    "savings-history chart."),

            new FeatureFaqEntry("cloudops-cloudk8s", "CloudOps", "CloudK8s",
                    List.of("kubernetes", "eks", "gke", "aks", "cluster", "monitoring", "k8s"),
                    "CloudK8s monitors your EKS, GKE, or AKS clusters in real time, lets you enable " +
                    "monitoring per cluster, and walks you through observability setup."),

            new FeatureFaqEntry("cloudops-code-quality", "CloudOps", "Code Quality",
                    List.of("sonarqube", "code quality", "bugs", "vulnerabilities", "coverage", "quality gate"),
                    "Code Quality integrates with SonarQube: register your projects and view bugs, " +
                    "vulnerabilities, code coverage percentage, and quality-gate status for each one."),

            new FeatureFaqEntry("cloudops-cicd", "CloudOps", "CICD Pipelines",
                    List.of("cicd", "pipeline", "github actions", "jenkins", "dora metrics", "build", "deployment"),
                    "CICD Pipelines pulls in your GitHub Actions and Jenkins run history, shows pipeline " +
                    "stage topology and DORA metrics with trend charts, and includes failure analysis " +
                    "with log viewing."),

            new FeatureFaqEntry("cloudops-devops-box", "CloudOps", "DevOps-in-the-Box",
                    List.of("devops in the box", "scripts", "terraform", "bash", "python", "ansible", "templates"),
                    "DevOps-in-the-Box is a searchable library of ready-made infrastructure scripts " +
                    "(Terraform, Bash, Python, Ansible) with fill-in placeholder tokens that you can " +
                    "customize and download."),

            new FeatureFaqEntry("cloudops-aiops", "CloudOps", "AIOps",
                    List.of("aiops", "llm", "ai gateway", "litellm", "token usage", "cost optimizer", "inference", "api keys"),
                    "AIOps is an LLM-ops cost and usage monitoring suite: an LLM dashboard, usage " +
                    "analytics, budgets and controls, a cost optimizer, API key management, managed " +
                    "inference, an AI gateway (LiteLLM), request logs, agents, model comparisons, and a " +
                    "token usage heatmap."),

            new FeatureFaqEntry("cloudops-dbops", "CloudOps", "DbOps",
                    List.of("dbops", "database", "rds", "idle instance", "snapshot waste"),
                    "DbOps monitors your RDS and other managed database instances: monthly DB spend, " +
                    "savings potential, idle-instance detection, rightsizing candidates, snapshot waste, " +
                    "and per-instance drill-down and alerts."),

            new FeatureFaqEntry("secops-security-center", "SecOps", "Security Center",
                    List.of("security findings", "prowler", "severity", "security center"),
                    "Security Center shows security findings from Prowler scans, filterable by severity " +
                    "and compliance framework."),

            new FeatureFaqEntry("secops-xamsecure", "SecOps", "XamSecure",
                    List.of("xamsecure", "cnapp", "attack surface", "cve", "vulnerabilities", "posture",
                            "exposed resources", "api security"),
                    "XamSecure is a cloud security (CNAPP) module covering an Overview, Issues, asset " +
                    "Inventory, Attack Surface (internet-exposed resources), Vulnerabilities (CVE " +
                    "tracking), Posture, API Security, Threats, Compliance, and account Onboarding."),

            new FeatureFaqEntry("secops-compliance", "SecOps", "ComplianceOps",
                    List.of("compliance", "cis", "soc2", "pci dss", "hipaa", "nist", "iso 27001", "gdpr", "framework"),
                    "ComplianceOps runs Prowler compliance scans and scores your account against " +
                    "frameworks like CIS, SOC 2, PCI DSS, HIPAA, NIST CSF, ISO 27001, GDPR, MITRE " +
                    "ATT&CK, and FedRAMP, showing pass/fail checks per framework."),

            new FeatureFaqEntry("secops-performance", "SecOps", "Performance Insights",
                    List.of("performance insights", "best practices", "audit", "performance score", "csv export"),
                    "Performance Insights gives you an overall performance score, critical insights " +
                    "filterable by severity, and best-practice audits, with bulk-archive and CSV export."),

            new FeatureFaqEntry("secops-alerts", "SecOps", "Alerts",
                    List.of("cloudguard", "quota alerts", "secops alerts", "security alerts"),
                    "SecOps Alerts combines CloudGuard alerts (quota and anomaly alerts filterable by " +
                    "service) with a Grafana Alerts tab for external alert monitoring."),

            new FeatureFaqEntry("obs-overview", "Observability", "Overview",
                    List.of("observability overview", "openobserve", "data streams", "retention", "org id"),
                    "The Observability Overview shows stat cards for your total log, metric, and trace " +
                    "data streams, your data retention period, and your OpenObserve org ID."),

            new FeatureFaqEntry("obs-logs", "Observability", "Logs",
                    List.of("logs", "log explorer", "search logs", "query logs", "observability logs"),
                    "The Logs tab is a log explorer for querying and browsing the log data ingested into " +
                    "your Observability workspace."),

            new FeatureFaqEntry("obs-metrics", "Observability", "Metrics",
                    List.of("metrics", "time series", "cpu", "memory", "observability metrics"),
                    "The Metrics tab is a dashboard of time-series metrics — CPU, memory, disk, and " +
                    "network — collected from your connected hosts."),

            new FeatureFaqEntry("obs-traces", "Observability", "Traces",
                    List.of("traces", "distributed tracing", "opentelemetry", "otlp", "coming soon"),
                    "Traces is a coming-soon feature for distributed request tracing — the plan is to " +
                    "instrument your services with the OpenTelemetry SDK and deploy the OTel Collector, " +
                    "but it isn't functional in the product yet."),

            new FeatureFaqEntry("obs-alerts", "Observability", "Alerts",
                    List.of("alert rules", "threshold", "webhook", "notification", "observability alerts"),
                    "Observability Alerts lets you create alert rules from templates with configurable " +
                    "thresholds, evaluation periods, and silence windows, routing notifications to your " +
                    "configured webhook destinations."),

            new FeatureFaqEntry("obs-setup", "Observability", "Setup",
                    List.of("otel collector", "ec2", "gce", "eks", "helm", "observability setup"),
                    "The Observability Setup tab walks you through installing the OTel Collector — as a " +
                    "systemd service on EC2/GCE Linux hosts today, with Helm/kubectl support for EKS " +
                    "coming soon — plus retention and other settings."),

            new FeatureFaqEntry("obs-grafana", "Observability", "Grafana",
                    List.of("grafana", "infrastructure monitoring", "terraform setup", "grafana dashboard"),
                    "The Grafana tab embeds an Infrastructure Monitoring Grafana dashboard directly in " +
                    "XamOps, based on your account's Grafana configuration, with a Terraform setup guide " +
                    "and a link to open it in Grafana directly."),

            new FeatureFaqEntry("sre-dashboard", "SRE", "Dashboard",
                    List.of("sre dashboard", "investigations", "detection events", "mttr"),
                    "The SRE Dashboard shows recent detection events and investigations, currently " +
                    "active investigations, and SRE stats like total investigations, completed today, " +
                    "failed today, and average MTTR. This module is marked Coming Soon in the sidebar."),

            new FeatureFaqEntry("sre-investigations", "SRE", "Investigations",
                    List.of("investigations", "root cause", "auto-detect", "ec2", "k8s pod", "rds", "mttr"),
                    "Investigations lists auto- or manually-triggered root-cause investigations across " +
                    "EC2, Kubernetes pods, RDS, and Azure/GCP VMs, filterable by status (Active, " +
                    "Completed, Failed) and searchable by service or root cause. This module is marked " +
                    "Coming Soon in the sidebar."),

            new FeatureFaqEntry("sre-setup", "SRE", "Setup",
                    List.of("sre setup", "prometheus", "datadog", "new relic", "openobserve", "detection stream"),
                    "SRE Setup is a wizard for connecting your existing observability stack — " +
                    "Prometheus/Grafana, Datadog, New Relic, or OpenObserve — so XamOps can auto-trigger " +
                    "root-cause investigations from detection events. This module is marked Coming Soon " +
                    "in the sidebar."),

            new FeatureFaqEntry("mediaops-overview", "MediaOps", "Overview",
                    List.of("mediaops overview", "transcode", "live stream", "media processing", "aws mediaconvert"),
                    "MediaOps Overview positions XamOps as a lower-cost alternative to AWS MediaConvert, " +
                    "MediaLive and MediaPackage, showing stats for active streams, jobs running and " +
                    "completed, total viewers, storage used, and savings versus AWS."),

            new FeatureFaqEntry("mediaops-transcode", "MediaOps", "Transcoding Jobs",
                    List.of("transcoding", "codec", "watermark", "trim", "simple mode", "advanced mode"),
                    "Transcoding Jobs lets you create and track video transcode jobs, in a Simple mode " +
                    "(pick a format and go) or an Advanced mode with full control over codecs, " +
                    "watermarks, and trimming."),

            new FeatureFaqEntry("mediaops-streams", "MediaOps", "Live Streams",
                    List.of("live streams", "rtmp", "hls", "broadcast", "viewers"),
                    "Live Streams lets you create and monitor RTMP/HLS live streams, including stream " +
                    "status and viewer counts."),

            new FeatureFaqEntry("mediaops-storage", "MediaOps", "Media Storage",
                    List.of("media storage", "upload assets", "video storage", "audio storage", "storage quota"),
                    "Media Storage lets you browse, upload, and manage every sourced and transcoded " +
                    "asset, and shows storage usage by type (video, HLS, audio, image) against your " +
                    "quota."),

            new FeatureFaqEntry("mediaops-cost-savings", "MediaOps", "Cost Savings",
                    List.of("mediaops cost savings", "aws media services", "monthly savings", "annual savings"),
                    "MediaOps Cost Savings compares what AWS Media Services would charge for the same " +
                    "pipeline against XamOps MediaOps pricing, showing monthly and annual potential " +
                    "savings alongside actual tracked savings."),

            new FeatureFaqEntry("licenses-marketops", "Licenses", "MarketOps",
                    List.of("marketplace", "subscriptions", "meter billing", "aws marketplace", "marketops"),
                    "MarketOps shows every AWS (or other cloud) Marketplace product subscription for the " +
                    "selected account, with detailed meter billing per product — a 12-month chart and " +
                    "table, plus a daily view per month."),

            new FeatureFaqEntry("licenses-tools", "Licenses", "Licenses & Tools",
                    List.of("google workspace", "license", "seat", "inactive user", "reclaim seats"),
                    "Licenses & Tools connects to Google Workspace to track seat usage and identify " +
                    "inactive accounts, giving you visibility into every user's last login and " +
                    "auto-detecting inactive or never-logged-in users so you can reclaim unused seats."),

            new FeatureFaqEntry("xam-tickets-technical", "Tickets", "Technical",
                    List.of("technical ticket", "technical support", "create ticket", "support request"),
                    "The Technical Tickets tab lets you submit and track technical support requests, " +
                    "with real-time updates as your ticket progresses."),

            new FeatureFaqEntry("xam-tickets-account-billing", "Tickets", "Account & Billing",
                    List.of("billing ticket", "account support", "billops ticket", "billing case"),
                    "The Account & Billing Tickets tab lets you create and track support cases for " +
                    "billing or account issues linked to your cloud accounts.")
    );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=FeatureFaqCatalogTest`
Expected: PASS — 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqEntry.java \
        xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqCatalog.java \
        xamops-service/src/test/java/com/xammer/cloud/service/faq/FeatureFaqCatalogTest.java
git commit -m "feat(chatbot): add feature/tab FAQ knowledge base catalog"
```

---

### Task 2: `FeatureFaqMatcher`

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqMatcher.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/faq/FeatureFaqMatcherTest.java`

**Interfaces:**
- Consumes: `FeatureFaqCatalog.ENTRIES` (Task 1), `FeatureFaqEntry` record (Task 1).
- Produces: `FeatureFaqMatcher` — a `@Component`-annotated Spring bean with `Optional<FeatureFaqEntry> match(String rawMessage)`. Consumed by `ChatbotController` in Task 4.

- [ ] **Step 1: Write the failing test**

Create `xamops-service/src/test/java/com/xammer/cloud/service/faq/FeatureFaqMatcherTest.java`:

```java
package com.xammer.cloud.service.faq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFaqMatcherTest {

    private FeatureFaqMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new FeatureFaqMatcher();
    }

    @Test
    void match_returnsEntry_whenExactLabelPhrasePresent() {
        Optional<FeatureFaqEntry> result = matcher.match("what does disk rightsizing do?");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("finops-disk-rightsizing");
    }

    @Test
    void match_isCaseAndPunctuationInsensitive() {
        Optional<FeatureFaqEntry> result = matcher.match("WHAT DOES DISK RIGHTSIZING DO?!?!");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("finops-disk-rightsizing");
    }

    @Test
    void match_returnsEntry_whenEnoughKeywordsPresentWithoutExactLabel() {
        // "gp2" + "gp3" + "unattached" = 3 keyword hits, no label phrase — above threshold.
        Optional<FeatureFaqEntry> result = matcher.match("can you resize my gp2 volume to gp3, it's unattached");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("finops-disk-rightsizing");
    }

    @Test
    void match_returnsEmpty_whenBelowMinimumScore() {
        // "cost" alone is a single keyword hit (score 1) on multiple entries — below threshold of 2.
        Optional<FeatureFaqEntry> result = matcher.match("cost");

        assertThat(result).isEmpty();
    }

    @Test
    void match_returnsEmpty_whenTopScoreIsTiedBetweenTwoEntries() {
        // "Alerts" is a tab label shared by secops-alerts and obs-alerts — a bare
        // "what does alerts do" ties at the label-match score with no differentiating
        // keywords, so the matcher must not guess.
        Optional<FeatureFaqEntry> result = matcher.match("what does alerts do");

        assertThat(result).isEmpty();
    }

    @Test
    void match_breaksTie_whenOneEntryHasAdditionalKeywordHits() {
        // Still contains the tied "alerts" label match, but "cloudguard" is a
        // secops-alerts-only keyword, pushing its score above obs-alerts.
        Optional<FeatureFaqEntry> result = matcher.match("what does the cloudguard alerts tab do");

        assertThat(result).isPresent();
        assertThat(result.get().tabKey()).isEqualTo("secops-alerts");
    }

    @Test
    void match_returnsEmpty_forBlankOrNullMessage() {
        assertThat(matcher.match("")).isEmpty();
        assertThat(matcher.match("   ")).isEmpty();
        assertThat(matcher.match(null)).isEmpty();
    }

    @Test
    void match_returnsEmpty_forUnrelatedFreeTextMessage() {
        Optional<FeatureFaqEntry> result = matcher.match("hi, my invoice for last month looks wrong, can someone check?");

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=FeatureFaqMatcherTest`
Expected: FAIL — compilation error, `FeatureFaqMatcher` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqMatcher.java`:

```java
package com.xammer.cloud.service.faq;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic keyword/label matcher for the chatbot's feature/tab FAQ.
 * Scoring: +3 if the tab's own label appears as a phrase in the message,
 * +1 per catalog keyword found in the message. Minimum score to fire is 2.
 * A tie for the top score is treated as no match — escalating a message to
 * human support is safer than guessing between two ambiguous features.
 */
@Component
public class FeatureFaqMatcher {

    private static final int LABEL_MATCH_SCORE = 3;
    private static final int KEYWORD_MATCH_SCORE = 1;
    private static final int MIN_SCORE = 2;

    public Optional<FeatureFaqEntry> match(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return Optional.empty();
        }

        String normalizedMessage = normalize(rawMessage);

        List<ScoredEntry> scored = FeatureFaqCatalog.ENTRIES.stream()
                .map(entry -> new ScoredEntry(entry, score(entry, normalizedMessage)))
                .filter(se -> se.score() >= MIN_SCORE)
                .sorted(Comparator.comparingInt(ScoredEntry::score).reversed())
                .toList();

        if (scored.isEmpty()) {
            return Optional.empty();
        }
        if (scored.size() > 1 && scored.get(0).score() == scored.get(1).score()) {
            return Optional.empty();
        }
        return Optional.of(scored.get(0).entry());
    }

    private int score(FeatureFaqEntry entry, String normalizedMessage) {
        int score = 0;
        if (normalizedMessage.contains(normalize(entry.tabLabel()))) {
            score += LABEL_MATCH_SCORE;
        }
        for (String keyword : entry.keywords()) {
            if (normalizedMessage.contains(normalize(keyword))) {
                score += KEYWORD_MATCH_SCORE;
            }
        }
        return score;
    }

    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record ScoredEntry(FeatureFaqEntry entry, int score) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=FeatureFaqMatcherTest`
Expected: PASS — 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/faq/FeatureFaqMatcher.java \
        xamops-service/src/test/java/com/xammer/cloud/service/faq/FeatureFaqMatcherTest.java
git commit -m "feat(chatbot): add deterministic FAQ matcher for feature/tab questions"
```

---

### Task 3: `SupportChatService` — auto-resolved question + bot answer methods

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/SupportChatService.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/SupportChatServiceTest.java` (new)

**Interfaces:**
- Consumes: `SupportMessage` (existing domain, `xamops-service/src/main/java/com/xammer/cloud/domain/SupportMessage.java`), `SupportMessageRepository`, `UserRepository`, `SecurityContextHolder`.
- Produces: `SupportChatService.recordAutoResolvedQuestion(String messageContent, String selectedAccountId)` and `SupportChatService.sendBotAnswer(String content)`, both returning `SupportMessage`. Consumed by `ChatbotController` in Task 4. Public signature of `sendUserMessage(String, String)` is unchanged.

- [ ] **Step 1: Write the failing test**

Create `xamops-service/src/test/java/com/xammer/cloud/service/SupportChatServiceTest.java`:

```java
package com.xammer.cloud.service;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.SupportMessageRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportChatServiceTest {

    private SupportMessageRepository messageRepository;
    private UserRepository userRepository;
    private SupportChatService service;
    private User user;

    @BeforeEach
    void setUp() {
        messageRepository = mock(SupportMessageRepository.class);
        userRepository = mock(UserRepository.class);
        service = new SupportChatService(messageRepository, userRepository);

        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setClient(null);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("x").authorities("ROLE_XAMOPS").build();
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        when(messageRepository.save(any(SupportMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sendUserMessage_stillDefaultsToUnreadStatus() {
        SupportMessage result = service.sendUserMessage("what does disk rightsizing do?", null);

        assertThat(result.getStatus()).isEqualTo("UNREAD");
        assertThat(result.isUserMessage()).isTrue();
        assertThat(result.getContent()).isEqualTo("what does disk rightsizing do?");
    }

    @Test
    void recordAutoResolvedQuestion_setsStatusToRead() {
        SupportMessage result = service.recordAutoResolvedQuestion("what does disk rightsizing do?", null);

        assertThat(result.getStatus()).isEqualTo("READ");
        assertThat(result.isUserMessage()).isTrue();
        assertThat(result.getContent()).isEqualTo("what does disk rightsizing do?");
    }

    @Test
    void sendBotAnswer_createsDeliveredNonUserMessageForCurrentUser() {
        SupportMessage result = service.sendBotAnswer("Disk Rightsizing analyzes your volumes.");

        assertThat(result.getStatus()).isEqualTo("DELIVERED");
        assertThat(result.isUserMessage()).isFalse();
        assertThat(result.getContent()).isEqualTo("Disk Rightsizing analyzes your volumes.");
        assertThat(result.getSender()).isEqualTo(user);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=SupportChatServiceTest`
Expected: FAIL — `recordAutoResolvedQuestion` and `sendBotAnswer` do not exist yet.

- [ ] **Step 3: Write the implementation**

In `xamops-service/src/main/java/com/xammer/cloud/service/SupportChatService.java`, replace the existing `sendUserMessage` method with the four methods below (`sendUserMessage`, the new `recordAutoResolvedQuestion`, the new private `saveUserMessage` helper that both call, and the new `sendBotAnswer` — insert all four in place of the old `sendUserMessage`; existing imports, `getAccountIdForCurrentUser`, `getCurrentUser`, and every other method in the file are untouched):

```java
    @Transactional
    public SupportMessage sendUserMessage(String messageContent, String selectedAccountId) {
        return saveUserMessage(messageContent, selectedAccountId, "UNREAD");
    }

    /**
     * Records a user's question that the chatbot auto-answered from the feature FAQ.
     * Saved with status "READ" (not the default "UNREAD") so it does not count toward
     * the BillOps admin inbox's unread/needs-attention badge — the question was already
     * resolved automatically, not escalated to a human.
     */
    @Transactional
    public SupportMessage recordAutoResolvedQuestion(String messageContent, String selectedAccountId) {
        return saveUserMessage(messageContent, selectedAccountId, "READ");
    }

    private SupportMessage saveUserMessage(String messageContent, String selectedAccountId, String status) {
        // 1. Get Logged-in User
        String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 2. Identify Context (Account ID, Name, Cloud)
        String accountId = "N/A";
        String accountName = "Unknown";
        String provider = "Unknown";

        if (user.getClient() != null && user.getClient().getCloudAccounts() != null && !user.getClient().getCloudAccounts().isEmpty()) {
            // Find the account matching the selected accountId, or fall back to first account
            CloudAccount account = user.getClient().getCloudAccounts().stream()
                    .filter(a -> a.getProviderAccountId() != null && a.getProviderAccountId().equals(selectedAccountId))
                    .findFirst()
                    .orElse(user.getClient().getCloudAccounts().get(0));

            accountId = account.getProviderAccountId();
            accountName = account.getAccountName();
            provider = account.getProvider();
        }

        // 3. Create and Save Message
        SupportMessage supportMessage = new SupportMessage(user, messageContent, true);
        supportMessage.setAccountId(accountId);
        supportMessage.setAccountName(accountName);
        supportMessage.setCloudProvider(provider);
        supportMessage.setSenderUsername(user.getUsername());
        supportMessage.setStatus(status);

        return messageRepository.save(supportMessage);
    }

    /**
     * Persists an automated bot reply (chatbot FAQ auto-answer) for the current user.
     * Uses status "DELIVERED" so it participates in the existing unread-badge and
     * read-receipt flow the same way a human admin reply does.
     */
    @Transactional
    public SupportMessage sendBotAnswer(String content) {
        User user = getCurrentUser();
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        SupportMessage botMessage = new SupportMessage(user, content, false);
        botMessage.setStatus("DELIVERED");
        botMessage.setSenderUsername("XamOps Assistant");

        return messageRepository.save(botMessage);
    }
```

This is a pure refactor of `sendUserMessage`'s body into a shared `saveUserMessage` helper — its public signature, return type, and default `"UNREAD"` status behavior are unchanged, so every existing caller keeps working exactly as before.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=SupportChatServiceTest`
Expected: PASS — 3 tests green.

`SupportChatService` has no other existing test file, and its only other caller besides `ChatbotController` is `BillOpsSupportController.deleteAdminMessage(...)`, which this refactor does not touch — so this is the full regression check for the change.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/SupportChatService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/SupportChatServiceTest.java
git commit -m "feat(chatbot): add auto-resolved question and bot answer persistence"
```

---

### Task 4: Wire `ChatbotController` to the matcher

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/ChatbotController.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/controller/ChatbotControllerTest.java` (new)

**Interfaces:**
- Consumes: `FeatureFaqMatcher.match(String)` (Task 2), `SupportChatService.recordAutoResolvedQuestion(String, String)` and `SupportChatService.sendBotAnswer(String)` (Task 3), `SupportChatService.sendUserMessage(String, String)` (existing, unchanged).
- Produces: `POST /api/ai/chat` behavior change — matched messages auto-answer, unmatched messages behave exactly as before. No other endpoint in this controller changes.

- [ ] **Step 1: Write the failing test**

Create `xamops-service/src/test/java/com/xammer/cloud/controller/ChatbotControllerTest.java`:

```java
package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.SupportChatService;
import com.xammer.cloud.service.faq.FeatureFaqEntry;
import com.xammer.cloud.service.faq.FeatureFaqMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatbotControllerTest {

    private SupportChatService supportChatService;
    private FeatureFaqMatcher featureFaqMatcher;
    private ChatbotController controller;

    @BeforeEach
    void setUp() {
        supportChatService = mock(SupportChatService.class);
        featureFaqMatcher = mock(FeatureFaqMatcher.class);
        controller = new ChatbotController(supportChatService, featureFaqMatcher);
    }

    @Test
    void chat_whenMatched_autoAnswersAndDoesNotForwardToHuman() {
        FeatureFaqEntry entry = new FeatureFaqEntry(
                "finops-disk-rightsizing", "FinOps", "Disk Rightsizing",
                List.of("disk", "ebs"), "Disk Rightsizing analyzes your volumes.");
        when(featureFaqMatcher.match("what does disk rightsizing do")).thenReturn(Optional.of(entry));

        ChatRequest request = new ChatRequest();
        request.setMessage("what does disk rightsizing do");
        request.setAccountId("acc-1");

        ChatResponse response = controller.chat(request);

        verify(supportChatService).recordAutoResolvedQuestion("what does disk rightsizing do", "acc-1");
        verify(supportChatService).sendBotAnswer(contains("Disk Rightsizing analyzes your volumes."));
        verify(supportChatService, never()).sendUserMessage(anyString(), anyString());
        assertThat(response.getResponse()).contains("Disk Rightsizing analyzes your volumes.");
        assertThat(response.getResponse()).contains("Did this answer your question?");
    }

    @Test
    void chat_whenUnmatched_behavesExactlyAsBefore() {
        when(featureFaqMatcher.match("my billing is wrong")).thenReturn(Optional.empty());

        ChatRequest request = new ChatRequest();
        request.setMessage("my billing is wrong");
        request.setAccountId("acc-1");

        ChatResponse response = controller.chat(request);

        verify(supportChatService).sendUserMessage("my billing is wrong", "acc-1");
        verify(supportChatService, never()).recordAutoResolvedQuestion(anyString(), anyString());
        verify(supportChatService, never()).sendBotAnswer(anyString());
        assertThat(response.getResponse()).isEqualTo("Request received. An agent will be with you shortly.");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=ChatbotControllerTest`
Expected: FAIL — `ChatbotController`'s constructor doesn't accept a `FeatureFaqMatcher` yet, `recordAutoResolvedQuestion`/`sendBotAnswer` mocks fine (Task 3 already added them) but production code doesn't call them.

- [ ] **Step 3: Write the implementation**

Replace `xamops-service/src/main/java/com/xammer/cloud/controller/ChatbotController.java` in full:

```java
package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.SupportChatService;
import com.xammer.cloud.service.faq.FeatureFaqEntry;
import com.xammer.cloud.service.faq.FeatureFaqMatcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai") // Keeping path same for now to match frontend
public class ChatbotController {

    private static final String FAQ_FOLLOW_UP =
            " Did this answer your question? If not, just send another message and I'll loop in our support team.";

    private final SupportChatService supportChatService;
    private final FeatureFaqMatcher featureFaqMatcher;

    public ChatbotController(SupportChatService supportChatService, FeatureFaqMatcher featureFaqMatcher) {
        this.supportChatService = supportChatService;
        this.featureFaqMatcher = featureFaqMatcher;
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        Optional<FeatureFaqEntry> match = featureFaqMatcher.match(request.getMessage());

        if (match.isPresent()) {
            // Question matched a known feature/tab — auto-answer instead of queuing for a human.
            supportChatService.recordAutoResolvedQuestion(request.getMessage(), request.getAccountId());
            String answer = match.get().description() + FAQ_FOLLOW_UP;
            supportChatService.sendBotAnswer(answer);
            return new ChatResponse(answer);
        }

        // 1. Send message to Human Support (Database)
        supportChatService.sendUserMessage(request.getMessage(), request.getAccountId());

        // 2. Return a standard acknowledgement
        // In a real real-time app, this might be empty, but we return a system note.
        return new ChatResponse("Request received. An agent will be with you shortly.");
    }

    @GetMapping("/chat/history")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public List<SupportMessage> getChatHistory(@RequestParam(required = false) String accountId) {
        return supportChatService.getMessagesForCurrentUser(accountId);
    }

    /**
     * Get unread count of admin messages for the current user.
     * This allows the frontend to efficiently check for new messages without fetching full history.
     */
    @GetMapping("/chat/unread")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public Map<String, Object> getUnreadCount(@RequestParam(required = false) String accountId) {
        long unreadCount = supportChatService.getUnreadAdminMessageCount(accountId);
        Map<String, Object> response = new HashMap<>();
        response.put("unreadCount", unreadCount);
        return response;
    }

    /**
     * Mark all admin messages as read for the current user's conversation.
     * Called when the user opens the chat widget.
     */
    @PostMapping("/chat/mark-read")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public Map<String, Object> markMessagesAsRead(@RequestParam(required = false) String accountId) {
        supportChatService.markAdminMessagesAsRead(accountId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return response;
    }

    /** Edit own message within 15-minute window */
    @PatchMapping("/chat/messages/{id}")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public ResponseEntity<SupportMessage> editMessage(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload) {
        return supportChatService.editMessage(id, payload.getOrDefault("content", ""))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(403).build());
    }

    /** Soft-delete own message within 60-minute window */
    @DeleteMapping("/chat/messages/{id}")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public ResponseEntity<SupportMessage> deleteMessage(@PathVariable Long id) {
        return supportChatService.deleteMessage(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(403).build());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=ChatbotControllerTest`
Expected: PASS — 2 tests green.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/ChatbotController.java \
        xamops-service/src/test/java/com/xammer/cloud/controller/ChatbotControllerTest.java
git commit -m "feat(chatbot): auto-answer feature/tab questions before human handover"
```

---

### Task 5: Full backend build and manual verification

**Files:** none (verification only).

**Interfaces:** none — this task only runs the suite and exercises the running app.

- [ ] **Step 1: Run the full backend test suite**

Run: `mvn -f xamops-service/pom.xml test`
Expected: BUILD SUCCESS, all tests pass (including the pre-existing suite — confirms the `SupportChatService` refactor introduced no regressions).

- [ ] **Step 2: Start the backend and frontend locally**

Run (in separate terminals, from repo root):
```bash
mvn -f xamops-service/pom.xml spring-boot:run
```
```bash
cd frontend-app && npm run dev
```

- [ ] **Step 3: Manually verify the matched path**

In the browser, log in as a XamOps user, open the support chat widget (bottom-right floating button), and send: `what does disk rightsizing do?`

Expected: an instant bot reply appears containing the Disk Rightsizing description and the "Did this answer your question?" follow-up — no delay, no "Request received" ack text.

- [ ] **Step 4: Manually verify the unmatched path is unchanged**

In the same widget, send: `my invoice from last month looks wrong`

Expected: the reply is exactly `"Request received. An agent will be with you shortly."`, matching current production behavior.

- [ ] **Step 5: Manually verify BillOps admin inbox is unaffected by auto-answered questions**

Log in as a BillOps admin (or whichever role has `ROLE_BILLOPS_ADMIN`/`ROLE_SUPER_ADMIN`), open the support inbox, and confirm:
- The conversation with the test user from Step 3/4 appears.
- Its `unreadCount` reflects only the Step 4 message (the escalated one) — the Step 3 auto-answered question does not count toward it.
- Opening the full thread shows both the Step 3 question and the bot's answer, for audit purposes.

- [ ] **Step 6: Commit if any fixups were needed during manual verification**

If Steps 3-5 required any code fixes, stage and commit them with a descriptive message before considering this plan complete. If no fixups were needed, this step is a no-op.
