package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.domain.ResourceLabelAssignment;
import com.xammer.cloud.domain.envallocation.EnvAllocationResult;
import com.xammer.cloud.domain.envallocation.EnvAllocationRun;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.repository.envallocation.EnvAllocationResultRepository;
import com.xammer.cloud.repository.envallocation.EnvAllocationRunRepository;
import com.xammer.cloud.service.ResourceIdResolver;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAllocationLineReader;
import com.xammer.cloud.service.cur.CurSourceResolver;
import com.xammer.cloud.service.cur.ReportCannotAnswerException;
import com.xammer.cloud.service.envallocation.AllocationModel.AllocationKind;
import com.xammer.cloud.service.envallocation.AllocationModel.CostLine;
import com.xammer.cloud.service.envallocation.AllocationModel.EnvironmentSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchField;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.EnvAllocationEngine.Outcome;
import com.xammer.cloud.service.envallocation.EnvAllocationEngine.ResultRow;
import com.xammer.cloud.service.envallocation.EnvAllocationRuleService.VersionSnapshot;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.Breakdown;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.RunView;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs rule versions over months of the bill, stores the results, and serves them back.
 *
 * <h2>Stored runs and live previews</h2>
 * A closed month is answered by a stored run: queued here, executed in the background, kept
 * unchanged afterwards. The open month changes daily, so it is only ever previewed — computed on
 * request and never stored. A preview of a draft works the same way for any month, which is how a
 * rule is tried before it is published.
 *
 * <h2>Cost</h2>
 * The expensive part is the Athena scan. Lines read for a preview are held for a short while
 * keyed on everything that shapes the query, so editing a percentage and previewing again does not
 * scan the report a second time; the rules are applied in memory.
 */
@Service
public class EnvAllocationRunService {

    private static final Logger logger = LoggerFactory.getLogger(EnvAllocationRunService.class);

    /** Longest range one request may queue. A year of history is what the client asked for. */
    static final int MAX_MONTHS_PER_REQUEST = 12;

    /** A run still QUEUED or RUNNING after this long belongs to an instance that stopped. */
    private static final Duration STALE_AFTER = Duration.ofHours(3);

    private static final Duration LINES_TTL = Duration.ofMinutes(30);
    private static final Duration OPEN_MONTH_LINES_TTL = Duration.ofMinutes(10);
    private static final int LINES_CACHE_SIZE = 12;

    private final EnvAllocationRunRepository runRepository;
    private final EnvAllocationResultRepository resultRepository;
    private final EnvAllocationRuleService ruleService;
    private final EnvAllocationEnvironmentService environmentService;
    private final EnvAllocationAuditService audit;
    private final CloudAccountRepository cloudAccountRepository;
    private final CurSourceResolver curSourceResolver;
    private final CurAllocationLineReader lineReader;
    private final ResourceLabelAssignmentRepository labelAssignments;
    private final ResourceIdResolver idResolver;
    private final Executor executor;
    private final TransactionTemplate tx;

    private final Map<String, CachedLines> linesCache = new ConcurrentHashMap<>();

    public EnvAllocationRunService(EnvAllocationRunRepository runRepository,
                                   EnvAllocationResultRepository resultRepository,
                                   EnvAllocationRuleService ruleService,
                                   EnvAllocationEnvironmentService environmentService,
                                   EnvAllocationAuditService audit,
                                   CloudAccountRepository cloudAccountRepository,
                                   CurSourceResolver curSourceResolver,
                                   CurAllocationLineReader lineReader,
                                   ResourceLabelAssignmentRepository labelAssignments,
                                   ResourceIdResolver idResolver,
                                   @Qualifier("envAllocationExecutor") Executor executor,
                                   PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.ruleService = ruleService;
        this.environmentService = environmentService;
        this.audit = audit;
        this.cloudAccountRepository = cloudAccountRepository;
        this.curSourceResolver = curSourceResolver;
        this.lineReader = lineReader;
        this.labelAssignments = labelAssignments;
        this.idResolver = idResolver;
        this.executor = executor;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── Breakdown ───────────────────────────────────────────────────────────────

    /**
     * What the card shows for one month: the official stored run for a closed month, a live
     * preview with the latest published version for the open one.
     */
    public Breakdown breakdown(Long clientId, String actor, CloudAccount account, YearMonth period,
                               CostBasis basis) {
        environmentService.ensureInitialised(clientId, actor);
        failStaleRuns();
        Optional<VersionSnapshot> latest = ruleService.latest(clientId);
        Integer latestNumber = latest.map(VersionSnapshot::versionNumber).orElse(null);
        String periodKey = period.toString();

        CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(
                account, period.atDay(1), period.atEndOfMonth(), false);
        if (!coverage.covered()) {
            return Breakdown.empty(Status.NO_REPORT, periodKey, basis.wire(), latestNumber, null,
                    coverage.message());
        }

        if (!period.isBefore(YearMonth.now())) {
            if (latest.isEmpty()) {
                return Breakdown.empty(Status.NO_RULES, periodKey, basis.wire(), null, null,
                        "Publish a rule version to see this month split by environment.");
            }
            VersionSnapshot v = latest.get();
            return preview(clientId, account, coverage.source().get(), period, basis, v.rules(),
                    v.environments(), v.versionNumber(), latestNumber,
                    "This month is still open, so it is calculated live with version "
                            + v.versionNumber() + " and is not stored. Run it once the month closes.");
        }

        List<EnvAllocationRun> runs = runRepository
                .findByClientIdAndAwsAccountIdAndBillingPeriodOrderByQueuedAtDesc(
                        clientId, account.getAwsAccountId(), periodKey).stream()
                .filter(r -> basis.wire().equals(r.getCostBasis()))
                .toList();
        RunView pending = runs.stream()
                .filter(r -> EnvAllocationRun.QUEUED.equals(r.getStatus())
                        || EnvAllocationRun.RUNNING.equals(r.getStatus()))
                .findFirst().map(RunView::of).orElse(null);
        Optional<EnvAllocationRun> official = runs.stream()
                .filter(r -> EnvAllocationRun.SUCCEEDED.equals(r.getStatus()) && r.isLocked())
                .findFirst()
                .or(() -> runs.stream().filter(r -> EnvAllocationRun.SUCCEEDED.equals(r.getStatus())).findFirst());

        if (official.isPresent()) {
            return fromRun(official.get(), latestNumber, pending);
        }
        if (pending != null) {
            return Breakdown.empty(Status.RUNNING, periodKey, basis.wire(), latestNumber, pending,
                    "A run for this month is in progress.");
        }
        if (latest.isEmpty()) {
            return Breakdown.empty(Status.NO_RULES, periodKey, basis.wire(), null, null,
                    "Publish a rule version, then run this month.");
        }
        String failed = runs.stream()
                .filter(r -> EnvAllocationRun.FAILED.equals(r.getStatus()))
                .findFirst()
                .map(r -> "The last run of this month failed: " + r.getError())
                .orElse("This month has not been run yet. Run it to store an audit-ready split,"
                        + " or preview it first.");
        return Breakdown.empty(Status.NOT_RUN, periodKey, basis.wire(), latestNumber, null, failed);
    }

    /**
     * Applies rules to a month without storing anything.
     *
     * @param useDraft the saved or posted draft when true, the latest published version when false
     */
    public Breakdown previewRules(Long clientId, String actor, CloudAccount account, YearMonth period,
                                  CostBasis basis, boolean useDraft, List<RuleSpec> postedRules) {
        environmentService.ensureInitialised(clientId, actor);
        CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(
                account, period.atDay(1), period.atEndOfMonth(), false);
        Optional<VersionSnapshot> latest = ruleService.latest(clientId);
        Integer latestNumber = latest.map(VersionSnapshot::versionNumber).orElse(null);
        if (!coverage.covered()) {
            return Breakdown.empty(Status.NO_REPORT, period.toString(), basis.wire(), latestNumber,
                    null, coverage.message());
        }
        if (useDraft) {
            List<RuleSpec> rules = ruleService.previewRules(clientId, actor, postedRules);
            List<EnvironmentSpec> envs = environmentService.specs(clientId, actor);
            return preview(clientId, account, coverage.source().get(), period, basis, rules, envs,
                    null, latestNumber, "Preview of the draft rules. Nothing is stored until you"
                            + " publish and run.");
        }
        VersionSnapshot v = latest.orElseThrow(() -> EnvAllocationException.conflict(
                "Nothing has been published yet."));
        return preview(clientId, account, coverage.source().get(), period, basis, v.rules(),
                v.environments(), v.versionNumber(), latestNumber,
                "Preview with version " + v.versionNumber() + ". Nothing is stored until you run it.");
    }

    private Breakdown preview(Long clientId, CloudAccount account, CurSource source, YearMonth period,
                              CostBasis basis, List<RuleSpec> rules, List<EnvironmentSpec> envs,
                              Integer versionNumber, Integer latestNumber, String message) {
        Computed computed = compute(clientId, account, source, period, basis, rules, envs, true);
        return EnvAllocationViews.build(Status.PREVIEW, period.toString(), basis.wire(), versionNumber,
                latestNumber, null, null, computed.outcome().totalCost(), computed.outcome().rows(),
                envs, rules, computed.missingTagKeys(), message);
    }

    /** A specific stored run as the card would show it — for a run picked from the history. */
    public Breakdown runBreakdown(Long clientId, EnvAllocationRun run) {
        if (!EnvAllocationRun.SUCCEEDED.equals(run.getStatus())) {
            return Breakdown.empty(Status.NOT_RUN, run.getBillingPeriod(), run.getCostBasis(),
                    ruleService.latest(clientId).map(VersionSnapshot::versionNumber).orElse(null),
                    RunView.of(run), run.getError());
        }
        return fromRun(run, ruleService.latest(clientId).map(VersionSnapshot::versionNumber).orElse(null), null);
    }

    private Breakdown fromRun(EnvAllocationRun run, Integer latestNumber, RunView pending) {
        VersionSnapshot v = ruleService.versionById(run.getRuleVersionId());
        List<ResultRow> rows = resultRepository.findByRunIdOrderByIdAsc(run.getId()).stream()
                .map(EnvAllocationRunService::toRow)
                .toList();
        List<String> notes = new ArrayList<>();
        if (latestNumber != null && latestNumber > run.getVersionNumber()) {
            notes.add("Run with version " + run.getVersionNumber() + ". Version " + latestNumber
                    + " has been published since; run the month again to apply it.");
        }
        if (run.getError() != null && !run.getError().isBlank()) {
            // On a finished run this is a warning recorded at run time, e.g. tags not in the report.
            notes.add(run.getError());
        }
        String message = notes.isEmpty() ? null : String.join(" ", notes);
        return EnvAllocationViews.build(Status.RUN, run.getBillingPeriod(), run.getCostBasis(),
                run.getVersionNumber(), latestNumber, RunView.of(run), pending, run.getTotalCost(), rows,
                v.environments(), v.rules(), List.of(), message);
    }

    // ── Queueing and executing runs ─────────────────────────────────────────────

    /**
     * Queues one run per month in {@code from..to} with the latest published version.
     *
     * <p>Refuses the whole request rather than part of it: a range that silently skipped its
     * locked months would read as having re-run them.
     */
    public List<RunView> queue(Long clientId, String actor, CloudAccount account, YearMonth from,
                               YearMonth to, CostBasis basis) {
        environmentService.ensureInitialised(clientId, actor);
        failStaleRuns();
        if (to.isBefore(from)) {
            throw EnvAllocationException.badRequest("The end month is before the start month.");
        }
        if (!to.isBefore(YearMonth.now())) {
            throw EnvAllocationException.badRequest("Only closed months can be run. "
                    + YearMonth.now() + " is still open and is shown as a live preview.");
        }
        int months = (int) (to.getYear() * 12L + to.getMonthValue() - (from.getYear() * 12L + from.getMonthValue())) + 1;
        if (months > MAX_MONTHS_PER_REQUEST) {
            throw EnvAllocationException.badRequest("At most " + MAX_MONTHS_PER_REQUEST
                    + " months per request.");
        }
        VersionSnapshot version = ruleService.latest(clientId).orElseThrow(() ->
                EnvAllocationException.conflict("Publish a rule version before running months."));

        List<String> locked = new ArrayList<>();
        List<String> busy = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            String key = m.toString();
            if (runRepository.existsByClientIdAndAwsAccountIdAndBillingPeriodAndCostBasisAndLockedTrue(
                    clientId, account.getAwsAccountId(), key, basis.wire())) {
                locked.add(key);
            }
            if (runRepository.existsByClientIdAndAwsAccountIdAndBillingPeriodAndCostBasisAndStatusIn(
                    clientId, account.getAwsAccountId(), key, basis.wire(),
                    List.of(EnvAllocationRun.QUEUED, EnvAllocationRun.RUNNING))) {
                busy.add(key);
            }
        }
        if (!locked.isEmpty()) {
            throw EnvAllocationException.conflict("Locked months cannot be re-run: "
                    + String.join(", ", locked) + ". Unlock them first.");
        }
        if (!busy.isEmpty()) {
            throw EnvAllocationException.conflict("Already running: " + String.join(", ", busy) + ".");
        }

        List<EnvAllocationRun> queued = tx.execute(status -> {
            List<EnvAllocationRun> created = new ArrayList<>();
            for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
                EnvAllocationRun run = new EnvAllocationRun();
                run.setClientId(clientId);
                run.setCloudAccountId(account.getId());
                run.setAwsAccountId(account.getAwsAccountId());
                run.setBillingPeriod(m.toString());
                run.setCostBasis(basis.wire());
                run.setRuleVersionId(version.id());
                run.setVersionNumber(version.versionNumber());
                run.setStatus(EnvAllocationRun.QUEUED);
                run.setQueuedAt(LocalDateTime.now());
                run.setStartedBy(actor);
                run.setLocked(false);
                created.add(runRepository.save(run));
            }
            audit.record(clientId, actor, "RUN_QUEUED", "RUN", account.getAwsAccountId(),
                    from + " to " + to + ", version " + version.versionNumber() + ", " + basis.wire());
            List<Long> ids = created.stream().map(EnvAllocationRun::getId).toList();
            // After commit, so the worker can never look for a row that is not visible yet.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(ids);
                }
            });
            return created;
        });
        return queued == null ? List.of() : queued.stream().map(RunView::of).toList();
    }

    private void submit(List<Long> runIds) {
        try {
            executor.execute(() -> runIds.forEach(this::execute));
        } catch (RejectedExecutionException full) {
            logger.warn("[EnvAllocation] Executor full, failing {} queued run(s)", runIds.size());
            runIds.forEach(id -> fail(id, "XamOps is running too many allocations right now."
                    + " Try again in a few minutes."));
        }
    }

    /** Executes one queued run. Never throws: a failure is recorded on the run. */
    public void execute(Long runId) {
        EnvAllocationRun run = tx.execute(status -> {
            EnvAllocationRun r = runRepository.findById(runId).orElse(null);
            if (r == null || !EnvAllocationRun.QUEUED.equals(r.getStatus())) {
                return null;
            }
            r.setStatus(EnvAllocationRun.RUNNING);
            r.setStartedAt(LocalDateTime.now());
            return runRepository.save(r);
        });
        if (run == null) {
            return;
        }

        try {
            CloudAccount account = cloudAccountRepository.findById(run.getCloudAccountId())
                    .orElseThrow(() -> new IllegalStateException("The account is no longer connected."));
            YearMonth period = YearMonth.parse(run.getBillingPeriod());
            CostBasis basis = CostBasis.from(run.getCostBasis());
            CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(
                    account, period.atDay(1), period.atEndOfMonth(), false);
            if (!coverage.covered()) {
                throw new IllegalStateException(coverage.message());
            }
            CurSource source = coverage.source().get();
            VersionSnapshot version = ruleService.versionById(run.getRuleVersionId());

            Computed computed = compute(run.getClientId(), account, source, period, basis,
                    version.rules(), version.environments(), false);
            Outcome outcome = computed.outcome();

            tx.executeWithoutResult(status -> {
                List<EnvAllocationResult> rows = new ArrayList<>(outcome.rows().size());
                for (ResultRow r : outcome.rows()) {
                    rows.add(toEntity(run.getId(), r));
                }
                resultRepository.saveAll(rows);

                EnvAllocationRun r = runRepository.findById(run.getId()).orElseThrow();
                r.setStatus(EnvAllocationRun.SUCCEEDED);
                r.setFinishedAt(LocalDateTime.now());
                r.setTotalCost(outcome.totalCost());
                r.setAllocatedCost(outcome.allocatedCost());
                r.setUnallocatedCost(outcome.unallocatedCost());
                r.setLineCount(outcome.lineCount());
                r.setCurSourceId(source.getId());
                r.setError(computed.missingTagKeys().isEmpty() ? null
                        : "Tags not in the report, so their rules matched nothing: "
                                + String.join(", ", computed.missingTagKeys()));
                runRepository.save(r);
                audit.record(r.getClientId(), "system", "RUN_SUCCEEDED", "RUN", String.valueOf(r.getId()),
                        r.getAwsAccountId() + " " + r.getBillingPeriod() + " v" + r.getVersionNumber()
                                + ": total " + outcome.totalCost().toPlainString()
                                + ", unallocated " + outcome.unallocatedCost().toPlainString());
            });
            logger.info("✅ [EnvAllocation] Run {} for {} {} — {} lines, total {}, unallocated {}",
                    run.getId(), run.getAwsAccountId(), run.getBillingPeriod(), outcome.lineCount(),
                    outcome.totalCost(), outcome.unallocatedCost());
        } catch (Exception e) {
            logger.warn("[EnvAllocation] Run {} failed: {}", runId, e.getMessage());
            fail(runId, failureMessage(e));
        }
    }

    private void fail(Long runId, String message) {
        try {
            tx.executeWithoutResult(status -> runRepository.findById(runId).ifPresent(r -> {
                r.setStatus(EnvAllocationRun.FAILED);
                r.setFinishedAt(LocalDateTime.now());
                r.setError(message);
                runRepository.save(r);
                audit.record(r.getClientId(), "system", "RUN_FAILED", "RUN", String.valueOf(runId), message);
            }));
        } catch (Exception e) {
            logger.error("[EnvAllocation] Could not record failure of run {}: {}", runId, e.getMessage());
        }
    }

    private static String failureMessage(Exception e) {
        if (e instanceof ReportCannotAnswerException r) {
            return r.getMessage();
        }
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 2000 ? m.substring(0, 2000) : m;
    }

    /** Marks runs abandoned by a restart as failed, so the month can be run again. */
    public void failStaleRuns() {
        List<EnvAllocationRun> stale = runRepository.findByStatusInAndQueuedAtBefore(
                List.of(EnvAllocationRun.QUEUED, EnvAllocationRun.RUNNING),
                LocalDateTime.now().minus(STALE_AFTER));
        for (EnvAllocationRun r : stale) {
            fail(r.getId(), "Interrupted: XamOps restarted before this run finished. Run the month again.");
        }
    }

    // ── History, locking ────────────────────────────────────────────────────────

    public List<RunView> history(Long clientId, CloudAccount account) {
        failStaleRuns();
        return runRepository.findTop100ByClientIdAndAwsAccountIdOrderByQueuedAtDesc(
                clientId, account.getAwsAccountId()).stream().map(RunView::of).toList();
    }

    /** The run, if it belongs to this client. Account entitlement is the caller's check. */
    public EnvAllocationRun run(Long clientId, Long runId) {
        return runRepository.findByIdAndClientId(runId, clientId)
                .orElseThrow(() -> EnvAllocationException.notFound("Run not found."));
    }

    public List<EnvAllocationResult> results(Long runId) {
        return resultRepository.findByRunIdOrderByIdAsc(runId);
    }

    /**
     * Marks a run as the month's official figure. One locked run per month and basis, and a
     * locked month cannot be re-run until it is unlocked.
     */
    public RunView lock(Long clientId, String actor, Long runId) {
        return tx.execute(status -> {
            EnvAllocationRun r = run(clientId, runId);
            if (!EnvAllocationRun.SUCCEEDED.equals(r.getStatus())) {
                throw EnvAllocationException.conflict("Only a finished run can be locked.");
            }
            if (r.isLocked()) {
                return RunView.of(r);
            }
            if (runRepository.existsByClientIdAndAwsAccountIdAndBillingPeriodAndCostBasisAndLockedTrue(
                    clientId, r.getAwsAccountId(), r.getBillingPeriod(), r.getCostBasis())) {
                throw EnvAllocationException.conflict("Another run of " + r.getBillingPeriod()
                        + " is already locked. Unlock it first.");
            }
            r.setLocked(true);
            r.setLockedAt(LocalDateTime.now());
            r.setLockedBy(actor);
            runRepository.save(r);
            audit.record(clientId, actor, "RUN_LOCKED", "RUN", String.valueOf(runId),
                    r.getAwsAccountId() + " " + r.getBillingPeriod() + " v" + r.getVersionNumber());
            return RunView.of(r);
        });
    }

    public RunView unlock(Long clientId, String actor, Long runId) {
        return tx.execute(status -> {
            EnvAllocationRun r = run(clientId, runId);
            if (!r.isLocked()) {
                return RunView.of(r);
            }
            r.setLocked(false);
            r.setLockedAt(null);
            r.setLockedBy(null);
            runRepository.save(r);
            audit.record(clientId, actor, "RUN_UNLOCKED", "RUN", String.valueOf(runId),
                    r.getAwsAccountId() + " " + r.getBillingPeriod() + " v" + r.getVersionNumber());
            return RunView.of(r);
        });
    }

    // ── Computing ───────────────────────────────────────────────────────────────

    private record Computed(Outcome outcome, List<String> missingTagKeys) {
    }

    private Computed compute(Long clientId, CloudAccount account, CurSource source, YearMonth period,
                             CostBasis basis, List<RuleSpec> rules, List<EnvironmentSpec> envs,
                             boolean cacheable) {
        Set<String> tagKeys = new TreeSet<>();
        boolean byResource = false;
        boolean needsLabels = false;
        for (RuleSpec r : rules) {
            if (r.field() == MatchField.TAG && r.tagKey() != null) {
                tagKeys.add(r.tagKey());
            }
            if (r.field() == MatchField.RESOURCE_ID || r.field() == MatchField.LABEL) {
                byResource = true;
            }
            if (r.field() == MatchField.LABEL) {
                needsLabels = true;
            }
        }

        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        Duration ttl = !cacheable ? null
                : period.isBefore(YearMonth.now()) ? LINES_TTL : OPEN_MONTH_LINES_TTL;
        CurAllocationLineReader.Lines lines = readLines(source, account, start, end, basis, tagKeys,
                byResource, ttl);

        Map<String, Set<String>> labels = needsLabels ? labelsByCurId(clientId, account) : Map.of();
        List<CostLine> costLines = new ArrayList<>(lines.lines().size());
        for (CurAllocationLineReader.Line l : lines.lines()) {
            Set<String> lineLabels = l.resourceId().isEmpty()
                    ? Set.of()
                    : labels.getOrDefault(l.resourceId(), Set.of());
            costLines.add(new CostLine(l.account(), l.service(), l.region(), l.usageType(),
                    l.chargeType(), l.resourceId(), l.tags(), lineLabels, l.cost()));
        }
        Outcome outcome = EnvAllocationEngine.allocate(costLines, rules, envs);
        return new Computed(outcome, lines.missingTagKeys());
    }

    /**
     * Reads a month's lines, through a short cache for previews.
     *
     * <p>Stored runs pass no TTL and always read the report fresh, so an audit figure never comes
     * from a cache. The open month is held for less time than a closed one, because the report
     * is redelivered through the day.
     */
    private CurAllocationLineReader.Lines readLines(CurSource source, CloudAccount account,
                                                    LocalDate start, LocalDate end, CostBasis basis,
                                                    Set<String> tagKeys, boolean byResource,
                                                    Duration ttl) {
        if (ttl == null) {
            return lineReader.read(source, account, start, end, basis, tagKeys, byResource);
        }
        String key = source.getId() + "|" + account.getAwsAccountId() + "|" + start + "|"
                + basis.wire() + "|" + byResource + "|" + String.join(",", tagKeys);
        CachedLines hit = linesCache.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.lines();
        }
        CurAllocationLineReader.Lines lines = lineReader.read(source, account, start, end, basis,
                tagKeys, byResource);
        linesCache.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
        while (linesCache.size() >= LINES_CACHE_SIZE) {
            linesCache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> linesCache.remove(e.getKey()));
        }
        linesCache.put(key, new CachedLines(lines, Instant.now().plus(ttl)));
        return lines;
    }

    private record CachedLines(CurAllocationLineReader.Lines lines, Instant expiresAt) {
    }

    /**
     * CUR resource id to the names of the client's labels on that resource.
     *
     * <p>A label is stored under the inventory's id for a resource, while the report may name the
     * same resource by its ARN. The resolver supplies every id the report could use for each
     * labelled resource, so a label rule finds the resource however the report spells it.
     */
    private Map<String, Set<String>> labelsByCurId(Long clientId, CloudAccount account) {
        List<ResourceLabelAssignment> assignments =
                labelAssignments.findByCloudAccountIdForClient(account.getId(), clientId);
        if (assignments.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ResourceLabelAssignment a : assignments) {
            if (a.getResourceId() != null && !a.getResourceId().isBlank()) {
                ids.add(a.getResourceId());
            }
        }
        ResourceIdResolver.CurIdentifiers candidates = idResolver.curIdentifiers(account.getId(), ids);

        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (ResourceLabelAssignment a : assignments) {
            String rid = a.getResourceId();
            if (rid == null || rid.isBlank()) {
                continue;
            }
            Set<String> forms = new HashSet<>(candidates.byRequestedId().getOrDefault(rid, List.of()));
            forms.add(rid);
            for (String form : forms) {
                out.computeIfAbsent(form, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                        .add(a.getLabel().getName());
            }
        }
        return out;
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────

    private static EnvAllocationResult toEntity(Long runId, ResultRow r) {
        EnvAllocationResult e = new EnvAllocationResult();
        e.setRunId(runId);
        e.setEnvironmentName(r.environmentName());
        e.setEnvironmentColor(r.environmentColor());
        e.setAllocationKind(r.kind().name());
        e.setRuleId(r.ruleId());
        e.setRuleName(truncate(r.ruleName(), 120));
        e.setRulePosition(r.rulePosition());
        e.setService(truncate(r.service(), 255));
        e.setUsageType(truncate(r.usageType(), 255));
        e.setRegion(truncate(r.region(), 64));
        e.setChargeType(truncate(r.chargeType(), 64));
        e.setResourceId(truncate(r.resourceId(), 512));
        e.setMatchDetail(truncate(r.matchDetail(), 255));
        e.setPercent(r.percent());
        e.setSourceCost(r.sourceCost());
        e.setAmount(r.amount());
        e.setLineCount(r.lineCount());
        return e;
    }

    static ResultRow toRow(EnvAllocationResult e) {
        return new ResultRow(e.getEnvironmentName(), e.getEnvironmentColor(),
                AllocationKind.valueOf(e.getAllocationKind()), e.getRuleId(), e.getRuleName(),
                e.getRulePosition(), e.getService(), e.getUsageType(), e.getRegion(), e.getChargeType(),
                e.getResourceId(), e.getMatchDetail(), e.getPercent(), e.getSourceCost(), e.getAmount(),
                e.getLineCount());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
