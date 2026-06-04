# Monthly Cloud Digest — Design Spec

**Date:** 2026-06-04
**Feature:** Automated monthly cloud digest email sent to all account users on the 30th of each month
**Scope:** AWS accounts only

---

## Problem

XamOps sends cost reports only when users explicitly subscribe via the UI. There is no automatic system-wide summary that every user receives. Account owners and team members have no guaranteed visibility into their cloud health unless they opt in.

---

## Goals

1. Send one digest email per AWS account on the 30th of each month at 6:00 PM IST.
2. Cover five sections: Cost Summary, Security Status, Waste Opportunities, EC2 Rightsizing, Disk Health.
3. Reach every user in the client account — no opt-in required.
4. Reuse all existing data services — no new AWS API integrations.

---

## Non-Goals

- GCP or Azure accounts (AWS only for now).
- Configurable send day or time per account.
- In-app digest view (email only).
- User unsubscribe/opt-out mechanism (v1).

---

## Schedule

`@Scheduled(cron = "0 30 12 30 * ?")` — fires at 12:30 UTC (6:00 PM IST) on the 30th of every month.
February is skipped (no 30th). Consistent with the existing monthly FinOps report trigger.

---

## Email Layout

**Structure:** black header block → white stats strip → four compact section rows → footer

### Header block (black background)
- White `XAMOPS` badge (top-left), generated date (top-right)
- Title: "Monthly Cloud Digest" (white, bold)
- Subtitle: account name · AWS account ID · date range (muted white)

### Stats strip (white background)
Three stat columns separated by hairline dividers:
- **Total Spend** — large black number, delta vs previous month (red if up, green if down)
- **Potential Savings** — green number, "across all recommendations" sub-label
- **Top Service** — service name, cost and % of total

### Section rows
Each row: left column = grey label tag (`Security` / `Waste` / `Resize` / `Disk`), right column = content.

| Row | Content |
|---|---|
| Security | Four severity pills (Critical / High / Medium / Low) + top finding text |
| Waste | List of wasted resources — monospace ID, type + reason, green saving/mo |
| Resize | List of EC2 rightsizing — monospace ID, current → recommended, green saving/mo |
| Disk | Coloured chips per flagged volume (red = near full, amber = underutilized) with region, size, %, action |

Disk row is **omitted entirely** if no agent data exists for the account.

### Footer (off-white background)
Black `XAMOPS` badge, tagline, copyright, automated report disclaimer.

---

## Architecture

Three new files — nothing existing is modified.

### `MonthlyDigestScheduler.java`
```
@Component
@Scheduled(cron = "0 30 12 30 * ?")
void runMonthlyDigest() → monthlyDigestService.runDigest()
```

### `MonthlyDigestService.java`
```
runDigest()
  accounts = finOpsReportScheduleRepository.findDistinctActiveAccounts()
             + any AWS account not already covered
  (simpler: cloudAccountRepository.findAll() filtered to AWS)

  for each AWS CloudAccount:
    try:
      report   = finOpsService.getFinOpsReport(accountId, false, firstOfMonth, lastOfMonth).join()
      security = awsSecurityHubService.getSecurityFindings(accountId, false).join()
      ec2Rs    = xamOpsRightsizingService.getLiveRecommendations(accountId, false)
      diskRs   = ebsDiskRightsizingService.getRecommendations(accountId, false)  // empty = no agent

      subject  = "Your Monthly Cloud Digest — <accountName> — <Month YYYY>"
      htmlBody = monthlyDigestEmailBuilder.build(report, security, ec2Rs, diskRs, accountName, dateRange)

      recipients = userRepository.findByClientId(account.getClient().getId())
                   .stream().map(email).distinct()

      for each recipient:
        emailService.sendHtmlEmail(recipient, subject, htmlBody)
    catch Exception:
      log.warn, skip account, continue
```

### `MonthlyDigestEmailBuilder.java`
```
String build(FinOpsReportDto report,
             List<AwsSecurityFinding> securityFindings,
             List<XamOpsRightsizingRecommendation> ec2Recommendations,
             List<Map<String,Object>> diskRecommendations,
             String accountName,
             String dateRange)
```

Builds the full HTML email matching the approved layout. Pure string building (consistent with existing email builders). No external template engine.

---

## Data Sources

| Section | Service | Method | Notes |
|---|---|---|---|
| Cost total + waste | `FinOpsService` | `getFinOpsReport()` | Returns KPIs + `List<WastedResource>` in one call |
| EC2 rightsizing | `XamOpsRightsizingService` | `getLiveRecommendations()` | Top N by `approxCostSavings` desc |
| Security | `AwsSecurityHubService` | `getSecurityFindings(accountId, false)` | Returns `List<AwsSecurityFinding>` with severity; grouped by severity; top finding = first CRITICAL or HIGH |
| Disk | `EbsDiskRightsizingService` | `getRecommendations()` | Empty list = no agent = section omitted |
| Recipients | `UserRepository` | `findByClientId()` | Same pattern as `BudgetAlertScheduler` |

All calls use `forceRefresh=false` — relies on existing Redis cache. No extra AWS API calls.

---

## Email Sections Detail

### Cost Summary stats strip
- Total spend: `report.getKpis().getMtdSpend()`
- Last month spend: `report.getKpis().getLastMonthSpend()` → compute delta %
- Potential savings: `report.getKpis().getTotalPotentialSavings()`
- Top service: first entry of `report.getCostBreakdown().getCostByService()`

### Security row
- Count findings by severity: CRITICAL / HIGH / MEDIUM / LOW
- Top finding: description of first CRITICAL finding, or first HIGH if no CRITICAL
- If no findings at all: show "No findings detected" in the row

### Waste row
- Source: `report.getWastedResources()`, sorted by `monthlySavings` desc
- Show top 5. If none: row shows "No waste identified this month."

### Resize row
- Source: `ec2Recommendations`, sorted by `approxCostSavings` desc
- Show top 5. If none: row shows "No rightsizing recommendations."

### Disk row
- Source: `diskRecommendations` filtered to `recommendation != "NONE"`
- Chip colour: red border/bg for `EXPAND` (near full), amber for `SHRINK` (underutilized)
- If list is empty: entire disk row is omitted from the HTML

---

## Error Handling

- Account-level failure (any data source throws) → log warning with account ID, skip account, continue loop
- Email send failure for one recipient → log error, continue to next recipient
- No retry — runs again next month

---

## New Files

| File | Type |
|---|---|
| `service/MonthlyDigestScheduler.java` | New `@Scheduled` component |
| `service/MonthlyDigestService.java` | New orchestration service |
| `service/MonthlyDigestEmailBuilder.java` | New HTML email builder |

## No DB Changes

No new tables, migrations, or entities required.

---

## Testing

### `MonthlyDigestServiceTest.java`
- Skips GCP accounts
- Fetches all 4 data sources for an AWS account
- Sends email to all users in the client
- Skips account cleanly on data source exception

### `MonthlyDigestEmailBuilderTest.java`
- Hero contains account name, date range, total spend, savings
- Security row shows correct severity counts
- Waste row shows top items with savings
- Resize row shows current → recommended with savings
- Disk row present when flagged volumes exist
- Disk row absent when `diskRecommendations` is empty
