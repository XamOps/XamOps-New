package com.xammer.cloud.service.war;

import com.xammer.cloud.dto.war.WarAttackPath;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.dto.war.WarControl;
import com.xammer.cloud.dto.war.WarFinding;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.dto.war.WarStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a completed scan into a {@link WarReport}.
 *
 * <p>
 * Shared by every provider deliberately. The scoring rules here have needed two rounds
 * of correction already — excluding UNKNOWN from the denominator, and gating the verdict
 * on coverage — and a per-provider copy would mean discovering the same bugs twice. The
 * only provider-specific inputs are the control catalog and the category order.
 */
@Component
public class WarReportAssembler {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Score weight per advisory severity. Failing one Critical control matters more
     * than failing four Low ones, so a flat pass-rate percentage would mislead.
     */
    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of(
            "critical", 10,
            "high", 6,
            "medium", 3,
            "low", 1);

    /** Below this share of checkable controls verified, the score is not a rating. */
    private static final double MIN_COVERAGE = 0.5;

    /** How many entries the "fix these first" list carries. */
    private static final int TOP_RISK_LIMIT = 10;

    public WarReport assemble(String provider,
            String accountId,
            String accountName,
            List<String> scannedRegions,
            List<WarControl> catalog,
            List<String> categoryOrder,
            WarScanContext ctx) {

        Map<String, String> blocked = ctx.blockedReasons();
        List<WarFinding> findings = new ArrayList<>(catalog.size());
        for (WarControl control : catalog) {
            findings.add(toFinding(control, ctx.results().get(control.id()),
                    blocked.get(control.id())));
        }

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (WarStatus status : WarStatus.values()) {
            statusCounts.put(status.name(), 0);
        }
        Map<String, Integer> severityCounts = new LinkedHashMap<>(Map.of(
                "Critical", 0, "High", 0, "Medium", 0, "Low", 0));

        for (WarFinding f : findings) {
            statusCounts.merge(f.getStatus().name(), 1, (a, b) -> a + b);
            if (isOpen(f.getStatus())) {
                severityCounts.merge(normaliseSeverity(f.getSeverity()), 1, (a, b) -> a + b);
            }
        }

        int verified = statusCounts.getOrDefault("PASS", 0)
                + statusCounts.getOrDefault("FAIL", 0)
                + statusCounts.getOrDefault("WARN", 0);
        int scoreable = verified + statusCounts.getOrDefault("UNKNOWN", 0);

        // A score drawn from a small slice of the baseline is not a security rating.
        // Two verified controls out of sixty-four is a scan failure wearing a number.
        boolean assessable = verified > 0 && verified >= scoreable * MIN_COVERAGE;
        int score = score(findings);

        // Confirmed exposures only. An unverified control has no fix beyond restoring
        // the scan's access, so listing one under "fix these first" sends the reader to
        // rewrite a policy when the actual problem is upstream.
        List<WarFinding> topRisks = findings.stream()
                .filter(f -> f.getStatus() == WarStatus.FAIL || f.getStatus() == WarStatus.WARN)
                .sorted(Comparator.<WarFinding>comparingInt(f -> f.getRiskWeight()).reversed()
                        .thenComparingInt(f -> f.getOrdinal()))
                .limit(TOP_RISK_LIMIT)
                .toList();

        return WarReport.builder()
                .accountId(accountId)
                .accountName(accountName)
                .provider(provider)
                .generatedAt(LocalDateTime.now(REPORT_ZONE).atZone(REPORT_ZONE).format(TIMESTAMP))
                .scannedRegions(scannedRegions)
                .warScore(assessable ? score : 0)
                .riskLevel(assessable
                        ? riskLevel(score, severityCounts.getOrDefault("Critical", 0))
                        : "UNKNOWN")
                .totalControls(catalog.size())
                .automatedControls((int) catalog.stream().filter(c -> c.automated()).count())
                .verifiedControls(verified)
                .assessable(assessable)
                .blockedReason(ctx.dominantFailure().orElse(null))
                .statusCounts(statusCounts)
                .severityCounts(severityCounts)
                .attackPaths(attackPathSummaries(findings))
                .categories(categorySummaries(findings, categoryOrder))
                .findings(findings)
                .topRisks(topRisks)
                .deniedActions(ctx.deniedActions())
                .blockedActions(ctx.blockedActions())
                .scanWarnings(ctx.warnings())
                .build();
    }

    /**
     * @param blockedReason why every region failed for this control, when none produced
     *                     a verdict. Preferred over a generic "not evaluated" message.
     */
    private WarFinding toFinding(WarControl control, WarCheckResult result, String blockedReason) {
        WarStatus status;
        String summary;
        List<String> evidence;
        int affected;
        int scanned;
        String region;
        String denied;

        if (result != null) {
            status = result.getStatus();
            summary = result.getSummary();
            evidence = result.getEvidence();
            affected = result.getAffectedCount();
            scanned = result.getScannedCount();
            region = result.getRegion();
            denied = result.getDeniedAction();
        } else if (control.automated()) {
            // An automated control with no result means no region produced a verdict.
            // Where the scan knows why, say why — a bare "not evaluated" makes the
            // customer go hunting for a cause we already have.
            status = WarStatus.UNKNOWN;
            summary = blockedReason != null
                    ? "Could not verify — " + blockedReason
                    : "This control was not evaluated in the last scan";
            evidence = List.of();
            affected = 0;
            scanned = 0;
            region = null;
            denied = null;
        } else {
            status = WarStatus.MANUAL;
            summary = "Needs human review — no API can prove this from a member account";
            evidence = List.of();
            affected = 0;
            scanned = 0;
            region = null;
            denied = null;
        }

        return WarFinding.builder()
                .controlId(control.id())
                .ordinal(control.ordinal())
                .category(control.category())
                .subCategory(control.subCategory())
                .title(control.title())
                .description(control.description())
                .severity(control.severity())
                .tools(control.tools())
                .reference(control.reference())
                .controlType(control.controlType())
                .businessImpact(control.businessImpact())
                .impactLevel(control.impactLevel())
                .attackPaths(control.attackPaths().stream().map(p -> p.name()).toList())
                .attackPathLabels(control.attackPaths().stream().map(p -> p.getLabel()).toList())
                .status(status)
                .automated(control.automated())
                .summary(summary)
                .evidence(evidence)
                .affectedCount(affected)
                .scannedCount(scanned)
                .region(region)
                .deniedAction(denied)
                .riskWeight(riskWeight(control, status, affected))
                .build();
    }

    /**
     * A finding's weight is its severity weight, doubled for a hard FAIL and nudged up
     * by how many resources are actually affected. The resource bump is capped so a
     * hundred unversioned buckets cannot outrank an open root account.
     */
    private int riskWeight(WarControl control, WarStatus status, int affectedCount) {
        int base = SEVERITY_WEIGHT.getOrDefault(
                normaliseSeverity(control.severity()).toLowerCase(), 1);
        int multiplier = switch (status) {
            case FAIL -> 2;
            case WARN, UNKNOWN -> 1;
            default -> 0;
        };
        return base * multiplier + Math.min(affectedCount, 5);
    }

    /**
     * Score is the weighted pass rate across controls that produced a verdict.
     *
     * <p>
     * Only PASS, FAIL and WARN are scored. MANUAL and NOT_APPLICABLE are excluded
     * because scoring them would either punish an account for a control that does not
     * apply to it or reward it for one nobody checked.
     *
     * <p>
     * UNKNOWN is excluded too, which is a deliberate reversal. Counting unverifiable
     * controls as failures made a blocked scan indistinguishable from a genuinely
     * insecure account: controls blocked by an expired token produced "score 0, critical
     * risk", which reads as an indictment of the customer's security rather than a
     * report of our own blindness. Coverage is reported separately instead.
     */
    private int score(List<WarFinding> findings) {
        int earned = 0;
        int possible = 0;

        for (WarFinding f : findings) {
            if (!isScored(f.getStatus())) {
                continue;
            }
            int weight = SEVERITY_WEIGHT.getOrDefault(
                    normaliseSeverity(f.getSeverity()).toLowerCase(), 1);
            possible += weight;
            switch (f.getStatus()) {
                case PASS -> earned += weight;
                // A partially-mitigated control earns half credit.
                case WARN -> earned += weight / 2;
                default -> {
                }
            }
        }

        return possible == 0 ? 0 : (int) Math.round(100.0 * earned / possible);
    }

    /** PASS, FAIL and WARN are verdicts. Everything else is an absence of one. */
    private static boolean isScored(WarStatus status) {
        return status == WarStatus.PASS || status == WarStatus.FAIL || status == WarStatus.WARN;
    }

    private String riskLevel(int score, int openCriticals) {
        // Open criticals dominate. An account can score 85 and still have its root user
        // unprotected, and calling that "low risk" would be wrong.
        if (openCriticals >= 3 || score < 50) {
            return "CRITICAL";
        }
        if (openCriticals >= 1 || score < 70) {
            return "HIGH";
        }
        return score < 85 ? "MEDIUM" : "LOW";
    }

    private List<WarReport.AttackPathSummary> attackPathSummaries(List<WarFinding> findings) {
        Map<WarAttackPath, List<WarFinding>> byPath = new EnumMap<>(WarAttackPath.class);
        for (WarAttackPath path : WarAttackPath.values()) {
            byPath.put(path, new ArrayList<>());
        }
        for (WarFinding f : findings) {
            for (String name : f.getAttackPaths()) {
                byPath.get(WarAttackPath.valueOf(name)).add(f);
            }
        }

        List<WarReport.AttackPathSummary> summaries = new ArrayList<>();
        for (var entry : byPath.entrySet()) {
            List<WarFinding> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            int failed = count(group, WarStatus.FAIL);
            int warned = count(group, WarStatus.WARN);
            int unknown = count(group, WarStatus.UNKNOWN);

            String worst = group.stream()
                    .filter(f -> isOpen(f.getStatus()))
                    .map(f -> normaliseSeverity(f.getSeverity()))
                    .min(Comparator.comparingInt(s -> switch (s) {
                        case "Critical" -> 0;
                        case "High" -> 1;
                        case "Medium" -> 2;
                        default -> 3;
                    }))
                    .orElse("None");

            String state;
            if (failed > 0) {
                state = "OPEN";
            } else if (warned > 0) {
                state = "PARTIAL";
            } else if (unknown > 0) {
                state = "UNVERIFIED";
            } else if (count(group, WarStatus.PASS) == 0) {
                // Every control on this path is manual-review or not applicable, so
                // nothing was actually verified. Calling that "contained" would render a
                // green badge over an unexamined attack path — the exact false assurance
                // the status vocabulary exists to prevent.
                state = "NEEDS_REVIEW";
            } else {
                state = "CONTAINED";
            }

            summaries.add(WarReport.AttackPathSummary.builder()
                    .path(entry.getKey().name())
                    .label(entry.getKey().getLabel())
                    .description(entry.getKey().getDescription())
                    .failed(failed)
                    .warned(warned)
                    .unverified(unknown)
                    .total(group.size())
                    .affectedResources(group.stream().mapToInt(f -> f.getAffectedCount()).sum())
                    .state(state)
                    .worstSeverity(worst)
                    .topControlIds(group.stream()
                            .filter(f -> isOpen(f.getStatus()))
                            .sorted(Comparator.<WarFinding>comparingInt(f -> f.getRiskWeight())
                                    .reversed())
                            .limit(5)
                            .map(f -> f.getControlId())
                            .toList())
                    .build());
        }

        // Most exposed path first — this ordering is what the page leads with. Confirmed
        // failures outrank weakened ones, which outrank merely unverified ones.
        summaries.sort(Comparator.<WarReport.AttackPathSummary>comparingInt(s -> s.getFailed())
                .thenComparingInt(s -> s.getWarned())
                .thenComparingInt(s -> s.getUnverified())
                .reversed());
        return summaries;
    }

    private List<WarReport.CategorySummary> categorySummaries(List<WarFinding> findings,
            List<String> categoryOrder) {
        Map<String, List<WarFinding>> byCategory = new LinkedHashMap<>();
        for (String category : categoryOrder) {
            byCategory.put(category, new ArrayList<>());
        }
        findings.forEach(f -> byCategory
                .computeIfAbsent(f.getCategory(), k -> new ArrayList<>()).add(f));

        List<WarReport.CategorySummary> summaries = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            List<WarFinding> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            summaries.add(WarReport.CategorySummary.builder()
                    .category(entry.getKey())
                    .total(group.size())
                    .passed(count(group, WarStatus.PASS))
                    .failed(count(group, WarStatus.FAIL))
                    .warned(count(group, WarStatus.WARN))
                    .manual(count(group, WarStatus.MANUAL))
                    .unknown(count(group, WarStatus.UNKNOWN))
                    .notApplicable(count(group, WarStatus.NOT_APPLICABLE))
                    .score(score(group))
                    .build());
        }
        return summaries;
    }

    private static int count(List<WarFinding> findings, WarStatus status) {
        return (int) findings.stream().filter(f -> f.getStatus() == status).count();
    }

    /** Open means "an attacker could use this today", which includes unverifiable. */
    private static boolean isOpen(WarStatus status) {
        return status == WarStatus.FAIL || status == WarStatus.WARN || status == WarStatus.UNKNOWN;
    }

    private static String normaliseSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "Low";
        }
        String trimmed = severity.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase();
    }
}
