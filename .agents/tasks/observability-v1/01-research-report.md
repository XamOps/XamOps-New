# XamOps Observability 1.0 — Phase 1 Research Report

Status: planning document. No production code written. Companion: `00-inspection-notes.md` (repo evidence, exact paths/signatures).

## How this research was done (and its limits)

- **Verified live (Oct 2026) by fetching primary sources:** licenses, stars, last-push dates and latest release tags of every OSS project via the GitHub API; OpenTelemetry Collector Contrib component stability from each component's `metadata.yaml`; VictoriaLogs/VictoriaMetrics docs from their GitHub repos; vendor pricing pages for Datadog, New Relic, Grafana Cloud, SigNoz, Honeycomb, Dynatrace, AWS CloudWatch, Google Cloud Logging, Azure Monitor. Source links are inline.
- **Not verified (flagged `[unverified]`):** feature rows for products whose pages were JavaScript-rendered or gated (Last9, Better Stack telemetry details, Dynatrace Davis internals), benchmark and compression numbers (vendor claims or planning assumptions), and S3 storage-class prices (published AWS list prices, region-dependent; the product will read live prices from the AWS Pricing API, which the backend already wraps as `AwsClientProvider.getPricingClient()`).
- No web *search* tool was available in this step, so discovery of reference products was from the brief plus known market leaders. Treat the feature matrix as a design input, not a marketing comparison.
- Dates: GitHub data was fetched 2026-10-30; versions quoted are the latest releases on that date.

---

## 1. Executive summary

**Product shape.** XamOps Observability 1.0 is a standalone, tenant-isolated telemetry product (logs + host/container metrics now, traces/APM later) whose differentiator is *onboarding in one step from an AWS account the customer has already connected to XamOps* and *customer-owned log storage in their own S3*. Everything else (search, dashboards, alerting, AI) must be at "big product" quality, but the hard commercial promise is: **connect, see data in under 5 minutes, own your data, never get a surprise bill.**

**Recommended stack (one line each):**

| Layer | Choice | License |
|---|---|---|
| Host collector | Custom-built OpenTelemetry Collector ("xamops-otelcol", built with `ocb`) with hostmetrics, filelog, journald, docker/containerd-aware log paths, windowseventlog, prometheus, OTLP receivers | Apache-2.0 |
| Host supervisor/installer | New Go binary `xamops-obs-agent` in the existing `xamops-agent` repo: registration, heartbeat, remote config (REST poll + ETag), health, self-update with checksum, rollback, uninstall; manages the collector as a child process | in-house (Apache-2.0 deps) |
| Transport | OTLP/HTTP (protobuf, gzip) over 443, outbound only, per-host rotating ingestion token | Apache-2.0 |
| Ingest gateway | OTel Collector (gateway mode, same custom build) behind nginx/ALB with token check; stamps tenant and account from the token, never from the payload; redaction; fan-out | Apache-2.0 |
| Durable buffer | Kafka-API broker (the repo already carries Kafka client config; prod broker existence must be confirmed) | Apache-2.0 |
| Hot logs | VictoriaLogs (single node -> cluster) with LogsQL, `(AccountID, ProjectID)` multitenancy, built-in live tail, facets, hits | Apache-2.0 |
| Hot metrics | VictoriaMetrics (cluster edition for tenant-by-URL isolation; single-node + enforced label for pilot) with MetricsQL/PromQL | Apache-2.0 |
| Cold / archive | Compressed Parquet (ZSTD) written by an in-house Go "archiver" to the **customer's S3** (or XamOps-managed fallback bucket) in the requested layout; queried by DuckDB workers, optional Athena | Apache-2.0 (parquet-go), MIT (DuckDB) |
| Control-plane DB | PostgreSQL (existing) via Flyway | PostgreSQL |
| Alerting, SLO, anomaly, forecasting | In-house evaluator in Spring (scheduled, Redis-locked), statistical baselines (robust z-score/STL/Holt-Winters) | in-house |
| AI features | LLM via the platform's existing gateway pattern, on redacted, sampled excerpts only | n/a |
| Traces (phase 3) | OTLP accepted from day 1; store in VictoriaTraces (pre-1.0, watch) or Jaeger v2; eBPF auto-instrumentation via OpenTelemetry eBPF Instrumentation / Beyla | Apache-2.0 |
| Frontend | Existing React 19 + Vite + Tailwind + react-query; CloudK8s shared primitives (recharts), plus uPlot (MIT) for dense series | MIT / Apache-2.0 |

**Agent decision:** do **not** extend the existing agent's collect-and-push pipeline; **do** extend the existing `xamops-agent` repo with a new supervisor binary that owns identity/auth/install/update, and run a purpose-built OpenTelemetry Collector as the data plane (separate process). Evidence and reasoning in section 7.

**Hard rule on licenses:** nothing AGPL ships inside the data path (Loki, Mimir, Tempo, Grafana, OpenObserve all rejected). If a component is AGPL it is listed in the rejected table with the reason.

---

## 2. Reference product research

### 2.1 Pricing models (verified from vendor pages, Oct 2026)

| Product | Model (as published) | Source |
|---|---|---|
| Datadog | Infrastructure Pro from $15/host/mo (annual; $18 on demand), Enterprise from $23/host/mo; Logs: ingest from $0.10 per ingested/scanned GB with Flex Logs storage from $0.60 per million events stored; "Standard" indexed logs $1.70 per million events (15-day retention tier shown); log forwarding to custom destinations from $0.25/GB; archives to S3/GCS/Azure included with ingest fee | https://www.datadoghq.com/pricing/ |
| New Relic | Usage-based: 100 GB/mo free then $0.40/GB (original) or $0.60/GB (Data Plus); per-user licences (basic free; core $49; full platform from $10 first user up to $349 annual / $418.80 monthly for Pro) | https://newrelic.com/pricing |
| Grafana Cloud | Free tier, Pro from $19/mo + usage; metrics from $6.50 per 1k series; logs from $0.05/GB write, $0.40/GB process, $0.10/GB retain (as listed); enterprise minimum $25,000/yr. Adaptive Logs/Metrics features to cut cost | https://grafana.com/pricing/ |
| SigNoz Cloud | From $49/mo including $49 usage; logs and traces $0.30/GB ingested, metrics $0.10 per million samples; unlimited users and hosts; 15/30-day retention options | https://signoz.io/pricing/ |
| Honeycomb | Free tier; Pro from $150/mo (50M events, 250M time-series data points, 100 triggers, 2 SLOs); OTel processing/fleet management from $0.10/GB | https://www.honeycomb.io/pricing |
| Dynatrace | Per-host-hour: Infrastructure Monitoring $0.04/h, Full-Stack priced per memory-GiB-hour ($0.01), Kubernetes platform monitoring $1.40/mo per pod (listed) | https://www.dynatrace.com/pricing/ |
| AWS CloudWatch | Logs ingest list price per GB (region-dependent), $0.12/GB scanned in Logs Insights as used in AWS's own worked example, OTel metrics ingest $0.50/GB (flat, 15 months retention), PromQL-queryable metrics ingest listed at $0.08/GB | https://aws.amazon.com/cloudwatch/pricing/ |
| Google Cloud Logging | $0.50/GiB for log ingestion into log bucket storage (first 50 GiB/project/mo free per Google's page), read API/alerting charges separately listed | https://cloud.google.com/stackdriver/pricing |
| Azure Monitor | Analytics Logs ingestion listed at $2.3/GB pay-as-you-go (as read from page; $3.5/GB for a higher tier column) | https://azure.microsoft.com/en-us/pricing/details/monitor/ |
| Better Stack | Tiered per-seat/usage; free tier and paid "Telemetry" from ~$29-34/mo shown; telemetry free for unlimited team members | https://betterstack.com/pricing |
| Last9 | Event-based pricing, high-cardinality focus `[unverified: page content did not render]` | https://last9.io/pricing/ |

**Pricing lesson for XamOps:** the market charges per host *and* per GB, and every customer complaint category is "surprise bill / cardinality explosion". Because logs sit in the *customer's own S3* and the hot tier is capped by retention days, XamOps can price **per monitored host with a fair-use hot GB allowance** and show cost live. Commercial packaging is out of scope here but the product must expose usage and projected cost per host and per source from day one (section 10, cost controls).

### 2.2 Feature matrix (what "table stakes" and "differentiators" look like)

Legend: Y = yes, P = partial/add-on, - = no / not a focus, `?` = not verified.

| Capability | Datadog | New Relic | Grafana Cloud | Dynatrace | Honeycomb | SigNoz | Better Stack | CloudWatch | Azure Monitor | GCP Ops | **XamOps Obs 1.0 target** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| One-command host install | Y | Y | Y (Alloy) | Y (OneAgent) | P (collector) | P | Y | P (CW agent via SSM) | P (AMA) | P (Ops Agent) | **Y, plus one-click SSM/CFN from connected AWS account** |
| Auto-discover EC2 from cloud connection | Y (integration) | P | P | Y | - | - | - | Y (native) | Y (native) | Y (native) | **Y** (reuse XamOps inventory) |
| Pre-flight checks before install | P | P | - | P | - | - | - | - | - | - | **Y** (SSM, IAM, egress, OS, disk, clock) |
| "Waiting for first data" live confirmation | Y | Y | P | Y | P | P | Y | - | - | - | **Y** |
| Logs: live tail | Y | Y | Y | Y | - | Y | Y | Y | P | Y | Y |
| Logs: query language | Y (own) | Y (NRQL) | Y (LogQL) | Y (DQL) | P | Y | Y (SQL/PromQL) | Y (Insights/PPL/SQL) | Y (KQL) | Y (LQL) | Y (friendly bar compiling to LogsQL) |
| Pattern clustering | Y | Y | P | Y | - | P | P | Y (patterns) | P | P | Y |
| Facets / field sidebar | Y | Y | P | Y | Y | Y | Y | P | P | Y | Y |
| Container/ECS/EKS | Y | Y | Y | Y | P | Y | Y | Y | Y | Y | Y (phased: Docker/containerd/ECS on EC2 in 1.0; EKS node-level 1.0, workload depth later) |
| Windows Event Log | Y | Y | Y | Y | - | P | P | Y | Y | Y | Y |
| Deep host metrics (cpu steal/iowait, inodes, tcp retransmits) | Y | Y | Y | Y | - | Y | P | P | P | P | Y |
| Process-level (top, zombies) | Y | Y | P | Y | - | P | - | - | P | - | Y |
| Correlation metric spike -> logs/traces | Y | Y | Y | Y | Y | Y | P | P | P | P | **Y, one click, same host + window** |
| Anomaly detection | Y | Y | P | Y (Davis) | - | P | - | Y | Y | P | Y (explained, tunable) |
| Forecasting (disk full, leak) | Y | P | P | Y | - | - | - | P | P | P | **Y, named outcomes** |
| AI root cause / NL query | Y (Bits AI) | Y | Y (Assistant) | Y (Davis CoPilot) | Y (query assistant) | P | P | P (Q) | P | P | Y (grounded, redacted) |
| SLO / error budgets | Y | Y | Y | Y | Y | P | P | P | P | P | Y |
| Cost visibility per source | P | P | Y (Adaptive) | P | P | P | P | P | P | P | **Y, plus rightsizing linked to XamOps cost data** |
| Customer-owned storage (BYO S3) | P (archives) | - | P (BYOC enterprise) | - | - | P (self-host) | - | native | native | native | **Y, core promise** |

Rows for Last9 omitted pending verification. `[unverified]` for the specific ticks of CloudWatch/Azure/GCP details beyond what their pricing pages mention; they are included to show the native-cloud baseline customers compare against.

### 2.3 UX patterns worth adopting (design language, not copies)

- **Datadog:** Host Map and infrastructure list with a facet rail; log explorer = facet sidebar + search bar + timeline histogram + list + side panel; "Related" tabs on a log (host metrics, traces). Time-range picker with live toggle. Takeaway: *log explorer is a four-region layout (search, histogram, facets, results) with a detail drawer*; histogram brush sets the time range.
- **Grafana Cloud:** Explore/Drilldown apps (no-query log/metric exploration), Alloy-driven "connections" tiles with install snippets and a "test connection" button. Takeaway: *drilldown without query knowledge*, *test connection*.
- **New Relic:** Guided install ("Add data") with a generated one-liner that includes a license key, then live "waiting for data" check; entity-centred pages. Takeaway: *installer produces a ready-to-paste command and a live status line*.
- **Dynatrace:** Davis problem cards that group symptoms into one problem with root cause and impact; Smartscape topology. Takeaway: *one problem = one card, deduped; show blast radius*.
- **Honeycomb:** BubbleUp (compare the selected slice vs baseline to find differentiating attributes), SLO burn alerts, query history. Takeaway: *"what is different about these errors?"* is the most useful single feature for high-cardinality data and can be done on logs too (field-value skew).
- **SigNoz / Better Stack / Last9:** simple onboarding, clear cost transparency, low-friction pricing. Takeaway: *always show cost*.
- **CloudWatch / Azure Monitor / GCP:** native, powerful, but fragmented consoles and query languages. Takeaway: *our edge is a single coherent UI and plain-language help*.

### 2.4 Pain points of existing tools (opportunities)

1. Cardinality and log-volume surprise bills. -> per-source volume meters, top-talker view, "drop this noise" suggestions with projected savings, hard caps and sampling.
2. Onboarding with 5 steps, scattered keys. -> single step, scoped token embedded, no keys typed.
3. Opaque data residency. -> data in customer S3, key owned by the customer (SSE-KMS), one-click revoke.
4. Alert fatigue. -> dedup, grouping, mute windows, composite alerts, anomaly sensitivity previews ("this rule would have fired 14 times last week").
5. Query-language wall. -> field-click filters, saved queries, NL-to-query with the compiled query visible and editable.

---

## 3. What "complete" means: capability catalogue (scoping input for the requirements phase)

The list below is the exhaustive input to requirements; later phases decide priority (P0 = 1.0 launch, P1 = fast follow, P2 = later).

### 3.1 Onboarding and fleet
- Start from a connected AWS account; list EC2 across active regions (reuse `InventoryProjectionService.byType(cloudAccountId, "EC2 Instance")`, live `DescribeInstances` fallback when not fresh). P0
- Select: individual instances, all in account, by region, by tag (key/value), by ASG, by instance state. P0
- Pre-flight per instance: SSM managed (`DescribeInstanceInformation` PingStatus), instance profile/IAM present, OS and arch supported (Linux x86_64/arm64 P0, Windows Server P1), egress reachability to the ingest endpoint (test via SSM command or from the install script itself), disk space, clock skew, IMDSv2 enabled, existing agents (Datadog, CW agent) detected for conflict, ports unused. P0
- Install paths: (a) one-click SSM Run Command / State Manager association; (b) copy-paste one-liner with embedded per-host-group token; (c) CloudFormation/Terraform snippet; (d) user-data and launch-template/AMI snippet; (e) Auto Scaling group integration (token per group, host auto-registers on boot; deregisters on terminate event); (f) ECS (daemon sidecar/task definition snippet), EKS (Helm/DaemonSet manifest, phase P1). P0 for a,b,d,e; P1 for c variations, ECS/EKS.
- Live status: Pending -> Installing -> Waiting for first data -> Connected (first log + first metric seen) with timestamps and per-step failure reasons and fixes. P0
- Trust centre inside the flow: what is installed (binaries, versions, checksums, paths, systemd units), what is collected (checkbox per source, default sources shown), exact IAM policy preview, read-only by default, outbound-only 443, no inbound ports, encryption in transit/at rest, redaction explainer, one-click uninstall/rollback (SSM document or script), data deletion request. P0
- Agent fleet: health, version, last seen, drift, remote config profiles, staged auto-update with canary %, pin version, rollback, uninstall, token rotation, quarantine. P0 (update/rollback P0; canary P1)

### 3.2 BYO S3 storage
- List the account's buckets (reuse `S3Client.listBuckets()` through `getS3Client(account, region)`; region per `getBucketLocation`). Filter out buckets with public access, wrong region (cross-region cost warning), versioning-less critical buckets (warn). P0
- Choose existing bucket + prefix (default `xamops-logs/`), or **Create a dedicated bucket** via CFN with: Block Public Access all four, SSE-KMS (customer CMK or `aws/s3`), bucket-owner-enforced, TLS-only policy, lifecycle (Standard -> Standard-IA at day 30 -> Glacier Instant/Flexible at day 90 -> expire per retention), abort incomplete multipart at 7 days, optional versioning off for logs. P0
- Layout (requested): `s3://<bucket>/xamops-logs/{account}/{region}/{host-or-service}/{type}/yyyy/mm/dd/hh/<file>.parquet.zst` with `type` in `application|container|system|auth|web|windows-event|metrics`. Recommendation: keep exactly this layout for human browsability, and add Athena **partition projection** DDL generation (works with non-Hive layouts) plus manifest/index files per hour so query planners never LIST. P0
- Bucket validation: region match, write test object + read-back + delete, KMS encrypt test, public-access-block status, ownership controls, existing lifecycle conflicts, cost estimate from projected volume (per-class $/GB-month from the Pricing API), versioning and Object Lock warnings. P0
- Access model: cross-account role `XamOpsObsLogWriter` (trust XamOps principal + ExternalId, `s3:PutObject` only on the prefix, `kms:GenerateDataKey`/`Decrypt` on the one CMK) and a separate read role for query (`GetObject`, `ListBucket` on the prefix). Bucket policy snippet provided for BYO buckets. P0
- Storage usage view: bytes and objects per prefix/type/host/day (S3 Inventory or our own manifest counters - manifests are cheaper and exact), cost to date and projected, tier distribution. P0
- Fallback: XamOps-managed bucket (host account, `AwsClientProvider.getHostS3Client`) with per-tenant prefix and a per-tenant KMS data key; clear label "managed by XamOps", export/migrate-to-my-bucket action. P0
- Hot index (VictoriaLogs, N days, default 14) + S3 cold/archive both queryable from one UI with explicit "searching archive" state, estimated scan size/time and a cancel button. P0

### 3.3 Logs
- Sources: application files (path globs), container logs (Docker `json-file`, containerd/CRI, ECS awslogs-less direct, EKS), server logs (syslog, journald, auth.log/secure, kern, cron), web (nginx, apache access/error with parsers), databases (mysql, postgres slow log - P1), Windows Event Log (Application/System/Security). P0 except where noted.
- Parsing: multiline (stack traces, Java/Python/Go/Node), JSON auto-detect and flatten, logfmt, regex/grok-lite templates, severity detection (keywords, syslog PRI, JSON `level`), timestamp extraction with timezone, source-time vs ingest-time. P0
- Explorer: live tail (pause, resume, filter, highlight), full-text, field filters, facets with counts, time histogram with brush, context (before/after N lines), patterns (cluster similar lines, trend per pattern, "new pattern" flag), saved queries, share link, export (CSV/JSON, up to a capped N, async for archive), field explorer, column chooser. P0
- Query language: friendly bar -> LogsQL; show compiled query; NL query. Aggregations (count by, top N, percentiles on numeric fields), group by, time-bucketing. P0 (basics), P1 (joins/subqueries).
- Log-derived metrics and alerts (error rate per service, pattern count). P1.

### 3.4 Metrics
- Host: CPU per-core, user/system/iowait/steal/nice/irq, load 1/5/15, run-queue, context switches; memory used/free/available/cache/buffers/slab, swap in/out, page faults, OOM kills (from `vmstat`/kernel log), hugepages; disk per device IOPS, read/write throughput, latency (await), queue depth, utilization, filesystem usage and inodes; network per interface bytes, packets, errors, drops, TCP connections by state, retransmits, socket counts; processes top-N by CPU/mem, thread counts, FD usage, zombies; systemd unit states (P1); NTP/clock skew; EC2 metadata (instance type, AZ, tags). P0
- Containers: per-container CPU/memory/limits/throttling/restarts/OOMKilled, network and block I/O; ECS task/service; EKS node/pod. P0 (Docker/ECS-on-EC2), P1 (EKS depth).
- Custom: Prometheus scrape (static targets and file_sd), StatsD/OTLP receive, "tail this number from a log". P0 (Prometheus, OTLP), P1 (log-derived).
- Frameworks: RED (rate/errors/duration) from logs/traces for services, USE (utilization/saturation/errors) for resources shown as ready-made views. P0 (USE host), P1 (RED), traces later.
- Correlation: from any metric point/range, one click opens logs for the same host(s) and window, preselected to errors; from a log, one click shows host metrics around its timestamp; traces later. P0

### 3.5 Alerting and reliability
- Rule types: threshold, rate-of-change, absence/no-data (agent silent), anomaly (baseline + sensitivity), log-pattern (count/new pattern/regex), composite (A and B within window, A then B), forecast (disk full in < N days), SLO burn rate (multi-window). P0 for threshold, absence, log, anomaly, forecast; composite P0; SLO P0.
- Notification: Slack, Microsoft Teams, email, PagerDuty, generic webhook, SNS P0. Per-channel test button and delivery log.
- Controls: dedup/grouping keys, repeat interval, escalation chain with timeouts, mute windows (one-off and recurring), maintenance mode per host/tag, ack/resolve from the notification (P1), flapping suppression, alert preview/backtest ("would have fired N times"). P0
- SLO/SLI: define from log error ratio or metric ratio; objective and window; error budget remaining; burn-rate alerts (e.g. 14.4x/1h and 6x/6h Google SRE multi-window pattern). P0

### 3.6 Smart features
- Anomaly detection and baselining: seasonal-aware (hour-of-week), robust to outliers; explain each anomaly ("CPU 3.2 MAD above its Tuesday-10:00 baseline"). P0
- Forecasting: disk full ETA (linear + seasonal), memory leak (monotonic slope + GC sawtooth detection), connection exhaustion. P0
- Noise reduction: log pattern dedup, rate-limit chatty sources, "top 10 noisiest patterns" with one-click drop/sample rule and projected saving. P0
- AI: plain-language log summary for a window/host/service; root-cause hypothesis ranking using correlated metric anomalies, new log patterns, deploy/config changes (EC2 state events), and CloudTrail where connected; natural-language query; "explain this error". Grounded: every claim cites evidence rows; redaction before the model; customer toggle to disable AI; no training on data. P0 (summary, NL query), P1 (RCA).
- Rightsizing hints: use observed p95 CPU/memory (from agent) against instance type to suggest downsize, link to the XamOps rightsizing/cost pages (reusing `XamOpsRightsizingService` outputs by API only; design phase decides the integration). P1.
- Service auto-discovery: from listening ports + process names + container images + nginx upstreams -> "services" with icons and default dashboards. P1.
- Suggested dashboards: per detected technology (nginx, postgres, redis, java) generated from templates. P1.

### 3.7 Multi-tenancy and security
- Isolation: tenant = XamOps `client_id`; account = `cloud_account_id`; every ingest record is stamped server-side from the token. Query path derives tenant from the authenticated session and injects it; users cannot pass tenant filters. P0
- RBAC: reuse XamOps roles and tab gating (`observability-v1-*` keys) plus per-account grants via `CloudAccountAccessService`; Obs-specific permissions (view logs, view archive, manage agents, manage alerts, manage storage, export, AI) mapped to roles, with a restricted "can see log content" permission because logs can hold PII. P0
- Tokens: per-host ingestion token, 32-byte random, shown once, stored as SHA-256, scoped (tenant, account, host group), expiring (90 days) with overlap rotation (the supervisor rotates automatically); revoke; anomaly lockout on IP/host mismatch. P0
- Redaction: on-host first (regex library: AWS keys, JWT, bearer tokens, private keys, credit cards with Luhn, emails/phones optional, Authorization/Cookie headers), repeated at the gateway; custom rules; "preview redaction on sample"; redaction applied before S3 or hot index. P0
- Audit: who viewed/exported/queried archive, config changes, token events, uninstall events; immutable append-only table, exportable. P0
- Cost controls: per-source and per-host caps (GB/day), sampling, drop rules, hot-retention selection, alert at 80% of caps. P0
- Retention tiers: hot N days (platform-wide fixed per plan because VictoriaLogs OSS has one global retention flag), cold per customer lifecycle on their bucket. P0

### 3.8 Phase 3+ (not 1.0 but ingest must not preclude)
Traces/APM (OTLP traces, service map, RED), continuous profiling (Parca/eBPF profiler), synthetic checks, RUM, DB monitoring, Kubernetes workload depth, change events overlays, status pages.

---

## 4. Open-source building blocks

All data from GitHub API on 2026-10-30 unless noted. "Fit" is for XamOps's constraints: multi-tenant SaaS, outbound-only agents, customer S3, small ops team.

### 4.1 Collection

| Tool | License | Version / activity | Strengths | Weaknesses | Fit |
|---|---|---|---|---|---|
| **OpenTelemetry Collector (core + contrib)** https://github.com/open-telemetry/opentelemetry-collector-contrib | Apache-2.0 | v0.164.0 (2026-10-26), ~5k stars (contrib), very active | One binary for logs/metrics/traces; vendor-neutral OTLP; `hostmetrics` (beta: cpu, memory, disk, filesystem, network, load, paging, processes, process), `filelog` (beta), `windowseventlog` (beta, Windows only), `journald` (alpha, Linux), `prometheus` (beta), `kubeletstats` (beta), `docker_stats` (alpha), `resourcedetection` (beta, EC2/ECS/EKS detectors), `transform` (beta), `redaction` (beta for traces, alpha for logs/metrics), `filter` (alpha); can be custom-built with `ocb` to include only what is needed | Config complexity; several components alpha; contrib binary is large (hundreds of MB prebuilt `[unverified exact size]`) so a trimmed custom build is required; no built-in Parquet | **Primary agent data plane** |
| Fluent Bit https://github.com/fluent/fluent-bit | Apache-2.0 | v5.1.5 (2026-10-21), 8k stars | Tiny C binary, very low memory, mature `tail`, `systemd`, `winevtlog`, Lua/WASM filters, OTLP output | Metrics story weaker than OTel hostmetrics; two config dialects; fewer processors | **Best alternative** for logs only; kept as fallback for journald (since OTel journald is alpha) |
| Vector https://github.com/vectordotdev/vector | MPL-2.0 (file-level copyleft; acceptable but is a Datadog-controlled project) | 22.8k stars, very active | Fast Rust pipeline, VRL transforms, host_metrics, journald, Windows event logs | MPL-2.0 plus vendor-controlled; no OTLP-first metrics model; smaller ecosystem for remote config | Rejected as primary (governance + OTLP-first goal), acceptable fallback |
| Grafana Alloy https://github.com/grafana/alloy | Apache-2.0 | 3.7k stars, active | OTel-compatible distribution, Prometheus + Loki native components | Tied to Grafana's config model (River/Alloy syntax), pulls toward Loki/Mimir | Rejected: steers architecture toward AGPL back ends |
| Telegraf https://github.com/influxdata/telegraf | MIT | 17.9k stars | 300+ input plugins; very mature host metrics | Metrics-first; log support limited; own protocol (line protocol) | Rejected as primary; idea source for plugin coverage |
| node_exporter https://github.com/prometheus/node_exporter | Apache-2.0 | 13.9k stars | Reference Linux host metrics | Needs a scraper; no logs; pull model (inbound port) violates "no inbound ports" | Rejected as separate component; OTel hostmetrics covers the same ground. Metric names borrowed for dashboards |
| cAdvisor https://github.com/google/cadvisor | Apache-2.0 (GitHub shows NOASSERTION; LICENSE file is Apache-2.0, verified) | 19.5k stars | Container resource metrics | Pull model, heavy on busy hosts | Rejected; use `docker_stats`/cgroup reading via OTel, or kubeletstats on K8s |
| Grafana Beyla / OpenTelemetry eBPF Instrumentation (OBI) https://github.com/grafana/beyla, https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation | Apache-2.0 | Beyla v3.40.0 (2026-10-26); OBI repo active | Zero-code RED metrics and traces for HTTP/gRPC/SQL via eBPF | Needs privileged/CAP_BPF, kernel >= 5.8 `[unverified exact]`; Linux only | **Phase 3 optional add-on**, off by default |
| Pixie https://github.com/pixie-io/pixie | Apache-2.0 | active | K8s eBPF observability | Kubernetes-only, heavy | Rejected for host/EC2 scope |
| Parca / OTel eBPF profiler https://github.com/parca-dev/parca, https://github.com/open-telemetry/opentelemetry-ebpf-profiler | Apache-2.0 | active | Continuous profiling | Out of 1.0 scope; storage is separate | Phase 3+ idea. Both Apache-2.0. The brief listed Parca among possible AGPL projects; its LICENSE file is Apache-2.0 (verified), so it is not blocked on licence grounds |

### 4.2 Storage and query

| Tool | License | Version / activity | Multi-tenancy | Scale / ops notes | Fit |
|---|---|---|---|---|---|
| **VictoriaLogs** https://github.com/VictoriaMetrics/VictoriaLogs | Apache-2.0 | v1.53.0 (2026-10-01) | Native: tenant = `(AccountID, ProjectID)` headers; "OK to have thousands of tenants in a single instance"; **no per-tenant authorization** (use vmauth/our gateway); retention is global (`-retentionPeriod`, plus disk-usage caps); per-day partitions dropped atomically | Single binary, simple ops; cluster mode (vlinsert/vlselect/vlstorage) exists with easy migration from single node; docs recommend scaling vertically first. Endpoints: `/select/logsql/query`, `/tail` (live tailing), `/hits`, `/facets`, `/stats_query(_range)`, `/field_names`, `/stream_field_names`. Ingests OTLP, syslog, Loki, Elastic, journald etc. | **Primary hot logs store.** Docs: https://docs.victoriametrics.com/victorialogs/ |
| **VictoriaMetrics** https://github.com/VictoriaMetrics/VictoriaMetrics | Apache-2.0 (Enterprise features separate: downsampling etc.) | v1.155.0 (2026-10-26) | Cluster edition: tenant by `accountID` in URL path (OSS). Single node: no first-class tenancy -> enforce a tenant label via our query gateway (`extra_label`) | Very efficient storage and PromQL/MetricsQL; OTLP ingest; vmalert/vmauth/vmagent ecosystem | **Primary hot metrics store** |
| VictoriaTraces https://github.com/VictoriaMetrics/VictoriaTraces | Apache-2.0 | v0.12.1 (pre-1.0) | follows VM model | Jaeger-compatible API | Phase 3 candidate; reevaluate at 1.0 |
| Prometheus https://github.com/prometheus/prometheus | Apache-2.0 | 66.9k stars | None native | Single-tenant, local storage; remote-write needed for scale | Rejected as store (no tenancy); PromQL compatibility via VM |
| Thanos / Cortex | Apache-2.0 | Thanos active | Cortex/Thanos have tenancy | Heavy multi-component ops | Rejected for 1.0 complexity; Thanos is the fallback if S3-native metrics become a requirement |
| ClickHouse https://github.com/ClickHouse/ClickHouse | Apache-2.0 | 26.10 (2026-10-24) | Row-policy/db-per-tenant, no turnkey | One engine for logs+metrics+traces (SigNoz architecture), SQL, excellent compression; operationally heavier (keeper, replication, schema evolution, MergeTree tuning), JSON column typing pitfalls | Strong runner-up; chosen against only on operational weight and the absence of a ready tenant-aware log-search UI primitives (live tail, facets). Revisit if VictoriaLogs hits limits |
| Quickwit https://github.com/quickwit-oss/quickwit | Apache-2.0 | active | index-per-tenant | S3-native logs/traces search | Interesting S3-native design; Rust, smaller community post-acquisition by Datadog `[unverified date]`; rejected for 1.0 on ecosystem/maintenance risk. |
| Loki https://github.com/grafana/loki | **AGPL-3.0** | 29k stars | Multi-tenant (X-Scope-OrgID) | Label-indexed, S3-native chunks, mature | **Rejected: AGPL.** Network-service copyleft obligations apply to modifications served to users; legal exposure for a SaaS product. |
| Mimir | **AGPL-3.0** | active | Yes | S3-backed metrics | **Rejected: AGPL** |
| Tempo | **AGPL-3.0** | active | Yes | S3-native traces | **Rejected: AGPL** (use Jaeger v2/VictoriaTraces) |
| Grafana | **AGPL-3.0** | 77.6k stars | org model | Dashboards | **Rejected as embedded UI** (also contradicts "own UI/UX") |
| OpenObserve | **AGPL-3.0** | 22.6k stars | orgs | Parquet on S3, all-in-one | **Rejected** (AGPL; and explicitly excluded by product direction) |
| SigNoz https://github.com/SigNoz/signoz | MIT core, `ee/` directory under a separate enterprise license (verified from LICENSE) | 32k stars | limited in OSS | ClickHouse-based all-in-one | Rejected as embeddable backend: it is a product with its own UI, not a library; but its schema design is a useful reference |
| Jaeger v2 https://github.com/jaegertracing/jaeger | Apache-2.0 | 23k stars | limited | OTel-collector-based | Phase 3 trace backend fallback |
| **DuckDB** https://github.com/duckdb/duckdb | MIT | v2.0.0 (2026-10-21) | n/a (embedded) | Reads Parquet on S3 via `httpfs`, hive/glob partition pruning, ZSTD, predicate/projection pushdown, runs in-process; memory & thread caps per connection | **Cold/archive query engine** (workers, per-query memory/time limits) |
| Trino https://github.com/trinodb/trino | Apache-2.0 | active | n/a | Distributed SQL over S3 | Rejected for 1.0 (cluster ops cost); Athena covers the "big scan" case without us running anything |
| Amazon Athena (service) | n/a | AWS | via workgroup / customer account | $5/TB scanned list price `[unverified, region]`; Glue partition projection works with non-Hive layouts | **Optional** "query in your own account" mode; XamOps already has Athena client plumbing (`AwsClientProvider.getAthenaClient`, `getRoleAthenaClient`) for CUR |
| Parquet writers: parquet-go https://github.com/parquet-go/parquet-go (Apache-2.0, v active), Apache parquet-java (Apache-2.0) | Apache-2.0 | active | n/a | Go writer suits the Go archiver; Java writer would add Hadoop-era dependencies to Spring | Go + parquet-go |
| Apache Kafka / Redpanda | Apache-2.0 / BSL (Redpanda) | n/a | topic keyed by tenant | Durable buffer, replay | Kafka API; reuse existing Kafka client config in `xamops-service` `[prod broker to confirm]` |
| vmalert / Alertmanager | Apache-2.0 | active | partial | Rule engines | Not used in 1.0 (see 5.4) |

### 4.3 Important fact checks (to avoid design mistakes)

1. **OTel `awss3exporter` is alpha and has no Parquet marshaller.** Verified in its README: marshalers are `otlp_json`, `otlp_proto` and `sumo_ic`; configurable `s3_partition_format` (strftime) and `compression`. Therefore the requested "compressed partitioned Parquet" layout needs our own archiver; the exporter is **not** the cold path. (Source: https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/awss3exporter/README.md)
2. **`redactionprocessor` is alpha for logs/metrics** and `journaldreceiver` alpha, `docker_stats` alpha, `opampextension` alpha, `opampsupervisor` alpha (all verified from `metadata.yaml`/README). Mitigation: gateway-side redaction implemented with the beta `transform` processor (OTTL `replace_pattern`) as the primary mechanism, plus a regression corpus in CI; pin collector versions; canary rollouts; journald fallback to Fluent Bit `systemd` input behind the same supervisor if soak tests show instability.
3. **VictoriaLogs OSS has one retention setting globally**, so per-tenant hot retention is not available (it appears in Enterprise `[unverified]`). Design consequence: hot window is uniform per deployment (default 14 days); customer-specific retention lives in their S3 lifecycle. Tenants wanting 90 days "searchable hot" are served by cold query, not by the hot index.
4. **VictoriaLogs performs no per-tenant authorization** (docs say so): XamOps's query service must be the only network path to it and must inject `AccountID`/`ProjectID`; the browser never talks to it.
5. **VictoriaMetrics OSS single-node lacks first-class tenancy**; cluster edition provides it. Validate in an early spike whether tenant-label enforcement via gateway is sufficient for the pilot scale before standing up the cluster.
6. **Existing readonly role lacks write/ssm-send permissions** (inspection note 0.5).

---

## 5. Recommended architecture (reasoned)

### 5.1 Data flow

```
EC2 host                                  XamOps (SaaS)                                    Customer AWS account
┌───────────────────────────┐  OTLP/HTTPS ┌─────────────────────────────┐
│ xamops-obs-agent          │──443 out──▶│ ingest edge (nginx/ALB)      │
│  (supervisor, Go)         │  token      │  auth_request → Spring       │
│   └ xamops-otelcol        │             │ OTel gateway collector       │
│     filelog/journald/     │             │  tenant stamp, redaction,    │
│     hostmetrics/docker/   │             │  rate limit, sampling        │
│     winevt/prometheus     │             └──────┬───────────────┬──────┘
└───────────────────────────┘                    │               │
        ▲ config/heartbeat/commands (REST, ETag) ▼               ▼
        └──────────── Spring control plane   Kafka (durable)   VictoriaLogs / VictoriaMetrics (hot, N days)
                       /api/obs/v1/agent/**      │
                                                 ▼
                                      Go archiver (batch 5 min / 64 MB, Parquet+ZSTD)
                                                 │ assume role XamOpsObsLogWriter
                                                 ▼
                                      s3://<customer-bucket>/xamops-logs/...  (or managed fallback)
Browser ─▶ Spring query API (/api/obs/v1/**, SSE for tail) ─▶ hot: VL/VM  |  cold: DuckDB worker pool ─▶ customer S3 (read role)
```

### 5.2 Why this shape

- **Outbound-only OTLP over 443** satisfies "no inbound ports" and passes most enterprise egress policies; push model avoids exposing node_exporter-style scrape ports.
- **Gateway stamps identity**: tenant/account/host come from the validated token; any `xamops.*` resource attributes sent by the agent are overwritten. Cross-tenant spoofing is a gateway invariant, not an agent promise.
- **Kafka between gateway and sinks** decouples bursts, enables replay after an archiver or VictoriaLogs outage, and lets the archiver batch by (tenant, host, hour) without back-pressuring ingest. (If no Kafka broker is operated in production today, fall back to VictoriaLogs/Metrics direct ingest plus a gateway disk queue for the hot path and run the archiver from a second collector exporter; decision belongs to the design phase after infra confirmation.)
- **Dual write (hot + S3)** rather than "hot then age out to S3": gives the customer an authoritative archive from second one, makes hot retention a pure performance choice, and makes BYO-S3 the system of record.
- **DuckDB for cold** because the data is small per query window (the UI forces a time range and host/service scoping), no cluster to operate, MIT licensed, and it reads Parquet on S3 with predicate pushdown. Athena is the escape hatch for multi-day full scans in the customer's own account. Hourly manifest/index objects (host, row counts, min/max timestamp, level counts, bloom of top fields) let us skip files and also power the storage-usage view without LIST calls.
- **Small-file problem**: one prefix per host/type/hour yields many small objects. Archiver batches 5 minutes or 64 MB and a compactor merges to hourly files (rewrite in place under `compacted/` suffix then delete originals only after verification); query planner reads whichever exists.
- **Schema**: fixed envelope columns (`ts`, `ingest_ts`, `tenant_hash`, `account`, `region`, `host`, `service`, `type`, `level`, `message`, `trace_id`, `span_id`, `pattern_id`) plus a `attributes` MAP<string,string> and optional typed columns for hot fields; keeps Parquet scans cheap and schema evolution trivial.

### 5.3 Tenancy and isolation (summary of invariants)

1. The token is the only source of tenant identity at ingest (gateway enforcement).
2. The authenticated session is the only source of tenant identity at query (Spring enforcement; `CloudAccountAccessService.requireAccess` for the account; VictoriaLogs/VM headers injected server-side).
3. S3 isolation is by the customer's own bucket and a role scoped to the prefix; XamOps-managed storage uses per-tenant prefixes and per-tenant KMS data keys.
4. No tenant-controlled string is ever interpolated unescaped into LogsQL/MetricsQL/SQL: queries are built from an AST produced by our parser, not string concatenation.
5. Live tail via SSE/WS per authenticated stream, never the shared STOMP broker.

### 5.4 Alerting, SLOs, anomalies: in-house evaluator

`vmalert` and Alertmanager were considered. They are excellent but (a) per-tenant rule lifecycle with a UI-owned rule store means generating and reloading rule files per tenant, (b) composite, forecast, anomaly, log-pattern and SLO rules do not map onto Prometheus rule semantics without extra tooling, and (c) escalation/mute/dedup state would live in a second system with its own persistence. A Spring service with a Redis-locked scheduler querying VictoriaMetrics/VictoriaLogs HTTP APIs gives one model for all rule types, one dedup/escalation implementation, and straightforward backtesting ("would have fired N times"). Risk: evaluator throughput; mitigated by batching queries per tenant/interval and sharding by hash. Revisit vmalert only if rule counts exceed what a few Spring replicas can evaluate.

### 5.5 Smart features implementation approach

Statistical, explainable, no ML platform in 1.0: robust z-score on hour-of-week seasonal baselines (median/MAD), EWMA for drift, Holt-Winters or linear regression for forecasts, Drain-style log template mining (open algorithm; implement in-house) for pattern clustering. LLM used only for summaries, NL-to-query and RCA narration over *pre-computed* evidence, with redaction and an on/off switch. The platform already runs a LiteLLM compose file at the repo root (`docker-compose.litellm.yml`) and `/api/ai/` routes in nginx; whether to route through that is a design-phase integration question (not inspected here).

---

## 6. Sizing and cost model (planning assumptions, not measurements)

Assumptions (explicitly adjustable in the design): 100 hosts, 1.5 GB/day raw logs/host average (range 0.2 to 20), host metrics 10 s interval, ~250 series/host, hot window 14 days, ZSTD Parquet ratio 8x to 12x, VictoriaLogs on-disk ratio similar `[vendor claims a high compression ratio; measure in spike]`.

| Item | Estimate |
|---|---|
| Raw logs | 100 x 1.5 GB = 150 GB/day = 4.5 TB/month |
| Hot index (14 d at 10x) | ~21 GB/day stored -> ~300 GB on disk |
| S3 archive (customer) | ~15 GB/day compressed -> ~450 GB/month; at S3 Standard list price ~$0.023/GB-mo (us-east-1, unverified for region) ≈ $10/month for the first month's data, trending down after IA/Glacier transitions |
| Metrics | 100 x 250 = 25k active series, 2.5k samples/s; VictoriaMetrics needs low single-digit GB per month `[assumption]` |
| Compute (hot tier) | One 8 vCPU / 32 GB node with ~1 TB gp3 for each of VL and VM would cover this pilot; scale vertical first, then cluster `[to be validated by load test]` |
| Comparison | Datadog list pricing for the same footprint (100 hosts x $15 to $23 plus 4.5 TB ingest x $0.10/GB + indexing) lands in the multi-thousand dollar monthly range; New Relic at $0.40/GB would be about $1.7k for ingest alone (4.5 TB minus 100 GB free). These are order-of-magnitude comparisons from the verified rates in 2.1. |

The cost view in the product should compute the same figures per customer from live meters (bytes in/out per source, objects, storage class distribution) and show projected month-end.

---

## 7. Agent decision (evidence-based)

**Question:** extend the existing `xamops-agent` (Go) vs ship OpenTelemetry Collector / Fluent Bit?

**Evidence from the repo (see `00-inspection-notes.md` section 6):**

1. Existing agent is a **Kubernetes/cluster-oriented JSON pusher**: collectors are Trivy, Kubescape, OpenCost, Karpenter, K8s resources, Prometheus, DB stats, disk usage; transport is `Push(*AgentPayload)` JSON to `/api/v1/agent/ingest`; default collect cadence is minutes (the disk agent comment says every 5 minutes). It has **no host CPU/memory/network/process collection**, no journald/syslog/Windows event paths, and no Windows build (artifacts are `linux_amd64`/`linux_arm64`).
2. Its `go.mod` pins `k8s.io/* v0.28.4` and database drivers; **no OpenTelemetry dependencies**. Embedding the OTel Collector as a library would drag a very large dependency set and force version reconciliation with the pinned Kubernetes client libraries.
3. It already provides valuable, hard-won plumbing that we should *not* rewrite: IMDSv2 helpers (`internal/collector/disk.go`), config fetch, heartbeat, command polling (`internal/pusher/pusher.go`), version stamping (`internal/version`), cross-compile + checksum-able distribution (`Dockerfile`, `ci-cd.yml agent-build-verify`), systemd installer scripts and the Spring endpoints that serve them (`AgentInstallController`).
4. Its install scripts and ingest auth carry legacy risk (baked-in global key in `disk_agent_install.sh`; plaintext per-account keys). The new product must not inherit that.

**Evidence from research:**

5. OTel Collector provides every required receiver with real-world coverage (hostmetrics scrapers for cpu/memory/disk/filesystem/network/load/paging/processes/process; filelog with multiline and JSON operators; windowseventlog; journald; prometheus; docker_stats; resourcedetection for EC2/ECS/EKS), uses OTLP, which makes the backend swap-able and traces a later toggle rather than a rewrite.
6. Its weaknesses are packaging and operability: big prebuilt binaries; alpha components; no first-class install UX; remote config is via OpAMP (supervisor is alpha, server libraries are Go, we run Spring).
7. Fluent Bit is lighter and rock solid for logs but would need a second tool for rich host metrics and gives a worse path to traces.

**Decision:**

- **Data plane = custom OTel Collector build** (`ocb` manifest with an explicit allow-list of receivers/processors/exporters), shipped as `xamops-otelcol` per OS/arch. Pin versions; soak-test alpha components; Fluent Bit `systemd` input is the documented fallback for journald only.
- **Control plane on host = new Go supervisor `xamops-obs-agent`**, living in the existing `xamops-agent` repository (same CI, same version package, same release channel, reuse IMDS helpers and patterns), built from a new entrypoint (planned `ROOT/xamops-agent/cmd/obs-supervisor/` with `internal/obs/`), **not** by adding collectors to the legacy `CollectAll` path. It: registers using a one-time enrollment token and receives a per-host rotating ingestion token; renders the collector config from a server-side desired-config JSON (ETag poll); supervises and restarts the collector with crash-loop backoff; reports health, version, drops, queue depth; downloads updates, verifies SHA-256 (and signature in P1), swaps atomically, rolls back on failed health; executes uninstall. Redaction runs as a collector processor on the host and again at the gateway; the supervisor only distributes the rule set.
- **Remote config protocol for 1.0 = plain HTTPS REST (poll + ETag + long-poll optional), not OpAMP.** Reasons: OpAMP supervisor and extension are alpha, server-side libraries are Go-only while the control plane is Spring, and the existing agent already speaks poll/heartbeat/commands. The REST contract is shaped so it can be mapped to OpAMP later without changing the UI.
- **Legacy agent is untouched.** Where a host already runs the legacy disk agent, the new supervisor coexists (different binary, systemd unit, directories, tokens), and pre-flight detects and reports it.
- **Rejected alternatives:** (a) extend `CollectAll` with host metrics/log shipping: wrong cadence/transport model, no OTLP, tight coupling to cluster payload types, no Windows story; (b) ship only Fluent Bit: insufficient metrics/traces path; (c) ship Datadog/CloudWatch agents: vendor lock-in and cost; (d) embed OTel as a library in the existing agent module: dependency conflicts and binary bloat.

**Open risks for the design phase:** exact binary size of trimmed collector; Windows support timeline; supervisor privilege model (run as dedicated `xamops` user with `adm`/`systemd-journal` groups for log reads, `CAP_DAC_READ_SEARCH` or ACLs for protected logs); code signing for update trust.

---

## 8. Rejected options (consolidated)

| Option | Reason |
|---|---|
| Loki, Mimir, Tempo, Grafana (AGPL-3.0) | AGPL network-copyleft risk for a SaaS; Grafana UI also contradicts the "own UI/UX" goal |
| OpenObserve (AGPL-3.0) | AGPL; explicitly out by product direction |
| SigNoz (MIT core + `ee/` licence) | A full product with its own UI/back end, not a component; enterprise directory under separate licence |
| Elasticsearch/OpenSearch | Heavy ops/cost, licensing history (ES SSPL/Elastic License; OpenSearch Apache-2.0 is viable but resource-hungry); worse compression than VictoriaLogs for this use |
| Vector as primary agent | MPL-2.0, Datadog-controlled, OTLP not first-class for metrics, no standard remote-config story |
| Grafana Alloy | Pulls toward Loki/Mimir components and Alloy-specific syntax |
| Telegraf + node_exporter + cAdvisor | Three agents, pull model needing inbound ports, fragmented logs story |
| Prometheus single-node | No tenancy |
| Thanos/Cortex | Operational weight beyond 1.0 needs |
| ClickHouse as single store (runner-up) | Operational weight and building log UI primitives ourselves; revisit if VictoriaLogs limits are hit |
| Quickwit | Ecosystem/maintenance risk; keep watching |
| Trino | Cluster to operate; Athena/DuckDB cover cases |
| Pixie | Kubernetes-only |
| OpAMP for 1.0 | Alpha supervisor/extension; Go-only server libs vs Spring control plane |
| vmalert / Alertmanager as primary | Rule model and per-tenant lifecycle mismatch (5.4) |
| OTel `awss3exporter` as cold path | Alpha, no Parquet, no manifests/compaction |
| vmanomaly | Enterprise-only `[unverified]`; in-house statistical baselines are enough for 1.0 |
| Direct-to-S3 from agent as default | Needs instance IAM changes on every host and loses central redaction/rate limiting; offered later as an optional "private mode" for regulated customers |

---

## 9. UX direction (input for the UI design phase)

- **Layout:** left sidebar entry "Observability" (new section, own route namespace `/observability-v1/*`) with sub-tabs: Overview (fleet health, KPIs, noisy sources, cost), Hosts (list + map + per-host page), Logs (Explorer, Live Tail, Patterns, Saved), Metrics (Explorer, Dashboards), Alerts (Rules, Incidents, Channels, Mute windows), SLOs, Insights (anomalies, forecasts, AI summaries, rightsizing), Storage (BYO S3, usage, retention, cost), Onboarding/Connect (the single-step flow), Settings (tokens, RBAC, redaction, audit).
- **Language to borrow from XamOps pages:** KPI hero + delta chips and card panels (AWS Cost, XamSecure Overview), filter chips with counts and active-filter bar (XamSecure Issues), right-side detail drawer (480 px, focus-trapped), severity/status shown as dot + word (CloudK8s `StatusDot`), account header with selected-account context. Rules from the repo's design-rule guard: no gradients, no emoji, bundled Iconify icons, status never colour-only, tokens only.
- **Onboarding wizard (one screen, progressive):** Account (preselected) -> Pick instances (table with pre-flight badges, filters, "select all N matching") -> Choose storage (managed or my S3 with validation) -> Review trust panel (what/where/permissions) -> Install (SSM one-click or copy command) -> live status rail ending in "Connected - first log 12 s ago" with a link to the host's data. Failure states give a specific fix and a "retry" per host.
- **Perceived quality checklist:** skeleton loaders not spinners, instant filter feedback, URL-encoded state (shareable), keyboard shortcuts (`/` focus search, `t` live tail toggle, `[`/`]` time shift), dark and light, empty states that teach, density toggle, virtualized lists, consistent time zone control, copy-as-curl/LogsQL, undo for destructive actions, accessible (WCAG-oriented; full validation requires manual assistive-tech testing).
- **Performance budgets:** first meaningful Logs result < 1 s for a 15-minute hot query; live tail latency < 3 s end-to-end; page TTI < 2.5 s on warm cache; archive query shows scan estimate before running.

---

## 10. Risks and open questions for later phases

1. Is a Kafka broker operated in production (config keys exist: `sre.kafka.bootstrap-servers`, `spring-boot-starter-kafka`)? If not, the buffering design changes (5.2).
2. Where do VictoriaLogs/VictoriaMetrics/archiver/DuckDB run (existing EKS/ECS? new EC2)? Infra owner and cost approval needed; the repo shows ECS/EKS and `eks/`, `xamops-autoscaler` directories but no IaC for Obs.
3. Ingest hostname/TLS (`ingest.<domain>`) and WAF/rate limits; nginx config additions listed in inspection notes (section 5).
4. Retention is global in VictoriaLogs OSS: confirm product accepts uniform hot retention.
5. Windows support scope for 1.0 (collector supports it; installer, SSM document and testing do).
6. Legal review for any component not Apache-2.0/MIT (Vector MPL-2.0 is not used; VictoriaMetrics Enterprise features not used).
7. Data protection: logs may contain personal data; DPA language, regional storage (customer bucket region), right-to-delete workflow.
8. IAM approval flow for the opt-in permissions stack must match the existing "preview then execute" stack-update UX (`AccountManagerController` `/stack-update/{accountId}/preview|execute`).
9. Existing notification plumbing (Slack/Teams/email) lives in other modules (`SnsNotificationService`, `EmailService`, BillOps `ExternalMessagingService`); reuse vs rebuild decided in design after inspecting them (not inspected in Phase 1).
10. Single-tenant DB mode (`TenantFilter` forces `"system"`) means isolation relies on application checks; Obs adds explicit `client_id` columns and tests that assert cross-tenant denial.

---

## 11. Source index

- Datadog pricing: https://www.datadoghq.com/pricing/
- New Relic pricing: https://newrelic.com/pricing
- Grafana Cloud pricing: https://grafana.com/pricing/
- SigNoz pricing: https://signoz.io/pricing/ ; license: https://github.com/SigNoz/signoz/blob/main/LICENSE
- Honeycomb pricing: https://www.honeycomb.io/pricing
- Dynatrace pricing: https://www.dynatrace.com/pricing/
- Better Stack pricing: https://betterstack.com/pricing
- AWS CloudWatch pricing: https://aws.amazon.com/cloudwatch/pricing/
- Google Cloud Observability pricing: https://cloud.google.com/stackdriver/pricing
- Azure Monitor pricing: https://azure.microsoft.com/en-us/pricing/details/monitor/
- OpenTelemetry Collector Contrib: https://github.com/open-telemetry/opentelemetry-collector-contrib (component `metadata.yaml` files for stability)
- OTel `awss3exporter` README: https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/awss3exporter/README.md
- OTel OpAMP supervisor: https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/cmd/opampsupervisor
- VictoriaLogs docs/source: https://docs.victoriametrics.com/victorialogs/ ; https://github.com/VictoriaMetrics/VictoriaLogs (docs: README multitenancy, querying, cluster)
- VictoriaMetrics: https://github.com/VictoriaMetrics/VictoriaMetrics ; VictoriaTraces: https://github.com/VictoriaMetrics/VictoriaTraces
- Fluent Bit: https://github.com/fluent/fluent-bit ; Vector: https://github.com/vectordotdev/vector ; Grafana Alloy: https://github.com/grafana/alloy ; Telegraf: https://github.com/influxdata/telegraf
- node_exporter: https://github.com/prometheus/node_exporter ; cAdvisor: https://github.com/google/cadvisor
- Beyla: https://github.com/grafana/beyla ; OTel eBPF instrumentation: https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation ; Pixie: https://github.com/pixie-io/pixie ; Parca: https://github.com/parca-dev/parca
- Loki (AGPL-3.0): https://github.com/grafana/loki ; Mimir: https://github.com/grafana/mimir ; Tempo: https://github.com/grafana/tempo ; Grafana: https://github.com/grafana/grafana ; OpenObserve: https://github.com/openobserve/openobserve
- ClickHouse: https://github.com/ClickHouse/ClickHouse ; Quickwit: https://github.com/quickwit-oss/quickwit ; Jaeger: https://github.com/jaegertracing/jaeger ; Thanos: https://github.com/thanos-io/thanos ; Prometheus: https://github.com/prometheus/prometheus
- DuckDB: https://github.com/duckdb/duckdb ; Trino: https://github.com/trinodb/trino ; parquet-go: https://github.com/parquet-go/parquet-go ; parquet-java: https://github.com/apache/parquet-java

---

## 12. Design-phase addendum (refinements, no change to recommendations above)

Added while writing `02-architecture.md` to `08-roadmap-risks-tests.md`. None of these contradicts sections 1 to 11.

1. **Fan-out detail.** Section 5.1 shows the gateway fanning out to Kafka and the hot stores. VictoriaLogs and VictoriaMetrics take tenant identity from request headers or URL, which an OTel exporter cannot vary per record. The hot path is therefore: gateway collector -> Kafka -> a small in-house Go service `hotwriter` (module `xamops-obs-pipeline`, with `archiver` and `compactor`) -> VictoriaLogs/VictoriaMetrics with per-tenant headers. Kafka remains the single buffer (see risk R1 in `08` for the no-Kafka fallback).
2. **Tenant mapping.** VictoriaLogs `AccountID = client_id`, `ProjectID = cloud_account_id`; VictoriaMetrics cluster uses the same pair; single-node uses enforced labels `xo_client`, `xo_account`.
3. **Cold path permissions.** The writer role has no `s3:DeleteObject`; compaction tags originals and a lifecycle rule expires them (consistent with the least-privilege goal in section 3.2).
4. **Machine-endpoint auth.** Agent and internal endpoints use a dedicated header (`X-XamOps-Ingest-Token`, `X-Obs-Internal-Key`) and a separate `@Order(0)` security chain, resolving inspection note 0.3 option (b) without editing `SecurityConfig.java`.
5. **Live tail.** Server-Sent Events from Spring, not STOMP and not a raw WebSocket handler (simpler through nginx, cookie auth works).
