# XamPulse: reaching Datadog / Dynatrace / New Relic level for EC2 (plan)

First written 2026-11-10; rewritten 2026-11-23 after comparing how the three leaders present EC2 data.
Direction (Aditya): deep EC2 monitoring, far beyond the old Observability (Beta) page; every step is engineering
**and** UI/UX, judged by how useful it is to the person looking at it.

## 1. How we work (applies to every step)

**Start from the question, not the data.** Each screen, section and widget names the question it answers. If no
real operator question needs it, it is cut. Order of every page: *status -> what is wrong -> why -> what to do ->
evidence*. A chart without an interpretation next to it is unfinished work.

**Three users, three jobs:**

| Who | Job | Their first question |
|---|---|---|
| On-call engineer | Fix the problem now | "What is broken, since when, and what do I do?" |
| Platform / DevOps lead | Keep the fleet healthy, plan capacity | "Which hosts are at risk, by team / service / ASG?" |
| Manager / FinOps | Spend well, report up | "Are we paying for capacity we do not use, and is it safe to cut?" |

**Definition of done for any UI change:** answers its named question with real data from a live instance;
correct units and time context; honest no-data; one click from every number to its evidence; light + dark,
1440 / 1024 / 390 px, keyboard; screenshot attached; the benchmark question it serves gets faster (section 4).

## 2. What we borrow from each leader (UI patterns, not features)

| Leader | Their core idea | Pattern we adopt | Where in XamPulse |
|---|---|---|---|
| Datadog | **Tags everywhere** | Group / filter / colour any list or map by any AWS tag, type, AZ, ASG; host map coloured by any metric | Hosts, Overview, Metrics |
| Datadog | **Quick look without losing your place** | Side panel from any list; full page for depth | Hosts list -> panel -> Host 360 |
| Datadog | **Everything linked by host + time** | Brush a chart -> same window in Logs / Changes / Processes | All drilldowns |
| Dynatrace | **One problem, one root cause** | Related alerts across hosts merge into one problem with the likely cause first | Alerts / Incidents, Overview |
| Dynatrace | **Notifications bar on the entity** | Host 360 header shows open problems + changes before any chart | Host 360 |
| Dynatrace | **Top contributors** | Every saturation chart shows "top 5 processes / mounts / peers causing it" | CPU, Memory, Storage, Network |
| Dynatrace | **Topology** | Per-host "talks to" table first, map later (eBPF decision pending) | Host 360 Network |
| New Relic | **Golden signals per entity** | Every entity (host, container, integration) has the same 4-signal header | Host / Container / Integration pages |
| New Relic | **Every page is a query you can edit** | "Open this chart in Metrics" and saved views, before a full query language | Metrics, Hosts |

## 2b. Visual language: XamOps identity (references: login page + AWS cost page, read from code)

Sources: `pages/LoginPage.tsx` + `styles/login.css`; `pages/CostPage.tsx` + `components/Cost/*`.

| Element | XamOps reference | XamPulse token / class |
|---|---|---|
| Accent | Login brand `#1be68b`, ink `#04231a` | `--xp-accent #1be68b`, `--xp-on-accent #04231a` (10.1:1) |
| Accent as text | Login `#0b8f5a` fails 4.5:1 on white (4.13) | `--xp-accent-text #087a4c` light (>= 4.9:1), `#1be68b` dark |
| Page titles | Instrument Serif, italic as emphasis (no bold) | `.xp-title` (`--xp-display`), `<em>` for the accent word |
| Numbers, card headings | Space Grotesk 600/700 | `--xp-num` on `.xp-kpi__v`, card h2/h3 |
| Reading text | Inter | `--xp-font` |
| Monospace | Not used for labels | Only ids, logs, code |
| KPI row | Cost page: one card, hairline-split columns, tinted icon tile, 10 px uppercase label, 26 px bold value, 11 px context | `xp-grid--kpi` + `KpiTile icon tone` |
| Cards | White, 7 to 8 % hairline, 10 px radius; hero cards 20 px with long soft shadow | `.xp-card`, `--xp-radius` |
| Proof chips | Login "EC2 Rightsizing -$178/mo" floating chip | Use for findings with a saving or risk figure |

Contrast and CSS/JS token parity are enforced by `components/XamPulse/ui/contrast.test.ts`.

## 3. UX rules (non-negotiable, already partly enforced by the Nov 17 review)

1. Never show Healthy without a measurement; no-data is its own state with a reason.
2. Units are correct (load is not %, rates say /s, sizes are binary), every number has its time range and freshness.
3. One threshold definition per signal, shared by list and detail.
4. Every finding = verdict + evidence + next step + link. Never a bare "Warning".
5. Account and instance identity survive every click (multi-account safe).
6. Cost is never invented: a missing price is said in words, never $0.
7. Billed AWS calls are explicit (CloudWatch Refresh shows count + cost before and after).
8. Fixed pages are opinionated defaults; anything the user narrows (filters, grouping, range) is shareable by URL.

## 4. Benchmark: how we measure "their level"

Run a Datadog trial on the same UAT instances and time each question in both tools after every step.
"Reached" = XamPulse answers each in the same or less time with the same or better confidence.

| # | Question | User |
|---|---|---|
| 1 | Which instances are unhealthy right now, and why (one line each)? | On-call |
| 2 | Why was host X slow at 03:00 yesterday? | On-call |
| 3 | What changed on host X before the error spike? | On-call |
| 4 | Which process is eating CPU / memory on host X, and since when? | On-call |
| 5 | Is host X being throttled by AWS (credits, EBS, network allowance)? | On-call |
| 6 | Is the disk going to fill, and when? | On-call |
| 7 | Did nginx / postgres / my service stop or restart anywhere? | On-call |
| 8 | Show me errors in logs for service Y across all hosts in the last hour | On-call |
| 9 | Which hosts in ASG / team / environment Z are at risk? | Lead |
| 10 | Which instances have no agent / no data? | Lead |
| 11 | Which hosts are near their EBS IOPS or throughput limit? | Lead |
| 12 | Is this host an outlier compared to its peers? | Lead |
| 13 | Which instances are idle or oversized, and what would I save? | FinOps |
| 14 | What does this host cost per unit of work it actually does? | FinOps |
| 15 | Which hosts expose ports to the internet they should not? | Lead |
| 16 | Is AWS planning maintenance / retirement on any of my instances? | Lead |
| 17 | One incident across 5 hosts: is it one problem or five? | On-call |
| 18 | Alert me when a host is about to run out of something, not after | Lead |

## 5. Roadmap (each step = engineering + UX + done-when)

### Step 1. Trust (now)
* Engineering: fill the three host-list fields still blank in production (EC2 state, tags, network rates); commit
  and push the uncommitted XamPulse work; UAT deployment with real ClickHouse, nginx, release pipeline
  (`scripts/xampulse/build-release.sh` + CI, signed agent); 20 to 50 real instances for two weeks.
* UX: Nov 17 review issues (units, no-data, thresholds, navigation, log paging, first-seen) are fixed in the tree;
  keep them pinned by tests.
* Done when: benchmark Q1, Q2, Q4, Q5, Q6 answered from real UAT data with no wrong number.

### Step 2. Fleet level (Datadog strength)
* Engineering: every EC2 instance in Hosts from the inventory (agent / no agent column); tags, type, AZ, ASG, state
  per row; group-by and filter by any of them; host map coloured by any metric; saved views; multi-account.
* UX: quick-look side panel from the list; grouped list with per-group health ("ASG web-prod: 2 of 12 at risk");
  coverage gaps shown as a work queue ("14 instances without agent, 3 in prod").
* Done when: Q9, Q10, Q11, Q16 in under 30 seconds.

### Step 3. Discovery and integrations (Dynatrace / Datadog strength)
* Engineering: process / service discovery on by default; "process missing" and "service restarting" alerts;
  auto-enabled nginx, Apache, MySQL, Postgres, Redis, MongoDB, JVM, systemd; log pipelines per integration; EKS.
* UX: Host 360 "What runs here" section; one page per integration with the same golden-signal header; top
  contributors on every saturation chart.
* Done when: Q7, Q8 answered; a new host shows its software with zero configuration.

### Step 4. Intelligence across hosts (Dynatrace Davis strength)
* Engineering: correlate alerts into one problem across hosts / ASG with likely root cause; baselines and anomaly
  detection for every key metric (today 4); per-host connections table; forecasts on every exhaustible resource.
* UX: Problems view (one card per problem, affected hosts, cause first, timeline); Host 360 header shows open
  problems and recent changes before charts.
* Done when: Q3, Q12, Q17, Q18.

### Step 5. Platform (ongoing)
* Boards (pin any chart), saved queries, PagerDuty / Opsgenie / Jira, API or Terraform for rules; load test at
  thousands of hosts per tenant; HA ClickHouse with backups; per-tenant ClickHouse users (V009 honest limit).

Not planned unless decided: APM / traces (first candidate; collector already speaks OTel), RUM, synthetics, SIEM.

## 6. Decisions

Made (2026-11-10): CloudWatch on demand only (Refresh, stored, no background polling); top-20 processes on by default.

Pending (Aditya): SSM runbooks on (needs `ssm:SendCommand` on the connection role); LLM for plain-English search
(Groq key = third-party data sharing); privileged eBPF agent for service map and profiling.

## 7. Original layer design (still valid)

* **A. AWS view (no agent):** status checks, scheduled events, facts, credits, EBS limits, ENA allowances, cost.
  Built: facts + findings + on-demand CloudWatch (V010 `xp_cloudwatch_points`), exposure and cost/use cards.
* **B. Deep OS view:** per-core CPU + steal/iowait, PSI, top processes, OOM / kernel / systemd / reboot / SSH events,
  ports, forecasts. Largely built in Host 360 (pressure, limits radar, processes, changes).
* **C. Workloads:** auto-discovery + integrations (Step 3). Container 360 v2 built 2026-11-23: verdict (findings,
  restarts, errors in its logs), "no limit" detection (Docker reports the host's memory as the limit), the
  container's own logs (severity with counts, phrase search, volume by severity, paging, live tail), Errors 15m
  column on Containers. Gateway `transform/log_level` raises severity from the line text (Docker sends all as INFO).
* **D. Answers:** Investigate (supported / ruled out / not measurable), scenario playbooks, merged timeline (Step 4).

## 7b. Working order agreed 2026-11-24 (after the external review)

1. Honesty fixes: no "create a ticket" option until ticketing exists; no rightsizing insight until real data
   produces one. **Done 2026-11-24** (pinned by `components/XamPulse/alerts/honesty.test.tsx`).
2. Baseline bands and compare (previous period / yesterday / last week) on Host 360 and Metrics. **Done 2026-11-24.**
   Found and fixed on the way: baselines averaged a metric's label series (CPU learned a constant 12.5% on an
   83%-busy host, memory 18.8% for 68%), and the 14-day window could never give an hour of the week the 3 samples
   it needs, so no baseline was ever written. Now one definition (`BaselineSignal`) for the baseline job, anomaly
   rules, Metrics bands and Host 360; daily rhythm after 3 days, weekly after 3 weeks (28-day window).
3. Cheap Throttle Radar checks from the instance: file descriptors, ephemeral ports, inodes, TCP retransmits,
   listen-queue overflow. **Built 2026-11-24**: radar has 12 checks (inodes from hostmetrics on every agent; the
   other four from the xampulse_ec2 receiver in agent 1.3.0: `xp.fs.file_handles`, `xp.process.open_files`,
   `xp.net.local_ports`, `xp.tcp.segments`, `xp.tcp.listen_queue`), and Investigate has two more causes (OS limit,
   network loss). Release 1.3.0 built in `~/Desktop/xampulse-release-1.3.0`; live once uploaded to S3 `latest/`.
4. "What's different" on a dragged range, built on Investigate (supported / ruled out / cannot tell).
5. Fault-injection lab: ~15 scenarios on a dedicated lab instance (not the shared UAT box), time to correct
   diagnosis in XamPulse vs Datadog / New Relic.
6. Group-by in lists (tag, ASG, account), then a minimal dashboard as a grid of saved Metrics views.

## 8. Known bugs outside the steps

* Setup wizard does not keep the existing bucket/KMS settings when changing monitoring.
* Update-stack "before" values come from XamOps' last plan, not the stack's real parameters.
* Old XamOps cluster agent on the UAT app box posts to the ngrok domain every few seconds (unrelated, noisy).
