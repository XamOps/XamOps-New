# Monthly Cloud Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send an automated monthly cloud digest email to all users in every AWS client account on the 30th of each month, covering cost, security, waste, EC2 rightsizing, and disk health.

**Architecture:** Three new files only — `MonthlyDigestEmailBuilder` builds the HTML, `MonthlyDigestService` orchestrates data fetching from four existing services and sends emails, `MonthlyDigestScheduler` triggers the service via `@Scheduled`. No DB changes, no new entities.

**Tech Stack:** Spring Boot, Spring `@Scheduled`, JUnit 5, Mockito, `FinOpsService`, `AwsSecurityHubService`, `XamOpsRightsizingService`, `EbsDiskRightsizingService`, `EmailService`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestEmailBuilder.java` | Create | Builds the full HTML email string from four data inputs |
| `xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestService.java` | Create | Fetches data from 4 services, resolves recipients, sends emails |
| `xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestScheduler.java` | Create | `@Scheduled(cron = "0 30 12 30 * ?")` wrapper that calls `MonthlyDigestService.runDigest()` |
| `xamops-service/src/test/java/com/xammer/cloud/service/MonthlyDigestEmailBuilderTest.java` | Create | Unit tests for the email builder |
| `xamops-service/src/test/java/com/xammer/cloud/service/MonthlyDigestServiceTest.java` | Create | Unit tests for the service orchestrator |

---

## Context You Need

**Before writing any code, read these files to understand patterns:**
- `xamops-service/src/main/java/com/xammer/cloud/service/BudgetAlertScheduler.java` — recipient resolution pattern (`userRepository.findByClientId`) and account iteration pattern
- `xamops-service/src/main/java/com/xammer/cloud/service/FinOpsReportEmailBuilder.java` — email HTML builder pattern (StringBuilder, inline styles, no template engine)
- `xamops-service/src/main/java/com/xammer/cloud/service/DiskAlertScheduler.java` — minimal scheduler pattern

**Key method signatures you will call:**
```java
// FinOpsService — returns cost KPIs + waste list in one call
CompletableFuture<FinOpsReportDto> finOpsService.getFinOpsReport(
    String accountId, boolean forceRefresh, String startDate, String endDate)

// FinOpsReportDto structure:
//   .getKpis().getMonthToDateSpend()    → double
//   .getKpis().getLastMonthSpend()      → double
//   .getKpis().getPotentialSavings()    → double
//   .getCostBreakdown().getByService()  → List<Map<String,Object>>
//       each map has "name" (String) and "amount" (Number) keys
//       NOTE: verify exact map keys by reading FinOpsService.java before implementing
//   .getWastedResources()               → List<DashboardData.WastedResource>
//       .getResourceId(), .getResourceType(), .getReason(), .getMonthlySavings() (double)

// AwsSecurityHubService — returns security findings
CompletableFuture<List<AwsSecurityFinding>> awsSecurityHubService.getSecurityFindings(
    String accountId, boolean forceRefresh)
// AwsSecurityFinding: .getSeverity() ("CRITICAL","HIGH","MEDIUM","LOW"), .getTitle(), .getResourceId(), .getRegion()

// XamOpsRightsizingService — returns EC2 rightsizing recommendations
List<XamOpsRightsizingRecommendation> xamOpsRightsizingService.getLiveRecommendations(
    String accountId, boolean forceRefresh)
// XamOpsRightsizingRecommendation: .getInstanceId(), .getCurrentInstance(),
//   .getIntelRecommendation(), .getApproxCostSavings() (String), .getRegion(), .getInstanceName()

// EbsDiskRightsizingService — returns flagged volume maps
List<Map<String,Object>> ebsDiskRightsizingService.getRecommendations(
    String accountId, boolean forceRefresh)
// each map: "volumeId" (String), "region" (String), "sizeGb" (Number),
//           "usedPercent" (Number), "recommendation" ("EXPAND"/"SHRINK"/...)

// CloudAccountRepository
List<CloudAccount> cloudAccountRepository.findByProvider("AWS")

// UserRepository — same pattern as BudgetAlertScheduler
List<User> userRepository.findByClientId(Long clientId)
// User: .getEmail() (may be null), .getUsername() (may contain "@")

// EmailService
void emailService.sendHtmlEmail(String to, String subject, String htmlBody)
```

---

## Task 1: MonthlyDigestEmailBuilder

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestEmailBuilder.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/MonthlyDigestEmailBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

Create `MonthlyDigestEmailBuilderTest.java`:

```java
package com.xammer.cloud.service;

import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlyDigestEmailBuilderTest {

    private MonthlyDigestEmailBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new MonthlyDigestEmailBuilder();
    }

    private FinOpsReportDto buildReport(double mtdSpend, double lastMonthSpend, double savings) {
        FinOpsReportDto report = mock(FinOpsReportDto.class);
        FinOpsReportDto.Kpis kpis = mock(FinOpsReportDto.Kpis.class);
        FinOpsReportDto.CostBreakdown breakdown = mock(FinOpsReportDto.CostBreakdown.class);

        when(report.getKpis()).thenReturn(kpis);
        when(kpis.getMonthToDateSpend()).thenReturn(mtdSpend);
        when(kpis.getLastMonthSpend()).thenReturn(lastMonthSpend);
        when(kpis.getPotentialSavings()).thenReturn(savings);

        Map<String, Object> svc = new HashMap<>();
        svc.put("name", "EC2");
        svc.put("amount", 6240.0);
        when(report.getCostBreakdown()).thenReturn(breakdown);
        when(breakdown.getByService()).thenReturn(List.of(svc));
        when(report.getWastedResources()).thenReturn(List.of());
        return report;
    }

    @Test
    void heroContainsAccountNameAndDateRange() {
        FinOpsReportDto report = buildReport(14820.0, 13685.0, 2380.0);

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 1 – June 30, 2026");

        assertThat(html).contains("prod-main");
        assertThat(html).contains("June 1 – June 30, 2026");
        assertThat(html).contains("XAMOPS");
        assertThat(html).contains("Monthly Cloud Digest");
    }

    @Test
    void statsStripShowsSpendAndSavings() {
        FinOpsReportDto report = buildReport(14820.0, 13685.0, 2380.0);

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 1 – June 30, 2026");

        assertThat(html).contains("$14,820");
        assertThat(html).contains("$2,380");
    }

    @Test
    void securityRowShowsCorrectSeverityCounts() {
        AwsSecurityFinding critical = mock(AwsSecurityFinding.class);
        when(critical.getSeverity()).thenReturn("CRITICAL");
        when(critical.getTitle()).thenReturn("S3 bucket public");
        when(critical.getResourceId()).thenReturn("my-bucket");
        when(critical.getRegion()).thenReturn("us-east-1");

        AwsSecurityFinding high = mock(AwsSecurityFinding.class);
        when(high.getSeverity()).thenReturn("HIGH");
        when(high.getTitle()).thenReturn("MFA not enabled");
        when(high.getResourceId()).thenReturn("iam-user");
        when(high.getRegion()).thenReturn("global");

        FinOpsReportDto report = buildReport(0, 0, 0);
        String html = builder.build(report, List.of(critical, high), List.of(), List.of(),
                "prod-main", "June 2026");

        assertThat(html).contains("1 Critical");
        assertThat(html).contains("1 High");
        assertThat(html).contains("0 Medium");
        assertThat(html).contains("0 Low");
        assertThat(html).contains("S3 bucket public");
    }

    @Test
    void wasteRowShowsResourcesWithSavings() {
        DashboardData.WastedResource waste = mock(DashboardData.WastedResource.class);
        when(waste.getResourceId()).thenReturn("i-0a1b2c3d");
        when(waste.getResourceType()).thenReturn("EC2");
        when(waste.getReason()).thenReturn("Idle CPU");
        when(waste.getMonthlySavings()).thenReturn(340.0);

        FinOpsReportDto report = mock(FinOpsReportDto.class);
        FinOpsReportDto.Kpis kpis = mock(FinOpsReportDto.Kpis.class);
        FinOpsReportDto.CostBreakdown breakdown = mock(FinOpsReportDto.CostBreakdown.class);
        when(report.getKpis()).thenReturn(kpis);
        when(kpis.getMonthToDateSpend()).thenReturn(0.0);
        when(kpis.getLastMonthSpend()).thenReturn(0.0);
        when(kpis.getPotentialSavings()).thenReturn(0.0);
        when(report.getCostBreakdown()).thenReturn(breakdown);
        when(breakdown.getByService()).thenReturn(List.of());
        when(report.getWastedResources()).thenReturn(List.of(waste));

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 2026");

        assertThat(html).contains("i-0a1b2c3d");
        assertThat(html).contains("Idle CPU");
        assertThat(html).contains("$340");
    }

    @Test
    void diskRowAbsentWhenNoDiskRecommendations() {
        FinOpsReportDto report = buildReport(0, 0, 0);

        String html = builder.build(report, List.of(), List.of(), List.of(),
                "prod-main", "June 2026");

        assertThat(html).doesNotContain("Disk");
    }

    @Test
    void diskRowPresentWhenFlaggedVolumesExist() {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-0abc123");
        vol.put("region", "us-east-1");
        vol.put("sizeGb", 30);
        vol.put("usedPercent", 95.0);
        vol.put("recommendation", "EXPAND");

        FinOpsReportDto report = buildReport(0, 0, 0);
        String html = builder.build(report, List.of(), List.of(), List.of(vol),
                "prod-main", "June 2026");

        assertThat(html).contains("vol-0abc123");
        assertThat(html).contains("95");
        assertThat(html).contains("Expand");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
mvn test -pl . -Dtest=MonthlyDigestEmailBuilderTest -q 2>&1 | tail -15
```

Expected: FAIL — `MonthlyDigestEmailBuilder` does not exist yet.

- [ ] **Step 3: Implement MonthlyDigestEmailBuilder**

Create `MonthlyDigestEmailBuilder.java`:

```java
package com.xammer.cloud.service;

import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MonthlyDigestEmailBuilder {

    public String build(
            FinOpsReportDto report,
            List<AwsSecurityFinding> securityFindings,
            List<XamOpsRightsizingRecommendation> ec2Recommendations,
            List<Map<String, Object>> diskRecommendations,
            String accountName,
            String dateRange) {

        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'></head>");
        sb.append("<body style='margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;background:#f1f5f9;line-height:1.5;'>");

        // Outer wrapper
        sb.append("<div style='max-width:700px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid rgba(0,0,0,0.08);box-shadow:0 2px 16px rgba(0,0,0,0.06);'>");

        // ── Black header ──
        appendHeader(sb, accountName, dateRange);

        // ── White stats strip ──
        appendStatsStrip(sb, report);

        // ── Section rows ──
        appendSecurityRow(sb, securityFindings);
        appendWasteRow(sb, report);
        appendResizeRow(sb, ec2Recommendations);
        if (!diskRecommendations.isEmpty()) {
            appendDiskRow(sb, diskRecommendations);
        }

        // ── Footer ──
        appendFooter(sb);

        sb.append("</div></body></html>");
        return sb.toString();
    }

    // ── Header (black background) ──────────────────────────────────────────

    private void appendHeader(StringBuilder sb, String accountName, String dateRange) {
        sb.append("<div style='background:#000000;padding:32px 36px 28px;border-bottom:1px solid rgba(0,0,0,0.06);'>");

        // Top bar: XAMOPS badge + date
        sb.append("<div style='display:flex;align-items:center;justify-content:space-between;margin-bottom:18px;'>");
        sb.append("<span style='background:#ffffff;color:#000000;font-size:11px;font-weight:900;letter-spacing:2px;padding:5px 14px;border-radius:6px;'>XAMOPS</span>");
        sb.append("</div>");

        // Title
        sb.append("<h1 style='color:#ffffff;font-size:26px;font-weight:900;letter-spacing:-0.5px;margin:0 0 4px;'>Monthly Cloud Digest</h1>");
        sb.append("<p style='color:rgba(255,255,255,0.40);font-size:13px;margin:0;'>")
          .append(escapeHtml(accountName))
          .append(" &nbsp;&middot;&nbsp; ")
          .append(escapeHtml(dateRange))
          .append("</p>");

        sb.append("</div>");
    }

    // ── Stats strip (white background) ─────────────────────────────────────

    private void appendStatsStrip(StringBuilder sb, FinOpsReportDto report) {
        double mtd      = report.getKpis().getMonthToDateSpend();
        double lastMonth = report.getKpis().getLastMonthSpend();
        double savings  = report.getKpis().getPotentialSavings();

        // Delta vs last month
        String deltaHtml = "";
        if (lastMonth > 0) {
            double pct = ((mtd - lastMonth) / lastMonth) * 100.0;
            boolean up = pct >= 0;
            String arrow = up ? "&#9650;" : "&#9660;";
            String color = up ? "#dc2626" : "#16A34A";
            deltaHtml = String.format("<span style='font-size:11px;font-weight:700;color:%s;'>%s %.1f%% vs last month ($%s)</span>",
                    color, arrow, Math.abs(pct), formatAmount(lastMonth));
        }

        // Top service
        String topServiceName = "—";
        String topServiceSub  = "";
        List<Map<String, Object>> byService = report.getCostBreakdown() != null
                ? report.getCostBreakdown().getByService() : List.of();
        if (byService != null && !byService.isEmpty()) {
            Map<String, Object> top = byService.get(0);
            topServiceName = String.valueOf(top.getOrDefault("name", "—"));
            Object amt = top.getOrDefault("amount", top.getOrDefault("cost", 0.0));
            double topAmt = amt instanceof Number ? ((Number) amt).doubleValue() : 0;
            double pct = mtd > 0 ? (topAmt / mtd) * 100.0 : 0;
            topServiceSub = String.format("$%s &middot; %.0f%% of total", formatAmount(topAmt), pct);
        }

        sb.append("<table role='presentation' style='width:100%;border-collapse:collapse;border-bottom:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<tr>");

        // Total Spend
        sb.append("<td style='padding:22px 0 22px 36px;width:33%;border-right:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<p style='margin:0 0 6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:rgba(0,0,0,0.35);'>Total Spend</p>");
        sb.append("<p style='margin:0 0 5px;font-size:32px;font-weight:900;color:rgba(0,0,0,0.88);letter-spacing:-1px;line-height:1;'>$").append(formatAmount(mtd)).append("</p>");
        sb.append(deltaHtml);
        sb.append("</td>");

        // Potential Savings
        sb.append("<td style='padding:22px 0 22px 24px;width:33%;border-right:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<p style='margin:0 0 6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:rgba(0,0,0,0.35);'>Potential Savings</p>");
        sb.append("<p style='margin:0 0 5px;font-size:32px;font-weight:900;color:#16A34A;letter-spacing:-1px;line-height:1;'>$").append(formatAmount(savings)).append("</p>");
        sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.35);'>across all recommendations</span>");
        sb.append("</td>");

        // Top Service
        sb.append("<td style='padding:22px 0 22px 24px;width:33%;'>");
        sb.append("<p style='margin:0 0 6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:rgba(0,0,0,0.35);'>Top Service</p>");
        sb.append("<p style='margin:0 0 5px;font-size:22px;font-weight:900;color:rgba(0,0,0,0.88);letter-spacing:-0.5px;line-height:1;padding-top:5px;'>")
          .append(escapeHtml(topServiceName)).append("</p>");
        if (!topServiceSub.isEmpty()) {
            sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.35);'>").append(topServiceSub).append("</span>");
        }
        sb.append("</td>");

        sb.append("</tr></table>");
    }

    // ── Security row ────────────────────────────────────────────────────────

    private void appendSecurityRow(StringBuilder sb, List<AwsSecurityFinding> findings) {
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.getSeverity())).count();
        long high     = findings.stream().filter(f -> "HIGH".equals(f.getSeverity())).count();
        long medium   = findings.stream().filter(f -> "MEDIUM".equals(f.getSeverity())).count();
        long low      = findings.stream().filter(f -> "LOW".equals(f.getSeverity())).count();

        String topFinding = findings.stream()
                .filter(f -> "CRITICAL".equals(f.getSeverity()) || "HIGH".equals(f.getSeverity()))
                .findFirst()
                .map(f -> escapeHtml(f.getTitle()) + " &middot; " + escapeHtml(f.getRegion()))
                .orElse(findings.isEmpty() ? "No active findings detected." : "");

        sb.append(rowOpen("Security"));

        // Severity pills
        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px;'>");
        appendSevPill(sb, critical + " Critical", "#fef2f2", "#fecaca", "#dc2626");
        appendSevPill(sb, high     + " High",     "#fff7ed", "#fed7aa", "#ea580c");
        appendSevPill(sb, medium   + " Medium",   "#fefce8", "#fef08a", "#ca8a04");
        appendSevPill(sb, low      + " Low",       "#f0fdf4", "#bbf7d0", "#16a34a");
        sb.append("</div>");

        if (!topFinding.isEmpty()) {
            sb.append("<p style='font-size:12px;color:rgba(0,0,0,0.40);margin:0;'>Top finding: <strong style='color:rgba(0,0,0,0.60);'>")
              .append(topFinding).append("</strong></p>");
        }

        sb.append(rowClose());
    }

    private void appendSevPill(StringBuilder sb, String label, String bg, String border, String color) {
        sb.append("<span style='display:inline-flex;align-items:center;gap:5px;border-radius:7px;padding:7px 12px;background:")
          .append(bg).append(";border:1px solid ").append(border).append(";font-size:12px;font-weight:700;color:").append(color).append(";'>")
          .append("<span style='width:6px;height:6px;border-radius:50%;background:currentColor;display:inline-block;'></span>")
          .append(label)
          .append("</span>");
    }

    // ── Waste row ───────────────────────────────────────────────────────────

    private void appendWasteRow(StringBuilder sb, FinOpsReportDto report) {
        List<DashboardData.WastedResource> items = report.getWastedResources();
        if (items == null) items = List.of();

        List<DashboardData.WastedResource> top5 = items.stream()
                .sorted((a, b) -> Double.compare(b.getMonthlySavings(), a.getMonthlySavings()))
                .limit(5)
                .collect(Collectors.toList());

        sb.append(rowOpen("Waste"));

        if (top5.isEmpty()) {
            sb.append("<p style='font-size:13px;color:rgba(0,0,0,0.40);margin:0;'>No waste identified this month.</p>");
        } else {
            for (DashboardData.WastedResource w : top5) {
                sb.append("<div style='display:flex;align-items:center;justify-content:space-between;padding:9px 0;border-bottom:1px solid rgba(0,0,0,0.04);'>");
                sb.append("<div>");
                sb.append("<span style='font-family:monospace;font-size:12px;color:rgba(0,0,0,0.75);font-weight:600;display:block;'>")
                  .append(escapeHtml(w.getResourceId())).append("</span>");
                sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.38);'>")
                  .append(escapeHtml(w.getResourceType()))
                  .append(" &middot; ").append(escapeHtml(w.getReason()))
                  .append("</span>");
                sb.append("</div>");
                sb.append("<span style='font-size:13px;font-weight:800;color:#16A34A;white-space:nowrap;'>$")
                  .append(formatAmount(w.getMonthlySavings())).append("/mo</span>");
                sb.append("</div>");
            }
        }

        sb.append(rowClose());
    }

    // ── Resize row ──────────────────────────────────────────────────────────

    private void appendResizeRow(StringBuilder sb, List<XamOpsRightsizingRecommendation> recs) {
        List<XamOpsRightsizingRecommendation> top5 = recs.stream()
                .limit(5)
                .collect(Collectors.toList());

        sb.append(rowOpen("Resize"));

        if (top5.isEmpty()) {
            sb.append("<p style='font-size:13px;color:rgba(0,0,0,0.40);margin:0;'>No rightsizing recommendations.</p>");
        } else {
            for (XamOpsRightsizingRecommendation r : top5) {
                String recommended = r.getIntelRecommendation() != null && !r.getIntelRecommendation().isEmpty()
                        ? r.getIntelRecommendation() : r.getAmdRecommendation();
                sb.append("<div style='display:flex;align-items:center;justify-content:space-between;padding:9px 0;border-bottom:1px solid rgba(0,0,0,0.04);'>");
                sb.append("<div>");
                sb.append("<span style='font-family:monospace;font-size:12px;color:rgba(0,0,0,0.75);font-weight:600;display:block;'>")
                  .append(escapeHtml(r.getInstanceId())).append("</span>");
                sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.38);'>")
                  .append(escapeHtml(r.getCurrentInstance()))
                  .append(" <span style='color:rgba(0,0,0,0.25);'>&#8594;</span> ")
                  .append(escapeHtml(recommended != null ? recommended : "—"))
                  .append(" &middot; ").append(escapeHtml(r.getRegion()))
                  .append("</span>");
                sb.append("</div>");
                if (r.getApproxCostSavings() != null && !r.getApproxCostSavings().isBlank()) {
                    sb.append("<span style='font-size:13px;font-weight:800;color:#16A34A;white-space:nowrap;'>")
                      .append(escapeHtml(r.getApproxCostSavings())).append("/mo</span>");
                }
                sb.append("</div>");
            }
        }

        sb.append(rowClose());
    }

    // ── Disk row ────────────────────────────────────────────────────────────

    private void appendDiskRow(StringBuilder sb, List<Map<String, Object>> vols) {
        sb.append(rowOpen("Disk"));

        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;'>");
        for (Map<String, Object> vol : vols) {
            String volumeId     = String.valueOf(vol.getOrDefault("volumeId", "—"));
            String region       = String.valueOf(vol.getOrDefault("region", "—"));
            int    sizeGb       = vol.containsKey("sizeGb") ? ((Number) vol.get("sizeGb")).intValue() : 0;
            double usedPct      = vol.containsKey("usedPercent") ? ((Number) vol.get("usedPercent")).doubleValue() : 0;
            String rec          = String.valueOf(vol.getOrDefault("recommendation", ""));
            boolean nearFull    = "EXPAND".equals(rec);
            String bg           = nearFull ? "#fef2f2" : "#fefce8";
            String border       = nearFull ? "#fecaca" : "#fef08a";
            String statusColor  = nearFull ? "#dc2626"  : "#ca8a04";
            String statusText   = nearFull ? String.format("%.0f%% full", usedPct) : String.format("%.0f%% used", usedPct);
            String action       = nearFull ? "Expand" : "Shrink";

            sb.append("<div style='flex:1;min-width:160px;border-radius:9px;padding:11px 14px;border:1px solid ")
              .append(border).append(";background:").append(bg).append(";'>");
            sb.append("<div style='font-family:monospace;font-size:11px;font-weight:700;color:rgba(0,0,0,0.70);margin-bottom:3px;'>")
              .append(escapeHtml(volumeId)).append("</div>");
            sb.append("<div style='font-size:11px;color:rgba(0,0,0,0.40);'>")
              .append(escapeHtml(region)).append(" &middot; ").append(sizeGb).append(" GB &middot; ")
              .append("<strong style='color:").append(statusColor).append(";'>").append(statusText).append("</strong>")
              .append(" &middot; ").append(action)
              .append("</div>");
            sb.append("</div>");
        }
        sb.append("</div>");

        sb.append(rowClose());
    }

    // ── Footer ──────────────────────────────────────────────────────────────

    private void appendFooter(StringBuilder sb) {
        sb.append("<div style='background:rgba(0,0,0,0.02);padding:22px 36px;border-top:1px solid rgba(0,0,0,0.06);text-align:center;'>");
        sb.append("<div style='display:inline-block;background:#000000;color:#ffffff;font-size:11px;font-weight:900;letter-spacing:2px;padding:5px 16px;border-radius:6px;margin-bottom:10px;'>XAMOPS</div><br>");
        sb.append("<p style='color:rgba(0,0,0,0.35);font-size:12px;margin:0 0 3px;'>Cloud Cost Management &amp; FinOps Platform</p>");
        sb.append("<p style='color:rgba(0,0,0,0.20);font-size:11px;margin:8px 0 0;padding-top:8px;border-top:1px solid rgba(0,0,0,0.05);'>");
        sb.append("&copy; 2026 XamOps &nbsp;&middot;&nbsp; Sent automatically on the 30th of each month &nbsp;&middot;&nbsp; This is an automated report. Please do not reply to this email.");
        sb.append("</p></div>");
    }

    // ── Row helpers ─────────────────────────────────────────────────────────

    private String rowOpen(String label) {
        return "<table role='presentation' style='width:100%;border-collapse:collapse;border-bottom:1px solid rgba(0,0,0,0.05);'>" +
               "<tr>" +
               "<td style='width:110px;padding:22px 0 22px 28px;border-right:1px solid rgba(0,0,0,0.05);vertical-align:top;'>" +
               "<span style='display:inline-flex;align-items:center;font-size:10px;font-weight:800;text-transform:uppercase;" +
               "letter-spacing:0.07em;color:rgba(0,0,0,0.35);background:rgba(0,0,0,0.03);border:1px solid rgba(0,0,0,0.07);" +
               "border-radius:5px;padding:4px 9px;'>" + label + "</span>" +
               "</td>" +
               "<td style='padding:20px 28px 20px 20px;vertical-align:top;'>";
    }

    private String rowClose() {
        return "</td></tr></table>";
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private String formatAmount(double amount) {
        if (amount >= 1000) {
            return String.format("%,.0f", amount);
        }
        return String.format("%.0f", amount);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
mvn test -pl . -Dtest=MonthlyDigestEmailBuilderTest -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS` with 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestEmailBuilder.java \
        xamops-service/src/test/java/com/xammer/cloud/service/MonthlyDigestEmailBuilderTest.java
git commit -m "feat(digest): add MonthlyDigestEmailBuilder with HTML email generation"
```

---

## Task 2: MonthlyDigestService

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestService.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/MonthlyDigestServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `MonthlyDigestServiceTest.java`:

```java
package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonthlyDigestServiceTest {

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private FinOpsService finOpsService;
    @Mock private AwsSecurityHubService awsSecurityHubService;
    @Mock private XamOpsRightsizingService xamOpsRightsizingService;
    @Mock private EbsDiskRightsizingService ebsDiskRightsizingService;
    @Mock private MonthlyDigestEmailBuilder emailBuilder;
    @Mock private EmailService emailService;

    private MonthlyDigestService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MonthlyDigestService(
                cloudAccountRepository, userRepository, finOpsService,
                awsSecurityHubService, xamOpsRightsizingService,
                ebsDiskRightsizingService, emailBuilder, emailService);
    }

    private CloudAccount awsAccount(String id, Long clientId) {
        CloudAccount a = mock(CloudAccount.class);
        Client client = mock(Client.class);
        when(a.getProvider()).thenReturn("AWS");
        when(a.getProviderAccountId()).thenReturn(id);
        when(a.getAccountName()).thenReturn("test-account");
        when(a.getClient()).thenReturn(client);
        when(client.getId()).thenReturn(clientId);
        return a;
    }

    private void stubAllDataSources(String accountId) throws Exception {
        FinOpsReportDto report = mock(FinOpsReportDto.class);
        FinOpsReportDto.Kpis kpis = mock(FinOpsReportDto.Kpis.class);
        FinOpsReportDto.CostBreakdown breakdown = mock(FinOpsReportDto.CostBreakdown.class);
        when(report.getKpis()).thenReturn(kpis);
        when(kpis.getMonthToDateSpend()).thenReturn(100.0);
        when(kpis.getLastMonthSpend()).thenReturn(90.0);
        when(kpis.getPotentialSavings()).thenReturn(10.0);
        when(report.getCostBreakdown()).thenReturn(breakdown);
        when(breakdown.getByService()).thenReturn(List.of());
        when(report.getWastedResources()).thenReturn(List.of());
        when(finOpsService.getFinOpsReport(eq(accountId), eq(false), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(report));
        when(awsSecurityHubService.getSecurityFindings(eq(accountId), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(xamOpsRightsizingService.getLiveRecommendations(eq(accountId), eq(false)))
                .thenReturn(List.of());
        when(ebsDiskRightsizingService.getRecommendations(eq(accountId), eq(false)))
                .thenReturn(List.of());
        when(emailBuilder.build(any(), any(), any(), any(), any(), any()))
                .thenReturn("<html>digest</html>");
    }

    @Test
    void sendsEmailToAllUsersInClientAccount() throws Exception {
        CloudAccount account = awsAccount("123456789012", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));

        stubAllDataSources("123456789012");

        User u1 = mock(User.class); when(u1.getEmail()).thenReturn("alice@example.com");
        User u2 = mock(User.class); when(u2.getEmail()).thenReturn("bob@example.com");
        when(userRepository.findByClientId(1L)).thenReturn(List.of(u1, u2));

        service.runDigest();

        verify(emailService).sendHtmlEmail(eq("alice@example.com"), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("bob@example.com"), anyString(), anyString());
    }

    @Test
    void skipsAccountWhenDataSourceThrows() throws Exception {
        CloudAccount account = awsAccount("bad-account", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        when(finOpsService.getFinOpsReport(eq("bad-account"), eq(false), anyString(), anyString()))
                .thenThrow(new RuntimeException("AWS error"));

        service.runDigest();

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void callsAllFourDataSourcesForAwsAccount() throws Exception {
        CloudAccount account = awsAccount("123456789012", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        stubAllDataSources("123456789012");
        when(userRepository.findByClientId(1L)).thenReturn(List.of());

        service.runDigest();

        verify(finOpsService).getFinOpsReport(eq("123456789012"), eq(false), anyString(), anyString());
        verify(awsSecurityHubService).getSecurityFindings(eq("123456789012"), eq(false));
        verify(xamOpsRightsizingService).getLiveRecommendations(eq("123456789012"), eq(false));
        verify(ebsDiskRightsizingService).getRecommendations(eq("123456789012"), eq(false));
    }

    @Test
    void skipsUsersWithoutEmail() throws Exception {
        CloudAccount account = awsAccount("123456789012", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        stubAllDataSources("123456789012");

        User noEmail = mock(User.class);
        when(noEmail.getEmail()).thenReturn(null);
        when(noEmail.getUsername()).thenReturn("not-an-email");
        User withEmail = mock(User.class);
        when(withEmail.getEmail()).thenReturn("valid@example.com");
        when(userRepository.findByClientId(1L)).thenReturn(List.of(noEmail, withEmail));

        service.runDigest();

        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("valid@example.com"), anyString(), anyString());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
mvn test -pl . -Dtest=MonthlyDigestServiceTest -q 2>&1 | tail -15
```

Expected: FAIL — `MonthlyDigestService` does not exist yet.

- [ ] **Step 3: Implement MonthlyDigestService**

Create `MonthlyDigestService.java`:

```java
package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MonthlyDigestService {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyDigestService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final FinOpsService finOpsService;
    private final AwsSecurityHubService awsSecurityHubService;
    private final XamOpsRightsizingService xamOpsRightsizingService;
    private final EbsDiskRightsizingService ebsDiskRightsizingService;
    private final MonthlyDigestEmailBuilder emailBuilder;
    private final EmailService emailService;

    public MonthlyDigestService(CloudAccountRepository cloudAccountRepository,
                                UserRepository userRepository,
                                FinOpsService finOpsService,
                                AwsSecurityHubService awsSecurityHubService,
                                XamOpsRightsizingService xamOpsRightsizingService,
                                EbsDiskRightsizingService ebsDiskRightsizingService,
                                MonthlyDigestEmailBuilder emailBuilder,
                                EmailService emailService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.finOpsService = finOpsService;
        this.awsSecurityHubService = awsSecurityHubService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;
        this.ebsDiskRightsizingService = ebsDiskRightsizingService;
        this.emailBuilder = emailBuilder;
        this.emailService = emailService;
    }

    public void runDigest() {
        LocalDate today     = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);
        String start        = startDate.format(ISO);
        String end          = today.format(ISO);
        String dateRange    = startDate.format(DateTimeFormatter.ofPattern("MMMM d"))
                              + " – "
                              + today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));

        logger.info("[MonthlyDigest] Starting monthly cloud digest for period {} to {}", start, end);

        List<CloudAccount> awsAccounts = cloudAccountRepository.findByProvider("AWS");
        logger.info("[MonthlyDigest] Found {} AWS accounts to process", awsAccounts.size());

        for (CloudAccount account : awsAccounts) {
            String accountId   = account.getProviderAccountId();
            String accountName = account.getAccountName();
            try {
                processAccount(account, accountId, accountName, start, end, dateRange);
            } catch (Exception e) {
                logger.warn("[MonthlyDigest] Failed to process account {}: {}", accountId, e.getMessage());
            }
        }

        logger.info("[MonthlyDigest] Monthly digest complete.");
    }

    private void processAccount(CloudAccount account, String accountId, String accountName,
                                String start, String end, String dateRange) throws Exception {
        logger.info("[MonthlyDigest] Processing account {}", accountId);

        // Fetch all data
        FinOpsReportDto report = finOpsService.getFinOpsReport(accountId, false, start, end).join();
        List<AwsSecurityFinding> security = awsSecurityHubService.getSecurityFindings(accountId, false).join();
        List<XamOpsRightsizingRecommendation> ec2Recs = xamOpsRightsizingService.getLiveRecommendations(accountId, false);
        List<Map<String, Object>> diskRecs = ebsDiskRightsizingService.getRecommendations(accountId, false);

        // Build email
        String subject = String.format("Your Monthly Cloud Digest — %s — %s",
                accountName, today(dateRange));
        String htmlBody = emailBuilder.build(report, security, ec2Recs, diskRecs, accountName, dateRange);

        // Resolve and send to all client users
        List<String> recipients = resolveRecipients(account);
        logger.info("[MonthlyDigest] Sending digest for {} to {} recipients", accountName, recipients.size());

        for (String email : recipients) {
            try {
                emailService.sendHtmlEmail(email, subject, htmlBody);
                logger.info("[MonthlyDigest] ✓ Sent to {} for account {}", email, accountName);
            } catch (Exception e) {
                logger.error("[MonthlyDigest] ✗ Failed to send to {}: {}", email, e.getMessage());
            }
        }
    }

    private List<String> resolveRecipients(CloudAccount account) {
        if (account.getClient() == null) return List.of();
        return userRepository.findByClientId(account.getClient().getId()).stream()
                .map(u -> {
                    if (u.getEmail() != null && !u.getEmail().isBlank()) return u.getEmail();
                    if (u.getUsername() != null && u.getUsername().contains("@")) return u.getUsername();
                    return null;
                })
                .filter(e -> e != null)
                .distinct()
                .collect(Collectors.toList());
    }

    private String today(String dateRange) {
        // Extract "June 2026" from "June 1 – June 30, 2026"
        String[] parts = dateRange.split("–");
        if (parts.length == 2) {
            String end = parts[1].trim(); // "June 30, 2026"
            // Extract month + year: "June 2026"
            String[] dateParts = end.split(" ");
            if (dateParts.length >= 3) {
                return dateParts[0] + " " + dateParts[2].replace(",", "");
            }
        }
        return dateRange;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
mvn test -pl . -Dtest=MonthlyDigestServiceTest -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS` with 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/MonthlyDigestServiceTest.java
git commit -m "feat(digest): add MonthlyDigestService orchestration"
```

---

## Task 3: MonthlyDigestScheduler

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestScheduler.java`

- [ ] **Step 1: Create the scheduler**

```java
package com.xammer.cloud.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MonthlyDigestScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyDigestScheduler.class);

    private final MonthlyDigestService monthlyDigestService;

    @Scheduled(cron = "0 30 12 30 * ?") // 6:00 PM IST on the 30th of each month
    public void runMonthlyDigest() {
        logger.info("[MonthlyDigestScheduler] Triggering monthly cloud digest");
        monthlyDigestService.runDigest();
        logger.info("[MonthlyDigestScheduler] Monthly cloud digest complete");
    }
}
```

- [ ] **Step 2: Compile to verify no errors**

```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
mvn compile -q 2>&1 | tail -10
```

Expected: clean compile, no output.

- [ ] **Step 3: Run full test suite to check for regressions**

```bash
cd /Users/apple/Desktop/XamOps-New/xamops-service
mvn test -pl . -Dtest="MonthlyDigestEmailBuilderTest,MonthlyDigestServiceTest" -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`, all 10 tests pass.

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/MonthlyDigestScheduler.java
git commit -m "feat(digest): add MonthlyDigestScheduler — fires 6PM IST on 30th of each month"
```

---

## Self-Review Notes

**Spec coverage:**
- ✅ AWS-only (uses `findByProvider("AWS")`)
- ✅ Sends to all client users (uses `userRepository.findByClientId`)
- ✅ Cron on 30th at 12:30 UTC (6PM IST)
- ✅ Five email sections: cost hero, security, waste, resize, disk
- ✅ Disk section absent when no agent data
- ✅ Account-level error handling
- ✅ Three new files, no DB changes

**Known implementation notes:**
- The `FinOpsReportDto.CostBreakdown.byService` map keys (`"name"` and `"amount"`) should be verified by reading `FinOpsService.java` before implementing — `MonthlyDigestEmailBuilder` uses `getOrDefault("amount", getOrDefault("cost", 0.0))` to handle both.
- `XamOpsRightsizingRecommendation.getApproxCostSavings()` is a String — the builder outputs it as-is (e.g., `"$420"`) rather than re-formatting.
- `FinOpsReportDto.Kpis` getter names (`getMonthToDateSpend`, `getLastMonthSpend`, `getPotentialSavings`) assumed from Lombok `@Data` — verify against actual class before building.
