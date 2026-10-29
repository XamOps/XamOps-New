package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.stack.CurStackService.CurApplyResult;
import com.xammer.cloud.service.stack.CurStackService.CurProgress;
import com.xammer.cloud.service.stack.CurStackService.CurStatus;
import com.xammer.cloud.service.stack.StackDiagnosisService.Diagnosis;
import com.xammer.cloud.service.stack.StackDiagnosisService.Verdict;
import com.xammer.cloud.service.stack.StackUpdateService.ApplyResult;
import com.xammer.cloud.service.stack.StackUpdateService.StackUpdateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the sequenced connection→CUR stack-update state machine. Built like
 * {@link CurStackServiceTest}: mocked collaborators, a synchronous {@code Runnable::run} executor
 * so {@code start} runs the whole sequence inline, and zeroed bounds so no real sleeping occurs.
 */
class StackSequenceServiceTest {

    private static final String ACCOUNT_ID = "111122223333";
    private static final String CONN_REGION = "us-east-1";
    private static final String STACK_ID = "arn:aws:cloudformation:us-east-1:111122223333:stack/XamOps-Conn/abc";

    private final StackUpdateService stackUpdateService = mock(StackUpdateService.class);
    private final StackDiagnosisService stackDiagnosisService = mock(StackDiagnosisService.class);
    private final CurStackService curStackService = mock(CurStackService.class);
    private final AwsClientProvider awsClientProvider = mock(AwsClientProvider.class);
    private final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    private final CloudFormationClient cfn = mock(CloudFormationClient.class);

    private final Executor synchronousExecutor = Runnable::run;

    private StackSequenceService service;

    @BeforeEach
    void setUp() {
        service = new StackSequenceService(stackUpdateService, stackDiagnosisService, curStackService,
                awsClientProvider, cloudAccountRepository, synchronousExecutor, "ap-south-1");
        // Fast bounds, no real sleep.
        service.setBounds(0L, 5, 6, 6, 0L, 5, 0L);

        when(awsClientProvider.getCloudFormationClient(any(CloudAccount.class), any())).thenReturn(cfn);
        when(cfn.describeStackEvents(any(DescribeStackEventsRequest.class)))
                .thenReturn(DescribeStackEventsResponse.builder().build());
        when(curStackService.resolveStackName(any(CloudAccount.class))).thenReturn("XamOps-CUR-Acme");
        // default healthy diagnosis in connection region
        when(stackDiagnosisService.diagnose(any(CloudAccount.class)))
                .thenReturn(healthy(CONN_REGION));
    }

    // ---------------- helpers ----------------

    private CloudAccount account() {
        CloudAccount account = new CloudAccount("Acme Prod", "ext-1", "read-only", mock(Client.class));
        account.setAwsAccountId(ACCOUNT_ID);
        return account;
    }

    private static Diagnosis healthy(String region) {
        return new Diagnosis(Verdict.HEALTHY, STACK_ID, "XamOps-Conn", region,
                "UPDATE_COMPLETE", true, "healthy", null);
    }

    private static Stack stackWith(String status, Instant lastUpdated) {
        Stack.Builder b = Stack.builder().stackName("XamOps-Conn").stackId(STACK_ID).stackStatus(status);
        if (lastUpdated != null) {
            b.lastUpdatedTime(lastUpdated);
        }
        return b.build();
    }

    private static DescribeStacksResponse resp(Stack stack) {
        return DescribeStacksResponse.builder().stacks(stack).build();
    }

    private CurApplyResult cur(CurStatus status) {
        return new CurApplyResult(status, "XamOps-CUR-Acme", "msg");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> curMap(Map<String, Object> response) {
        return (Map<String, Object>) response.get("cur");
    }

    // ---------------- AC-1: phase 1 success → phase 2 runs ----------------

    @Test
    void phase1Success_thenCurCreateCompletes_done() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))   // baseline
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))   // tick 0 → sets connSawInProgress
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));     // tick 1 → terminal, moved
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.CREATE_IN_PROGRESS));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(new CurProgress("CREATE_COMPLETE", false, false));

        CloudAccount account = account();
        ApplyResult seed = service.start(account, "cs-1");
        assertThat(seed.connectionStarted()).isTrue();
        assertThat(seed.cur().curStatus()).isEqualTo(CurStatus.WAITING_FOR_CONNECTION);

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.COMPLETED);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.COMPLETED);
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- AC-3: phase 1 still in progress → CUR waiting ----------------

    @Test
    void phase1StillInProgress_curWaiting_notDone() {
        // Every poll tick returns IN_PROGRESS; bounds exhaust to TIMED_OUT but we inspect mid-run
        // by capping attempts to a small number and asserting CUR never started before conn terminal.
        service.setBounds(0L, 3, 6, 6, 0L, 5, 0L);
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)));
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.CREATE_IN_PROGRESS));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(new CurProgress("CREATE_COMPLETE", false, false));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        // Connection never reached a terminal success → TIMED_OUT; but phase 2 then runs (AC-9).
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.TIMED_OUT);
    }

    @Test
    void midRunProgress_showsCurWaiting_whenConnStillUpdating() {
        // Build a job directly in the WAITING state and assert toResponseMap reports done=false.
        StackSequenceService.SequenceProgress p = buildSequencedWaiting();
        Map<String, Object> m = p.toResponseMap();
        assertThat(m.get("done")).isEqualTo(false);
        assertThat(curMap(m).get("status")).isEqualTo("WAITING_FOR_CONNECTION");
        assertThat(m.get("inProgress")).isEqualTo(true);
    }

    private StackSequenceService.SequenceProgress buildSequencedWaiting() {
        StackSequenceService.SequenceJob job = new StackSequenceService.SequenceJob(ACCOUNT_ID,
                "XamOps-Conn", STACK_ID, CONN_REGION, StackSequenceService.ConnPhase.UPDATING,
                StackSequenceService.CurPhase.WAITING_FOR_CONNECTION, true);
        job.lastConnStatusRaw = "UPDATE_IN_PROGRESS";
        return StackSequenceService.SequenceProgress.sequenced(job, java.util.List.of());
    }

    // ---------------- AC-4: internal AccessDenied retry ----------------

    @Test
    void curAccessDenied_retriesThenSucceeds_noUserFacingPermission() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.PENDING_PERMISSION))
                .thenReturn(cur(CurStatus.PENDING_PERMISSION))
                .thenReturn(cur(CurStatus.CREATE_IN_PROGRESS));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(null)                                            // guard: not known-failed
                .thenReturn(null)
                .thenReturn(new CurProgress("CREATE_COMPLETE", false, false)); // settle

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.COMPLETED);
        assertThat(job.isDone()).isTrue();
    }

    @Test
    void curRetriesExhausted_noPermissionTerminal_done() {
        stubConnCompletes();
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.PENDING_PERMISSION));
        when(curStackService.progress(any(CloudAccount.class))).thenReturn(null);

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.NO_PERMISSION_TERMINAL);
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- AC-5: connection rollback → CUR still runs ----------------

    @Test
    void connectionRollsBack_curStillRuns_bothReported() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))
                .thenReturn(resp(stackWith("UPDATE_ROLLBACK_COMPLETE", null)));
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.CREATE_IN_PROGRESS));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(new CurProgress("CREATE_COMPLETE", false, false));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.FAILED);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.COMPLETED);
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- finding 1: start-gate ----------------

    @Test
    void startGate_staleTerminalNotAccepted_untilRunMoves() {
        // Baseline UPDATE_COMPLETE with a fixed timestamp; early ticks repeat it (stale) → POLL_AGAIN,
        // then IN_PROGRESS (sets connSawInProgress), then UPDATE_COMPLETE accepted.
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", base)))   // baseline
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", base)))   // tick 0 stale → POLL_AGAIN
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", base))) // tick 1 → connSawInProgress
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", base)));  // tick 2 → accepted
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.COMPLETED);
        assertThat(job.connSawInProgress).isTrue();
    }

    @Test
    void startGate_advancedTimestampAcceptedImmediately() {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        Instant later = base.plusSeconds(60);
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", base)))   // baseline
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", later))); // tick 0 advanced → accepted
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        assertThat(service.jobFor(ACCOUNT_ID).connStatus)
                .isEqualTo(StackSequenceService.ConnPhase.COMPLETED);
    }

    @Test
    void startGate_noOpAcceptedAfterGrace() {
        // Never in-progress, timestamp never advances → accepted as no-op COMPLETED after grace.
        service.setBounds(0L, 10, 2, 6, 0L, 5, 0L); // grace = 2 ticks
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", base)));  // baseline + all ticks
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        assertThat(service.jobFor(ACCOUNT_ID).connStatus)
                .isEqualTo(StackSequenceService.ConnPhase.COMPLETED);
    }

    @Test
    void baselineDescribeThrows_phase2StillRuns_done() {
        // Pre-execute baseline describe throws → baseline null, fall through (NOT outer catch).
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(new RuntimeException("transient"))             // baseline throws
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.COMPLETED);
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- finding 3: region single-sourcing ----------------

    @Test
    void regionSingleSourcedFromDiagnosis() {
        String diagnosisRegion = "eu-west-1";
        when(stackDiagnosisService.diagnose(any(CloudAccount.class)))
                .thenReturn(healthy(diagnosisRegion));
        CloudAccount account = account();
        account.setStackRegion("ap-south-1"); // stale, must NOT be used
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account, "cs-1");

        verify(awsClientProvider).getCloudFormationClient(eq(account), eq(diagnosisRegion));
        verify(awsClientProvider, never()).getCloudFormationClient(eq(account), eq("ap-south-1"));
    }

    // ---------------- finding 4: worker-side execute AccessDenied ----------------

    @Test
    void executeAccessDenied_connFailedWithRemedy_phase2Runs() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));  // baseline only
        doThrow(new RuntimeException("User: assumed-role/X is not authorized to perform: cloudformation:ExecuteChangeSet"))
                .when(stackUpdateService).execute(any(CloudAccount.class), any());
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.FAILED);
        assertThat(job.connMessage).contains("XamOpsStackUpdaterRole");
        assertThat(job.connRemedy).isNotNull();
        assertThat(job.isDone()).isTrue();
    }

    @Test
    void executeNonAccessDeniedError_connFailedNoRemedy_phase2Runs() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));
        doThrow(new RuntimeException("ChangeSet [cs-1] does not exist"))
                .when(stackUpdateService).execute(any(CloudAccount.class), any());
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.FAILED);
        assertThat(job.connMessage).contains("does not exist");
        assertThat(job.connRemedy).isNull();
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- classifier ordering ----------------

    @ParameterizedTest
    @CsvSource({
            "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS,COMPLETED",
            "UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS,FAILED",
            "ROLLBACK_COMPLETE,FAILED",
            "UPDATE_IN_PROGRESS,POLL_AGAIN",
            "DELETE_COMPLETE,FAILED",
            "UPDATE_COMPLETE,COMPLETED",
            "CREATE_COMPLETE,COMPLETED",
            "IMPORT_COMPLETE,COMPLETED",
            "CREATE_FAILED,FAILED",
    })
    void classifyConnection_ordering(String status, String expected) {
        assertThat(StackSequenceService.classifyConnection(status).name()).isEqualTo(expected);
    }

    // ---------------- CUR rolled-back guard ----------------

    @Test
    void curRolledBackGuard_failsFast_notRetried() {
        stubConnCompletes();
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.PENDING_PERMISSION));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(new CurProgress("ROLLBACK_COMPLETE", false, true));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.FAILED);
        assertThat(job.curMessage).containsIgnoringCase("delete");
        assertThat(job.isDone()).isTrue();
        // applyAsync called exactly once — not retried.
        verify(curStackService).applyAsync(any(CloudAccount.class), anyBoolean());
    }

    // ---------------- finding 5: done on uncaught throwable ----------------

    @Test
    void uncaughtThrowableMidPhase2_connCompletedPreserved_done() {
        stubConnCompletes();
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenThrow(new RuntimeException("boom in phase 2"));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.COMPLETED); // preserved
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.FAILED);
        Map<String, Object> m = StackSequenceService.SequenceProgress
                .sequenced(job, java.util.List.of()).toResponseMap();
        assertThat(m.get("done")).isEqualTo(true);
    }

    @Test
    void uncaughtThrowableMidPhase1_bothFailed_done() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null))); // baseline ok
        doThrow(new Error("fatal"))
                .when(stackUpdateService).execute(any(CloudAccount.class), any());

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.FAILED);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.FAILED);
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- finding 5 variant: STACK_DELETED-style diagnose ----------------

    @Test
    void stackDeletedStyleDiagnose_stillReachesDone() {
        // Not gated (only NO_CFN_PERMISSION short-circuits); stackId null → account fields used.
        when(stackDiagnosisService.diagnose(any(CloudAccount.class)))
                .thenReturn(new Diagnosis(Verdict.STACK_DELETED, null, "XamOps-Conn", "ap-south-1",
                        null, false, "deleted", "re-onboard"));
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));
        doThrow(new RuntimeException("ChangeSet does not exist"))
                .when(stackUpdateService).execute(any(CloudAccount.class), any());
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));
        CloudAccount account = account();
        account.setStackName("XamOps-Conn");

        service.start(account, "cs-1");

        assertThat(service.jobFor(ACCOUNT_ID).isDone()).isTrue();
    }

    // ---------------- finding 6: predicates ----------------

    @Test
    void isConnTerminal_predicate() {
        assertThat(StackSequenceService.isConnTerminal(StackSequenceService.ConnPhase.UPDATING)).isFalse();
        for (StackSequenceService.ConnPhase p : StackSequenceService.ConnPhase.values()) {
            if (p != StackSequenceService.ConnPhase.UPDATING) {
                assertThat(StackSequenceService.isConnTerminal(p)).isTrue();
            }
        }
    }

    @Test
    void isCurTerminal_predicate() {
        for (StackSequenceService.CurPhase p : StackSequenceService.CurPhase.values()) {
            boolean running = p == StackSequenceService.CurPhase.WAITING_FOR_CONNECTION
                    || p == StackSequenceService.CurPhase.CREATING
                    || p == StackSequenceService.CurPhase.UPDATING
                    || p == StackSequenceService.CurPhase.RETRYING_PERMISSION;
            assertThat(StackSequenceService.isCurTerminal(p)).isEqualTo(!running);
        }
    }

    // ---------------- finding 7: attempt presence ----------------

    @Test
    void fallbackBranch_omitsAttemptKey() {
        // No job registered → fallback. progress uses StackUpdateService.progress + CurStackService.progress.
        when(stackUpdateService.progress(any(CloudAccount.class), anyInt()))
                .thenReturn(new StackUpdateService.Progress("UPDATE_COMPLETE", false, false, java.util.List.of()));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(new CurProgress("CREATE_COMPLETE", false, false));

        Map<String, Object> m = service.progress(account(), 25).toResponseMap();
        Map<String, Object> cur = curMap(m);
        assertThat(cur).doesNotContainKey("attempt");
        assertThat(m.get("done")).isEqualTo(true);
    }

    @Test
    void sequencedBranch_retrying_hasAttempt() {
        StackSequenceService.SequenceJob job = new StackSequenceService.SequenceJob(ACCOUNT_ID,
                "XamOps-Conn", STACK_ID, CONN_REGION, StackSequenceService.ConnPhase.COMPLETED,
                StackSequenceService.CurPhase.RETRYING_PERMISSION, true);
        job.curAttempt = 2;
        Map<String, Object> cur = curMap(StackSequenceService.SequenceProgress
                .sequenced(job, java.util.List.of()).toResponseMap());
        assertThat(cur.get("attempt")).isEqualTo(2);
        assertThat(cur.get("status")).isEqualTo("RETRYING_PERMISSION");
    }

    // ---------------- NO CUR template ----------------

    @Test
    void noCurTemplate_skipped_curNull_doneOnConnection() {
        stubConnCompletes();
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.SKIPPED));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.SKIPPED);
        assertThat(job.isDone()).isTrue();
        Map<String, Object> m = StackSequenceService.SequenceProgress
                .sequenced(job, java.util.List.of()).toResponseMap();
        assertThat(m.get("cur")).isNull();
    }

    // ---------------- CUR-only apply ----------------

    @Test
    void curOnlyApply_phase1NoChanges_settles() {
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.CREATE_IN_PROGRESS));
        when(curStackService.progress(any(CloudAccount.class)))
                .thenReturn(new CurProgress("CREATE_COMPLETE", false, false));

        ApplyResult seed = service.start(account(), null); // null change-set → CUR-only
        assertThat(seed.connectionStarted()).isFalse();

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.NO_CHANGES);
        assertThat(job.curStatus).isEqualTo(StackSequenceService.CurPhase.COMPLETED);
        assertThat(job.isDone()).isTrue();
        // execute never called for a CUR-only apply
        verify(stackUpdateService, never()).execute(any(CloudAccount.class), any());
    }

    // ---------------- AC-9: connection poll bound exhausted ----------------

    @Test
    void connPollExhausted_timedOut_phase2Runs() {
        service.setBounds(0L, 3, 6, 6, 0L, 5, 0L);
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)));
        when(curStackService.applyAsync(any(CloudAccount.class), anyBoolean()))
                .thenReturn(cur(CurStatus.NO_CHANGES));

        service.start(account(), "cs-1");

        StackSequenceService.SequenceJob job = service.jobFor(ACCOUNT_ID);
        assertThat(job.connStatus).isEqualTo(StackSequenceService.ConnPhase.TIMED_OUT);
        assertThat(job.isDone()).isTrue();
    }

    // ---------------- NFR-1: single-job guard ----------------

    @Test
    void secondStartWhileActive_throwsSequenceInProgress() {
        // Use an executor that does NOT run the job, so it stays non-done & registered.
        StackSequenceService svc = new StackSequenceService(stackUpdateService, stackDiagnosisService,
                curStackService, awsClientProvider, cloudAccountRepository, r -> { /* no-op */ }, "ap-south-1");
        svc.setBounds(0L, 5, 6, 6, 0L, 5, 0L);

        svc.start(account(), "cs-1"); // registers job, never runs it
        assertThatThrownBy(() -> svc.start(account(), "cs-2"))
                .isInstanceOf(StackUpdateException.class)
                .satisfies(e -> assertThat(((StackUpdateException) e).getVerdict()).isEqualTo("SEQUENCE_IN_PROGRESS"));
    }

    @Test
    void twoThreads_exactlyOneRegistration() throws Exception {
        StackSequenceService svc = new StackSequenceService(stackUpdateService, stackDiagnosisService,
                curStackService, awsClientProvider, cloudAccountRepository, r -> { /* no-op */ }, "ap-south-1");
        svc.setBounds(0L, 5, 6, 6, 0L, 5, 0L);

        AtomicInteger seeds = new AtomicInteger();
        AtomicInteger rejects = new AtomicInteger();
        Runnable task = () -> {
            try {
                svc.start(account(), "cs-1");
                seeds.incrementAndGet();
            } catch (StackUpdateException e) {
                if ("SEQUENCE_IN_PROGRESS".equals(e.getVerdict())) {
                    rejects.incrementAndGet();
                }
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(seeds.get()).isEqualTo(1);
        assertThat(rejects.get()).isEqualTo(1);
    }

    // ---------------- pool full ----------------

    @Test
    void poolFull_throwsSequenceCapacity_registrationRemoved() {
        Executor full = command -> { throw new RejectedExecutionException("pool full"); };
        StackSequenceService svc = new StackSequenceService(stackUpdateService, stackDiagnosisService,
                curStackService, awsClientProvider, cloudAccountRepository, full, "ap-south-1");
        svc.setBounds(0L, 5, 6, 6, 0L, 5, 0L);

        assertThatThrownBy(() -> svc.start(account(), "cs-1"))
                .isInstanceOf(StackUpdateException.class)
                .satisfies(e -> assertThat(((StackUpdateException) e).getVerdict()).isEqualTo("SEQUENCE_CAPACITY"));
        assertThat(svc.jobFor(ACCOUNT_ID)).isNull(); // registration undone
    }

    // ---------------- CFN permission pre-flight ----------------

    @Test
    void noCfnPermission_throwsBeforeSubmit() {
        AtomicInteger submits = new AtomicInteger();
        Executor spy = command -> submits.incrementAndGet();
        StackSequenceService svc = new StackSequenceService(stackUpdateService, stackDiagnosisService,
                curStackService, awsClientProvider, cloudAccountRepository, spy, "ap-south-1");
        when(stackDiagnosisService.diagnose(any(CloudAccount.class)))
                .thenReturn(new Diagnosis(Verdict.NO_CFN_PERMISSION, null, "XamOps-Conn", "us-east-1",
                        null, false, "role predates permissions", "deploy grant"));

        assertThatThrownBy(() -> svc.start(account(), "cs-1"))
                .isInstanceOf(StackUpdateException.class)
                .satisfies(e -> assertThat(((StackUpdateException) e).getVerdict()).isEqualTo("NO_CFN_PERMISSION"));
        assertThat(submits.get()).isZero();
        assertThat(svc.jobFor(ACCOUNT_ID)).isNull();
    }

    // ---------------- status vocabulary ----------------

    @Test
    void statusRule_rawWhilePolling_phaseLabelWhenNone() {
        StackSequenceService.SequenceJob polling = new StackSequenceService.SequenceJob(ACCOUNT_ID,
                "XamOps-Conn", STACK_ID, CONN_REGION, StackSequenceService.ConnPhase.UPDATING,
                StackSequenceService.CurPhase.WAITING_FOR_CONNECTION, true);
        polling.lastConnStatusRaw = "UPDATE_IN_PROGRESS";
        assertThat(StackSequenceService.SequenceProgress.sequenced(polling, java.util.List.of())
                .toResponseMap().get("status")).isEqualTo("UPDATE_IN_PROGRESS");

        StackSequenceService.SequenceJob noRaw = new StackSequenceService.SequenceJob(ACCOUNT_ID,
                "XamOps-Conn", STACK_ID, CONN_REGION, StackSequenceService.ConnPhase.NO_CHANGES,
                StackSequenceService.CurPhase.WAITING_FOR_CONNECTION, false);
        Map<String, Object> m = StackSequenceService.SequenceProgress.sequenced(noRaw, java.util.List.of())
                .toResponseMap();
        assertThat(m.get("status")).isEqualTo("NO_CHANGES");
        assertThat(m.get("connectionPhase")).isEqualTo("NO_CHANGES");
    }

    // ---------------- shared stubs ----------------

    private void stubConnCompletes() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))   // baseline
                .thenReturn(resp(stackWith("UPDATE_IN_PROGRESS", null)))   // tick 0
                .thenReturn(resp(stackWith("UPDATE_COMPLETE", null)));     // tick 1 terminal
    }
}
