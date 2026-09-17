package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.domain.SecurityFindingResolution;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import com.xammer.cloud.repository.SecurityFindingResolutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lets a user say "I have fixed this", and decides how long to believe them.
 *
 * <p>
 * <b>The gap this closes.</b> A {@link CloudSecurityFinding} moved off ACTIVE only when the
 * provider emitted a closing event. Config does that, and Security Hub does it for controls. The
 * other two sources do not: GuardDuty archives a finding only when someone archives it in
 * GuardDuty, and {@code SOURCE_XAMOPS_DETECTOR} findings are derived from a single CloudTrail
 * event, so XamOps sees the {@code AuthorizeSecurityGroupIngress} that opened port 22 and never
 * sees the {@code RevokeSecurityGroupIngress} that closed it. A resource fixed silently stayed
 * ACTIVE for the lifetime of the connection, and no amount of cache expiry could help — the
 * underlying row was still, as far as the database knew, a live problem.
 *
 * <p>
 * <b>Why a user action rather than an automatic sweep.</b> A sweep would have to decide that a
 * finding is gone because it did not appear in the latest enumeration, and nothing here can
 * support that inference safely. {@code AwsSecurityHubService} reads at most 100 findings per
 * region with no {@code NextToken} pagination and filters to CRITICAL/HIGH with
 * {@code workflowStatus=NEW}, so "absent from the enumeration" routinely means "on page two" or
 * "MEDIUM". There is no GuardDuty enumerator at all, so a sweep would close every GuardDuty
 * finding on its first run. And {@code CloudSecurityFindingRepository.purgeResolvedBefore} is a
 * hard {@code DELETE} against a stream that only republishes on state change, so a finding
 * wrongly closed is permanently destroyed a retention period later. The failure mode of a wrong
 * sweep is telling a customer they are secure when they are not; the failure mode of asking is a
 * button.
 *
 * <p>
 * <b>Why the answer is not simply trusted.</b> A dismissal that never expires is a blindfold, and
 * an honest page cannot contain one. So a resolution is retired the moment evidence contradicts
 * it, from either direction:
 * <ul>
 * <li>the event stream produces the finding as news again — see the {@code Outcome.CREATED}
 * branch in {@link SecurityFindingIngestService}, which covers both a reopen and a re-creation
 * after the retention purge removed the row;
 * <li>a fresh scan still reports it — see {@link #reconcileAgainstFreshScan}, which is why the
 * cached and live read paths in the controller are treated differently.
 * </ul>
 * The user's claim is therefore checked against reality at the next opportunity, rather than
 * being trusted indefinitely.
 */
@Service
public class SecurityFindingResolutionService {

    private static final Logger log =
            LoggerFactory.getLogger(SecurityFindingResolutionService.class);

    private final SecurityFindingResolutionRepository resolutionRepository;
    private final CloudSecurityFindingRepository findingRepository;

    public SecurityFindingResolutionService(
            SecurityFindingResolutionRepository resolutionRepository,
            CloudSecurityFindingRepository findingRepository) {
        this.resolutionRepository = resolutionRepository;
        this.findingRepository = findingRepository;
    }

    // ── Read path ───────────────────────────────────────────────────────────

    /** The key hashes a user has dismissed and that nothing has contradicted yet. */
    @Transactional(readOnly = true)
    public Set<String> liveKeyHashes(Long cloudAccountId) {
        if (cloudAccountId == null) {
            return Set.of();
        }
        return resolutionRepository.findLive(cloudAccountId).stream()
                .map(SecurityFindingResolution::getFindingKeyHash)
                .collect(Collectors.toSet());
    }

    /**
     * Drops findings the user has marked fixed.
     *
     * <p>
     * Applied to a list that may have come from a cache, so it must not conclude anything about
     * whether the problem is still real — that is {@link #reconcileAgainstFreshScan}'s job. Here
     * a dismissal is simply honoured.
     */
    @Transactional(readOnly = true)
    public List<DashboardData.SecurityFinding> filterResolved(
            List<DashboardData.SecurityFinding> findings, Long cloudAccountId) {

        if (findings == null || findings.isEmpty() || cloudAccountId == null) {
            return findings;
        }
        Set<String> resolved = liveKeyHashes(cloudAccountId);
        if (resolved.isEmpty()) {
            return findings;
        }
        List<DashboardData.SecurityFinding> visible = findings.stream()
                .filter(f -> !resolved.contains(SecurityFindingKey.hash(SecurityFindingKey.of(f))))
                .collect(Collectors.toList());

        int hidden = findings.size() - visible.size();
        if (hidden > 0) {
            log.debug("[SecResolution] Hid {} finding(s) marked fixed for account row {}",
                    hidden, cloudAccountId);
        }
        return visible;
    }

    /**
     * Checks dismissals against a scan that genuinely just ran, then filters.
     *
     * <p>
     * This is the half of the design that keeps a dismissal honest. If a scan performed now still
     * reports the same resource failing the same control, the user's claim was wrong — the fix did
     * not take, or was reverted, or addressed something else — and continuing to hide it would
     * make XamOps complicit in the misunderstanding. The resolution is retired with
     * {@code STILL_PRESENT_IN_SCAN} and the finding comes back.
     *
     * <p>
     * Deliberately <em>not</em> called on the cached read path. A {@code cached_data} snapshot can
     * predate the fix by hours, so treating its contents as current evidence would retire a
     * correct dismissal and make the finding reappear until the next real scan — the page
     * flapping between two answers, which is worse than either.
     */
    @Transactional
    public List<DashboardData.SecurityFinding> reconcileAgainstFreshScan(
            List<DashboardData.SecurityFinding> findings, Long cloudAccountId) {

        if (cloudAccountId == null) {
            return findings;
        }
        Set<String> resolved = liveKeyHashes(cloudAccountId);
        if (resolved.isEmpty()) {
            return findings;
        }

        Set<String> contradicted = new HashSet<>();
        if (findings != null) {
            for (DashboardData.SecurityFinding finding : findings) {
                String hash = SecurityFindingKey.hash(SecurityFindingKey.of(finding));
                if (resolved.contains(hash)) {
                    contradicted.add(hash);
                }
            }
        }

        if (!contradicted.isEmpty()) {
            resolutionRepository.retire(cloudAccountId, new ArrayList<>(contradicted),
                    SecurityFindingResolution.RETIRED_STILL_PRESENT_IN_SCAN, Instant.now());
            log.info("[SecResolution] A fresh scan still reports {} finding(s) marked fixed for "
                    + "account row {}; showing them again", contradicted.size(), cloudAccountId);
            resolved.removeAll(contradicted);
        }

        if (findings == null || resolved.isEmpty()) {
            return findings;
        }
        return findings.stream()
                .filter(f -> !resolved.contains(SecurityFindingKey.hash(SecurityFindingKey.of(f))))
                .collect(Collectors.toList());
    }

    // ── Write path ──────────────────────────────────────────────────────────

    /**
     * Records that a user believes a finding is fixed.
     *
     * <p>
     * Idempotent: dismissing the same finding twice returns the existing resolution rather than
     * failing, because the page can be open in two tabs and the second click is not an error.
     *
     * <p>
     * Any event-driven row matching the same key is moved off ACTIVE in the same transaction. That
     * is not redundant with the read filter — it is what stops
     * {@code SecurityFindingNotifier} emailing about a finding the user has already dealt with,
     * since {@code findUnnotified} selects on {@code status = 'ACTIVE'}. Filtering only at read
     * time would hide the finding from the page while continuing to send mail about it.
     */
    @Transactional
    public SecurityFindingResolution resolve(Long cloudAccountId, String resourceId,
            String controlId, String severity, String title, String note, String actor) {

        if (cloudAccountId == null) {
            throw new IllegalArgumentException("cloudAccountId is required");
        }
        String key = SecurityFindingKey.of(resourceId, controlId);
        if ("|".equals(key)) {
            // Neither component survived normalisation, so this would dismiss every finding that
            // also has no resource and no control. Refused rather than silently scoped wide.
            throw new IllegalArgumentException(
                    "A finding needs a resource or a control id before it can be marked fixed");
        }
        String hash = SecurityFindingKey.hash(key);

        Optional<SecurityFindingResolution> existing =
                resolutionRepository.findLiveByHash(cloudAccountId, hash);
        if (existing.isPresent()) {
            return existing.get();
        }

        SecurityFindingResolution resolution = new SecurityFindingResolution();
        resolution.setCloudAccountId(cloudAccountId);
        resolution.setFindingKeyHash(hash);
        resolution.setResourceId(truncate(resourceId, 2048));
        resolution.setControlId(truncate(controlId, 256));
        resolution.setSeverity(truncate(severity, 16));
        resolution.setTitle(truncate(title, 1024));
        resolution.setResolvedBy(actor == null || actor.isBlank() ? "unknown" : truncate(actor, 255));
        resolution.setResolvedAt(Instant.now());
        resolution.setNote(note);
        resolutionRepository.save(resolution);

        int closed = applyStatusToMatchingRows(cloudAccountId, hash,
                CloudSecurityFinding.STATUS_RESOLVED);

        log.info("[SecResolution] {} marked {} / {} fixed for account row {} ({} stored finding(s) "
                + "closed)", resolution.getResolvedBy(), resourceId, controlId, cloudAccountId,
                closed);
        return resolution;
    }

    /**
     * Undoes a dismissal at the user's request.
     *
     * <p>
     * The inverse has to exist. Marking the wrong row fixed is a single misclick on a dense table,
     * and without a way back the only remedy would be waiting for a scan to contradict it —
     * which, for a finding that genuinely is fixed, never comes.
     */
    @Transactional
    public boolean restore(Long cloudAccountId, String resourceId, String controlId, String actor) {
        if (cloudAccountId == null) {
            return false;
        }
        String hash = SecurityFindingKey.hashOf(resourceId, controlId);
        Optional<SecurityFindingResolution> existing =
                resolutionRepository.findLiveByHash(cloudAccountId, hash);
        if (existing.isEmpty()) {
            return false;
        }
        SecurityFindingResolution resolution = existing.get();
        resolution.setRetiredAt(Instant.now());
        resolution.setRetiredReason(SecurityFindingResolution.RETIRED_RESTORED_BY_USER);
        resolutionRepository.save(resolution);

        applyStatusToMatchingRows(cloudAccountId, hash, CloudSecurityFinding.STATUS_ACTIVE);
        log.info("[SecResolution] {} restored {} / {} for account row {}",
                actor, resourceId, controlId, cloudAccountId);
        return true;
    }

    /**
     * Retires a dismissal because the event stream produced the finding as news again.
     *
     * <p>
     * Called from ingest's {@code CREATED} branch, which covers both a reopen and a fresh row
     * created after the retention purge removed the original. Both mean the same thing: the
     * provider currently considers this active, so the user's claim no longer holds.
     */
    @Transactional
    public void retireForReportedFinding(Long cloudAccountId, String resourceId, String controlId) {
        if (cloudAccountId == null) {
            return;
        }
        String hash = SecurityFindingKey.hashOf(resourceId, controlId);
        int retired = resolutionRepository.retire(cloudAccountId, List.of(hash),
                SecurityFindingResolution.RETIRED_REOPENED_BY_PROVIDER, Instant.now());
        if (retired > 0) {
            log.info("[SecResolution] {} / {} was marked fixed but the provider reported it again "
                    + "for account row {}; showing it again", resourceId, controlId, cloudAccountId);
        }
    }

    /** The audit trail: what was dismissed, by whom, and whether it came back. */
    @Transactional(readOnly = true)
    public List<SecurityFindingResolution> history(Long cloudAccountId) {
        if (cloudAccountId == null) {
            return List.of();
        }
        return resolutionRepository.findHistory(cloudAccountId);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Moves every stored finding sharing a key to a status.
     *
     * <p>
     * Matched in memory against {@code findActive} rather than by a JPQL predicate on
     * {@code resourceArn} and {@code controlId}. Both columns are nullable and either can be
     * absent in real data, so an equality predicate would silently miss exactly those rows, and
     * the null-safe form would be a JPQL expression that no longer obviously agrees with
     * {@link SecurityFindingKey}. Sharing one key function is what guarantees the write path
     * hides precisely what the read path hides.
     */
    private int applyStatusToMatchingRows(Long cloudAccountId, String hash, String status) {
        List<CloudSecurityFinding> candidates =
                CloudSecurityFinding.STATUS_ACTIVE.equals(status)
                        ? findingRepository.findByCloudAccountIdAndStatus(
                                cloudAccountId, CloudSecurityFinding.STATUS_RESOLVED)
                        : findingRepository.findActive(cloudAccountId);

        int changed = 0;
        Instant now = Instant.now();
        for (CloudSecurityFinding row : candidates) {
            if (!hash.equals(SecurityFindingKey.hash(SecurityFindingKey.of(row)))) {
                continue;
            }
            // A finding the user suppressed outright is left alone. That is a different and
            // stronger decision than "this is fixed", and quietly downgrading it here would
            // undo it.
            if (CloudSecurityFinding.STATUS_SUPPRESSED.equals(row.getStatus())) {
                continue;
            }
            row.setStatus(status);
            row.setResolvedAt(CloudSecurityFinding.STATUS_ACTIVE.equals(status) ? null : now);
            findingRepository.save(row);
            changed++;
        }
        return changed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
