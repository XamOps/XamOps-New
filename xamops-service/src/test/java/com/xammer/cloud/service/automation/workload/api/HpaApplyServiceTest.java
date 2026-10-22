package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.ActionTrigger;
import com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NativeVpaMode;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyResponseDto;
import com.xammer.cloud.service.automation.workload.execute.HpaActionCommand;
import com.xammer.cloud.service.automation.workload.execute.HpaApplyExecutor;
import com.xammer.cloud.service.automation.workload.execute.HpaApplyOutcome;
import com.xammer.cloud.service.automation.workload.execute.HpaPatchClient;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The HPA apply path's gates and its re-validation, with the cluster, the HPA read and the executor
 * mocked so nothing reaches AWS or a real API server. Each refusal is asserted by its contract code
 * and — for the {@link com.xammer.cloud.service.automation.workload.HpaReason} refusals — the rule
 * named in the detail.
 */
class HpaApplyServiceTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final WorkloadRef REF = WorkloadRef.of("team-b", "Deployment", "php-apache");
    private static final ResourceEnvelope GUARANTEED =
            new ResourceEnvelope(200, 200, 268_435_456L, 268_435_456L);
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    private static final VerticalMetricsProperties METRICS =
            VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 72);

    private AutomationWritePermission writePermission;
    private ClusterWorkloadInventory inventory;
    private UpstreamVpaReader vpaReader;
    private HpaPatchClient patchClient;
    private HpaApplyExecutor executor;
    private WorkloadPolicyService policyService;
    private FakeProvider provider;

    @BeforeEach
    void setUp() {
        writePermission = mock(AutomationWritePermission.class);
        when(writePermission.decide(any(), any()))
                .thenReturn(new AutomationWritePermission.Decision(true, null, null, null, null));
        inventory = mock(ClusterWorkloadInventory.class);
        vpaReader = mock(UpstreamVpaReader.class);
        patchClient = mock(HpaPatchClient.class);
        executor = mock(HpaApplyExecutor.class);
        policyService = mock(WorkloadPolicyService.class);
        provider = new FakeProvider();

        givenInventory(workload(cpuHpaView(1, 5)));
        when(vpaReader.read(any())).thenReturn(upstreamMode(NativeVpaMode.OFF));
        when(patchClient.currentHpa(any(), eq("team-b"), eq("php-apache"))).thenReturn(cpuHpa());
        when(policyService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        givenExecutorApplies();
    }

    // ---------------------------------------------------------------- the gates, in order

    @Test
    @DisplayName("no principal is 401 K8S_UNAUTHENTICATED, and nothing is read")
    void anUnauthenticatedCallIsRefusedFirst() {
        VerticalApplyService.ApplyRefusedException refusal = refuse(null, readWriteAccount(), body(true));

        assertThat(refusal.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refusal.code()).isEqualTo("K8S_UNAUTHENTICATED");
        verifyNoInteractions(inventory, executor, patchClient);
    }

    @Test
    @DisplayName("the write-permission refusal is passed through with its own code")
    void theWritePermissionRefusalIsPassedThrough() {
        for (String code : List.of("K8S_NO_USER_PERMISSION", "K8S_DEMO", "K8S_NO_ACCOUNT_ACCESS")) {
            when(writePermission.decide(any(), any())).thenReturn(new AutomationWritePermission.Decision(
                    false, null, AutomationWritePermission.Detail.ROLE, "Refused: " + code, code));

            VerticalApplyService.ApplyRefusedException refusal =
                    refuse(principal(), readWriteAccount(), body(true));

            assertThat(refusal.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refusal.code()).isEqualTo(code);
        }
        verifyNoInteractions(inventory, executor, patchClient);
    }

    @Test
    @DisplayName("a read-only connection is 403 K8S_READ_ONLY_CONNECTION before any cluster contact")
    void aReadOnlyConnectionIsRefused() {
        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");

        assertThat(refuse(principal(), readOnly, body(true)).code())
                .isEqualTo("K8S_READ_ONLY_CONNECTION");
        verifyNoInteractions(inventory, executor, patchClient);
    }

    @Test
    @DisplayName("the feature off, or no executor, is 403 K8S_CONTROLLER_NOT_READY with the reworded message")
    void anUnreadyControllerIsRefused() {
        // Readiness now derives from the read-write connection and the feature/executor wiring, NOT
        // the standalone staging flag (ready). Feature off or no executor still refuses.
        for (HorizontalAutoscalingProperties gated : List.of(
                new HorizontalAutoscalingProperties(false, false, true),
                new HorizontalAutoscalingProperties(true, false, false))) {
            VerticalApplyService.ApplyRefusedException refusal = catchRefusal(
                    () -> service(gated).apply(principal(), readWriteAccount(), TARGET, REF, body(true)));

            assertThat(refusal.code()).isEqualTo("K8S_CONTROLLER_NOT_READY");
            assertThat(refusal.getMessage())
                    .doesNotContain("not been enabled and validated")
                    .isEqualTo("HPA automation is not available in this environment: the HPA feature"
                            + " is off or no apply path is wired.");
        }
        verifyNoInteractions(inventory, executor, patchClient);
    }

    @Test
    @DisplayName("read-write + feature on + executor present reaches the apply path with the staging flag FALSE")
    void readWriteReachesTheApplyPathWithTheStagingFlagFalse() {
        // ready == false (the standalone hpa-apply readiness flag is off): readiness derives from
        // the read-write connection, so the gate passes and the apply path is reached.
        HorizontalAutoscalingProperties stagingFlagOff =
                new HorizontalAutoscalingProperties(true, false, true);

        HpaApplyResponseDto response = service(stagingFlagOff)
                .apply(principal(), readWriteAccount(), TARGET, REF, body(true));

        assertThat(response.state()).isEqualTo("ACCEPTED");
        verify(executor).submit(any(), any(), any());
    }

    @Test
    @DisplayName("no provider that can reach the cluster is 403 K8S_CLUSTER_ACCESS_NOT_CONFIGURED")
    void anUnreachableClusterIsRefused() {
        provider.supports = false;

        assertThat(refuse(principal(), readWriteAccount(), body(true)).code())
                .isEqualTo("K8S_CLUSTER_ACCESS_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("a provider that cannot connect is 403 K8S_CLUSTER_UNREACHABLE")
    void aClusterThatDoesNotAnswerIsRefused() {
        provider.failure = new ClusterAccessException(
                ClusterAccessException.Kind.CLUSTER_UNREACHABLE, "private endpoint");

        assertThat(refuse(principal(), readWriteAccount(), body(true)).code())
                .isEqualTo("K8S_CLUSTER_UNREACHABLE");
    }

    // ---------------------------------------------------------------- the body

    @Test
    @DisplayName("dryRun is mandatory: an omitted one is 400 K8S_INVALID_REQUEST naming dryRun")
    void anOmittedDryRunIsRejected() {
        HpaApplyRequestDto noDryRun = new HpaApplyRequestDto(1, 8, null, null);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), noDryRun);

        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.code()).isEqualTo("K8S_INVALID_REQUEST");
        assertThat(refusal.field()).isEqualTo("dryRun");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("an all-null body is 400 K8S_INVALID_REQUEST: nothing to change")
    void anAllNullBodyIsRejected() {
        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, null, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_REQUEST");
        assertThat(refusal.field()).isEqualTo("minReplicas");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("min greater than max is rejected before enqueue")
    void minAboveMaxIsRejected() {
        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(9, 5, null, true));

        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.code()).isEqualTo("K8S_INVALID_REQUEST");
        verifyNoInteractions(executor);
    }

    // ---------------------------------------------------------------- re-validation refusals

    @Test
    @DisplayName("a GitOps-owned HPA is refused with GITOPS_MANAGED, and nothing is patched")
    void gitopsManagedIsRefused() {
        givenInventory(workload(cpuHpaView(1, 5).withGitopsManaged(true)));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 8, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("GITOPS_MANAGED");
        assertThat(refusal.getMessage()).startsWith("GITOPS_MANAGED:");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a minReplicas below a PDB's minAvailable is refused with PDB_FLOOR_BINDS")
    void pdbFloorBinds() {
        givenInventory(workload(cpuHpaView(1, 5), 3)); // PDB minAvailable 3

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(2, null, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("PDB_FLOOR_BINDS");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a maxReplicas above the operator ceiling is refused with USER_MAX_RESPECTED, not capped")
    void userMaxRespected() {
        givenInventory(workload(cpuHpaView(1, 5).withUserMaxReplicasCeiling(6)));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 10, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("USER_MAX_RESPECTED");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a lower on unreadable history is refused with BELOW_OBSERVED_NEED")
    void belowObservedNeedOnUnreadableHistory() {
        givenInventory(workload(cpuHpaView(1, 5).withHistory(null, null, false)));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 3, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("BELOW_OBSERVED_NEED");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a lower below the observed peak is refused with BELOW_OBSERVED_NEED")
    void belowObservedNeedBelowPeak() {
        givenInventory(workload(cpuHpaView(1, 10).withHistory(6, 2, true)));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 4, null, true));

        assertThat(refusal.detail()).isEqualTo("BELOW_OBSERVED_NEED");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("an unreadable VPA yields the whole workload with VPA_NOT_READABLE")
    void vpaNotReadableYields() {
        when(vpaReader.read(any())).thenReturn(upstreamMode(NativeVpaMode.UNREADABLE));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 8, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("VPA_NOT_READABLE");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a mutating native VPA does NOT block a bound tune: the two write different resources")
    void aMutatingVpaDoesNotBlockABoundTune() {
        when(vpaReader.read(any())).thenReturn(upstreamMode(NativeVpaMode.MUTATING));

        HpaApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                new HpaApplyRequestDto(null, 8, null, true));

        assertThat(response.state()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("the HPA could not be read back is refused with HPA_NOT_READABLE")
    void hpaNotReadable() {
        when(patchClient.currentHpa(any(), anyString(), anyString())).thenReturn(null);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 8, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("HPA_NOT_READABLE");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a workload with no HPA is refused by name, pointing at the create endpoint")
    void aWorkloadWithNoHpaIsRefused() {
        givenInventory(workload(null));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 8, null, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.getMessage()).contains("no HPA");
    }

    @Test
    @DisplayName("moving a CPU target on an HPA with no Resource: cpu metric is refused with CLAMP_BOUND")
    void aTargetMoveNeedsACpuMetric() {
        when(patchClient.currentHpa(any(), anyString(), anyString())).thenReturn(memOnlyHpa());

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, null, 69, true));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("CLAMP_BOUND");
        verifyNoInteractions(executor);
    }

    // ---------------------------------------------------------------- accepted + cooldown

    @Test
    @DisplayName("a dry run answers 202 ACCEPTED and sends no patch")
    void aDryRunIsAcceptedAndNotRecorded() {
        org.mockito.Mockito.doReturn(HpaApplyOutcome.applied(command(null, 8, null, true), true))
                .when(executor).submit(any(), any(), any());

        HpaApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(true));

        assertThat(response.state()).isEqualTo("ACCEPTED");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.applied().maxReplicas()).isEqualTo(8);
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a confirmed apply records last_applied_at, which is what makes the cooldown real")
    void aConfirmedApplyIsRecorded() {
        HpaApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.state()).isEqualTo("ACCEPTED");
        assertThat(response.dryRun()).isFalse();
        verify(policyService).recordApplied(eq(7L), eq(42L), eq(REF), any(),
                eq(Instant.parse("2026-10-13T12:00:00Z")));
    }

    @Test
    @DisplayName("a replayed command answers DUPLICATE and sends no second patch")
    void aReplayIsADuplicate() {
        org.mockito.Mockito.doReturn(
                        HpaApplyOutcome.duplicate("corr-1", HpaApplyOutcome.applied(command(null, 8, null, false), false)))
                .when(executor).submit(any(), any(), any());

        HpaApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.state()).isEqualTo("DUPLICATE");
    }

    @Test
    @DisplayName("a failed executor outcome surfaces as a failure with the detail, NOT 202 ACCEPTED (Defect 3)")
    void aFailedApplyIsReportedAsFailed() {
        // The API server refused the patch: the executor returns a FAILED outcome carrying the
        // reason. The old code answered 202 {state:ACCEPTED, applied:null} and dropped the detail,
        // making a rejected patch indistinguishable from a pending QUEUED submission.
        String detail = "The API server refused the patch of HPA team-b/php-apache: HTTP 403.";
        org.mockito.Mockito.doReturn(HpaApplyOutcome.failed(command(null, 8, null, false), detail))
                .when(executor).submit(any(), any(), any());

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(false));

        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(refusal.code()).isEqualTo("K8S_APPLY_FAILED");
        assertThat(refusal.getMessage()).isEqualTo(detail);
        // A failed apply never records last_applied_at (there is no landed change to cool down).
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a genuinely QUEUED submission still answers 202 ACCEPTED (pending, not failed)")
    void aQueuedSubmissionIsStillAccepted() {
        org.mockito.Mockito.doReturn(HpaApplyOutcome.queued(command(null, 8, null, false)))
                .when(executor).submit(any(), any(), any());

        HpaApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.state()).isEqualTo("ACCEPTED");
        assertThat(response.applied()).isNull();
    }

    @Test
    @DisplayName("a second apply inside the cooldown is refused with COOLDOWN; a raise is NOT exempt")
    void aSecondApplyInsideTheCooldownIsRefused() {
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-13T11:59:00Z")));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), new HpaApplyRequestDto(null, 8, null, false));

        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.detail()).isEqualTo("COOLDOWN");
        assertThat(refusal.getMessage()).contains("60-minute");
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("the command carries the HPA's resourceVersion as the optimistic precondition")
    void theCommandCarriesTheResourceVersion() {
        service().apply(principal(), readWriteAccount(), TARGET, REF, body(false));

        ArgumentCaptor<HpaActionCommand> command = ArgumentCaptor.forClass(HpaActionCommand.class);
        verify(executor).submit(command.capture(), any(), any());
        assertThat(command.getValue().expectedObservedRevision()).isEqualTo("12345");
        assertThat(command.getValue().maxReplicas()).isEqualTo(8);
        assertThat(command.getValue().notValidAfter())
                .isEqualTo(Instant.parse("2026-10-13T12:00:00Z").plus(HpaApplyService.COMMAND_VALIDITY));
    }

    // ---------------------------------------------------------------- the activity log (FEAT-007)

    @Test
    @DisplayName("activity log: an applied HPA change is recorded with the live bounds before and the new ones after")
    void anAppliedChangeIsRecorded() {
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);

        recordingService(recorder).apply(principal(), readWriteAccount(), TARGET, REF, body(false));

        verify(recorder).recordHpa(eq(7L), eq(TARGET), eq("team-b"), eq("php-apache"), eq(ActionTrigger.MANUAL),
                eq(false), eq(new WorkloadActionRecorder.HpaBounds(1, 5, 50)),
                eq(new WorkloadActionRecorder.HpaBounds(1, 8, 50)), eq("APPLIED"), isNull(), anyString(),
                eq(Instant.parse("2026-10-13T12:00:00Z")));
        verify(recorder, never()).recordRefusal(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("activity log: an HPA dry run is recorded as a test run")
    void aDryRunIsRecordedAsATestRun() {
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);

        recordingService(recorder).apply(principal(), readWriteAccount(), TARGET, REF, body(true));

        verify(recorder).recordHpa(eq(7L), eq(TARGET), eq("team-b"), eq("php-apache"), eq(ActionTrigger.MANUAL),
                eq(true), any(), eq(new WorkloadActionRecorder.HpaBounds(1, 8, 50)),
                eq(WorkloadActionRecorder.OUTCOME_TEST_RUN), any(), anyString(), any());
    }

    @Test
    @DisplayName("activity log: a failed patch is recorded once, as FAILED with its detail, not again as a refusal")
    void aFailedPatchIsRecordedOnce() {
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);
        String detail = "The API server refused the patch of HPA team-b/php-apache: HTTP 403.";
        org.mockito.Mockito.doReturn(HpaApplyOutcome.failed(command(null, 8, null, false), detail))
                .when(executor).submit(any(), any(), any());

        VerticalApplyService.ApplyRefusedException refusal = catchRefusal(() -> recordingService(recorder)
                .apply(principal(), readWriteAccount(), TARGET, REF, body(false)));

        assertThat(refusal.code()).isEqualTo("K8S_APPLY_FAILED");
        verify(recorder).recordHpa(any(), any(), eq("team-b"), eq("php-apache"), any(), eq(false), any(), any(),
                eq("FAILED"), eq(detail), eq("corr-1"), any());
        verify(recorder, never()).recordRefusal(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("activity log: a safety-rule refusal is recorded against the HPA, with its code and message")
    void aRefusalIsRecordedAgainstTheHpa() {
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-13T11:59:00Z")));

        VerticalApplyService.ApplyRefusedException refusal = catchRefusal(() -> recordingService(recorder)
                .apply(principal(), readWriteAccount(), TARGET, REF, new HpaApplyRequestDto(null, 8, null, false)));

        verify(recorder).recordRefusal(eq(7L), eq(TARGET),
                eq(WorkloadRef.of("team-b", WorkloadActionRecorder.KIND_HPA, "php-apache")),
                eq(ActionTrigger.MANUAL), eq(false), eq("K8S_INVALID_POLICY"), eq(refusal.getMessage()),
                eq(Instant.parse("2026-10-13T12:00:00Z")));
        verify(recorder, never()).recordHpa(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("activity log: a refusal before the HPA is read is recorded under the workload's name, and says so")
    void anEarlyRefusalNamesTheWorkload() {
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);
        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");

        VerticalApplyService.ApplyRefusedException refusal = catchRefusal(() -> recordingService(recorder)
                .apply(principal(), readOnly, TARGET, REF, body(true)));

        assertThat(refusal.code()).isEqualTo("K8S_READ_ONLY_CONNECTION");
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(recorder).recordRefusal(eq(7L), eq(TARGET),
                eq(WorkloadRef.of("team-b", WorkloadActionRecorder.KIND_HPA, "php-apache")),
                eq(ActionTrigger.MANUAL), eq(true), eq("K8S_READ_ONLY_CONNECTION"), message.capture(), any());
        assertThat(message.getValue())
                .startsWith(refusal.getMessage())
                .contains("Recorded under the name of Deployment team-b/php-apache");
    }

    @Test
    @DisplayName("activity log: the demo identity's refusal is never recorded")
    void aDemoRefusalIsNotRecorded() {
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);
        when(writePermission.decide(any(), any())).thenReturn(new AutomationWritePermission.Decision(false,
                ReasonCode.DEMO, AutomationWritePermission.Detail.DEMO, "Demo.", "K8S_DEMO"));

        assertThat(catchRefusal(() -> recordingService(recorder)
                .apply(principal(), readWriteAccount(), TARGET, REF, body(true))).code()).isEqualTo("K8S_DEMO");
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("activity log: recording changes no response — applied, test run and failure answer byte for byte the same")
    void recordingChangesNoResponse() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        WorkloadActionRecorder recorder = mock(WorkloadActionRecorder.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            HpaActionCommand command = invocation.getArgument(0);
            return HpaApplyOutcome.applied(new HpaActionCommand("corr-1", TARGET, REF, "team-b", "php-apache",
                    command.minReplicas(), command.maxReplicas(), command.targetUtilizationPct(), "12345",
                    command.dryRun(), command.notValidAfter()), command.dryRun());
        }).when(executor).submit(any(), any(), any());

        for (boolean dryRun : new boolean[]{false, true}) {
            assertThat(json.writeValueAsString(recordingService(recorder)
                    .apply(principal(), readWriteAccount(), TARGET, REF, body(dryRun))))
                    .isEqualTo(json.writeValueAsString(service()
                            .apply(principal(), readWriteAccount(), TARGET, REF, body(dryRun))));
        }

        org.mockito.Mockito.doReturn(HpaApplyOutcome.failed(command(null, 8, null, false), "Rejected."))
                .when(executor).submit(any(), any(), any());
        VerticalApplyService.ApplyRefusedException recorded = catchRefusal(() -> recordingService(recorder)
                .apply(principal(), readWriteAccount(), TARGET, REF, body(false)));
        VerticalApplyService.ApplyRefusedException plain = catchRefusal(() -> service()
                .apply(principal(), readWriteAccount(), TARGET, REF, body(false)));
        assertThat(recorded.status()).isEqualTo(plain.status());
        assertThat(json.writeValueAsString(recorded.body())).isEqualTo(json.writeValueAsString(plain.body()));
    }

    // ---------------------------------------------------------------- fixtures

    private HpaApplyService recordingService(WorkloadActionRecorder recorder) {
        return new HpaApplyService(new HorizontalAutoscalingProperties(true, true, true), writePermission,
                List.of(provider), inventory, vpaReader, patchClient, executor, policyService, recorder,
                METRICS, FIXED);
    }

    private void givenExecutorApplies() {
        when(executor.submit(any(), any(), any())).thenAnswer(invocation -> {
            HpaActionCommand command = invocation.getArgument(0);
            return HpaApplyOutcome.applied(command, command.dryRun());
        });
    }

    private HpaActionCommand command(Integer min, Integer max, Integer target, boolean dryRun) {
        return new HpaActionCommand("corr-1", TARGET, REF, "team-b", "php-apache", min, max, target,
                "12345", dryRun, Instant.parse("2026-10-13T12:05:00Z"));
    }

    private VerticalApplyService.ApplyRefusedException refuse(ClientUserDetails principal,
                                                              CloudAccount account,
                                                              HpaApplyRequestDto request) {
        return catchRefusal(() -> service().apply(principal, account, TARGET, REF, request));
    }

    private static VerticalApplyService.ApplyRefusedException catchRefusal(Runnable call) {
        try {
            call.run();
        } catch (VerticalApplyService.ApplyRefusedException refused) {
            return refused;
        }
        throw new AssertionError("the call was expected to be refused, and was not");
    }

    private HpaApplyService service() {
        return service(new HorizontalAutoscalingProperties(true, true, true));
    }

    private HpaApplyService service(HorizontalAutoscalingProperties properties) {
        return new HpaApplyService(properties, writePermission, List.of(provider), inventory,
                vpaReader, patchClient, executor, policyService, METRICS, FIXED);
    }

    private void givenInventory(ClusterWorkloadInventory.Workload workload) {
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workload), new NodeCapacity(1930, 3_454_423_040L)));
    }

    private static HpaApplyRequestDto body(boolean dryRun) {
        return new HpaApplyRequestDto(null, 8, null, dryRun);
    }

    private static HpaView cpuHpaView(int min, int max) {
        return HpaView.cpuHpa("php-apache", REF, 50, min, max);
    }

    private static ClusterWorkloadInventory.Workload workload(HpaView hpa) {
        return workload(hpa, null);
    }

    private static ClusterWorkloadInventory.Workload workload(HpaView hpa, Integer pdbMinAvailable) {
        return new ClusterWorkloadInventory.Workload(REF, 5, false, false, hpa, pdbMinAvailable,
                List.of(new ClusterWorkloadInventory.ContainerSpec("php-apache", GUARANTEED, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("php-apache-1", "uid-1"),
                        new WorkloadPods.Pod("php-apache-2", "uid-2"))));
    }

    private static UpstreamVpaReader.View upstreamMode(NativeVpaMode mode) {
        if (mode == NativeVpaMode.UNREADABLE) {
            return UpstreamVpaReader.View.notReadable();
        }
        return new UpstreamVpaReader.View(Map.of(), Map.of(UpstreamVpaReader.key(REF), mode), true);
    }

    private static HorizontalPodAutoscaler cpuHpa() {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache")
                .withResourceVersion("12345").endMetadata()
                .withNewSpec().withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("cpu")
                .withNewTarget().withType("Utilization").withAverageUtilization(50).endTarget()
                .endResource().endMetric()
                .endSpec().build();
    }

    private static HorizontalPodAutoscaler memOnlyHpa() {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache")
                .withResourceVersion("12345").endMetadata()
                .withNewSpec().withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("memory")
                .withNewTarget().withType("Utilization").withAverageUtilization(80).endTarget()
                .endResource().endMetric()
                .endSpec().build();
    }

    private static ClientUserDetails principal() {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_XAMOPS"));
        return new ClientUserDetails("user@example.com", "pw", granted, 7L, 10L, "tenant-a");
    }

    private static CloudAccount readWriteAccount() {
        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId("102227938259");
        account.setAccessType("read-write");
        account.setClient(client);
        return account;
    }

    /** A provider that can be told not to support the target, or to fail opening it. */
    private static final class FakeProvider implements ClusterAccessProvider {

        private boolean supports = true;
        private ClusterAccessException failure;

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public boolean supports(ClusterTarget target) {
            return supports;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
            if (failure != null) {
                throw failure;
            }
            return new ClusterAccess(mock(KubernetesClient.class), "https://example.invalid",
                    target.clusterName(), target.region(), providerName());
        }
    }
}
