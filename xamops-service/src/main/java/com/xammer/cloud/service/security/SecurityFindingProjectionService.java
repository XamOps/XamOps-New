package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read side for live security findings.
 *
 * <p>
 * <b>Why there is no cache in front of this.</b> The obvious design was to evict
 * {@code securityFindings-{accountId}} whenever a finding arrived and let the page rebuild.
 * That would have made things worse: {@code SecurityService.getComprehensiveSecurityFindings}
 * performs live CloudTrail, VPC Flow Log, IAM, KMS, RDS and S3 describes across every active
 * region, so evicting it means every new finding schedules a multi-region scan. On an account
 * generating findings steadily that is a permanent background load, triggered by exactly the
 * events that should be cheap to handle.
 *
 * <p>
 * So the pages read this instead, as a tier ahead of their caches — the pattern
 * {@code DashboardDataService.getGranularInventory} already uses, and for the same second
 * reason: the snapshot tier is a {@code cached_data} row with no expiry, so any eviction
 * someone forgets is permanent staleness rather than a slow refresh. One indexed query has
 * nothing worth caching in front of it and nothing to invalidate.
 */
@Service
public class SecurityFindingProjectionService {

    private static final Logger log = LoggerFactory.getLogger(SecurityFindingProjectionService.class);

    private final CloudSecurityFindingRepository repository;

    /**
     * Off by default, matching {@code xamops.inventory.enabled}. With this false every page
     * behaves exactly as it did before, so enabling it is the only behavioural switch.
     */
    @Value("${xamops.security.live-findings.enabled:false}")
    private boolean enabled;

    public SecurityFindingProjectionService(CloudSecurityFindingRepository repository) {
        this.repository = repository;
    }

    /*
     * There is deliberately no resolution filter in this class. When a user marks a finding
     * fixed, SecurityFindingResolutionService moves every matching row off ACTIVE in the same
     * transaction, so findActive, findActiveBySeverityIn and countActiveBySeverity all stop
     * returning it without knowing the feature exists — and so does the notifier, which selects
     * on status. Only findings with no row here — Prowler results and SecurityService's
     * describe-based checks — need filtering by key, and that happens once in SecurityController
     * after the merge, where all three sources are in one list.
     */

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether live findings can be served for this connection.
     *
     * <p>
     * Unlike the inventory plane there is no staleness check, because the semantics differ.
     * An inventory that has not synced recently is <em>wrong</em> — resources may have been
     * deleted. A findings list that has received nothing recently is simply an account where
     * nothing has happened, which is the good case. Requiring recent activity would hide
     * findings precisely when the account is quiet.
     */
    public boolean canServe(Long cloudAccountId) {
        return enabled && cloudAccountId != null;
    }

    // ── Projections ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CloudSecurityFinding> active(Long cloudAccountId) {
        if (!canServe(cloudAccountId)) {
            return List.of();
        }
        return repository.findActive(cloudAccountId);
    }

    @Transactional(readOnly = true)
    public List<CloudSecurityFinding> recent(Long cloudAccountId, int withinMinutes) {
        if (!canServe(cloudAccountId)) {
            return List.of();
        }
        return repository.findRecent(cloudAccountId,
                Instant.now().minus(withinMinutes, ChronoUnit.MINUTES));
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countsBySeverity(Long cloudAccountId) {
        if (!canServe(cloudAccountId)) {
            return Map.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : repository.countActiveBySeverity(cloudAccountId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public long activeCount(Long cloudAccountId) {
        if (!canServe(cloudAccountId)) {
            return 0L;
        }
        return repository.countByCloudAccountIdAndStatus(
                cloudAccountId, CloudSecurityFinding.STATUS_ACTIVE);
    }

    /**
     * Findings first seen since a moment — what the WAR page needs for its staleness banner.
     *
     * <p>
     * WAR deliberately does <em>not</em> invalidate on a finding: its report has a 720-minute TTL
     * and a rescan is expensive, so recomputing per event would trade a stale number for a slow
     * one. Instead the page keeps showing the score it computed and says how many findings have
     * landed since. That is honest in a way a silently-recalculated score would not be — the user
     * is told the number is old rather than shown a new one that is only partly refreshed.
     */
    @Transactional(readOnly = true)
    public long countSince(Long cloudAccountId, Instant since) {
        if (!canServe(cloudAccountId) || since == null) {
            return 0L;
        }
        return repository.findRecent(cloudAccountId, since).size();
    }

    /**
     * Control ids currently failing, for ComplianceOps.
     *
     * <p>
     * Config findings carry the rule name as their control id, and Security Hub findings carry the
     * security control id, so the same key joins to a compliance control from either source. This
     * is what lets a control flip to failing minutes after reality changes instead of waiting for
     * the Saturday Prowler scan — and back to passing when Config reports COMPLIANT, which is the
     * one source that closes its own findings.
     */
    @Transactional(readOnly = true)
    public Map<String, String> failingControlIds(Long cloudAccountId) {
        if (!canServe(cloudAccountId)) {
            return Map.of();
        }
        Map<String, String> worstByControl = new HashMap<>();
        for (CloudSecurityFinding f : repository.findActive(cloudAccountId)) {
            String control = f.getControlId();
            if (control == null || control.isBlank()) {
                continue;
            }
            // Keeps the most severe finding per control: a control failing for two reasons should
            // show the worse of them, not whichever arrived last.
            String existing = worstByControl.get(control);
            if (existing == null
                    || SecuritySeverity.rank(f.getSeverity()) < SecuritySeverity.rank(existing)) {
                worstByControl.put(control, SecuritySeverity.normalize(f.getSeverity()));
            }
        }
        return worstByControl;
    }

    // ── Mapping to the shapes existing pages already render ─────────────────

    /**
     * Live findings as {@link DashboardData.SecurityFinding}, for the Security Center page.
     *
     * <p>
     * Severity is converted to title case because that page's existing rows come from
     * {@code SecurityService} and {@code ProwlerService}, which emit "Critical"/"High", and the
     * frontend maps severity to a badge colour by exact string match. Emitting the stored
     * upper-case form here would render live findings with no badge — visible, but visibly
     * different from the rows beside them for no reason a user could understand.
     */
    @Transactional(readOnly = true)
    public List<DashboardData.SecurityFinding> asSecurityFindings(Long cloudAccountId) {
        return active(cloudAccountId).stream()
                .map(f -> new DashboardData.SecurityFinding(
                        f.getResourceArn() != null ? f.getResourceArn() : "—",
                        f.getRegion(),
                        categoryFor(f),
                        SecuritySeverity.toTitleCase(f.getSeverity()),
                        descriptionFor(f),
                        f.getComplianceFramework() != null ? f.getComplianceFramework() : sourceLabel(f),
                        f.getControlId()))
                .collect(Collectors.toList());
    }

    /**
     * Live findings as {@link AlertDto}, for the Alerts page.
     *
     * <p>
     * Only CRITICAL and HIGH are surfaced here. The Alerts page is a "what needs attention
     * now" list that already carries quota and cost-anomaly rows; adding every LOW config
     * drift would bury the rows a user opened the page for. The full list stays on the
     * Security Center page.
     */
    @Transactional(readOnly = true)
    public List<AlertDto> asAlerts(Long cloudAccountId) {
        return asAlerts(cloudAccountId, java.util.Set.of());
    }

    /**
     * As {@link #asAlerts(Long)}, leaving out findings from the given sources.
     *
     * <p>For a caller that has just read one of those sources directly. The AWS Alerts page reads
     * Security Hub itself; the same findings also arrive here through EventBridge, so without
     * this each Security Hub finding was listed twice.
     *
     * @param excludedSources {@link CloudSecurityFinding} {@code SOURCE_*} values
     */
    public List<AlertDto> asAlerts(Long cloudAccountId, java.util.Set<String> excludedSources) {
        if (!canServe(cloudAccountId)) {
            return List.of();
        }
        List<CloudSecurityFinding> findings = repository.findActiveBySeverityIn(
                cloudAccountId, List.of(SecuritySeverity.CRITICAL, SecuritySeverity.HIGH));

        List<AlertDto> alerts = new ArrayList<>(findings.size());
        for (CloudSecurityFinding f : findings) {
            if (f.getSource() != null && excludedSources.contains(f.getSource())) {
                continue;
            }
            AlertDto alert = new AlertDto();
            alert.setId("finding-" + f.getId());
            alert.setService(sourceLabel(f));
            alert.setName(f.getTitle());
            alert.setDescription(descriptionFor(f));
            alert.setStatus(f.getSeverity());
            alert.setType("SECURITY_FINDING");
            alert.setRegion(f.getRegion());
            // usage/limit are meaningless for a finding; the page renders them only for
            // quota rows and leaving them at zero is how the existing security alerts behave.
            alert.setUsage(0.0);
            alert.setLimit(0.0);
            alerts.add(alert);
        }
        return alerts;
    }

    /**
     * Merges live findings into a page's existing list, newest and most severe first.
     *
     * <p>
     * A merge rather than a replacement, because the two sets are complementary: Prowler and
     * the describe-based checks cover posture that no event reports, while these cover changes
     * no scan has run since. Showing only one would lose real findings either way.
     */
    public List<DashboardData.SecurityFinding> mergeInto(
            List<DashboardData.SecurityFinding> existing, Long cloudAccountId) {

        List<DashboardData.SecurityFinding> live = asSecurityFindings(cloudAccountId);
        if (live.isEmpty()) {
            return existing;
        }
        List<DashboardData.SecurityFinding> merged = new ArrayList<>(live);
        if (existing != null) {
            // Deduplicated on resource plus control: a Config rule breach and the Prowler
            // check for the same control are the same problem said twice, and a user counting
            // findings should not see it double.
            java.util.Set<String> seen = live.stream()
                    .map(SecurityFindingProjectionService::dedupKey)
                    .collect(Collectors.toSet());
            for (DashboardData.SecurityFinding finding : existing) {
                if (seen.add(dedupKey(finding))) {
                    merged.add(finding);
                }
            }
        }
        log.debug("[SecFinding] Merged {} live finding(s) into {} existing for account row {}",
                live.size(), existing == null ? 0 : existing.size(), cloudAccountId);
        return merged;
    }

    private static String dedupKey(DashboardData.SecurityFinding finding) {
        return (finding.getResourceId() == null ? "" : finding.getResourceId())
                + "|" + (finding.getControlId() == null ? "" : finding.getControlId());
    }

    private static String categoryFor(CloudSecurityFinding f) {
        if (f.getResourceType() != null && !f.getResourceType().isBlank()) {
            return f.getResourceType();
        }
        return sourceLabel(f);
    }

    /**
     * Description with the remediation appended.
     *
     * <p>
     * {@code DashboardData.SecurityFinding} has no remediation field and the page renders no
     * such column, so folding it into the description is the only way the advice reaches the
     * user without a frontend change. Worth doing: a finding without a remediation is a
     * complaint.
     */
    private static String descriptionFor(CloudSecurityFinding f) {
        StringBuilder text = new StringBuilder();
        text.append(f.getTitle());
        if (f.getDescription() != null && !f.getDescription().isBlank()) {
            text.append(" — ").append(f.getDescription());
        }
        if (f.getRemediation() != null && !f.getRemediation().isBlank()) {
            text.append(" Remediation: ").append(f.getRemediation());
        }
        return text.toString();
    }

    private static String sourceLabel(CloudSecurityFinding f) {
        switch (f.getSource() == null ? "" : f.getSource()) {
            case CloudSecurityFinding.SOURCE_SECURITY_HUB:
                return "Security Hub";
            case CloudSecurityFinding.SOURCE_GUARDDUTY:
                return "GuardDuty";
            case CloudSecurityFinding.SOURCE_CONFIG:
                return "AWS Config";
            case CloudSecurityFinding.SOURCE_XAMOPS_DETECTOR:
                return "XamOps";
            default:
                return "Security";
        }
    }
}
