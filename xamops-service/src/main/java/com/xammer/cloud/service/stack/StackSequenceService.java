package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.xammer.cloud.service.stack.CurStackService.CurApplyResult;
import com.xammer.cloud.service.stack.CurStackService.CurProgress;
import com.xammer.cloud.service.stack.CurStackService.CurStatus;
import com.xammer.cloud.service.stack.StackDiagnosisService.Diagnosis;
import com.xammer.cloud.service.stack.StackDiagnosisService.Verdict;
import com.xammer.cloud.service.stack.StackUpdateService.ApplyResult;
import com.xammer.cloud.service.stack.StackUpdateService.StackUpdateException;

/**
 * Drives the one-click "Update stack" as a server-side, stateful, sequenced background job.
 *
 * <p>A single Apply click starts a job that: (phase 1) executes the connection change-set and
 * polls {@code DescribeStacks} on the connection stack to a terminal CloudFormation state; then
 * (phase 2) — regardless of whether phase 1 succeeded or failed — runs the CUR create/update,
 * retrying internally on {@code AccessDenied} while the grant propagates, up to a bounded number
 * of attempts. {@link #start} returns the 202 seed immediately; {@link #progress} reads the
 * in-memory job state into a two-phase view with an overall {@code done} flag.
 *
 * <p>This service owns the sequencing only — it reuses {@link StackUpdateService#execute},
 * {@link CurStackService#applyAsync}/{@link CurStackService#progress} and
 * {@link StackDiagnosisService#diagnose} rather than re-implementing any CloudFormation or CUR
 * logic. Job state is in-memory only, keyed by AWS account id.
 */
@Service
public class StackSequenceService {

    private static final Logger logger = LoggerFactory.getLogger(StackSequenceService.class);

    private final StackUpdateService stackUpdateService;
    private final StackDiagnosisService stackDiagnosisService;
    private final CurStackService curStackService;
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final Executor stackSequenceExecutor;

    /**
     * Fallback region when neither the diagnosis nor the account carries one. MUST match the
     * value {@link StackUpdateService}/{@link StackDiagnosisService} use, so phase-1 execute and
     * poll resolve to one identical region even for a null-everywhere account.
     */
    private final String defaultRegion;

    // ---- bounds, injectable so tests run without real sleeps ----
    private long connectionPollMs = 10_000;
    private int connectionPollAttempts = 108;      // ~18 min at 10 s
    private int connStartgateGraceTicks = 6;       // ~60 s
    private int curRetryAttempts = 6;
    private long curRetryBackoffMs = 12_000;
    private int curSettleAttempts = 90;            // ~15 min at 10 s
    private long curSettleMs = 10_000;

    /** account AWS id -> live job snapshot. */
    private final ConcurrentHashMap<String, SequenceJob> jobs = new ConcurrentHashMap<>();

    public StackSequenceService(StackUpdateService stackUpdateService,
                                StackDiagnosisService stackDiagnosisService,
                                CurStackService curStackService,
                                AwsClientProvider awsClientProvider,
                                CloudAccountRepository cloudAccountRepository,
                                @Qualifier("stackSequenceExecutor") Executor stackSequenceExecutor,
                                @Value("${aws.region:ap-south-1}") String defaultRegion) {
        this.stackUpdateService = stackUpdateService;
        this.stackDiagnosisService = stackDiagnosisService;
        this.curStackService = curStackService;
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.stackSequenceExecutor = stackSequenceExecutor;
        this.defaultRegion = defaultRegion;
    }

    // ================= enums & predicates =================

    /** The connection phase's own state. {@code UPDATING} is the only non-terminal value. */
    enum ConnPhase { NO_CHANGES, UPDATING, COMPLETED, FAILED, TIMED_OUT }

    /** The CUR phase's own state. The three running/waiting values are the only non-terminal ones. */
    enum CurPhase {
        WAITING_FOR_CONNECTION, CREATING, UPDATING, RETRYING_PERMISSION,
        COMPLETED, NO_CHANGES, SKIPPED, NO_PERMISSION_TERMINAL, FAILED, BUSY
    }

    /** Classification of a raw CloudFormation status while polling the connection stack. */
    enum PollVerdict { COMPLETED, FAILED, POLL_AGAIN }

    /**
     * Terminal set declared once (finding 6): everything except {@code UPDATING}. {@code done},
     * the flat {@code inProgress}/{@code failed}, and the outer catch all call this.
     */
    static boolean isConnTerminal(ConnPhase p) {
        return p == ConnPhase.NO_CHANGES || p == ConnPhase.COMPLETED
                || p == ConnPhase.FAILED || p == ConnPhase.TIMED_OUT;
    }

    /**
     * Terminal set declared once (finding 6): everything except the three running/waiting states.
     * Terminal set = {COMPLETED, NO_CHANGES, SKIPPED, NO_PERMISSION_TERMINAL, FAILED, BUSY}.
     */
    static boolean isCurTerminal(CurPhase p) {
        return !(p == CurPhase.WAITING_FOR_CONNECTION
                || p == CurPhase.CREATING
                || p == CurPhase.UPDATING
                || p == CurPhase.RETRYING_PERMISSION);
    }

    /**
     * Ordered, explicit raw-status classifier for the connection stack (finding 3 of the prior
     * pass). Failure first (so a {@code *ROLLBACK*_CLEANUP_IN_PROGRESS} alias is FAILED), then an
     * equality success set (so the success cleanup alias is COMPLETED, not caught by the
     * {@code _IN_PROGRESS} rule), then the generic in-progress poll-again, then a conservative
     * failure default.
     */
    static PollVerdict classifyConnection(String s) {
        if (s == null) {
            return PollVerdict.FAILED;
        }
        // 1) FAILURE WINS over any _IN_PROGRESS cleanup alias.
        if (s.contains("ROLLBACK") || s.endsWith("_FAILED")) {
            return PollVerdict.FAILED;
        }
        // 2) Exact success states (equality, NOT endsWith) — includes the success cleanup alias.
        if (s.equals("UPDATE_COMPLETE")
                || s.equals("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS")
                || s.equals("CREATE_COMPLETE")
                || s.equals("IMPORT_COMPLETE")) {
            return PollVerdict.COMPLETED;
        }
        // 3) Any other in-progress status — keep polling.
        if (s.endsWith("_IN_PROGRESS")) {
            return PollVerdict.POLL_AGAIN;
        }
        // 4) Any other settled status (e.g. DELETE_COMPLETE) — cannot host the grant, treat as failure.
        return PollVerdict.FAILED;
    }

    /** Whether the stack's lastUpdatedTime advanced past the captured baseline. */
    static boolean advanced(Instant baseline, Instant current) {
        if (current == null) {
            return false;    // no current timestamp — cannot confirm movement
        }
        if (baseline == null) {
            return true;     // had no baseline, now has one → moved
        }
        return current.isAfter(baseline);
    }

    // ================= job model =================

    /**
     * Mutable per-account job snapshot. Mutated only on the sequence worker thread, read by
     * {@link #progress} via volatile fields.
     */
    static final class SequenceJob {
        final String awsAccountId;
        final String stackName;          // connection stack display name
        final String stackId;            // connection stack id/arn for describe calls
        final String region;             // connection stack region (single-sourced from diagnose)
        volatile ConnPhase connStatus;
        volatile String connMessage;     // human message for the connection line
        volatile String connRemedy;      // grant/remedy hint for a worker-side execute AccessDenied
        volatile String lastConnStatusRaw; // last raw CFN status while polling; null until first describe
        volatile CurPhase curStatus;
        volatile String curMessage;
        volatile int curAttempt;         // 0-based retry counter
        volatile Instant startedAt;
        final boolean connectionChangeSetRan;

        // --- phase-1 start-gate state ---
        volatile Instant connBaselineUpdatedAt; // stack.lastUpdatedTime captured BEFORE executeChangeSet
        volatile boolean connSawInProgress;     // true once the stack is observed *_IN_PROGRESS

        SequenceJob(String awsAccountId, String stackName, String stackId, String region,
                    ConnPhase connStatus, CurPhase curStatus, boolean connectionChangeSetRan) {
            this.awsAccountId = awsAccountId;
            this.stackName = stackName;
            this.stackId = stackId;
            this.region = region;
            this.connStatus = connStatus;
            this.curStatus = curStatus;
            this.connectionChangeSetRan = connectionChangeSetRan;
            this.startedAt = Instant.now();
        }

        boolean isDone() {
            return isConnTerminal(connStatus) && isCurTerminal(curStatus);
        }
    }

    // ================= public API =================

    /**
     * Starts the sequenced job on the request thread (non-blocking) and returns the 202 seed.
     *
     * @throws StackUpdateException NO_CFN_PERMISSION (pre-flight), SEQUENCE_IN_PROGRESS (a job is
     *                              already running for the account), or SEQUENCE_CAPACITY (pool full)
     */
    public ApplyResult start(CloudAccount account, String changeSetId) {
        String jobKey = account.getAwsAccountId();

        // 1) CFN permission pre-flight AND authoritative region source. diagnose persists
        //    stackRegion/stackId/stackName onto the account (when stackId != null), which is how
        //    execute and the poll end up on the same region.
        Diagnosis diagnosis = stackDiagnosisService.diagnose(account);
        if (diagnosis.verdict() == Verdict.NO_CFN_PERMISSION) {
            // Throw BEFORE registering/submitting so addGrant still fires on the request thread.
            throw new StackUpdateException("NO_CFN_PERMISSION", diagnosis.detail(), diagnosis.remedy());
        }

        // 2/3) Single-source region/stack from the diagnosis, falling back to account/default only
        //       when null. For STACK_DELETED/NOT_STACK_MANAGED/some verdicts the account region is
        //       NOT refreshed, so execute and poll regions may differ — acceptable: phase 1 records
        //       FAILED and phase 2 still runs.
        String region = diagnosis.stackRegion() != null ? diagnosis.stackRegion()
                : (account.getStackRegion() != null ? account.getStackRegion() : defaultRegion);
        String stackId = diagnosis.stackId() != null ? diagnosis.stackId()
                : (account.getStackId() != null ? account.getStackId() : account.getStackName());
        String stackName = diagnosis.stackName() != null ? diagnosis.stackName() : account.getStackName();

        boolean connectionChangeSetRan = changeSetId != null && !changeSetId.isBlank();

        // 4) Build the seeded job WITHOUT touching the map yet.
        SequenceJob seeded = new SequenceJob(jobKey, stackName, stackId, region,
                connectionChangeSetRan ? ConnPhase.UPDATING : ConnPhase.NO_CHANGES,
                CurPhase.WAITING_FOR_CONNECTION, connectionChangeSetRan);

        // 5) Atomic single-job guard (NFR-1): reject if present and not done, else register.
        jobs.compute(jobKey, (k, existing) -> {
            if (existing != null && !existing.isDone()) {
                throw new StackUpdateException("SEQUENCE_IN_PROGRESS",
                        "An update is already running for this account; wait for it to finish.", null);
            }
            return seeded;
        });

        // 6) Seed ApplyResult the modal's CUR line uses before the first poll.
        String curStackName = curStackService.resolveStackName(account);
        CurApplyResult seedCur = new CurApplyResult(CurStatus.WAITING_FOR_CONNECTION, curStackName,
                "Waiting for the connection stack to finish before the CUR stack runs.");
        ApplyResult seed = new ApplyResult(connectionChangeSetRan, seedCur);

        // 7) Submit and return. A full pool is a clean 409, never a blocked request.
        try {
            stackSequenceExecutor.execute(() -> runSequence(jobKey, account, changeSetId));
        } catch (RejectedExecutionException rex) {
            jobs.remove(jobKey, seeded); // undo the step-5 registration so the account is not stuck "in progress"
            throw new StackUpdateException("SEQUENCE_CAPACITY",
                    "XamOps is already running the maximum number of stack updates. Try again in a few minutes.",
                    null);
        }
        return seed;
    }

    /**
     * A fast two-phase progress read. Uses the in-memory job snapshot when one exists (sequenced
     * branch), otherwise falls back to the legacy live read (fallback branch).
     */
    public SequenceProgress progress(CloudAccount account, int eventLimit) {
        SequenceJob job = jobs.get(account.getAwsAccountId());
        if (job != null) {
            List<StackUpdateService.EventLine> events = fetchConnectionEvents(account, job, eventLimit);
            return SequenceProgress.sequenced(job, events);
        }
        // Fallback: no job — legacy live read, degrades gracefully (server restart / no active job).
        return fallbackProgress(account, eventLimit);
    }

    private SequenceProgress fallbackProgress(CloudAccount account, int eventLimit) {
        StackUpdateService.Progress connProgress = stackUpdateService.progress(account, eventLimit);
        CurProgress cur = curStackService.progress(account);
        return SequenceProgress.fallback(connProgress, cur);
    }

    private List<StackUpdateService.EventLine> fetchConnectionEvents(CloudAccount account,
                                                                     SequenceJob job, int eventLimit) {
        try {
            CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, job.region);
            return cfn.describeStackEvents(DescribeStackEventsRequest.builder()
                            .stackName(job.stackId).build())
                    .stackEvents().stream()
                    .limit(eventLimit)
                    .map(StackSequenceService::toLine)
                    .toList();
        } catch (Exception transientErr) {
            logger.debug("[seq] {} event read failed: {}", account.getAwsAccountId(), transientErr.getMessage());
            return List.of();
        }
    }

    private static StackUpdateService.EventLine toLine(StackEvent e) {
        return new StackUpdateService.EventLine(String.valueOf(e.timestamp()), e.logicalResourceId(),
                e.resourceStatusAsString(), e.resourceStatusReason() == null ? "" : e.resourceStatusReason());
    }

    // ================= the background state machine =================

    private void runSequence(String key, CloudAccount account, String changeSetId) {
        SequenceJob job = jobs.get(key);
        if (job == null) {
            return; // registration was removed (pool full) — nothing to run
        }
        try {
            runPhases(account, changeSetId, job);
        } catch (Throwable t) {
            logger.warn("[seq] {} sequence failed", account.getAwsAccountId(), t);
            // curStatus set UNCONDITIONALLY terminal (it may still be WAITING_FOR_CONNECTION / …).
            job.curStatus = CurPhase.FAILED;
            job.curMessage = "The stack update did not finish: " + rootMessage(t);
            // connStatus forced terminal ONLY if not already terminal, preserving a real COMPLETED
            // result and its event log.
            if (!isConnTerminal(job.connStatus)) {
                job.connStatus = ConnPhase.FAILED;
            }
        }
    }

    private void runPhases(CloudAccount account, String changeSetId, SequenceJob job) {
        // ---------- Phase 1 — connection ----------
        if (!job.connectionChangeSetRan) {
            job.connStatus = ConnPhase.NO_CHANGES;
            logger.info("[seq] {} conn NO_CHANGES (CUR-only apply)", account.getAwsAccountId());
        } else {
            runConnectionPhase(account, changeSetId, job);
        }

        // ---------- Phase 2 — CUR (runs regardless of phase 1 outcome) ----------
        runCurPhase(account, job);
    }

    private void runConnectionPhase(CloudAccount account, String changeSetId, SequenceJob job) {
        job.connStatus = ConnPhase.UPDATING;
        CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, job.region);

        // Capture the start-gate baseline BEFORE executing the change-set (finding 1). Wrapped in
        // a transient-tolerant try/catch: on failure set baseline null and FALL THROUGH — never
        // propagate to the outer catch (that would skip phase 2).
        try {
            Stack baselineStack = cfn.describeStacks(DescribeStacksRequest.builder()
                    .stackName(job.stackId).build()).stacks().get(0);
            job.connBaselineUpdatedAt = baselineStack.lastUpdatedTime();
        } catch (Exception transientErr) {
            logger.debug("[seq] {} baseline describe failed: {}", account.getAwsAccountId(),
                    transientErr.getMessage());
            job.connBaselineUpdatedAt = null;
        }

        // Execute the change-set, classifying an execute-time AccessDenied (finding 4).
        try {
            stackUpdateService.execute(account, changeSetId);
        } catch (RuntimeException ex) {
            if (isAccessDenied(ex)) {
                job.connStatus = ConnPhase.FAILED;
                job.connMessage = "XamOps could not execute the connection change-set — the stack-updater "
                        + "role (XamOpsStackUpdaterRole) is missing or lacks permission in this account.";
                job.connRemedy = "Deploy/refresh the stack-updater grant, then re-run Update stack. "
                        + "Use Diagnose (stack-status) to get the grant link.";
                logger.warn("[seq] {} conn execute AccessDenied: {}", account.getAwsAccountId(),
                        rootMessage(ex));
            } else {
                job.connStatus = ConnPhase.FAILED;
                job.connMessage = rootMessage(ex);
                logger.warn("[seq] {} conn execute failed: {}", account.getAwsAccountId(),
                        rootMessage(ex));
            }
            return; // skip the poll — the change-set never started — and fall through to phase 2
        }

        // Poll DescribeStacks to terminal, applying the start-gate on every tick.
        for (int tick = 0; tick < connectionPollAttempts; tick++) {
            Stack stack;
            try {
                stack = cfn.describeStacks(DescribeStacksRequest.builder()
                        .stackName(job.stackId).build()).stacks().get(0);
            } catch (Exception transientErr) {
                logger.debug("[seq] {} conn describe failed (tick {}): {}", account.getAwsAccountId(),
                        tick, transientErr.getMessage());
                sleep(connectionPollMs);
                continue; // transient read — does not abort (AC-14)
            }
            String raw = stack.stackStatusAsString();
            job.lastConnStatusRaw = raw;
            if (raw != null && raw.endsWith("_IN_PROGRESS")) {
                job.connSawInProgress = true;
            }

            boolean moved = job.connSawInProgress
                    || advanced(job.connBaselineUpdatedAt, stack.lastUpdatedTime());
            PollVerdict v = classifyConnection(raw);

            if (v != PollVerdict.POLL_AGAIN && moved) {
                job.connStatus = (v == PollVerdict.COMPLETED) ? ConnPhase.COMPLETED : ConnPhase.FAILED;
                logger.info("[seq] {} conn terminal {} (raw {})", account.getAwsAccountId(),
                        job.connStatus, raw);
                return;
            }
            // terminal-but-not-moved (stale prior status): accept as no-op COMPLETED after grace.
            if (v != PollVerdict.POLL_AGAIN && !moved && tick >= connStartgateGraceTicks) {
                job.connStatus = ConnPhase.COMPLETED;
                logger.info("[seq] {} conn accepted as no-op after grace (raw {})",
                        account.getAwsAccountId(), raw);
                return;
            }
            sleep(connectionPollMs);
        }
        // Loop fell through without a terminal verdict → timed out.
        job.connStatus = ConnPhase.TIMED_OUT;
        logger.warn("[seq] {} conn poll timed out", account.getAwsAccountId());
    }

    private void runCurPhase(CloudAccount account, SequenceJob job) {
        String curStackName = curStackService.resolveStackName(account);
        boolean enteredSettle = false;

        for (int attempt = 0; attempt < curRetryAttempts; attempt++) {
            job.curAttempt = attempt;
            CurApplyResult r = curStackService.applyAsync(account, job.connectionChangeSetRan);
            switch (r.curStatus()) {
                case CREATE_IN_PROGRESS:
                    job.curStatus = CurPhase.CREATING;
                    job.curMessage = r.message();
                    enteredSettle = true;
                    break;
                case UPDATE_IN_PROGRESS:
                    job.curStatus = CurPhase.UPDATING;
                    job.curMessage = r.message();
                    enteredSettle = true;
                    break;
                case NO_CHANGES:
                    job.curStatus = CurPhase.NO_CHANGES;
                    job.curMessage = r.message();
                    return;
                case SKIPPED:
                    job.curStatus = CurPhase.SKIPPED;
                    job.curMessage = r.message();
                    return;
                case BUSY:
                    job.curStatus = CurPhase.BUSY;
                    job.curMessage = r.message();
                    return;
                case FAILED:
                    job.curStatus = CurPhase.FAILED;
                    job.curMessage = r.message();
                    return;
                case PENDING_PERMISSION: {
                    // Rolled-back-first-create guard: distinguish "grant not propagated yet" from
                    // "CUR stack is in a rolled-back/failed state updateStack cannot recover".
                    CurProgress p = curStackService.progress(account);
                    if (p != null && p.failed()) {
                        job.curStatus = CurPhase.FAILED;
                        job.curMessage = "The CUR stack is in a failed/rolled-back state (" + p.status()
                                + ") that cannot be updated. Delete the " + curStackName
                                + " stack in us-east-1 and re-run Update stack.";
                        logger.warn("[seq] {} CUR rolled-back ({}), not retrying",
                                account.getAwsAccountId(), p.status());
                        return;
                    }
                    if (attempt < curRetryAttempts - 1 && job.connectionChangeSetRan) {
                        job.curStatus = CurPhase.RETRYING_PERMISSION;
                        logger.warn("[seq] {} CUR AccessDenied, retry {} of {}",
                                account.getAwsAccountId(), attempt + 1, curRetryAttempts);
                        sleep(curRetryBackoffMs);
                        continue;
                    }
                    job.curStatus = CurPhase.NO_PERMISSION_TERMINAL;
                    job.curMessage = "XamOps still could not manage the CUR stack after the connection "
                            + "update; the CUR grant has not propagated. Re-run Update stack in a few "
                            + "minutes if the CUR report is required.";
                    return;
                }
                case NO_PERMISSION_TERMINAL:
                    job.curStatus = CurPhase.NO_PERMISSION_TERMINAL;
                    job.curMessage = r.message();
                    return;
                case WAITING_FOR_CONNECTION:
                default:
                    // applyAsync never returns WAITING_FOR_CONNECTION; treat anything unexpected as a retry-exit.
                    job.curStatus = CurPhase.FAILED;
                    job.curMessage = r.message();
                    return;
            }
            if (enteredSettle) {
                break;
            }
        }

        if (enteredSettle) {
            curSettleWatch(account, job);
        }
    }

    /** Polls the CUR stack to its own terminal state (FR-6). */
    private void curSettleWatch(CloudAccount account, SequenceJob job) {
        for (int tick = 0; tick < curSettleAttempts; tick++) {
            CurProgress p = curStackService.progress(account);
            if (p == null) {
                sleep(curSettleMs);
                continue; // transient read failure — keep polling (AC-14)
            }
            if (p.inProgress()) {
                sleep(curSettleMs);
                continue;
            }
            job.curStatus = p.failed() ? CurPhase.FAILED : CurPhase.COMPLETED;
            logger.info("[seq] {} CUR settled {} (status {})", account.getAwsAccountId(),
                    job.curStatus, p.status());
            return;
        }
        if (!isCurTerminal(job.curStatus)) {
            job.curStatus = CurPhase.FAILED;
            job.curMessage = "The CUR stack did not finish within the expected window.";
            logger.warn("[seq] {} CUR settle watch timed out", account.getAwsAccountId());
        }
    }

    // ================= helpers =================

    /** Same message-matching StackDiagnosisService uses — duplicated to keep that class untouched. */
    private boolean isAccessDenied(Throwable e) {
        String m = rootMessage(e);
        return m != null && (m.contains("AccessDenied") || m.contains("not authorized")
                || m.contains("is not authorized to perform"));
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    // ================= progress response =================

    /**
     * The two-phase progress snapshot. Built either from a live job (sequenced branch) or from a
     * legacy live read (fallback branch). {@link #toResponseMap()} produces the exact JSON the
     * controller returns.
     */
    public static final class SequenceProgress {
        // connection
        private final String status;            // last raw CFN status, or a phase label when none
        private final ConnPhase connectionPhase;
        private final boolean connInProgress;
        private final boolean connFailed;
        private final String connectionMessage;
        private final String connectionRemedy;
        private final List<StackUpdateService.EventLine> events;
        // cur
        private final CurPhase curPhase;        // null in the fallback branch
        private final String curStatusRaw;      // used only in the fallback branch
        private final boolean curInProgress;
        private final boolean curFailed;
        private final String curMessage;
        private final Integer curAttempt;       // null → omit the key (fallback branch, finding 7)
        private final boolean curNull;          // fallback branch: cur sub-object is null
        // overall
        private final boolean done;

        private SequenceProgress(String status, ConnPhase connectionPhase, boolean connInProgress,
                                 boolean connFailed, String connectionMessage, String connectionRemedy,
                                 List<StackUpdateService.EventLine> events, CurPhase curPhase,
                                 String curStatusRaw, boolean curInProgress, boolean curFailed,
                                 String curMessage, Integer curAttempt, boolean curNull, boolean done) {
            this.status = status;
            this.connectionPhase = connectionPhase;
            this.connInProgress = connInProgress;
            this.connFailed = connFailed;
            this.connectionMessage = connectionMessage;
            this.connectionRemedy = connectionRemedy;
            this.events = events;
            this.curPhase = curPhase;
            this.curStatusRaw = curStatusRaw;
            this.curInProgress = curInProgress;
            this.curFailed = curFailed;
            this.curMessage = curMessage;
            this.curAttempt = curAttempt;
            this.curNull = curNull;
            this.done = done;
        }

        static SequenceProgress sequenced(SequenceJob job, List<StackUpdateService.EventLine> events) {
            boolean connInProgress = (job.connStatus == ConnPhase.UPDATING);
            boolean connFailed = (job.connStatus == ConnPhase.FAILED || job.connStatus == ConnPhase.TIMED_OUT);
            String status = job.lastConnStatusRaw != null ? job.lastConnStatusRaw : job.connStatus.name();
            boolean curInProgress = (job.curStatus == CurPhase.CREATING
                    || job.curStatus == CurPhase.UPDATING
                    || job.curStatus == CurPhase.RETRYING_PERMISSION);
            boolean curFailed = (job.curStatus == CurPhase.FAILED
                    || job.curStatus == CurPhase.NO_PERMISSION_TERMINAL);
            boolean curNull = (job.curStatus == CurPhase.SKIPPED);
            boolean done = isConnTerminal(job.connStatus) && isCurTerminal(job.curStatus);
            return new SequenceProgress(status, job.connStatus, connInProgress, connFailed,
                    job.connMessage, job.connRemedy, events, job.curStatus, null, curInProgress,
                    curFailed, job.curMessage, job.curAttempt, curNull, done);
        }

        static SequenceProgress fallback(StackUpdateService.Progress conn, CurProgress cur) {
            boolean curInProgress = cur != null && cur.inProgress();
            boolean curFailed = cur != null && cur.failed();
            boolean done = !conn.inProgress() && !curInProgress;
            return new SequenceProgress(conn.status(), null, conn.inProgress(), conn.failed(),
                    null, null, conn.events(), null, cur != null ? cur.status() : null,
                    curInProgress, curFailed, null, null, cur == null, done);
        }

        public boolean isDone() {
            return done;
        }

        /** The exact JSON contract (FR-8, NFR-4). */
        public Map<String, Object> toResponseMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("inProgress", connInProgress);
            map.put("failed", connFailed);
            map.put("events", events);
            map.put("connectionPhase", connectionPhase != null ? connectionPhase.name() : null);
            map.put("connectionMessage", connectionMessage);
            map.put("connectionRemedy", connectionRemedy);
            map.put("cur", buildCur());
            map.put("done", done);
            return map;
        }

        private Map<String, Object> buildCur() {
            if (curPhase != null) {
                // sequenced branch
                if (curPhase == CurPhase.SKIPPED) {
                    return null;
                }
                Map<String, Object> cur = new HashMap<>();
                cur.put("status", curPhase.name());
                cur.put("inProgress", curInProgress);
                cur.put("failed", curFailed);
                cur.put("message", curMessage);
                cur.put("attempt", curAttempt); // 0-based; present only in the sequenced branch
                return cur;
            }
            // fallback branch — omit the attempt key entirely (finding 7)
            if (curNull) {
                return null;
            }
            Map<String, Object> cur = new HashMap<>();
            cur.put("status", curStatusRaw);
            cur.put("inProgress", curInProgress);
            cur.put("failed", curFailed);
            return cur;
        }
    }

    // ================= test hooks =================

    void setBounds(long connectionPollMs, int connectionPollAttempts, int connStartgateGraceTicks,
                   int curRetryAttempts, long curRetryBackoffMs, int curSettleAttempts, long curSettleMs) {
        this.connectionPollMs = connectionPollMs;
        this.connectionPollAttempts = connectionPollAttempts;
        this.connStartgateGraceTicks = connStartgateGraceTicks;
        this.curRetryAttempts = curRetryAttempts;
        this.curRetryBackoffMs = curRetryBackoffMs;
        this.curSettleAttempts = curSettleAttempts;
        this.curSettleMs = curSettleMs;
    }

    SequenceJob jobFor(String awsAccountId) {
        return jobs.get(awsAccountId);
    }
}
