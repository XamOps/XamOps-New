package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.*;
import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.repository.compliance.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Turns raw scanner output into the persisted compliance record.
 *
 * This is the only writer of scan data. It runs after the scan completes and is
 * deliberately independent of the Redis/CachedData path that SecurityPage still reads:
 * if this throws, the existing behaviour is unaffected and only the history is missing.
 * Callers log and continue rather than failing the scan.
 */
@Service
public class ComplianceScanRecorder {

    private static final Logger logger = LoggerFactory.getLogger(ComplianceScanRecorder.class);

    /**
     * Evidence rows kept per control. A single misconfigured check can span thousands of
     * resources; past a couple of hundred the list stops being something an engineer
     * reads and the exact total is already on the control row.
     */
    private static final int MAX_EVIDENCE_PER_CHECK = 200;

    /** Insert batch size — keeps the JDBC batch and the Hibernate session bounded. */
    private static final int BATCH = 500;

    private final ComplianceScanRepository scanRepo;
    private final ComplianceControlResultRepository resultRepo;
    private final ComplianceControlFrameworkRepository frameworkRepo;
    private final ComplianceFindingRepository findingRepo;
    private final ComplianceExceptionRepository exceptionRepo;
    private final ComplianceRemediationRepository remediationRepo;

    public ComplianceScanRecorder(ComplianceScanRepository scanRepo,
            ComplianceControlResultRepository resultRepo,
            ComplianceControlFrameworkRepository frameworkRepo,
            ComplianceFindingRepository findingRepo,
            ComplianceExceptionRepository exceptionRepo,
            ComplianceRemediationRepository remediationRepo) {
        this.scanRepo = scanRepo;
        this.resultRepo = resultRepo;
        this.frameworkRepo = frameworkRepo;
        this.findingRepo = findingRepo;
        this.exceptionRepo = exceptionRepo;
        this.remediationRepo = remediationRepo;
    }

    /**
     * Persist a completed scan and return its id.
     *
     * @param startedAt when the scan actually began, not when it finished — the trend
     *                  plots the moment the account was observed.
     */
    @Transactional
    public Long recordCompleted(String accountId, String provider, List<String> regions,
            List<String> frameworks, List<ProwlerFinding> findings, Instant startedAt) {

        Instant now = Instant.now();
        Map<String, List<ProwlerFinding>> byCheck = groupByCheck(findings);

        ComplianceScan scan = new ComplianceScan();
        scan.setCloudAccountId(accountId);
        scan.setProvider(provider);
        scan.setStatus(ComplianceScan.COMPLETED);
        scan.setStartedAt(startedAt != null ? startedAt : now);
        scan.setCompletedAt(now);
        scan.setRegions(join(regions));
        scan.setFrameworks(join(frameworks));
        scan.setProwlerVersion(detectVersion(findings));
        scan = scanRepo.save(scan);
        Long scanId = scan.getId();

        List<ComplianceControlResult> results = new ArrayList<>(byCheck.size());
        List<ComplianceControlFramework> frameworkRows = new ArrayList<>();
        List<ComplianceFinding> evidence = new ArrayList<>();

        int totalFindings = 0;
        int passedFindings = 0;
        int failedFindings = 0;

        for (Map.Entry<String, List<ProwlerFinding>> entry : byCheck.entrySet()) {
            String checkId = entry.getKey();
            List<ProwlerFinding> group = entry.getValue();

            int passed = 0;
            int failed = 0;
            int manual = 0;
            String severity = null;
            String title = null;
            String service = null;
            String remediation = null;
            List<String> remediationRefs = null;

            // framework → ordered distinct control refs
            Map<String, LinkedHashSet<String>> frameworkControls = new LinkedHashMap<>();
            int kept = 0;

            for (ProwlerFinding f : group) {
                String status = normalizeStatus(f.getStatusCode());
                if (ComplianceControlResult.PASS.equals(status)) passed++;
                else if (ComplianceControlResult.FAIL.equals(status)) failed++;
                else manual++;

                if (severity == null && f.getSeverity() != null) severity = f.getSeverity().toUpperCase();
                if (title == null) title = f.getCheckTitle();
                if (service == null) service = f.getServiceName();
                if (remediation == null && f.getRemediation() != null) {
                    remediation = f.getRemediation().getDesc();
                    remediationRefs = f.getRemediation().getReferences();
                }

                Map<String, List<String>> compliance = f.getCompliance();
                if (compliance != null) {
                    compliance.forEach((fw, controls) -> {
                        if (fw == null || controls == null) return;
                        LinkedHashSet<String> set =
                                frameworkControls.computeIfAbsent(fw, k -> new LinkedHashSet<>());
                        controls.stream().filter(Objects::nonNull).forEach(set::add);
                    });
                }

                // Only failing/undecided resources become evidence rows.
                if (!ComplianceControlResult.PASS.equals(status) && kept < MAX_EVIDENCE_PER_CHECK) {
                    evidence.add(toEvidence(scanId, accountId, checkId, status, f, now));
                    kept++;
                }
            }

            totalFindings += group.size();
            passedFindings += passed;
            failedFindings += failed;

            ComplianceControlResult result = new ComplianceControlResult();
            result.setScanId(scanId);
            result.setCloudAccountId(accountId);
            result.setCheckId(checkId);
            result.setCheckTitle(title);
            result.setService(service);
            // "UNKNOWN", not "LOW". Defaulting an absent severity to the lowest band was a
            // fabricated fact that then propagated into the risk score and the severity
            // breakdown; an explicit unknown is visible and can be reported as one.
            result.setSeverity(severity != null ? severity : "UNKNOWN");
            result.setStatus(deriveControlStatus(passed, failed, manual));
            result.setPassedResources(passed);
            result.setFailedResources(failed);
            result.setRemediation(remediation);
            result.setRemediationRefs(remediationRefs == null || remediationRefs.isEmpty()
                    ? null : String.join("\n", remediationRefs));
            results.add(result);

            String controlStatus = result.getStatus();
            frameworkControls.forEach((fw, controls) -> controls.forEach(controlRef -> {
                ComplianceControlFramework row = new ComplianceControlFramework();
                row.setScanId(scanId);
                row.setCloudAccountId(accountId);
                row.setCheckId(checkId);
                row.setFramework(fw);
                row.setFrameworkControl(truncate(controlRef, 128));
                row.setStatus(controlStatus);
                frameworkRows.add(row);
            }));
        }

        // Stamps the same unweighted compliance score the page shows, so the trend line and the
        // headline are the same measurement. WAIVED is never persisted, and waivers are
        // score-neutral by construction, so the stored value stays correct as exceptions come and
        // go rather than needing a rescan to catch up.
        ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(results);
        scan.setScore(acc.score());
        scan.setTotalFindings(totalFindings);
        scan.setPassedFindings(passedFindings);
        scan.setFailedFindings(failedFindings);
        scanRepo.save(scan);

        saveInBatches(results, resultRepo::saveAll);
        saveInBatches(frameworkRows, frameworkRepo::saveAll);
        saveInBatches(evidence, findingRepo::saveAll);

        logger.info("Recorded compliance scan {} for {} — {} controls, {} framework refs, "
                        + "{} evidence rows, score {}",
                scanId, accountId, results.size(), frameworkRows.size(), evidence.size(), acc.score());

        expireLapsedExceptions(accountId, now);
        verifyRemediations(accountId, scanId, results);

        return scanId;
    }

    /**
     * Record a scan that never produced results.
     *
     * Worth persisting: a gap in the trend is information, and an account whose scans
     * keep failing looks identical to one that is simply never scanned unless the
     * failures are on the record.
     */
    @Transactional
    public Long recordFailed(String accountId, String provider, List<String> regions,
            List<String> frameworks, Instant startedAt, String error) {
        ComplianceScan scan = new ComplianceScan();
        scan.setCloudAccountId(accountId);
        scan.setProvider(provider);
        scan.setStatus(ComplianceScan.FAILED);
        scan.setStartedAt(startedAt != null ? startedAt : Instant.now());
        scan.setCompletedAt(Instant.now());
        scan.setRegions(join(regions));
        scan.setFrameworks(join(frameworks));
        scan.setErrorMessage(truncate(error, 4000));
        return scanRepo.save(scan).getId();
    }

    // ── Post-scan sweeps ────────────────────────────────────────────────────

    /**
     * Flip approved-but-lapsed waivers to EXPIRED. The read queries already ignore them
     * by date, so this is about the audit trail telling the truth rather than about
     * suppressing anything.
     */
    private void expireLapsedExceptions(String accountId, Instant now) {
        List<ComplianceException> lapsed = exceptionRepo.findLapsed(accountId, now);
        if (lapsed.isEmpty()) return;
        lapsed.forEach(e -> e.setStatus(ComplianceException.EXPIRED));
        exceptionRepo.saveAll(lapsed);
        logger.info("Expired {} lapsed compliance exceptions for {}", lapsed.size(), accountId);
    }

    /**
     * Close out remediation work that this scan can prove landed.
     *
     * This is the difference between a task board and a compliance record: someone
     * marking a ticket RESOLVED is a claim, a subsequent scan observing the control pass
     * is evidence. Only the second one sets verifiedAt.
     */
    private void verifyRemediations(String accountId, Long scanId,
            List<ComplianceControlResult> results) {
        List<ComplianceRemediation> awaiting = remediationRepo.findAwaitingVerification(accountId);
        if (awaiting.isEmpty()) return;

        Set<String> nowPassing = new HashSet<>();
        for (ComplianceControlResult r : results) {
            if (ComplianceControlResult.PASS.equals(r.getStatus())) nowPassing.add(r.getCheckId());
        }

        List<ComplianceRemediation> verified = new ArrayList<>();
        for (ComplianceRemediation task : awaiting) {
            if (!nowPassing.contains(task.getCheckId())) continue;
            task.setStatus(ComplianceRemediation.VERIFIED);
            task.setVerifiedAt(Instant.now());
            task.setVerifiedScanId(scanId);
            task.setUpdatedAt(Instant.now());
            verified.add(task);
        }
        if (!verified.isEmpty()) {
            remediationRepo.saveAll(verified);
            logger.info("Scan {} verified {} remediations for {}", scanId, verified.size(), accountId);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Findings with no event_code cannot be attributed to a control, so they are counted
     * and dropped rather than bucketed under a synthetic id that would then appear in
     * the UI as a real control.
     */
    private Map<String, List<ProwlerFinding>> groupByCheck(List<ProwlerFinding> findings) {
        Map<String, List<ProwlerFinding>> byCheck = new LinkedHashMap<>();
        int unattributed = 0;
        for (ProwlerFinding f : findings == null ? List.<ProwlerFinding>of() : findings) {
            String checkId = f.getCheckId();
            if (checkId == null || checkId.isBlank()) {
                unattributed++;
                continue;
            }
            byCheck.computeIfAbsent(truncate(checkId, 256), k -> new ArrayList<>()).add(f);
        }
        if (unattributed > 0) {
            logger.warn("Dropped {} scanner findings with no event_code — they cannot be "
                    + "attributed to a control", unattributed);
        }
        return byCheck;
    }

    /**
     * A control fails if any in-scope resource fails. It only passes when at least one
     * resource was checked and none failed. Everything else is MANUAL, which means
     * nobody has established the answer — distinct from having established a pass.
     */
    private String deriveControlStatus(int passed, int failed, int manual) {
        if (failed > 0) return ComplianceControlResult.FAIL;
        if (passed > 0) return ComplianceControlResult.PASS;
        return ComplianceControlResult.MANUAL;
    }

    private String normalizeStatus(String statusCode) {
        if (statusCode == null) return ComplianceControlResult.MANUAL;
        switch (statusCode.toUpperCase()) {
            case "PASS": return ComplianceControlResult.PASS;
            case "FAIL": return ComplianceControlResult.FAIL;
            default:     return ComplianceControlResult.MANUAL;
        }
    }

    private ComplianceFinding toEvidence(Long scanId, String accountId, String checkId,
            String status, ProwlerFinding f, Instant now) {
        ComplianceFinding cf = new ComplianceFinding();
        cf.setScanId(scanId);
        cf.setCloudAccountId(accountId);
        cf.setCheckId(checkId);
        cf.setStatus(status);
        cf.setSeverity(f.getSeverity() == null ? null : f.getSeverity().toUpperCase());
        cf.setResourceUid(truncate(f.getResourceId(), 512));
        cf.setResourceName(truncate(resourceName(f), 512));
        cf.setResourceType(truncate(resourceType(f), 128));
        cf.setRegion(truncate(f.getRegion(), 64));
        cf.setStatusDetail(f.getMessage());
        cf.setObservedAt(now);
        return cf;
    }

    private String resourceName(ProwlerFinding f) {
        List<ProwlerFinding.Resource> rs = f.getResources();
        return rs == null || rs.isEmpty() ? null : rs.get(0).getName();
    }

    private String resourceType(ProwlerFinding f) {
        List<ProwlerFinding.Resource> rs = f.getResources();
        return rs == null || rs.isEmpty() ? null : rs.get(0).getType();
    }

    private String detectVersion(List<ProwlerFinding> findings) {
        if (findings == null) return null;
        for (ProwlerFinding f : findings) {
            ProwlerFinding.FindingMetadata md = f.getMetadata();
            if (md != null && md.getProduct() != null && md.getProduct().getVersion() != null) {
                return truncate(md.getProduct().getVersion(), 32);
            }
        }
        return null;
    }

    private <T> void saveInBatches(List<T> rows, java.util.function.Consumer<List<T>> save) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            save.accept(rows.subList(i, Math.min(i + BATCH, rows.size())));
        }
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
