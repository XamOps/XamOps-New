package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.*;
import com.xammer.cloud.dto.compliance.ComplianceReport;
import com.xammer.cloud.repository.compliance.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Assembles the ComplianceOps report from persisted scan data.
 *
 * All aggregation happens here rather than in the browser. That is not only a
 * performance point: the old page shipped ~60k findings to the client and re-derived
 * control identity from the first 120 characters of a description, so control totals
 * shifted whenever the scanner reworded a message. Grouping by {@code check_id} in SQL
 * makes the same control the same row across releases.
 */
@Service
public class ComplianceOpsService {

    private static final Logger logger = LoggerFactory.getLogger(ComplianceOpsService.class);

    /** Scans inspected when working out how long a control has been failing. */
    private static final int STREAK_WINDOW = 12;

    /** A waiver inside this window is close enough to expiry to need attention. */
    private static final int EXPIRING_SOON_DAYS = 30;

    private static final int TOP_RISKS = 12;

    private final ComplianceScanRepository scanRepo;
    private final ComplianceControlResultRepository resultRepo;
    private final ComplianceControlFrameworkRepository frameworkRepo;
    private final ComplianceFindingRepository findingRepo;
    private final ComplianceExceptionRepository exceptionRepo;
    private final ComplianceControlOwnerRepository ownerRepo;
    private final ComplianceRemediationRepository remediationRepo;

    public ComplianceOpsService(ComplianceScanRepository scanRepo,
            ComplianceControlResultRepository resultRepo,
            ComplianceControlFrameworkRepository frameworkRepo,
            ComplianceFindingRepository findingRepo,
            ComplianceExceptionRepository exceptionRepo,
            ComplianceControlOwnerRepository ownerRepo,
            ComplianceRemediationRepository remediationRepo) {
        this.scanRepo = scanRepo;
        this.resultRepo = resultRepo;
        this.frameworkRepo = frameworkRepo;
        this.findingRepo = findingRepo;
        this.exceptionRepo = exceptionRepo;
        this.ownerRepo = ownerRepo;
        this.remediationRepo = remediationRepo;
    }

    // ── Report ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ComplianceReport getReport(String accountId, int trendDays) {
        ComplianceReport report = new ComplianceReport();
        report.setAccountId(accountId);

        Optional<ComplianceScan> latest = scanRepo
                .findFirstByCloudAccountIdAndStatusOrderByStartedAtDesc(accountId, ComplianceScan.COMPLETED);

        if (latest.isEmpty()) {
            report.setScanStatus("NONE");
            report.setAssessable(false);
            report.setScoreLabel(ComplianceScoring.label(null));
            // Distinguish "never scanned" from "every scan failed": the second is an
            // operational problem the user needs to see, not an empty state.
            List<ComplianceScan> recent = scanRepo
                    .findByCloudAccountIdOrderByStartedAtDesc(accountId, PageRequest.of(0, 1));
            if (!recent.isEmpty() && ComplianceScan.FAILED.equals(recent.get(0).getStatus())) {
                report.setProvider(recent.get(0).getProvider());
                report.setScanStatus("FAILED");
                report.setScanError(recent.get(0).getErrorMessage());
                report.setScannedAt(recent.get(0).getStartedAt());
                report.getNotes().add("The most recent scan did not complete, so there is no "
                        + "compliance record to show. The error is shown above.");
            } else {
                report.getNotes().add("No compliance scan has been recorded for this account yet.");
            }
            return report;
        }

        ComplianceScan scan = latest.get();
        Long scanId = scan.getId();
        Instant now = Instant.now();

        report.setProvider(scan.getProvider());
        report.setScanId(scanId);
        report.setScannedAt(scan.getStartedAt());
        report.setScanStatus(scan.getStatus());
        report.setRegions(split(scan.getRegions()));
        report.setFrameworksRequested(split(scan.getFrameworks()));
        report.setScannerVersion(scan.getProwlerVersion());
        report.setScoreMethod("Severity-weighted across evaluated controls "
                + "(critical 10, high 6, medium 3, low 1). Manual and waived controls are "
                + "excluded from the denominator.");

        List<ComplianceControlResult> results = resultRepo.findByScanId(scanId);
        if (results.isEmpty()) {
            report.setAssessable(false);
            report.setScoreLabel(ComplianceScoring.label(null));
            report.getNotes().add("The scan completed but produced no control results. "
                    + "This usually means the scanner could not authenticate to the account.");
            return report;
        }

        Map<String, ComplianceControlOwner> owners = ownerRepo.findByCloudAccountId(accountId).stream()
                .collect(Collectors.toMap(ComplianceControlOwner::getCheckId, o -> o, (a, b) -> a));
        List<ComplianceException> activeExceptions = exceptionRepo.findActive(accountId, now);
        Map<String, List<ComplianceRemediation>> tasksByCheck = remediationRepo.findOpen(accountId).stream()
                .collect(Collectors.groupingBy(ComplianceRemediation::getCheckId));
        Map<String, List<ComplianceFinding>> evidenceByCheck = findingRepo.findByScanId(scanId).stream()
                .collect(Collectors.groupingBy(ComplianceFinding::getCheckId));
        Map<String, List<ComplianceControlFramework>> frameworkByCheck = frameworkRepo.findByScanId(scanId).stream()
                .collect(Collectors.groupingBy(ComplianceControlFramework::getCheckId));

        // Waivers first: every later calculation reads the post-waiver status.
        Map<String, String> effectiveStatus = applyExceptions(results, activeExceptions, evidenceByCheck, report);

        ScanDiff diff = diffAgainstHistory(accountId, scanId);

        List<ComplianceReport.ControlView> controls = new ArrayList<>(results.size());
        for (ComplianceControlResult r : results) {
            controls.add(toView(r, effectiveStatus.get(r.getCheckId()), owners, activeExceptions,
                    tasksByCheck, evidenceByCheck, frameworkByCheck, diff, now));
        }
        controls.sort(CONTROL_ORDER);
        report.setControls(controls);

        report.setCounts(buildCounts(results, effectiveStatus, diff));
        ComplianceScoring.Accumulator acc = new ComplianceScoring.Accumulator();
        results.forEach(r -> acc.add(r.getSeverity(), effectiveStatus.get(r.getCheckId())));
        report.setScore(acc.score());
        report.setAssessable(acc.assessable());
        report.setScoreLabel(ComplianceScoring.label(acc.score()));

        report.setFrameworks(buildFrameworkScores(frameworkByCheck, results, effectiveStatus));
        report.setTopRisks(controls.stream()
                .filter(c -> ComplianceControlResult.FAIL.equals(c.getStatus()))
                .limit(TOP_RISKS)
                .collect(Collectors.toList()));
        report.setTrend(buildTrend(accountId, trendDays));
        report.setProgram(buildProgramHealth(accountId, results, effectiveStatus, owners, now));

        addNotes(report, scan, results, effectiveStatus);
        return report;
    }

    // ── Waivers ─────────────────────────────────────────────────────────────

    /**
     * Returns check id → status with active waivers applied.
     *
     * A check-level waiver (no resource uid) suppresses the control outright. A
     * resource-level waiver only suppresses the control when every failing resource is
     * covered — and only when the evidence list for that check was not truncated,
     * because otherwise "all covered" would be an assumption about resources we did not
     * store. A partially covered control stays FAIL and says so in the notes, which is
     * the conservative direction: showing a real failure is recoverable, hiding one is not.
     */
    private Map<String, String> applyExceptions(List<ComplianceControlResult> results,
            List<ComplianceException> active,
            Map<String, List<ComplianceFinding>> evidenceByCheck,
            ComplianceReport report) {

        Map<String, String> status = new HashMap<>();
        results.forEach(r -> status.put(r.getCheckId(), r.getStatus()));
        if (active.isEmpty()) return status;

        Set<String> checkLevel = active.stream()
                .filter(e -> e.getResourceUid() == null || e.getResourceUid().isBlank())
                .map(ComplianceException::getCheckId)
                .collect(Collectors.toSet());

        Map<String, Set<String>> resourceLevel = active.stream()
                .filter(e -> e.getResourceUid() != null && !e.getResourceUid().isBlank())
                .collect(Collectors.groupingBy(ComplianceException::getCheckId,
                        Collectors.mapping(ComplianceException::getResourceUid, Collectors.toSet())));

        int partial = 0;
        for (ComplianceControlResult r : results) {
            if (!ComplianceControlResult.FAIL.equals(r.getStatus())) continue;
            String checkId = r.getCheckId();

            if (checkLevel.contains(checkId)) {
                status.put(checkId, ComplianceControlResult.WAIVED);
                continue;
            }

            Set<String> waivedResources = resourceLevel.get(checkId);
            if (waivedResources == null) continue;

            List<ComplianceFinding> evidence = evidenceByCheck.getOrDefault(checkId, List.of());
            List<String> failingUids = evidence.stream()
                    .filter(f -> ComplianceControlResult.FAIL.equals(f.getStatus()))
                    .map(ComplianceFinding::getResourceUid)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            boolean evidenceComplete = !failingUids.isEmpty()
                    && failingUids.size() >= r.getFailedResources();
            if (evidenceComplete && waivedResources.containsAll(failingUids)) {
                status.put(checkId, ComplianceControlResult.WAIVED);
            } else {
                partial++;
            }
        }

        if (partial > 0) {
            report.getNotes().add(partial + " control" + (partial == 1 ? " has" : "s have")
                    + " a waiver covering some but not all failing resources, so "
                    + (partial == 1 ? "it is" : "they are") + " still counted as failing.");
        }
        return status;
    }

    // ── Scan-to-scan diff ───────────────────────────────────────────────────

    /** Failing sets for recent scans, used for regression detection and streaks. */
    private static final class ScanDiff {
        Set<String> failingNow = Set.of();
        Set<String> failingPrevious = Set.of();
        Set<String> passingPrevious = Set.of();
        Map<String, Integer> streak = Map.of();
        boolean hasPrevious;
        Instant previousAt;
    }

    private ScanDiff diffAgainstHistory(String accountId, Long currentScanId) {
        ScanDiff diff = new ScanDiff();
        List<ComplianceScan> window = scanRepo.findByCloudAccountIdOrderByStartedAtDesc(
                accountId, PageRequest.of(0, STREAK_WINDOW * 2)).stream()
                .filter(s -> ComplianceScan.COMPLETED.equals(s.getStatus()))
                .limit(STREAK_WINDOW)
                .collect(Collectors.toList());
        if (window.isEmpty()) return diff;

        List<Long> scanIds = window.stream().map(ComplianceScan::getId).collect(Collectors.toList());
        Map<Long, Set<String>> failingByScan = new HashMap<>();
        for (Object[] row : resultRepo.findFailingByScans(scanIds)) {
            failingByScan.computeIfAbsent((Long) row[0], k -> new HashSet<>()).add((String) row[1]);
        }

        diff.failingNow = failingByScan.getOrDefault(currentScanId, Set.of());

        if (window.size() > 1) {
            ComplianceScan previous = window.get(1);
            diff.hasPrevious = true;
            diff.previousAt = previous.getStartedAt();
            diff.failingPrevious = failingByScan.getOrDefault(previous.getId(), Set.of());
            diff.passingPrevious = new HashSet<>(resultRepo.findPassingCheckIds(previous.getId()));
        }

        // window is newest-first, so walking forward walks backwards in time. The streak
        // ends at the first scan where the control was not failing.
        Map<String, Integer> streak = new HashMap<>();
        for (String checkId : diff.failingNow) {
            int count = 0;
            for (ComplianceScan s : window) {
                if (failingByScan.getOrDefault(s.getId(), Set.of()).contains(checkId)) count++;
                else break;
            }
            streak.put(checkId, count);
        }
        diff.streak = streak;
        return diff;
    }

    // ── Views ───────────────────────────────────────────────────────────────

    private static final Comparator<ComplianceReport.ControlView> CONTROL_ORDER =
            Comparator.<ComplianceReport.ControlView>comparingInt(c -> statusRank(c.getStatus()))
                    .thenComparing(Comparator.comparingInt(
                            (ComplianceReport.ControlView c) -> ComplianceScoring.weight(c.getSeverity())).reversed())
                    .thenComparing(Comparator.comparingInt(
                            ComplianceReport.ControlView::getFailedResources).reversed())
                    .thenComparing(ComplianceReport.ControlView::getCheckId);

    /** Failing work first, then things nobody has established, then settled rows. */
    private static int statusRank(String status) {
        if (ComplianceControlResult.FAIL.equals(status)) return 0;
        if (ComplianceControlResult.MANUAL.equals(status)) return 1;
        if (ComplianceControlResult.WAIVED.equals(status)) return 2;
        return 3;
    }

    private ComplianceReport.ControlView toView(ComplianceControlResult r, String status,
            Map<String, ComplianceControlOwner> owners,
            List<ComplianceException> activeExceptions,
            Map<String, List<ComplianceRemediation>> tasksByCheck,
            Map<String, List<ComplianceFinding>> evidenceByCheck,
            Map<String, List<ComplianceControlFramework>> frameworkByCheck,
            ScanDiff diff, Instant now) {

        ComplianceReport.ControlView v = new ComplianceReport.ControlView();
        v.setCheckId(r.getCheckId());
        v.setTitle(r.getCheckTitle());
        v.setService(r.getService());
        v.setSeverity(r.getSeverity());
        v.setStatus(status != null ? status : r.getStatus());
        v.setPassedResources(r.getPassedResources());
        v.setFailedResources(r.getFailedResources());
        v.setRemediation(r.getRemediation());
        if (r.getRemediationRefs() != null && !r.getRemediationRefs().isBlank()) {
            v.setRemediationRefs(Arrays.stream(r.getRemediationRefs().split("\n"))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        }

        frameworkByCheck.getOrDefault(r.getCheckId(), List.of()).stream()
                .collect(Collectors.groupingBy(ComplianceControlFramework::getFramework,
                        LinkedHashMap::new,
                        Collectors.mapping(ComplianceControlFramework::getFrameworkControl,
                                Collectors.toCollection(LinkedHashSet::new))))
                .forEach((fw, controls) -> {
                    ComplianceReport.FrameworkRef ref = new ComplianceReport.FrameworkRef();
                    ref.setFramework(fw);
                    ref.setControls(new ArrayList<>(controls));
                    v.getFrameworks().add(ref);
                });

        for (ComplianceFinding f : evidenceByCheck.getOrDefault(r.getCheckId(), List.of())) {
            ComplianceReport.ResourceRef ref = new ComplianceReport.ResourceRef();
            ref.setUid(f.getResourceUid());
            ref.setName(f.getResourceName());
            ref.setType(f.getResourceType());
            ref.setRegion(f.getRegion());
            ref.setDetail(f.getStatusDetail());
            ref.setStatus(f.getStatus());
            v.getResources().add(ref);
        }

        ComplianceControlOwner owner = owners.get(r.getCheckId());
        if (owner != null) {
            ComplianceReport.Owner o = new ComplianceReport.Owner();
            o.setName(owner.getOwnerName());
            o.setEmail(owner.getOwnerEmail());
            o.setTeam(owner.getTeam());
            v.setOwner(o);
        }

        activeExceptions.stream()
                .filter(e -> e.getCheckId().equals(r.getCheckId()))
                .findFirst()
                .ifPresent(e -> {
                    ComplianceReport.ExceptionRef ref = new ComplianceReport.ExceptionRef();
                    ref.setId(e.getId());
                    ref.setStatus(e.getStatus());
                    ref.setJustification(e.getJustification());
                    ref.setCompensatingControl(e.getCompensatingControl());
                    ref.setRequestedBy(e.getRequestedBy());
                    ref.setApprovedBy(e.getApprovedBy());
                    ref.setExpiresAt(e.getExpiresAt());
                    ref.setDaysUntilExpiry(ChronoUnit.DAYS.between(now, e.getExpiresAt()));
                    v.setException(ref);
                });

        List<ComplianceRemediation> tasks = tasksByCheck.get(r.getCheckId());
        if (tasks != null && !tasks.isEmpty()) {
            ComplianceRemediation t = tasks.get(0);
            ComplianceReport.RemediationRef ref = new ComplianceReport.RemediationRef();
            ref.setId(t.getId());
            ref.setStatus(t.getStatus());
            ref.setAssigneeName(t.getAssigneeName());
            ref.setAssigneeEmail(t.getAssigneeEmail());
            ref.setTicketRef(t.getTicketRef());
            ref.setDueAt(t.getDueAt());
            ref.setOverdue(t.getDueAt() != null && t.getDueAt().isBefore(now)
                    && !ComplianceRemediation.VERIFIED.equals(t.getStatus()));
            v.setTask(ref);
        }

        v.setNewlyFailing(ComplianceControlResult.FAIL.equals(v.getStatus())
                && diff.hasPrevious && diff.passingPrevious.contains(r.getCheckId()));
        v.setFailingForScans(diff.streak.getOrDefault(r.getCheckId(), 0));
        return v;
    }

    private ComplianceReport.Counts buildCounts(List<ComplianceControlResult> results,
            Map<String, String> effectiveStatus, ScanDiff diff) {
        ComplianceReport.Counts c = new ComplianceReport.Counts();
        c.setTotalControls(results.size());
        for (ComplianceControlResult r : results) {
            String status = effectiveStatus.get(r.getCheckId());
            c.setPassedResources(c.getPassedResources() + r.getPassedResources());
            c.setFailedResources(c.getFailedResources() + r.getFailedResources());
            if (ComplianceControlResult.PASS.equals(status)) {
                c.setPassedControls(c.getPassedControls() + 1);
            } else if (ComplianceControlResult.FAIL.equals(status)) {
                c.setFailedControls(c.getFailedControls() + 1);
                bumpSeverity(c, r.getSeverity());
            } else if (ComplianceControlResult.WAIVED.equals(status)) {
                c.setWaivedControls(c.getWaivedControls() + 1);
            } else {
                c.setManualControls(c.getManualControls() + 1);
            }
        }
        if (diff.hasPrevious) {
            c.setNewlyFailing((int) diff.failingNow.stream()
                    .filter(diff.passingPrevious::contains).count());
            c.setNewlyPassing((int) diff.failingPrevious.stream()
                    .filter(id -> !diff.failingNow.contains(id)).count());
        }
        return c;
    }

    private void bumpSeverity(ComplianceReport.Counts c, String severity) {
        String s = severity == null ? "LOW" : severity.toUpperCase();
        switch (s) {
            case "CRITICAL": c.setCritical(c.getCritical() + 1); break;
            case "HIGH":     c.setHigh(c.getHigh() + 1); break;
            case "MEDIUM":   c.setMedium(c.getMedium() + 1); break;
            default:         c.setLow(c.getLow() + 1);
        }
    }

    // ── Frameworks ──────────────────────────────────────────────────────────

    /**
     * Per-framework rollup. Status is re-read from the current effective status rather
     * than the status stored on the mapping row, so approving a waiver moves every
     * framework the control touches without needing a re-scan.
     */
    private List<ComplianceReport.FrameworkScore> buildFrameworkScores(
            Map<String, List<ComplianceControlFramework>> frameworkByCheck,
            List<ComplianceControlResult> results,
            Map<String, String> effectiveStatus) {

        Map<String, String> severityByCheck = results.stream()
                .collect(Collectors.toMap(ComplianceControlResult::getCheckId,
                        r -> r.getSeverity() == null ? "LOW" : r.getSeverity(), (a, b) -> a));

        Map<String, Set<String>> checksByFramework = new LinkedHashMap<>();
        Map<String, Set<String>> refsByFramework = new LinkedHashMap<>();
        frameworkByCheck.forEach((checkId, rows) -> rows.forEach(row -> {
            checksByFramework.computeIfAbsent(row.getFramework(), k -> new LinkedHashSet<>()).add(checkId);
            refsByFramework.computeIfAbsent(row.getFramework(), k -> new HashSet<>())
                    .add(row.getFrameworkControl());
        }));

        List<ComplianceReport.FrameworkScore> out = new ArrayList<>();
        checksByFramework.forEach((framework, checks) -> {
            ComplianceReport.FrameworkScore fs = new ComplianceReport.FrameworkScore();
            fs.setFramework(framework);
            fs.setTotalControls(checks.size());
            fs.setFrameworkControlCount(refsByFramework.getOrDefault(framework, Set.of()).size());
            ComplianceScoring.Accumulator acc = new ComplianceScoring.Accumulator();
            for (String checkId : checks) {
                String status = effectiveStatus.get(checkId);
                String severity = severityByCheck.get(checkId);
                acc.add(severity, status);
                if (ComplianceControlResult.PASS.equals(status)) {
                    fs.setPassedControls(fs.getPassedControls() + 1);
                } else if (ComplianceControlResult.FAIL.equals(status)) {
                    fs.setFailedControls(fs.getFailedControls() + 1);
                    switch (severity == null ? "LOW" : severity.toUpperCase()) {
                        case "CRITICAL": fs.setCritical(fs.getCritical() + 1); break;
                        case "HIGH":     fs.setHigh(fs.getHigh() + 1); break;
                        case "MEDIUM":   fs.setMedium(fs.getMedium() + 1); break;
                        default:         fs.setLow(fs.getLow() + 1);
                    }
                } else if (ComplianceControlResult.WAIVED.equals(status)) {
                    fs.setWaivedControls(fs.getWaivedControls() + 1);
                } else {
                    fs.setManualControls(fs.getManualControls() + 1);
                }
            }
            fs.setScore(acc.score());
            fs.setAssessable(acc.assessable());
            out.add(fs);
        });

        out.sort(Comparator.comparingInt(ComplianceReport.FrameworkScore::getFailedControls).reversed()
                .thenComparing(ComplianceReport.FrameworkScore::getFramework));
        return out;
    }

    // ── Trend ───────────────────────────────────────────────────────────────

    /**
     * The real trend: one point per completed scan, from the stored score.
     *
     * The version this replaces took today's counts and multiplied them by
     * {@code 1 - progress * 0.15} with a sine wave on top, producing a 14-day improving
     * curve that had no relationship to the account. On a page used in audit
     * conversations that is manufactured evidence, so it is gone rather than fixed.
     */
    @Transactional(readOnly = true)
    public List<ComplianceReport.TrendPoint> buildTrend(String accountId, int days) {
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, days)));
        List<ComplianceScan> scans = scanRepo.findCompletedSince(accountId, since);
        if (scans.isEmpty()) return List.of();

        List<Long> ids = scans.stream().map(ComplianceScan::getId).collect(Collectors.toList());
        Map<Long, Map<String, Integer>> severityByScan = new HashMap<>();
        for (Object[] row : resultRepo.countFailedBySeverity(ids)) {
            Long scanId = (Long) row[0];
            String severity = row[1] == null ? "LOW" : ((String) row[1]).toUpperCase();
            int count = ((Number) row[2]).intValue();
            severityByScan.computeIfAbsent(scanId, k -> new HashMap<>()).merge(severity, count, Integer::sum);
        }

        List<ComplianceReport.TrendPoint> points = new ArrayList<>(scans.size());
        for (ComplianceScan s : scans) {
            Map<String, Integer> sev = severityByScan.getOrDefault(s.getId(), Map.of());
            ComplianceReport.TrendPoint p = new ComplianceReport.TrendPoint();
            p.setScanId(s.getId());
            p.setAt(s.getStartedAt());
            p.setScore(s.getScore());
            p.setCritical(sev.getOrDefault("CRITICAL", 0));
            p.setHigh(sev.getOrDefault("HIGH", 0));
            p.setMedium(sev.getOrDefault("MEDIUM", 0));
            p.setLow(sev.getOrDefault("LOW", 0) + sev.getOrDefault("INFORMATIONAL", 0));
            p.setFailedControls(p.getCritical() + p.getHigh() + p.getMedium() + p.getLow());
            p.setPassedControls(0);
            points.add(p);
        }
        return points;
    }

    // ── Programme health ────────────────────────────────────────────────────

    private ComplianceReport.ProgramHealth buildProgramHealth(String accountId,
            List<ComplianceControlResult> results, Map<String, String> effectiveStatus,
            Map<String, ComplianceControlOwner> owners, Instant now) {

        ComplianceReport.ProgramHealth h = new ComplianceReport.ProgramHealth();

        List<ComplianceException> all = exceptionRepo.findByCloudAccountIdOrderByCreatedAtDesc(accountId);
        Instant soon = now.plus(Duration.ofDays(EXPIRING_SOON_DAYS));
        for (ComplianceException e : all) {
            if (ComplianceException.PENDING.equals(e.getStatus())) {
                h.setPendingExceptions(h.getPendingExceptions() + 1);
            } else if (ComplianceException.APPROVED.equals(e.getStatus())) {
                if (e.getExpiresAt() != null && e.getExpiresAt().isAfter(now)) {
                    h.setActiveExceptions(h.getActiveExceptions() + 1);
                    if (e.getExpiresAt().isBefore(soon)) {
                        h.setExceptionsExpiringSoon(h.getExceptionsExpiringSoon() + 1);
                    }
                } else {
                    h.setLapsedExceptions(h.getLapsedExceptions() + 1);
                }
            } else if (ComplianceException.EXPIRED.equals(e.getStatus())) {
                h.setLapsedExceptions(h.getLapsedExceptions() + 1);
            }
        }

        for (ComplianceControlResult r : results) {
            if (!ComplianceControlResult.FAIL.equals(effectiveStatus.get(r.getCheckId()))) continue;
            if (owners.containsKey(r.getCheckId())) {
                h.setFailingControlsWithOwner(h.getFailingControlsWithOwner() + 1);
            } else {
                h.setFailingControlsWithoutOwner(h.getFailingControlsWithoutOwner() + 1);
            }
        }

        for (ComplianceRemediation t : remediationRepo.findOpen(accountId)) {
            switch (t.getStatus()) {
                case ComplianceRemediation.OPEN: h.setRemediationOpen(h.getRemediationOpen() + 1); break;
                case ComplianceRemediation.IN_PROGRESS:
                    h.setRemediationInProgress(h.getRemediationInProgress() + 1); break;
                case ComplianceRemediation.RESOLVED:
                    h.setRemediationAwaitingVerification(h.getRemediationAwaitingVerification() + 1); break;
                default: break;
            }
            if (t.getDueAt() != null && t.getDueAt().isBefore(now)) {
                h.setRemediationOverdue(h.getRemediationOverdue() + 1);
            }
        }

        h.setCompletedScans((int) scanRepo.countByCloudAccountIdAndStatus(
                accountId, ComplianceScan.COMPLETED));
        return h;
    }

    // ── Notes ───────────────────────────────────────────────────────────────

    private void addNotes(ComplianceReport report, ComplianceScan scan,
            List<ComplianceControlResult> results, Map<String, String> effectiveStatus) {

        if (report.getTrend().size() < 2) {
            report.getNotes().add("Only one completed scan is on record, so there is no trend "
                    + "to plot yet. A second scan will produce the first comparison.");
        }

        long manual = effectiveStatus.values().stream()
                .filter(ComplianceControlResult.MANUAL::equals).count();
        if (manual > 0 && manual * 2 > results.size()) {
            report.getNotes().add(manual + " of " + results.size() + " controls could not be "
                    + "decided automatically, so the score reflects a minority of the control set.");
        }

        if (report.getCounts().getWaivedControls() > 0) {
            report.getNotes().add(report.getCounts().getWaivedControls()
                    + " control(s) are covered by an approved exception and are excluded from "
                    + "the score. They are not passing.");
        }

        if (report.getRegions().isEmpty()) {
            report.getNotes().add("The scanned region list was not recorded for this scan, so "
                    + "coverage cannot be confirmed.");
        }

        if (scan.getScore() != null && report.getScore() != null
                && !scan.getScore().equals(report.getScore())) {
            report.getNotes().add("The score shown reflects exceptions approved after the scan ran; "
                    + "the score recorded at scan time was " + scan.getScore() + "%.");
        }
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return new ArrayList<>();
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ComplianceScan> getScanHistory(String accountId, int limit) {
        return scanRepo.findByCloudAccountIdOrderByStartedAtDesc(
                accountId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }
}
