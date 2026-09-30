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
                    List.of("cloudsitter", "cloud sitter", "schedule", "start stop", "ec2", "timezone", "policy"),
                    "CloudSitter schedules your EC2 instances to start and stop on a weekly, " +
                    "timezone-aware grid, so you stop paying for compute outside business hours, and " +
                    "estimates the savings from each schedule."),

            new FeatureFaqEntry("finops-flex-compute", "FinOps", "Flex Compute",
                    List.of("flex compute", "capacity reservation", "ec2", "cloudidr", "scheduled capacity"),
                    "Flex Compute manages scheduled EC2 capacity reservations through a CloudiDR " +
                    "integration, which you connect and authenticate from this page."),

            new FeatureFaqEntry("finops-autospotting", "FinOps", "AutoSpotting",
                    List.of("autospotting", "auto spotting", "spot instances", "asg", "auto scaling group", "mig", "vmss"),
                    "AutoSpotting automates Spot Instance usage inside your Auto Scaling Groups (or GCP " +
                    "MIGs / Azure VMSS), replacing on-demand capacity with spot capacity for up to " +
                    "around 70% compute savings, with per-group enable/disable toggles and a " +
                    "savings-history chart."),

            new FeatureFaqEntry("cloudops-cloudk8s", "CloudOps", "CloudK8s",
                    List.of("kubernetes", "cloud k8s", "eks", "gke", "aks", "cluster", "monitoring", "k8s"),
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
                    List.of("devops in the box", "dev ops", "scripts", "terraform", "bash", "python", "ansible", "templates"),
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
                    List.of("dbops", "db ops", "database", "rds", "idle instance", "snapshot waste"),
                    "DbOps monitors your RDS and other managed database instances: monthly DB spend, " +
                    "savings potential, idle-instance detection, rightsizing candidates, snapshot waste, " +
                    "and per-instance drill-down and alerts."),

            new FeatureFaqEntry("secops-security-center", "SecOps", "Security Center",
                    List.of("security findings", "prowler", "severity", "security center"),
                    "Security Center shows security findings from Prowler scans, filterable by severity " +
                    "and compliance framework."),

            new FeatureFaqEntry("secops-war", "SecOps", "WAR",
                    List.of("war", "attack surface review", "crypto mining", "cryptomining", "miner",
                            "attacker access", "exposure", "hardening", "war score", "attack path",
                            "workload attack surface review"),
                    "WAR (Workload Attack-surface Review) answers what in your infrastructure would let " +
                    "an attacker or crypto-miner into the account, and what stops the bill from growing " +
                    "once they are in. It scores the account against 86 controls from the cloud security " +
                    "advisory baseline, groups failures by attack path (credential theft, public exposure, " +
                    "crypto mining, data exfiltration, detection blind spots), lists the affected " +
                    "resources as evidence, and ranks an ordered fix list."),

            new FeatureFaqEntry("secops-xamsecure", "SecOps", "XamSecure",
                    List.of("xamsecure", "xam secure", "cnapp", "attack surface", "cve", "vulnerabilities", "posture",
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

            new FeatureFaqEntry("secops-xamlens", "SecOps", "XamLens",
                    List.of("dspm", "data security posture management", "sensitive data", "pii",
                            "google workspace", "endpoint data"),
                    "XamLens is data security posture management (DSPM): it finds where sensitive data " +
                    "lives and what kind it is (personal, secrets, financial). Its Cloud tab covers cloud " +
                    "and self-hosted datastores such as S3, EBS, BigQuery and Postgres; SaaS covers " +
                    "accounts such as Google Workspace mail and Drive; Endpoint covers managed laptops " +
                    "and desktops. Each shows record counts, severity, scan status and the files that " +
                    "hold the data."),

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
                    "billing or account issues linked to your cloud accounts."),

            // ── Module-level catch-alls ──────────────────────────────────────────
            // One entry per multi-tab module (from XAMOPS_MODULES), for bare
            // "what is <module>" questions. tabKey uses the module's own key
            // (never a leaf tab key, so no collision with the entries above).
            // Deliberately no keyword duplicates the bare module name — the +3
            // label match alone clears MIN_SCORE, so a module entry never
            // outscores a more specific tab entry on a tab-specific query.
            // "Dashboard" has no catch-all: it's a single-tab module already
            // covered by account-dashboard, and "Dashboard" bare would just
            // collide with sre-dashboard's identical label.

            new FeatureFaqEntry("finops", "FinOps", "FinOps",
                    List.of("finops module", "cost optimization suite", "cloud spend management", "financial operations"),
                    "FinOps is XamOps's cost-management suite — Cost Management, Reservations, FinOps " +
                    "Reports, Waste Management, Rightsizing, Disk Rightsizing, CloudSitter scheduling, " +
                    "Flex Compute, and AutoSpotting all live here, covering cost visibility, savings " +
                    "automation, and spend optimization across your cloud accounts."),

            new FeatureFaqEntry("cloudops", "CloudOps", "CloudOps",
                    List.of("cloudops module", "engineering operations", "devops tooling suite"),
                    "CloudOps covers your engineering operations tooling — CloudK8s cluster monitoring, " +
                    "Code Quality (SonarQube), CICD Pipelines, DevOps-in-the-Box scripts, AIOps (LLM " +
                    "cost/usage monitoring), and DbOps database monitoring."),

            new FeatureFaqEntry("secops", "SecOps", "SecOps",
                    List.of("secops module", "security operations", "cloud security suite"),
                    "SecOps covers your security tooling — Security Center findings, XamSecure (CNAPP), " +
                    "XamLens (data security posture), ComplianceOps framework scoring, Performance " +
                    "Insights, and Alerts."),

            new FeatureFaqEntry("observability", "Observability", "Observability",
                    List.of("observability module", "logs metrics traces", "monitoring suite"),
                    "Observability covers your logs, metrics, and traces — an Overview, Logs explorer, " +
                    "Metrics dashboard, Traces (coming soon), Alerts, a Setup guide, and an embedded " +
                    "Grafana dashboard."),

            new FeatureFaqEntry("sre", "SRE", "SRE",
                    List.of("sre module", "site reliability engineering", "reliability engineering suite"),
                    "SRE covers reliability engineering — a Dashboard of detection events and " +
                    "investigations, an Investigations list for root-cause analysis, and a Setup wizard " +
                    "to connect your existing observability stack. This module is marked Coming Soon in " +
                    "the sidebar."),

            new FeatureFaqEntry("mediaops", "MediaOps", "MediaOps",
                    List.of("mediaops module", "media processing suite", "video transcoding platform"),
                    "MediaOps is XamOps's media processing suite — an Overview, Transcoding Jobs, Live " +
                    "Streams, Media Storage, and Cost Savings versus AWS Media Services, positioned as a " +
                    "lower-cost alternative to AWS MediaConvert, MediaLive, and MediaPackage."),

            new FeatureFaqEntry("licenses", "Licenses", "Licenses",
                    List.of("licenses module", "software licensing", "subscription management"),
                    "Licenses covers your subscriptions and seats — MarketOps (cloud Marketplace " +
                    "subscription billing) and Licenses & Tools (Google Workspace seat tracking and " +
                    "reclaiming)."),

            new FeatureFaqEntry("tickets", "Tickets", "Tickets",
                    List.of("tickets module", "support tickets", "help desk"),
                    "Tickets is where you submit and track support requests — Technical tickets for " +
                    "engineering issues, and Account & Billing tickets for billing or account issues.")
    );
}
