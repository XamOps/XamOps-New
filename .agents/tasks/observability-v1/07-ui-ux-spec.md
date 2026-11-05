# 07 — UI/UX Specification (XamPulse)

Component names are those in 05; endpoints in 06. Inspiration is limited to the *vocabulary* of AWS Cost (KPI cards with deltas, forecast chart, drawers), XamSecure Overview (KPI hero, panels, feed rows) and XamSecure Issues (filter chips with counts, active-filter bar, grouping tabs, side drawer). Nothing is imported from those pages. Screens below are original layouts. The existing Observability (Beta) page is not referenced.

## 1. Visual identity: "Signal on ink"

- **Idea:** observability is about hearing a system's pulse. The UI is quiet and dense by default, and the only thing that "moves" is live data. A single teal accent (`--obs-accent`) marks what is alive or actionable: the primary button, the live dot, the selected tab underline, the focus ring.
- **Motif:** the *pulse line*, a 2 px polyline that appears in the brand mark, the "Waiting for data" empty state (a flat line that gains a beat when the first datapoint arrives), and the page-load progress hairline. No gradients, no emoji, no stock illustrations. Illustrations are monoline SVGs (1.5 px stroke) built from the same pulse motif.
- **Surfaces:** page canvas `--obs-surface-0`, cards `--obs-surface-1` with a 1 px `--obs-line` border and 12 px radius, raised overlays `--obs-surface-2`. Dark and light are first-class; dark is designed first because operators live there.
- **Typography:** existing type scale (`typeScale.ts`); numerals tabular (`font-variant-numeric: tabular-nums`) in KPIs, tables and charts; log text in the mono stack at 12/18 px comfortable, 11/16 compact.
- **Colour use:** status tones only for state (good/warn/bad/info/off) and always paired with a word or glyph. Series use Okabe-Ito. Teal accent is never used as a status colour.
- **Spacing:** 4 px base scale; card padding 16 px (comfortable) / 12 px (compact); section gap 24 px; page gutters 24 px (≥ 1280 px), 16 px below.
- **Motion:** 120 ms for hover/focus, 200 ms for drawers/popovers, ease `cubic-bezier(.2,0,0,1)`; count-up on KPI first paint only (≤ 600 ms); everything off under `prefers-reduced-motion`.
- **Voice (microcopy):** short, concrete, second person, no jargon without a tooltip, no exclamation marks. Errors say what happened, what it means, what to do. Examples are in section 6.

## 2. Information architecture

Sidebar (new top-level section "XamPulse", own icon, badge "New"):

```
XamPulse
├─ + Connect servers          (action item, /observability-v1/connect)
├─ Overview                   fleet health, KPIs, incidents, insights, cost
├─ Hosts                      table / map, host detail
├─ Logs                       Explorer · Patterns · Saved
├─ Metrics                    Explorer · Dashboards
├─ Containers                 Docker / containerd / ECS / EKS nodes
├─ Services                   auto-discovered, service map
├─ Alerts                     Incidents · Rules · SLOs · Channels · Mute windows
├─ Storage                    your S3, usage, retention, cost
├─ Agents                     fleet, versions, rollouts, remote config
└─ Settings                   General · Tokens · Access · Redaction · Cost controls · Audit · AI
```

Within a page, secondary views are a segmented control under the header (not new sidebar items), so the sidebar stays at ten entries. Global elements on every page: scope bar (accounts, regions, tags), time picker with compare, refresh/live, density, command palette hint, theme follows app.

## 3. Global frame and shared patterns

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ Logs                                                         ⌘K Search or jump…   ?   ◐ theme │
│ Search and tail logs across your servers                                                      │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ [prod-aws ▾] [all regions ▾] [env=prod ✕] [+ filter]      [● Live] [Last 1h ▾] [Compare ▾] ⟳  │  ← ObsScopeBar
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ …page content…                                                                 (drawer slot →)│
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Time picker (`ObsTimePicker`)**

```
┌─ Time range ──────────────────────────────────────────┐
│ Quick:  5m  15m  [1h]  4h  24h  3d  7d  14d(hot max)  │
│ Relative: now-[90]m → now          Timezone: (•)Local ( )UTC │
│ Absolute: [2026-11-02 09:15] → [2026-11-02 10:15]     │
│ ☑ Compare to  (•) previous period ( ) yesterday ( ) last week │
│ ⓘ Older than 14 days is searched in your S3 archive.  │
│                                  [Cancel] [Apply]     │
└───────────────────────────────────────────────────────┘
```

States: invalid range (from ≥ to) → inline "Start must be before end"; > 7 days on Logs → hint "Long ranges are heavy. Narrow to a host or service for faster results."; > 14 d → archive notice.

**Command palette (`ObsCommandPalette`, ⌘K)**

```
┌──────────────────────────────────────────────────────┐
│ ›  type a host, service, saved query or action…      │
├──────────────────────────────────────────────────────┤
│ ACTIONS                                              │
│   Connect servers                         ⌘⇧C        │
│   Start live tail                         t          │
│   Create alert from current query                    │
│ NAVIGATE                                             │
│   Overview   g o     Hosts   g h     Logs   g l      │
│ HOSTS                                                │
│   web-1  i-0abc  us-east-1   ● Connected             │
│ SAVED QUERIES                                        │
│   5xx on nginx (prod)                                │
└──────────────────────────────────────────────────────┘
 empty:  "No match. Try a host name, service or ‘connect’."
```

**Shared non-happy states (`ObsStateBlock`)** — every data region uses one of these. Layout:

```
 loading      skeleton shapes matching final layout (never a lone spinner); header text real
 empty        monoline pulse icon · title · one sentence · primary action · "Learn how it works"
 waiting      flat pulse line animating · "Waiting for first data from web-1" · elapsed 00:42 · "Still waiting? Check egress" 
 error        ⚠ title · what happened · what to do · [Retry] · Details ▸ (code, requestId)
 degraded     inline banner: "Showing last known data (2m old). Recent logs are temporarily unavailable." [Retry]
 noPermission lock glyph · "You can see health and counts. Ask an admin for log access." · [Request access]
 partial      data shown + banner "3 of 12 servers didn't respond" [Show which]
```

## 4. Screens

### 4.1 Connect (onboarding) — `/observability-v1/connect`

Single page, three cards plus a permanent Trust panel. Primary button is always visible in the sticky summary bar.

**A. Initial load (account known, instances loading)**

```
┌─ Connect servers to XamPulse ─────────────────────────────────────────────── ✕ close ───────┐
│ Account  [ prod-aws · 1234…9012 ▾ ]      Everything stays in your AWS account unless you say so. │
├──────────────────────────────────────────────┬───────────────────────────────────────────────┤
│ 1  Choose servers                            │  TRUST                                        │
│ ┌──────────────────────────────────────────┐ │  ✔ Outbound only (443). No inbound ports.     │
│ │ ▒▒▒▒▒▒▒▒▒▒▒▒   ▒▒▒▒▒▒   ▒▒▒▒▒▒   ▒▒▒▒    │ │  ✔ Read-only by default.                      │
│ │ ▒▒▒▒▒▒▒▒▒▒▒▒   ▒▒▒▒▒▒   ▒▒▒▒▒▒   ▒▒▒▒    │ │  ✔ Remove anytime with one click.             │
│ │ ▒▒▒▒▒▒▒▒▒▒▒▒   ▒▒▒▒▒▒   ▒▒▒▒▒▒   ▒▒▒▒    │ │  ▸ What gets installed                        │
│ └──────────────────────────────────────────┘ │  ▸ What gets collected                        │
│ 2  Where logs are stored   (locked until 1)  │  ▸ Permissions (policy preview)               │
│ 3  Install                 (locked until 2)  │  ▸ Encryption and redaction                   │
├──────────────────────────────────────────────┴───────────────────────────────────────────────┤
│ Select servers to continue                                          [ Connect 0 servers ] (disabled) │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

**B. Discovered + selected (the main state)**

```
│ 1  Choose servers                                   12 selected of 48                         │
│ [🔎 search name / id]  Region [all ▾]  Tag [env ▾ = prod ▾]  ASG [all ▾]  State [running ▾]   │
│ ☑ 12 on this page are selected.  [ Select all 31 matching these filters ]                     │
│ ┌────┬──────────────┬────────────┬───────────┬───────────┬──────────┬─────────────────────┐ │
│ │ ☐  │ Name         │ Instance   │ Region    │ Platform  │ ASG      │ Ready?              │ │
│ │ ☑  │ web-1        │ i-0abc…    │ us-east-1 │ Linux x86 │ web-asg  │ ✔ Ready             │ │
│ │ ☑  │ web-2        │ i-0abd…    │ us-east-1 │ Linux x86 │ web-asg  │ ✔ Ready             │ │
│ │ ☐  │ db-1         │ i-0aff…    │ us-east-1 │ Linux arm │ —        │ ◐ Needs attention ▸ │ │
│ │ ☐  │ legacy-win   │ i-0b01…    │ eu-west-1 │ Windows   │ —        │ ✖ Blocked: OS ▸     │ │
│ │ ☐  │ old-app      │ i-0b02…    │ us-east-1 │ Linux x86 │ —        │ ● Connected already │ │
│ └────┴──────────────┴────────────┴───────────┴───────────┴──────────┴─────────────────────┘ │
│ Also found: ECS cluster “prod” (6 container instances) · EKS “main” (8 nodes)   [Include ▾]   │
│ Not in the list? [ Connect any other Linux server (copy command) ▸ ]                          │
│                                                                                               │
│ 2  Where logs are stored                                                                      │
│  (•) Managed by XamOps (default, encrypted with your own tenant key)  ← easiest               │
│  ( ) My S3 bucket        ( ) Create a new bucket for me                                       │
│                                                                                               │
│ 3  Install                                                                                    │
│  Method  [ One-click (SSM) ]  [ Copy command ]       Sources ☑ System ☑ Auth ☑ Docker ☑ nginx │
│  ⓘ One-click tags the selected servers xamops:obs=enabled. You can remove the tag any time.   │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│ 12 servers · 2 regions · Managed storage · ~$0/mo to you        [ Preview permissions ] [ Connect 12 servers ] │
```

Row-state popovers: "Needs attention ▸" lists the failing checks with fix text (for example "SSM isn't online on this server. Use the copy command instead, or add this role: [Copy snippet]"). "Blocked" rows are unselectable with the reason in a tooltip and inline text.

**B2. Storage choices**

```
│  ( ) My S3 bucket                                                                          │
│      Bucket [ acme-logs (us-east-1) ▾ ]   Folder [ xamops-logs/ ]   Keep for [ 365 days ▾ ] │
│      ┌ Checks ───────────────────────────────────────────────────────────────────┐          │
│      │ ✔ Bucket reachable in us-east-1                                           │          │
│      │ ✔ Public access blocked                                                   │          │
│      │ ✔ Encrypted (KMS)                                                         │          │
│      │ ✖ XamOps can't write yet.  Create the access role (1 click)  [Review & create] │      │
│      │ ◐ Lifecycle: no tiering. Add recommended rules  [Copy JSON]               │          │
│      └───────────────────────────────────────────────────────────────────────────┘          │
│      Estimated storage cost: ~$11/mo (540 GB/mo, IA after 30d, Glacier IR after 90d)  ⓘ        │
│      Hot (fast) search covers the last 14 days. Older logs are searched in this bucket.        │
```

Retention control: presets 7 / 30 / 90 / 180 / 365 days and custom (7 to 3650). The cost line and the "Copy JSON" lifecycle only mention tiering steps that apply (for 30 days: "no tiering, expires after 30 days"; for 90 days: "IA after 30d"), matching the generator in 03 section 7.
Bucket list item states: public (disabled, "Public access is on"), wrong region ("Different region: transfer fees apply"), cross-account (disabled: "Only buckets in this AWS account are supported").

**B3. Policy preview (`PolicyPreviewDialog`)**

```
┌─ What XamPulse is allowed to do ─────────────────────────────────────── ✕ ┐
│ Three roles are created in YOUR account by CloudFormation you approve.     │
│ ▸ Installer     Run only 3 XamOps install scripts on servers tagged xamops:obs=enabled │
│ ▸ Log writer    Write files under  acme-logs/xamops-logs/  (cannot delete or read others) │
│ ▸ Log reader    Read files under   acme-logs/xamops-logs/  (cannot write)   │
│  [ Plain language | JSON ]                         [ Copy JSON ] [ Download template ] │
│  No role can create IAM users, read other buckets, or open inbound ports.   │
│                                 [ Cancel ]  [ Open CloudFormation to approve ] │
└────────────────────────────────────────────────────────────────────────────┘
```

**C. Installing (progress rail replaces the button; page becomes read-only)**

```
│ Installing on 12 servers                              7 connected · 3 waiting · 1 installing · 1 failed │
│ ████████████████████░░░░░░░░  75%                                                                 │
│ web-1   i-0abc  ✔ Connected      first metric 12s ago · first log 20s ago        [Open host ▸]    │
│ web-2   i-0abd  ✔ Connected                                                                        │
│ web-3   i-0abe  ◔ Waiting for first data…  00:42        [ Still waiting? Why ▸ ]                  │
│ api-1   i-0ac1  ◐ Installing…                                                                      │
│ api-2   i-0ac2  ✖ Couldn't reach XamPulse over port 443                                           │
│                 Allow outbound HTTPS to obs-ingest.example.com, then  [ Retry ]  [ Show fix ▸ ]   │
│ [ Cancel remaining ]                                  [ Roll back all ]                            │
```

(live region announces "web-1 connected".) Failure fix card states: `EGRESS_BLOCKED`, `CHECKSUM_MISMATCH` ("The download didn't match its checksum, so nothing was installed. Retry; if it repeats, contact support."), `SSM_TIMEOUT`, `OPERATOR_ROLE_DENIED`, `NO_DATA_AFTER_INSTALL`.

**D. Done (`SuccessPanel`)**

```
│ ✔ 12 of 12 servers connected                                                                 │
│ [ Open Hosts ]  [ See their logs ]  [ Set up an alert ]  [ Invite a teammate ]               │
│ What happens next: data appears within a minute; logs older than 14 days live in your bucket.│
```

**E. Copy command mode**

```
│ Run this on each server (Linux, needs sudo):                                                 │
│ ┌──────────────────────────────────────────────────────────────────────────── [Copy] ┐      │
│ │ curl -fsSL https://app.example.com/obs-agent/install.sh | sudo bash -s -- --token xo_enr_••••• │
│ └─────────────────────────────────────────────────────────────────────────────────────┘      │
│ ⓘ Single-use token for up to 12 servers, expires in 60 min.  [ Dry run first ] [ Uninstall command ] │
│ Live: ◔ 0 of 12 servers have checked in…  (this list updates by itself)                        │
```

**F. Other states:** no instances found ("We couldn't find running EC2 instances in this account. Check regions, or connect any server with the copy command."), inventory stale ("Using live data from AWS; this may take a few seconds"), region partial failure banner, account without permission stack (One-click shows lock + "Enable one-click" opening B3; Copy command works), over quota ("Your plan allows 500 servers; you selected 520"), error loading ("We couldn't load your servers. Retry. Details ▸").

### 4.2 Overview — `/observability-v1/overview`

```
┌ Overview ───────────────────────────────────────────────── [Compare ▾] [Last 24h ▾] [Connect servers] ┐
│ ┌ Fleet health ───┐ ┌ Servers ────────┐ ┌ Errors ────────┐ ┌ Logs ingested ─┐ ┌ Open incidents ─┐ │
│ │   ◜ 91 ◝        │ │ 44 / 48 online  │ │ 1.2% of lines  │ │ 52.6 GB  ▲7%   │ │ 3  (1 Critical) │ │
│ │ Good · ▲2 vs prev│ │ 2 delayed 1 silent│ │ ▲ 33% vs prev │ │ ▁▂▂▃▅▅▆       │ │ C H M           │ │
│ └─────────────────┘ └─────────────────┘ └────────────────┘ └────────────────┘ └─────────────────┘ │
│ ┌ Needs attention (top issues) ───────────────────────┐ ┌ Insights ───────────────────────────┐ │
│ │ C  web-1  iowait 38% for 12m              [Logs][Host]│ │ ⌁ web-1: /var fills in ~6 days      │ │
│ │ H  api-2  no data for 18m                 [Host]     │ │ ⌁ New error pattern on nginx (214×) │ │
│ │ M  db-1   memory +4%/day (possible leak)  [Metrics]  │ │ ⌁ 3 hosts oversized: save ≈ $86/mo │ │
│ └──────────────────────────────────────────────────────┘ └─────────────────────────────────────┘ │
│ ┌ Fleet map (health) ─────────────────────┐ ┌ Noisiest sources ─────────┐ ┌ Cost & storage ───┐ │
│ │ ▣▣▣▣▣▣▣▣▣▣  legend: ✔ ◐ ✖ ◔ ○           │ │ nginx 15% ▇▇▇▇  [Reduce ▸]│ │ This month $62     │ │
│ │ ▣▣▣▣▣▣▣▣▣◐                               │ │ app   11% ▇▇▇             │ │ Projected $141      │ │
│ │ ▣▣◐✖○                                    │ │ syslog 8% ▇▇              │ │ Your bucket: Healthy│ │
│ └──────────────────────────────────────────┘ └───────────────────────────┘ └────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

States: **first run (no hosts)** replaces everything below the header with `OnboardingNudge`: large pulse line, "Connect your first server. Takes about 2 minutes." [Connect servers] + three trust bullets. **Loading:** skeleton cards in the same grid. **Partial:** "Cost isn't available right now" inside that card only. **Error:** page-level `ObsStateBlock` with Retry. Health score tooltip lists the breakdown (06 section 5.1). Clicking any KPI drills to the filtered list (Servers → Hosts filtered `state=DELAYED,SILENT`; Errors → Logs `level:error`; Incidents → Alerts).

### 4.3 Hosts — `/observability-v1/hosts`

```
┌ Hosts   48 servers ───────────────────────────────────────── [ Table | Map ]  [Connect servers] ┐
│ [🔎 name, id, ip, tag]  Status chips: [All 48] [Connected 44] [Delayed 2] [Silent 1] [Uninstalled 1] │
│ Grouping:  None | Region | Account | Tag…        Active: [env=prod ✕] [Clear all]               │
│ ┌─────────────┬────────────┬───────────┬──────┬──────┬──────┬────────┬─────────┬────────────┐ │
│ │ ☐ Name      │ Status     │ Region    │ CPU  │ Mem  │ Disk │ Logs/m │ Agent   │ Last data  │ │
│ │ ☐ web-1     │ ✔ Online   │ us-east-1 │ ▁▃▅ 37%│ 62%│ 71%  │ 1.2k   │ 1.0.0   │ 3s ago     │ │
│ │ ☐ api-2     │ ✖ Silent   │ us-east-1 │ —    │ —    │ —    │ —      │ 1.0.0   │ 18m ago    │ │
│ └─────────────┴────────────┴───────────┴──────┴──────┴──────┴────────┴─────────┴────────────┘ │
│ Bulk: 2 selected  [Restart agent] [Set maintenance…] [Disconnect]                               │
```

Map view = `HostMap` tiles grouped by account/region, each tile a glyph + short name; hover shows CPU/mem/disk; click opens host detail. Empty: "No servers match these filters" + "Clear filters". No hosts at all: first-run nudge. Row keyboard: `j/k`, `Enter`, `x`.

### 4.4 Host detail — `/observability-v1/hosts/:hostId`

```
┌ ← Hosts / web-1   ✔ Online · i-0abc · m6i.large · us-east-1a · Ubuntu 22.04 · agent 1.0.0 ──────┐
│ Tags: env=prod  tier=web          [Open logs] [Metrics explorer] [Maintenance ▾] [Disconnect]      │
│ Tabs: Overview | Metrics | Logs | Processes | Containers | Config                                │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│ Overview                                                                                        │
│ ┌ CPU ────────────┐ ┌ Memory ──────────┐ ┌ Disk ───────────┐ ┌ Network ─────────┐                │
│ │ 37% ▲ vs prev   │ │ 62% (swap 0%)    │ │ / 71%  inodes 12%│ │ in 14 MB/s       │                │
│ │ user 21 sys 9    │ │ cache 18 GB      │ │ await 1.8 ms     │ │ retrans 0.02%    │                │
│ │ iowait 5 steal 0 │ │ OOM kills 0      │ │ IOPS r/w 320/90  │ │ conns 1.2k       │                │
│ └──────────────────┘ └──────────────────┘ └──────────────────┘ └──────────────────┘                │
│ ┌ Timeline (events, incidents, deploys, new patterns) ─────────────────────────────────────────┐ │
│ │ 10:12 ▲ iowait high ──┬── 10:14 ✖ error burst (nginx) ── 10:20 ✔ recovered                   │ │
│ └──────────────────────────────────────────────────────────────────────────────────────────────┘ │
│ ┌ Recent errors (from logs) ──────────────────────────┐ ┌ Agent ─────────────────────────────┐ │
│ │ 10:14:59 nginx upstream timed out … ×214  [Open ▸]  │ │ Healthy · queue 2% · dropped 0     │ │
│ └─────────────────────────────────────────────────────┘ │ Token expires in 61 days (auto)    │ │
```

**Metrics tab** shows panels with per-core CPU heat strip, load, run queue, context switches; memory (used/available/cache/buffers/slab, swap in/out, page faults, OOM kills); disk per device IOPS/throughput/await/queue/utilization, filesystem and inode usage; network per interface bytes/packets/errors/drops, TCP connections by state, retransmits; sync crosshair across all charts; "Show logs for this window" on brush. **Processes tab:** top N by CPU/mem table, zombies count banner, thread/FD columns, snapshot time. States: host silent → banner "No data since 10:02. Last known values shown. [Troubleshoot]" listing checks (service status command, egress test). Host uninstalled → read-only history.

### 4.5 Logs — Explorer — `/observability-v1/logs`

```
┌ Logs ─────────────────────────────────  [ Explorer | Patterns | Saved ]  [Export ▾] [Save query] ┐
│ ┌──────────────────────────────────────────────────────────────────────────────────────────┐  │
│ │ 🔎  service:nginx level:(error OR warn) "timed out"                         ⓘ  { }  [Search] │  │
│ │ Ask in plain English:  “show 5xx errors on web servers in the last hour”         [✦ Translate] │  │
│ └──────────────────────────────────────────────────────────────────────────────────────────┘  │
│ ┌ Histogram (drag to zoom) ───────────────────────────────────────────────────────────────┐   │
│ │ ▁▁▂▁▁▁▃▅▇▆▃▂▁▁▁▁▁▂▁▁▁   ▮ error ▮ warn ▮ info (letters E W I in legend)                   │   │
│ └─────────────────────────────────────────────────────────────────────────────────────────┘   │
│ ┌ Fields ───────┐ ┌ 1,284 logs · 184 ms · hot (last 14d)    [ ▶ Live tail ] ⚙ columns  wrap ┐  │
│ │ ▸ level       │ │ TIME          LVL HOST   SERVICE  MESSAGE                              │  │
│ │   E 214  W 51 │ │ 10:14:59.812  E   web-1  nginx    upstream timed out (110: …)          │  │
│ │ ▸ host        │ │ 10:14:59.640  W   web-2  nginx    slow upstream 3.2s /api/orders       │  │
│ │   web-1  980  │ │ 10:14:58.100  I   web-1  app      request completed status=200         │  │
│ │ ▸ service     │ │ …                                                                       │  │
│ │ ▸ pattern     │ └─────────────────────────────────────────────────────────────────────────┘  │
│ │ ▸ + field…    │   Detail drawer opens on row click ⟶                                          │
│ └───────────────┘                                                                                │
```

Details:
- **Search bar:** syntax highlighting; autocompletes fields then values; invalid input shows inline hint and underlines the offending token; "{ }" opens `CompiledQueryPopover` (shows the compiled LogsQL, copy). Natural-language row appears only when AI is enabled for the tenant; result shows the generated XLQ in the bar for review; it never runs silently.
- **Facets:** click includes, shift-click excludes; counts update with the query; active filters appear as chips under the bar (`ActiveFilters`) with ✕.
- **Row:** level shown as letter + word-colour; hover reveals row actions (copy line, filter by this value, context, "Metrics at this time").
- **Detail drawer (480 px):** tabs Fields | Raw | Context (±20 lines) | Metrics around this time (mini charts for the host) | Pattern (template, count, first seen). Actions: "Explain this error" (AI), "Create alert from this pattern".
- **AI summary:** `[✦ Summarize]` in the result header produces a 3-bullet summary with citations ("[1] 214 lines like …") each linking to a filtered view.

**Explorer states**

```
 loading         histogram skeleton + 12 skeleton rows; facets skeleton
 empty (no match)  “No logs match this query in the last hour.”  [Widen to 24h] [Remove filters] [Search archive]
 empty (no data)   “No logs yet from these servers.”  [Check agents]  [Connect servers]
 syntax error      inline under bar: “Expected a value after level:” (position-highlighted)
 hot store down    degraded banner: “Recent logs are temporarily unavailable.  [Retry]  [Search archive instead]”
 too many results  footer “Showing the newest 10,000. Refine your query or Export.”
 no permission     NoPermissionState
```

**Live tail**

```
│ ● LIVE  service:nginx level:error   12 lines/s   [ ❚❚ Pause ]  [ Clear ]  [ Stop ]   Buffer 5,000     │
│ 10:15:00.456  E web-1 nginx upstream timed out …                                                   │
│ ─────────────────────────────── ▲ 38 new lines — click to jump to newest ────────────────────────── │
```

States: connecting ("Connecting…" with pulse line), live, paused ("Paused. 142 new lines waiting."), throttled (banner: "Showing 200 of ~1,900 lines/s. Narrow the query to see every line." with dropped counter), reconnecting ("Connection lost. Reconnecting in 4s…"; after 8 failed attempts: "Can't reach live tail" with a Reconnect button), gap (a divider "Gap in the stream: some lines may be missing" after `RESUMED_WITH_GAP`), partial (chip "Some accounts unavailable: 15" when `PARTIAL`), pre-stream errors shown as `ObsStateBlock` (syntax error with the position highlighted in the query bar, "Live tail can't run stats or top; remove the stage" for `TAIL_UNSUPPORTED_STAGE`, "Too many live streams; close one to start another" for 429), ended after 30 min (silently reconnects once, then "Live tail resumed"), error (ObsStateBlock error with Retry), impersonation ("Live tail isn't available while viewing as another user"), no permission.

**Archive search (range older than 14 days)**

```
│ ⓘ This range is older than 14 days. It lives in your S3 bucket and is searched on demand.     │
│ ┌ Plan ─────────────────────────────────────────────────────────────────────────────────┐   │
│ │ 48 files · 612 MB · about 14 s · bucket acme-logs (us-east-1)                          │   │
│ │ ◐ 6 files are in Glacier Instant Retrieval (slightly slower, small retrieval fee)       │   │
│ │                                           [ Narrow range ]  [ Run archive search ]     │   │
│ └────────────────────────────────────────────────────────────────────────────────────────┘   │
│ Running… 31 / 48 files · 12,402 rows so far          [ Cancel ]                               │
│ Done: 18,220 rows · 612 MB scanned · 13 s            [ Export ] [ Save query ]                │
│ Too large:  “This search would scan 41 GB (limit 20 GB). Narrow the range or pick a host.”     │
│ Needs restore: “Hours 2026-03-04 02:00–05:00 are in Deep Archive. Restore them first.”         │
```

### 4.6 Logs — Patterns and Saved

```
┌ Patterns   Last 24h   Group by service ▾   [New only] [Rising] [Muted] ───────────────────────┐
│ #  Pattern                                               Count  Trend      First seen   Level │
│ 1  mysql: lock wait timeout exceeded <*> on table <*>      190  ▁▂▅▇▇  ● NEW 10:12      E   │
│ 2  GET <*> HTTP/1.1 <*> <*>                              48,210 ▅▅▅▅▅    3 weeks      I   │
│ Row actions: View logs · Mute (hide from noise view) · Create alert · Drop at source (shows saving) │
```

Saved: table of saved queries with owner, scope, visibility (Private / Shared), run, edit, duplicate; empty: "Save a query to reuse it or share it with your team."

### 4.7 Metrics Explorer — `/observability-v1/metrics`

```
┌ Metrics   [ Explorer | Dashboards ] ─────────────────────────────── [Last 1h ▾] [Compare ▾] ┐
│ ┌ Query ─────────────────────────────────────────────────────────────────────────────────┐  │
│ │ Metric [ CPU › iowait ▾ ]  Filter [ env = prod + ]  Group by [ host ▾ ]  Agg [ avg ▾ ] Top [10] │
│ │                                                      [ + Add another metric ]  [ Advanced PromQL ] │
│ └────────────────────────────────────────────────────────────────────────────────────────┘  │
│ ┌ host.cpu.usage (iowait) ─────────────────────────────────────────── ⋯ drill-down ▾ ──┐   │
│ │ 40% ┤              ╭╮                                                                 │   │
│ │ 20% ┤         ╭────╯╰──╮     ── web-1  ── web-2  - - previous period                   │   │
│ │  0% ┼─────────╯        ╰────────────────────────────                                  │   │
│ │      09:15        09:45         10:15        (drag to select a range)                  │   │
│ │ [ Show logs for this range ✦ correlate ]  [ Create alert ]  [ Add to dashboard ]       │   │
│ └────────────────────────────────────────────────────────────────────────────────────────┘  │
```

Correlation panel (opens after brushing a spike):

```
┌ What happened between 10:12 and 10:22 on web-1 ─────────────────────────────────── ✕ ┐
│ Logs: 214 errors · 51 warnings     [ Open these logs ▸ ]                               │
│ New patterns: “mysql: lock wait timeout …” (190×, first seen 10:12)                    │
│ Moved together: disk latency (nvme1n1) r=0.93   run queue r=0.81                       │
│ ✦ Likely cause (AI, cites evidence 1–3): Database lock contention … [Why?]              │
```

Empty: "Pick a metric to start." Error: "That query returned too many points. Try a step of 60s." (button applies). No data for filter: "No data for these filters. The agent may be off or the metric isn't collected on this host." Dashboards: grid of cards + `SuggestedDashboards` ("We found nginx and PostgreSQL on 4 servers. Add the ready-made dashboards?").

### 4.8 Containers and Services

Containers table: Name, Runtime (Docker/containerd/ECS/EKS) glyph+word, Host, Image:tag, CPU, Mem vs limit bar, Throttling %, Restarts, State; filter chips by runtime and "Restarting"/"OOM killed"; detail page: limits vs usage, throttling chart, restart timeline, logs tab. Empty: "No containers detected. If you run Docker, enable the Docker source on the host. [Open Agents]".

Service map (`/observability-v1/services?view=map`):

```
┌ Services   22 discovered ─────────────────────────────────── [ Map | Table ]  [Include hosts ☐] ┐
│            ┌────────┐      ┌────────┐      ┌───────────┐                                         │
│  Internet ▶│ nginx  │─────▶│  api   │─────▶│ PostgreSQL│        Legend: ✔ healthy ◐ degraded ✖ failing │
│            │ ✔ 1.2k/s│     │ ◐ 5xx 2%│    │ ✔ 40 conn │        Edge label = conn/s · errors %        │
│            └────────┘      └───┬────┘      └───────────┘                                         │
│                                └────▶┌───────┐                                                   │
│                                      │ Redis │                                                   │
│  Click a node → drawer: RED metrics (rate, errors, p95), hosts, top log patterns, alerts.          │
```

Discovery caveat shown once: "Built from listening ports, processes and proxy configs. Traces will add exact calls." Empty: "No services found yet. They appear after an agent has run for a few minutes."

### 4.9 Alerts — `/observability-v1/alerts`

```
┌ Alerts   [ Incidents | Rules | SLOs | Channels | Mute windows ] ─────────── [ + New rule ]  ┐
│ Status [Open 3] [Acknowledged 1] [Resolved 24h 9] [Suppressed 2]   Severity [C][H][M][L][I]   │
│ ┌────┬─────┬────────────────────────────────────────┬────────────┬───────────┬──────────┐  │
│ │ Sev│ Age │ Incident                               │ Where      │ Status    │ Notified │  │
│ │ C  │ 14m │ High iowait on web-1                   │ web-1      │ Open      │ Slack ✔  │  │
│ │ H  │ 18m │ No data from api-2                     │ api-2      │ Ack by Sam│ PagerDuty✔│ │
│ └────┴─────┴────────────────────────────────────────┴────────────┴───────────┴──────────┘  │
```

Incident drawer: summary (plain language), timeline (opened, notified, escalated, acked, comments), evidence (chart, top patterns, co-moving metrics), `RcaPanel` ("✦ Probable cause" with citations; "AI is off for your team" state with enable link for admins), actions [Acknowledge] [Resolve] [Mute 1h] [Open logs] [Open host]. Dedup shown as "Seen 7 times in 14 min".

**Rule editor (`RuleEditor`)**

```
┌ New rule ──────────────────────────────────────────────────────────────────────────────────┐
│ Type  (•) Threshold ( ) Anomaly ( ) Log pattern ( ) Forecast ( ) Absence ( ) Composite ( ) SLO burn │
│ Name [ Disk will fill within 3 days ]            Severity [ High ▾ ]                          │
│ When  host.fs.used on mount “/”  is forecast to reach 100%  within [3] days  (lookback 7d)   │
│ For   [ 0 ] min    Check every [ 5 ] min   Group by [ host, mountpoint ]   Scope [ env=prod ] │
│ Notify [Slack #ops ✔] [PagerDuty ☐]   Escalate: after 15m → [On-call email]   Repeat every [4h] │
│ ┌ Preview: would have fired 14 times in the last 7 days across 3 servers ──────────────────┐ │
│ │ ▁▁█▁▁▁█▁▁▁▁█   ⚠ That's more than 10 a week. Consider ‘for 10 min’.       [Apply suggestion] │ │
│ └───────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                              [ Cancel ]  [ Save rule ]       │
```

SLOs: cards with objective, current SLI, `ErrorBudgetBar` (remaining %, burn rate 1h/6h, "budget gone in ~2d"), create wizard from a log error-ratio or metric ratio. Channels: list with type icon, last test result, `[Send test]`, delivery log. Mute windows: calendar-style list with one-off and recurring; "maintenance mode on web-1 until 12:00" entries.

### 4.10 Storage — `/observability-v1/storage`

```
┌ Storage ───────────────────────────────────────────────────────────── [ Change destination ] ┐
│ ┌ Destination ─────────────────────────────────────────────┐ ┌ Usage (30 days) ──────────────┐ │
│ │ s3://acme-logs/xamops-logs/   us-east-1   ✔ Healthy      │ │ ▁▂▂▃▃▄▅▅▆▆  912 GB · 3.1 M objs│ │
│ │ Encrypted: KMS · Public access: blocked · Format Parquet │ │ by type ▾  by host ▾           │ │
│ │ Keep: 365 days  [Edit]   Last write 12s ago              │ └───────────────────────────────┘ │
│ └──────────────────────────────────────────────────────────┘ ┌ Cost ─────────────────────────┐ │
│ ┌ Tiers ───────────────────────────────────────────────────┐ │ This month $62 · Projected $141│ │
│ │ Standard 41% ▇▇▇▇  IA 33% ▇▇▇  Glacier IR 26% ▇▇          │ │ Biggest: nginx logs $18        │ │
│ └──────────────────────────────────────────────────────────┘ └───────────────────────────────┘ │
│ ┌ Search windows ───────────────────────────────────────────────────────────────────────────┐ │
│ │ Fast search (hot)  last 14 days   ·   Archive search (your bucket)  14 days → 365 days      │ │
│ └───────────────────────────────────────────────────────────────────────────────────────────┘ │
│ [ Browse layout ▸ ]  [ Athena query setup ▸ ]  [ Move to my bucket ▸ ] (if managed)             │
```

States: **no destination** ("Logs are stored in XamOps-managed storage. Use your own bucket for full control." [Use my bucket] [Create one for me]); **degraded** banner ("XamPulse can't write to your bucket since 10:02 (AccessDenied). Logs are buffered for 7 days. [Fix access] [Switch to managed storage]"); **failed** (same plus "Safe fallback is on: new logs go to managed storage until you fix this."); **permission stack removed** card; **migrating** progress; **loading** skeleton. The `Browse layout` dialog shows the exact path pattern with a copyable example key.

### 4.11 Agents — `/observability-v1/agents`

```
┌ Agents   48 total ─────────────────────────────────────── [Roll out update ▾] [Config profiles] ┐
│ Version drift: 1.0.1 ▇▇▇▇▇▇▇▇ 38   1.0.0 ▇▇ 8   1.0.0-beta ▇ 2        Latest stable: 1.0.1       │
│ Status [All] [Healthy 44] [Update available 8] [Degraded 2] [Silent 1] [Quarantined 0]            │
│ ┌ ☐ Host   │ Version │ Health          │ Queue │ Dropped │ Config │ Last seen │ Token expires ┐ │
│ │ ☐ web-1  │ 1.0.1   │ ✔ Healthy       │ 2%    │ 0       │ prod ✔ │ 3s        │ 61 d (auto)   │ │
│ │ ☐ api-2  │ 1.0.0   │ ✖ Silent        │ —     │ —       │ —      │ 18m       │ 40 d          │ │
│ Bulk: [Update to 1.0.1] [Restart] [Rotate token] [Quarantine] [Uninstall]                         │
```

Rollout panel: pick release, scope, canary % (10 → 50 → 100) with automatic halt on failed health gate; states Pending/Canary/Rolling/Paused/Completed/Rolled back; rollback button. Agent health drawer: source states, recent errors, queue/drops, resource use of the agent itself, "Collect diagnostics". Destructive actions use `ConfirmDialog` with the consequence text ("This stops log and metric collection on 2 servers. You can reinstall any time.") and an Undo toast for quarantine/maintenance (not for uninstall).

### 4.12 Settings (summary)

Tokens: enrollment tokens (prefix, uses, expiry, revoke) and ingest tokens per host (prefix, last used, expiry; "Revoke all"). Access: per-user permission matrix with plain labels ("See log content"), warning when granting export. Redaction: built-in list with toggles, custom regex rules, **live preview** (paste a sample, see before/after, matches highlighted). Cost controls: caps per tenant/account/host/source, action at cap (Sample / Drop / Alert only), sampling rules with projected savings. Audit: filterable table with CSV export. AI: single toggle, explanation of what is sent ("Only short, redacted excerpts and summaries. Never raw logs in bulk. Never used to train models."), off by default.

## 5. Interaction specification (cross-cutting)

- **Selection model:** row checkbox, shift-range select, header checkbox = page, banner offers "select all N matching" (selector persisted server-side beyond 500).
- **Filters:** chips with counts (XamSecure Issues vocabulary), AND across groups, OR within; clear-all; filters live in the URL; saved views store them.
- **Drill-down:** every chart and KPI is clickable or has a "⋯" menu with at least: open logs, open metric explorer, open host. Brush selects a range; double-click resets; `Alt+drag` pans.
- **Hover:** crosshair tooltips are synced across charts in a group; tooltip has a pinned state on click (Esc to unpin).
- **Live:** global "● Live" toggle on explorer pages; auto-refresh intervals 10 s/30 s/1 m/off; pauses when tab hidden.
- **Undo:** non-destructive changes (mute, quarantine, rule disable, dismiss insight) show a 6-second toast with Undo. Destructive (uninstall, delete rule, delete destination) require typed confirmation only for delete destination/stack removal guidance; otherwise `ConfirmDialog`.
- **Drawers:** right-hand 480 px (`DetailDrawer`); `Esc` closes; focus returns to the invoking row; deep links reopen the drawer.
- **Copy affordances:** copy line, copy query, copy as curl (search API), copy command, copy JSON; success shown inline ("Copied") for 1.5 s with `aria-live`.
- **Density:** Comfortable (40 px rows) / Compact (28 px) persisted per user; logs default Compact.
- **Responsive:** ≥ 1280 full layout with facet sidebar; 1024–1279 facets collapse into a "Fields" drawer; 768–1023 tables switch to stacked cards for Hosts/Alerts, charts single column, scope bar collapses into a "Filters" button; < 768 supports read-only monitoring (Overview, Hosts list/detail, Incidents with acknowledge) while Connect and rule editing show "Open on a larger screen to set this up" with a copy-link action.
- **Keyboard:** see 05 section 5; every shortcut is listed in `ObsShortcutsDialog` (`?`), none triggers while typing in inputs.
- **Loading budgets:** skeleton within 100 ms; first Logs result < 1 s for a 15-min hot query; live tail latency < 3 s; Overview TTI < 2.5 s on warm cache; archive search shows plan before running.

## 6. Microcopy bank (selected)

| Where | Copy |
|---|---|
| Connect button | "Connect 12 servers" |
| Trust summary | "Outbound only (443). No inbound ports. Read-only by default. Remove anytime." |
| Waiting | "Waiting for first data from web-1. This usually takes under a minute." |
| Connected | "web-1 is connected. First metric 12 s ago." |
| Egress fail | "This server can't reach XamPulse over port 443. Allow outbound HTTPS to obs-ingest.example.com, then retry." |
| SSM offline | "Systems Manager isn't online on this server, so one-click install can't reach it. Use the copy command instead." |
| Hot window | "Fast search covers the last 14 days. Older logs are searched in your bucket." |
| Archive cost | "This search reads 612 MB from your bucket (about $0.00 in S3 request fees)." |
| Empty logs | "No logs match this query in the last hour." |
| Redaction | "Secrets and tokens are masked on the server before logs leave it." |
| AI off | "AI summaries are off for your team. An admin can turn them on in Settings." |
| Permission | "You can see health and counts. Ask an admin for log access." |
| Destructive | "This stops collection on 2 servers. Their history stays." |

## 7. Accessibility and quality bar

- Contrast: text ≥ 4.5:1, UI components and focus ≥ 3:1 in both themes (token pairs enforced by the design-rule test once added).
- Never colour alone: every status/level has a glyph and word (✔ Online, ◐ Needs attention, ✖ Blocked, ◔ Waiting, ○ Off; levels E W I D T F). Compare series use dash + legend text.
- Keyboard: everything operable; focus order follows visual order; drawers trap and restore focus; no keyboard traps in the search bar; shortcuts can be disabled in Settings.
- Screen readers: landmarks, table semantics, chart summaries and data-table toggle, live regions for progress and "new lines", meaningful names for icon buttons.
- Motion/zoom: reduced motion honoured; layout works at 200% zoom and 320 px reflow for read-only monitoring views.
- Note: automated checks and this spec cannot certify WCAG conformance; manual testing with assistive technologies and an expert review are required (08 test plan).

## 8. UX review checklist (to run before each phase exit)

1. Can a new user connect a server in under 3 minutes, with only one decision (which servers)? Time it with 5 people.
2. Does every screen have designed loading, empty, error, degraded, waiting and no-permission states (list them per screen)?
3. Is every status readable without colour? Is every icon-only button labelled?
4. Can every chart be drilled into logs/host/metric explorer? Can every list be filtered via URL and shared?
5. Is it always clear whether data is hot (fast) or archive (your bucket), and what a search will cost or scan?
6. Are all destructive actions confirmed with consequence text, and are reversible ones undoable?
7. Does the Trust panel show installed software, collected data, exact IAM JSON, network, uninstall, before the user commits?
8. Do dark and light both pass contrast and look intentional (no leftover light-only styles)?
9. Does the keyboard cover search, navigation, selection, drawers, live tail and the palette?
10. Is microcopy plain, specific and free of internal jargon (`LogsQL`, `MAD`, `OTLP` only behind "Details")?
11. Do large tables (10k rows) and the log list scroll at 60 fps in Chrome and Safari?
12. Are numbers formatted consistently (units, precision, tabular figures, local time zone indicator)?
13. Do error messages include what happened, what it means, what to do, and a request id under Details?
14. Is nothing sensitive stored in browser storage (log lines, hostnames, queries)?
15. Does the page work at 1024 px and read-only at 390 px?
16. Does the pulse-motif identity stay consistent (no one-off styles)? Is `prefers-reduced-motion` honoured everywhere?
