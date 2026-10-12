package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.execute.ActionCommand;
import com.xammer.cloud.service.automation.workload.execute.ActionSubmission;
import com.xammer.cloud.service.automation.workload.execute.ContainerApplyTarget;
import com.xammer.cloud.service.automation.workload.execute.DirectInPlaceResizeExecutor;
import com.xammer.cloud.service.automation.workload.execute.ResizeProof;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Endpoint 3's gates and its re-validation, with the cluster and the executor mocked so nothing
 * reaches AWS or a real API server.
 *
 * <p>Each refusal is asserted by its <strong>contract code</strong>, not by its status alone: the UI
 * disables a control and explains why from that code, so a gate that answered a bare 403 would be
 * indistinguishable from a bug.
 */
class VerticalApplyServiceTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final WorkloadRef REF =
            WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");
    private static final ResourceEnvelope GUARANTEED_1CPU_1GI =
            new ResourceEnvelope(1000, 1000, 1_073_741_824L, 1_073_741_824L);
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
    /** UAT's configuration: default mode RECOMMEND over a 72h window. */
    private static final VerticalMetricsProperties METRICS =
            VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 72);

    private AutomationWritePermission writePermission;
    private ClusterWorkloadInventory inventory;
    private DirectInPlaceResizeExecutor executor;
    private WorkloadPolicyService policyService;
    private FakeProvider provider;

    @BeforeEach
    void setUp() {
        writePermission = mock(AutomationWritePermission.class);
        when(writePermission.decide(any(), any()))
                .thenReturn(new AutomationWritePermission.Decision(true, null, null, null, null));
        inventory = mock(ClusterWorkloadInventory.class);
        executor = mock(DirectInPlaceResizeExecutor.class);
        policyService = mock(WorkloadPolicyService.class);
        provider = new FakeProvider();
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workload()), new NodeCapacity(1930, 3_454_423_040L)));
        // No stored row: the resolver hands back the fallback it was given, which must be the
        // environment's configured defaults (RECOMMEND / 72h here, as on UAT).
        when(policyService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    // ---------------------------------------------------------------- the gates, in order

    @Test
    @DisplayName("no principal is 401 K8S_UNAUTHENTICATED, and nothing is read")
    void anUnauthenticatedCallIsRefusedFirst() {
        VerticalApplyService.ApplyRefusedException refusal =
                refuse(null, readWriteAccount(), body(true));

        assertThat(refusal.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refusal.code()).isEqualTo("K8S_UNAUTHENTICATED");
        verifyNoInteractions(inventory, executor);
    }

    @Test
    @DisplayName("the shared permission predicate's refusal is passed through with its own code")
    void theWritePermissionRefusalIsPassedThrough() {
        // The predicate itself is AutomationWritePermission's (the UI revamp's D2 rule) and is
        // tested there. What matters here is that this service asks it and reports its exact answer
        // -- the UI disables a control and picks its copy from that code.
        for (String code : List.of("K8S_NO_USER_PERMISSION", "K8S_DEMO", "K8S_NO_ACCOUNT_ACCESS")) {
            when(writePermission.decide(any(), any())).thenReturn(
                    new AutomationWritePermission.Decision(false, null,
                            AutomationWritePermission.Detail.ROLE, "Refused: " + code, code));

            VerticalApplyService.ApplyRefusedException refusal =
                    refuse(principal(), readWriteAccount(), body(true));

            assertThat(refusal.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refusal.code()).isEqualTo(code);
            assertThat(refusal.field()).isNull();
            assertThat(refusal.getMessage()).isEqualTo("Refused: " + code);
        }
        verifyNoInteractions(inventory, executor);
    }

    @Test
    @DisplayName("a read-only connection is 403 K8S_READ_ONLY_CONNECTION before any cluster contact")
    void aReadOnlyConnectionIsRefused() {
        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");

        VerticalApplyService.ApplyRefusedException refusal = refuse(principal(), readOnly, body(true));

        assertThat(refusal.code()).isEqualTo("K8S_READ_ONLY_CONNECTION");
        verifyNoInteractions(inventory, executor);
    }

    @Test
    @DisplayName("either safety flag off, or no executor, is 403 K8S_CONTROLLER_NOT_READY")
    void anUnreadyControllerIsRefused() {
        for (WorkloadAutoscalingProperties gated : List.of(
                new WorkloadAutoscalingProperties(false, true, true),
                new WorkloadAutoscalingProperties(true, false, true),
                new WorkloadAutoscalingProperties(true, true, false))) {
            VerticalApplyService.ApplyRefusedException refusal = catchRefusal(
                    () -> service(gated).apply(principal(), readWriteAccount(), TARGET, REF, body(true)));

            assertThat(refusal.code()).isEqualTo("K8S_CONTROLLER_NOT_READY");
        }
        verifyNoInteractions(inventory, executor);
    }

    @Test
    @DisplayName("no provider that can reach the cluster is 403 K8S_CLUSTER_ACCESS_NOT_CONFIGURED")
    void anUnreachableClusterIsRefusedWithItsRemedy() {
        provider.supports = false;

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(true));

        assertThat(refusal.code()).isEqualTo("K8S_CLUSTER_ACCESS_NOT_CONFIGURED");
        assertThat(refusal.getMessage()).contains("access entry");
    }

    @Test
    @DisplayName("a provider that cannot connect is 403 K8S_CLUSTER_UNREACHABLE, with its own message")
    void aClusterThatDoesNotAnswerIsRefused() {
        provider.failure = new ClusterAccessException(
                ClusterAccessException.Kind.CLUSTER_UNREACHABLE,
                "The API server did not answer; this looks like a private endpoint.");

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(true));

        assertThat(refusal.code()).isEqualTo("K8S_CLUSTER_UNREACHABLE");
        assertThat(refusal.getMessage()).contains("private endpoint");
    }

    @Test
    @DisplayName("IN_PLACE_AND_PERSIST is 403 K8S_GITOPS_MANAGED: no template is ever patched")
    void templatePersistenceIsRefused() {
        ApplyRequestDto persist = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 25, 250L, 25, 250L)),
                "IN_PLACE_AND_PERSIST", true);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), persist);

        assertThat(refusal.code()).isEqualTo("K8S_GITOPS_MANAGED");
        assertThat(refusal.getMessage()).contains("does not patch a workload template");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("an unknown applyMethod is 400 K8S_INVALID_POLICY naming the field")
    void anUnknownApplyMethodIsRejected() {
        ApplyRequestDto unknown = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 25, 250L, 25, 250L)), "CANARY", true);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), unknown);

        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.field()).isEqualTo("applyMethod");
    }

    // ---------------------------------------------------------------- the body

    @Test
    @DisplayName("dryRun is mandatory: an omitted one is 400, never a live patch")
    void anOmittedDryRunIsRejected() {
        ApplyRequestDto noDryRun = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 25, 250L, 25, 250L)), "IN_PLACE", null);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), noDryRun);

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.field()).isEqualTo("dryRun");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("an empty body, a duplicate container and a zero are each 400 with the field named")
    void malformedBodiesAreRejectedWithTheirField() {
        assertField(new ApplyRequestDto(List.of(), "IN_PLACE", true), "containers");
        assertField(null, "containers");
        assertField(new ApplyRequestDto(List.of(
                new ApplyRequestDto.Container("api", 25, 250L, 25, 250L),
                new ApplyRequestDto.Container("api", 50, 250L, 50, 250L)), "IN_PLACE", true),
                "containers[1].name");
        assertField(new ApplyRequestDto(List.of(
                new ApplyRequestDto.Container("api", 0, 250L, 25, 250L)), "IN_PLACE", true),
                "containers[0].cpuRequestM");
        assertField(new ApplyRequestDto(List.of(
                new ApplyRequestDto.Container("api", null, null, null, null)), "IN_PLACE", true),
                "containers[0]");
        assertField(new ApplyRequestDto(List.of(
                new ApplyRequestDto.Container(" ", 25, 250L, 25, 250L)), "IN_PLACE", true),
                "containers[0].name");
    }

    @Test
    @DisplayName("a protected namespace is refused by name, whatever the request says")
    void aProtectedNamespaceIsRefused() {
        VerticalApplyService.ApplyRefusedException refusal = catchRefusal(
                () -> service().apply(principal(), readWriteAccount(), TARGET,
                        WorkloadRef.of("kube-system", "Deployment", "coredns"), body(true)));

        assertThat(refusal.field()).isEqualTo("namespace");
        assertThat(refusal.getMessage()).contains("PROTECTED_NAMESPACE");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("a workload the cluster does not have is 400, not an invented success")
    void anAbsentWorkloadIsRejected() {
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(List.of(), null));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(true));

        assertThat(refusal.field()).isEqualTo("name");
        assertThat(refusal.getMessage()).contains("nothing to apply");
    }

    // ---------------------------------------------------------------- re-validation

    @Test
    @DisplayName("a value below the engine's floor is refused, never silently clamped to it")
    void aValueBelowTheFloorIsRefused() {
        // A floor blocking an apply is the product working correctly. Clamping would apply a number
        // nobody submitted and nobody recommended.
        ApplyRequestDto belowFloor = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 1, 1L, 1, 1L)), "IN_PLACE", false);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), belowFloor);

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.field()).isEqualTo("containers[0].cpuRequestM");
        assertThat(refusal.getMessage())
                .contains("floor is 25")
                .contains("refused rather than clamped");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("a request above its own limit is refused with the field named")
    void aRequestAboveItsLimitIsRefused() {
        ApplyRequestDto inverted = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 500, 250L, 100, 250L)), "IN_PLACE", false);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), inverted);

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.getMessage()).contains("exceeds its limit");
    }

    @Test
    @DisplayName("a body that would change the QoS class is refused, because the cluster would too")
    void aQosChangeIsRefused() {
        // Guaranteed today. Moving only the request is what silently downgraded a workload to
        // Burstable in the measured reference run, and the resize subresource rejects it outright.
        ApplyRequestDto requestOnly = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 25, null, null, null)), "IN_PLACE", false);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), requestOnly);

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.getMessage()).contains("QoS");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("a container the workload does not have is refused before any command is built")
    void anAbsentContainerIsRefused() {
        ApplyRequestDto wrongContainer = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("sidecar", 25, 250L, 25, 250L)), "IN_PLACE", true);

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), wrongContainer);

        assertThat(refusal.field()).isEqualTo("containers[0].name");
    }

    @Test
    @DisplayName("a value below the floor is accepted when it does not take the container any lower")
    void aContainerAlreadyBelowItsFloorIsNotForcedUp() {
        // Floors clamp decreases only: a workload already below its floor is never raised to reach
        // it, so a body that keeps it where it is must not be refused for being below it.
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workload(new ResourceEnvelope(10, 10, 262_144_000L, 262_144_000L))),
                new NodeCapacity(1930, 3_454_423_040L)));
        givenExecutorApplies();
        // CPU stays at 10m, which is below the 25m floor and exactly where it already is; memory
        // stays at the floor. Neither is a decrease, so neither is refused.
        ApplyRequestDto stayLow = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 10, 250L, 10, 250L)), "IN_PLACE", true);

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                stayLow);

        assertThat(response.state()).isEqualTo("ACCEPTED");
    }

    // ---------------------------------------------------------------- accepted

    @Test
    @DisplayName("a dry run answers 202 ACCEPTED with the DRY_RUN result, and records no apply")
    void aDryRunIsSubmittedAndNotRecorded() {
        when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("ignored"));
        when(executor.proof(anyString())).thenReturn(Optional.of(new ResizeProof(
                ResizeProof.Outcome.DRY_RUN, "Dry run: no patch was sent to the cluster.", false,
                List.of(new ResizeProof.ContainerResult("api", ResizeProof.ContainerState.DRY_RUN,
                        null, new ResourceEnvelope(25, 25, 262_144_000L, 262_144_000L), false)),
                List.of())));

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(true));

        assertThat(response.correlationId()).isNotBlank();
        assertThat(response.state()).isEqualTo("ACCEPTED");
        assertThat(response.dryRun()).isTrue();
        assertThat(response.result().terminal()).isEqualTo("DRY_RUN");
        assertThat(response.result().method()).isEqualTo("IN_PLACE");
        assertThat(response.result().restartFree()).isFalse();
        assertThat(response.result().containers().get(0).requested().memRequestMi())
                .as("memory is published in whole MiB, the contract's unit")
                .isEqualTo(250L);
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a confirmed apply records last_applied_at, which is what makes the cooldown real")
    void aConfirmedApplyIsRecorded() {
        givenExecutorApplies();

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.dryRun()).isFalse();
        assertThat(response.result().terminal()).isEqualTo("APPLIED");
        assertThat(response.result().restartFree()).isTrue();
        assertThat(response.result().pods().get(0).samePod()).isTrue();
        assertThat(response.result().pods().get(0).containers().get(0).restartCountAfter()).isZero();
        verify(policyService).recordApplied(7L, 42L, REF,
                WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND).withWindowHours(72),
                Instant.parse("2026-10-11T12:00:00Z"));
    }

    @Test
    @DisplayName("with no row, an apply records the configured defaults (RECOMMEND, 72h), never the Java OFF")
    void anApplyRecordsThePolicyEndpointOneReported() {
        // The UAT defect: the apply path fell back to the bare Java defaults, whose mode is OFF, so
        // the row recordApplied created turned a RECOMMEND workload OFF and endpoint 1 then
        // reported POLICY_OFF for it. The fallback must be the read path's own.
        givenExecutorApplies();

        service().apply(principal(), readWriteAccount(), TARGET, REF, body(false));

        verify(policyService).resolve(42L, REF, METRICS.defaultsPolicy());
        ArgumentCaptor<WorkloadPolicy> recorded = ArgumentCaptor.forClass(WorkloadPolicy.class);
        verify(policyService).recordApplied(any(), any(), any(), recorded.capture(), any());
        assertThat(recorded.getValue().mode()).isEqualTo(WorkloadMode.RECOMMEND);
        assertThat(recorded.getValue().windowHours()).isEqualTo(72);
        assertThat(recorded.getValue())
                .as("exactly the policy endpoint 1 reports for a workload with no row")
                .isEqualTo(METRICS.defaultsPolicy());
    }

    @Test
    @DisplayName("a second real apply inside the cooldown is refused, and nothing reaches the cluster")
    void aSecondApplyInsideTheCooldownIsRefused() {
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-11T11:59:00Z")));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(false));

        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.field()).isEqualTo("name");
        assertThat(refusal.getMessage()).startsWith("COOLDOWN:").contains("60-minute");
        verify(executor, never()).submit(any(), any(), any());
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a dry run is held by the cooldown too, so it predicts the real apply")
    void aDryRunIsHeldByTheCooldownToo() {
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-11T11:59:00Z")));

        assertThat(refuse(principal(), readWriteAccount(), body(true)).getMessage())
                .startsWith("COOLDOWN:");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("an OOM-driven memory-only raise inside the cooldown goes through, as the engine publishes it")
    void aMemoryOnlyRaiseIsExemptFromTheCooldown() {
        // Review-2 SF1: 1 GiB -> 250Mi is applied, the container is OOM-killed twenty minutes later,
        // and endpoint 1 publishes a memory raise under OOM_RISK. Refusing it would keep the
        // container being killed for the rest of the hour.
        givenExecutorApplies();
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-11T11:59:00Z")));
        ApplyRequestDto raise = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", null, 1280L, null, 1280L)), "IN_PLACE", false);

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF, raise);

        assertThat(response.state()).isEqualTo("ACCEPTED");
        verify(executor).submit(any(), any(), any());
    }

    @Test
    @DisplayName("a second real apply on a workload whose rollout is still running is refused 409, and sends nothing")
    void oneRealApplyPerWorkloadAtATime() throws Exception {
        // Review-2 SF5: the cooldown is stamped only after the rollout, so without a lock a second
        // request read last_applied_at while the first was still polling its canary.
        java.util.concurrent.CountDownLatch inRollout = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(executor.submit(any(), any(), any())).thenAnswer(invocation -> {
            ActionCommand command = invocation.getArgument(0);
            if (!command.dryRun()) {
                inRollout.countDown();
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            return ActionSubmission.accepted(command.correlationId());
        });
        when(executor.proof(anyString())).thenReturn(Optional.empty());
        VerticalApplyService service = service();

        java.util.concurrent.ExecutorService first = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ApplyResponseDto> running =
                    first.submit(() -> service.apply(principal(), readWriteAccount(), TARGET, REF, body(false)));
            assertThat(inRollout.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            VerticalApplyService.ApplyRefusedException second = catchRefusal(
                    () -> service.apply(principal(), readWriteAccount(), TARGET, REF, body(false)));
            assertThat(second.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(second.code()).isEqualTo("K8S_APPLY_IN_PROGRESS");
            assertThat(service.apply(principal(), readWriteAccount(), TARGET, REF, body(true)).dryRun())
                    .as("a dry run sends nothing, so it is not held by the lock")
                    .isTrue();

            release.countDown();
            assertThat(running.get(10, java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("ACCEPTED");
        } finally {
            release.countDown();
            first.shutdownNow();
        }
        assertThat(service.apply(principal(), readWriteAccount(), TARGET, REF, body(false)).state())
                .as("the lock is released when the rollout returns")
                .isEqualTo("ACCEPTED");
        verify(executor, org.mockito.Mockito.times(3)).submit(any(), any(), any());
    }

    @Test
    @DisplayName("with disagreeing pods, a raise is judged against the smallest live size, as in the engine (SF2)")
    void aRaiseIsJudgedAgainstTheSmallestLivePod() {
        List<ApplyRequestDto.Container> raise =
                List.of(new ApplyRequestDto.Container("api", null, 313L, null, 313L));
        ClusterWorkloadInventory.Workload agreeing = workload();
        ClusterWorkloadInventory.Workload disagreeing = new ClusterWorkloadInventory.Workload(REF, 3,
                false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of(),
                        new ResourceEnvelope(1000, 1000, 262_144_000L, 262_144_000L))),
                agreeing.pods());

        assertThat(VerticalApplyService.isMemoryOnlyIncrease(raise, agreeing))
                .as("313Mi is below the 1 GiB every pod runs")
                .isFalse();
        assertThat(VerticalApplyService.isMemoryOnlyIncrease(raise, disagreeing))
                .as("313Mi is above the 250Mi the smallest pods run, which the OOM guard judges against")
                .isTrue();
    }

    @Test
    @DisplayName("inside the cooldown, a body with any CPU field or any memory decrease is still refused")
    void onlyAMemoryRaiseIsExempt() {
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-11T11:59:00Z")));

        for (ApplyRequestDto.Container container : List.of(
                new ApplyRequestDto.Container("api", 1200, 1280L, 1200, 1280L),   // CPU moves too
                new ApplyRequestDto.Container("api", null, 512L, null, 512L),     // memory decrease
                new ApplyRequestDto.Container("api", null, 1024L, null, 1024L))) { // equal, not a raise
            assertThat(refuse(principal(), readWriteAccount(),
                    new ApplyRequestDto(List.of(container), "IN_PLACE", true)).getMessage())
                    .startsWith("COOLDOWN:");
        }
        verify(executor, never()).submit(any(), any(), any());
    }

    // ---------------------------------------------------------------- the engine's holds (review S1)

    @Test
    @DisplayName("a single-replica workload is refused while the policy protects it")
    void aSingleReplicaWorkloadIsRefused() {
        givenInventory(holdWorkload(1, false, false, null));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(false));

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.getMessage()).startsWith("SINGLE_REPLICA:");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("a single-replica workload is applied when its policy does not protect it")
    void anUnprotectedSingleReplicaWorkloadIsApplied() {
        givenInventory(holdWorkload(1, false, false, null));
        when(policyService.resolve(any(), any(), any()))
                .thenReturn(METRICS.defaultsPolicy().withProtectSingleReplica(false));
        givenExecutorApplies();

        assertThat(service().apply(principal(), readWriteAccount(), TARGET, REF, body(false))
                .result().terminal()).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("a workload mid-rollout is refused, dry run included")
    void aRolloutInProgressIsRefused() {
        givenInventory(holdWorkload(2, true, false, null));

        assertThat(refuse(principal(), readWriteAccount(), body(true)).getMessage())
                .startsWith("ROLLOUT_IN_PROGRESS:");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("a workload locked with xamops.io/lock=active is refused")
    void aLockedWorkloadIsRefused() {
        givenInventory(holdWorkload(2, false, true, null));

        assertThat(refuse(principal(), readWriteAccount(), body(false)).getMessage())
                .startsWith("LOCKED:")
                .contains("xamops.io/lock=active");
        verify(executor, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("moving CPU on a workload whose HPA scales on CPU is refused; memory alone is applied")
    void cpuIsRefusedUnderACpuHpa() {
        givenInventory(holdWorkload(2, false, false, HpaView.cpuHpa("api-hpa", REF, 50, 2, 5)));

        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), body(false));
        assertThat(refusal.getMessage()).startsWith("HPA_CPU_MANAGED:");
        assertThat(refusal.field()).isEqualTo("containers[0].cpuRequestM");

        givenExecutorApplies();
        ApplyRequestDto memoryOnly = new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 1000, 250L, 1000, 250L)), "IN_PLACE", false);
        assertThat(service().apply(principal(), readWriteAccount(), TARGET, REF, memoryOnly)
                .result().terminal())
                .as("a CPU value equal to the current one does not move CPU")
                .isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("an HPA on another workload does not hold this one")
    void anHpaOnAnotherWorkloadDoesNotHold() {
        givenInventory(holdWorkload(2, false, false,
                HpaView.cpuHpa("other-hpa", WorkloadRef.of("team-a", "Deployment", "other"), 50, 2, 5)));
        givenExecutorApplies();

        assertThat(service().apply(principal(), readWriteAccount(), TARGET, REF, body(false))
                .result().terminal()).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("once the cooldown has elapsed, the next apply goes through and is recorded")
    void anApplyAfterTheCooldownGoesThrough() {
        when(policyService.resolve(any(), any(), any())).thenReturn(METRICS.defaultsPolicy()
                .withLastAppliedAt(Instant.parse("2026-10-11T10:59:00Z")));
        givenExecutorApplies();

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.result().terminal()).isEqualTo("APPLIED");
        verify(policyService).recordApplied(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-10-11T12:00:00Z")));
    }

    @Test
    @DisplayName("a Deferred outcome is reported as Deferred and records no apply")
    void aDeferredOutcomeIsNotRecordedAsApplied() {
        when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("ignored"));
        when(executor.proof(anyString())).thenReturn(Optional.of(new ResizeProof(
                ResizeProof.Outcome.DEFERRED,
                "The kubelet deferred the resize and will retry when there is capacity.", false,
                List.of(new ResizeProof.ContainerResult("api", ResizeProof.ContainerState.DEFERRED,
                        "waiting for node capacity",
                        new ResourceEnvelope(25, 25, 262_144_000L, 262_144_000L), false)),
                List.of())));

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.result().terminal()).isEqualTo("DEFERRED");
        assertThat(response.result().containers().get(0).state()).isEqualTo("DEFERRED");
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PARTIALLY_APPLIED, RESTART_DETECTED and NOT_READY changed pods, so each starts the cooldown")
    void everyOutcomeThatChangedAPodStartsTheCooldown() {
        // Review-2 SF4: the confirmation promises 60 minutes, and these three leave pods at the new size.
        for (ResizeProof.Outcome outcome : List.of(ResizeProof.Outcome.PARTIALLY_APPLIED,
                ResizeProof.Outcome.RESTART_DETECTED, ResizeProof.Outcome.NOT_READY)) {
            org.mockito.Mockito.clearInvocations(policyService);
            when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("ignored"));
            when(executor.proof(anyString())).thenReturn(Optional.of(new ResizeProof(outcome,
                    outcome + " on the canary.", false, List.of(),
                    List.of(podProof(SMALL_ENVELOPE, null)))));

            service().apply(principal(), readWriteAccount(), TARGET, REF, body(false));

            verify(policyService).recordApplied(any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("a declined pod proved back where it was starts no cooldown; one not proved back does")
    void aRevertedPodStartsNoCooldownButAnUnprovedOneDoes() {
        when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("ignored"));
        when(executor.proof(anyString())).thenReturn(Optional.of(new ResizeProof(
                ResizeProof.Outcome.DEFERRED, "Deferred, and put back.", false, List.of(),
                List.of(podProof(GUARANTEED_1CPU_1GI, new ResizeProof.Revert(
                        ResizeProof.RevertState.REVERTED, "Put back."))))));

        ApplyResponseDto putBack = service().apply(principal(), readWriteAccount(), TARGET, REF, body(false));

        assertThat(putBack.result().reverted()).isTrue();
        assertThat(putBack.result().pods().get(0).changed()).isFalse();
        assertThat(putBack.result().pods().get(0).revert().state()).isEqualTo("REVERTED");
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());

        when(executor.proof(anyString())).thenReturn(Optional.of(new ResizeProof(
                ResizeProof.Outcome.DEFERRED, "Deferred, and not proved back.", false, List.of(),
                List.of(podProof(GUARANTEED_1CPU_1GI, new ResizeProof.Revert(
                        ResizeProof.RevertState.NOT_CONFIRMED, "Sent back, not confirmed."))))));

        ApplyResponseDto notProved = service().apply(principal(), readWriteAccount(), TARGET, REF, body(false));

        assertThat(notProved.result().reverted()).isFalse();
        // The spec may still hold the new size, so the kubelet can still apply it: start the cooldown.
        verify(policyService).recordApplied(any(), any(), any(), any(), any());
    }

    private static final ResourceEnvelope SMALL_ENVELOPE = new ResourceEnvelope(25, 25, 262_144_000L, 262_144_000L);

    /** One pod that went from 1 CPU / 1 GiB to {@code after}, with the given revert. */
    private static ResizeProof.PodProof podProof(ResourceEnvelope after, ResizeProof.Revert revert) {
        return new ResizeProof.PodProof("api-1", "uid-1", "uid-1", true,
                List.of(new ResizeProof.ContainerProof("api", "containerd://c1", "containerd://c1", 0, 0,
                        GUARANTEED_1CPU_1GI, after)), revert, null);
    }

    @Test
    @DisplayName("an outcome the executor did not record publishes result: null, never a guess")
    void anUnknownOutcomeIsNull() {
        when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("ignored"));
        when(executor.proof(anyString())).thenReturn(Optional.empty());

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(response.result()).isNull();
        verify(policyService, never()).recordApplied(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the submitted numbers reach the executor in the engine's units, diffed as sent")
    void theCommandCarriesTheSubmittedNumbers() {
        givenExecutorApplies();

        service().apply(principal(), readWriteAccount(), TARGET, REF, body(false));

        ArgumentCaptor<ActionCommand> command = ArgumentCaptor.forClass(ActionCommand.class);
        verify(executor).submit(command.capture(), any(), any());
        ContainerApplyTarget target = command.getValue().containers().get(0);
        assertThat(target.containerName()).isEqualTo("api");
        assertThat(target.cpuRequestMillis()).isEqualTo(25);
        assertThat(target.memRequestBytes())
                .as("MiB on the wire, bytes inside the engine, converted exactly once")
                .isEqualTo(262_144_000L);
        assertThat(command.getValue().notValidAfter())
                .as("a command applied long after it was computed writes stale numbers")
                .isEqualTo(Instant.parse("2026-10-11T12:00:00Z")
                        .plus(VerticalApplyService.COMMAND_VALIDITY));
        assertThat(command.getValue().dryRun()).isFalse();
    }

    // ---------------------------------------------------------------- write gates for endpoint 1

    @Test
    @DisplayName("a permitted caller on a read-write connection has no write gates")
    void aPermittedReadWriteCallerHasNoWriteGates() {
        assertThat(service().writeGates(principal(), readWriteAccount())).isEmpty();
    }

    @Test
    @DisplayName("a read-only connection reports READ_ONLY_CONNECTION, the code apply refuses with")
    void aReadOnlyConnectionIsAWriteGate() {
        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");

        assertThat(service().writeGates(principal(), readOnly)).containsExactly("READ_ONLY_CONNECTION");
        assertThat(refuse(principal(), readOnly, body(true)).code())
                .isEqualTo("K8S_READ_ONLY_CONNECTION");
    }

    @Test
    @DisplayName("a demo or unpermitted caller reports the permission refusal's own gate, first")
    void thePermissionRefusalIsAWriteGate() {
        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");
        when(writePermission.decide(any(), any())).thenReturn(new AutomationWritePermission.Decision(
                false, ReasonCode.DEMO, AutomationWritePermission.Detail.DEMO,
                "Automation is disabled for the shared demo identity.", "K8S_DEMO"));
        assertThat(service().writeGates(principal(), readOnly))
                .containsExactly("DEMO", "READ_ONLY_CONNECTION");

        when(writePermission.decide(any(), any())).thenReturn(new AutomationWritePermission.Decision(
                false, ReasonCode.NO_USER_PERMISSION, AutomationWritePermission.Detail.IMPERSONATION,
                "Automation cannot be changed from a Login As session.", "K8S_NO_USER_PERMISSION"));
        assertThat(service().writeGates(principal(), readWriteAccount()))
                .as("a Login-As session is NO_USER_PERMISSION, as on endpoint 3")
                .containsExactly("NO_USER_PERMISSION");
    }

    @Test
    @DisplayName("endpoint 1's gate and endpoint 3's refusal name the same rule for the same caller")
    void theReadAndTheRefusalAgreeOnTheRule() {
        // The UAT 403 of 2026-10-12: a user with tab rows but no automation key. Endpoint 3 answered
        // K8S_NO_USER_PERMISSION with a message only the TAB_GRANT rule writes; endpoint 1 has to
        // carry the same gate and the same detail, or the UI offers an Apply the server refuses.
        for (String detail : List.of(AutomationWritePermission.Detail.TAB_GRANT,
                AutomationWritePermission.Detail.IMPERSONATION, AutomationWritePermission.Detail.ROLE,
                AutomationWritePermission.Detail.NO_PRINCIPAL)) {
            when(writePermission.decide(any(), any())).thenReturn(new AutomationWritePermission.Decision(
                    false, ReasonCode.NO_USER_PERMISSION, detail, "Refused by " + detail + ".",
                    "K8S_NO_USER_PERMISSION"));

            VerticalApplyService.WriteGates gates = service().callerWriteGates(principal(), readWriteAccount());
            VerticalApplyService.ApplyRefusedException refusal = refuse(principal(), readWriteAccount(), body(true));

            assertThat(gates.codes()).containsExactly("NO_USER_PERMISSION");
            assertThat(gates.details()).containsExactly(org.assertj.core.api.Assertions.entry("NO_USER_PERMISSION", detail));
            assertThat(refusal.code()).isEqualTo("K8S_" + gates.codes().get(0));
            assertThat(refusal.detail()).as("the 403 body names the rule, not only the code").isEqualTo(detail);
            assertThat(refusal.body().detail()).isEqualTo(detail);
            assertThat(refusal.body().field()).isNull();
        }

        when(writePermission.decide(any(), any())).thenReturn(new AutomationWritePermission.Decision(
                false, ReasonCode.DEMO, AutomationWritePermission.Detail.DEMO,
                "This is the shared demo identity, so nothing is applied to a real cluster.", "K8S_DEMO"));
        assertThat(service().callerWriteGates(principal(), readWriteAccount()).details())
                .containsExactly(org.assertj.core.api.Assertions.entry("DEMO", "DEMO"));
        assertThat(refuse(principal(), readWriteAccount(), body(true)).body().detail()).isEqualTo("DEMO");
    }

    @Test
    @DisplayName("a permitted caller has no gate details, and a missing principal is NO_PRINCIPAL")
    void gateDetailsForThePermittedAndTheAbsent() {
        assertThat(service().callerWriteGates(principal(), readWriteAccount()).details()).isEmpty();

        VerticalApplyService.WriteGates anonymous = service().callerWriteGates(null, readWriteAccount());
        assertThat(anonymous.codes()).containsExactly("NO_USER_PERMISSION");
        assertThat(anonymous.details())
                .containsExactly(org.assertj.core.api.Assertions.entry("NO_USER_PERMISSION", "NO_PRINCIPAL"));

        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");
        assertThat(service().callerWriteGates(principal(), readOnly).details())
                .as("a read-only connection has no finer rule than its code")
                .isEmpty();
        assertThat(refuse(principal(), readOnly, body(true)).body().detail()).isNull();
    }

    @Test
    @DisplayName("an apply under RECOMMEND is accepted: endpoint 3 never requires AUTO")
    void anApplyDoesNotRequireAuto() {
        givenExecutorApplies();

        ApplyResponseDto response = service().apply(principal(), readWriteAccount(), TARGET, REF,
                body(false));

        assertThat(METRICS.defaultsPolicy().mode()).isEqualTo(WorkloadMode.RECOMMEND);
        assertThat(response.state()).isEqualTo("ACCEPTED");
        assertThat(response.result().terminal()).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("the permission gate is asked before anything else, and with this account")
    void thePermissionGateIsAskedFirst() {
        givenExecutorApplies();

        service().apply(principal(), readWriteAccount(), TARGET, REF, body(true));

        ArgumentCaptor<CloudAccount> asked = ArgumentCaptor.forClass(CloudAccount.class);
        verify(writePermission).decide(any(), asked.capture());
        assertThat(asked.getValue().getAwsAccountId())
                .as("entitlement is re-asked against the account this apply is for")
                .isEqualTo("102227938259");
    }

    // ---------------------------------------------------------------- fixtures

    private void givenExecutorApplies() {
        when(executor.submit(any(), any(), any())).thenReturn(ActionSubmission.accepted("ignored"));
        when(executor.proof(anyString())).thenReturn(Optional.of(new ResizeProof(
                ResizeProof.Outcome.APPLIED, null, true,
                List.of(new ResizeProof.ContainerResult("api", ResizeProof.ContainerState.APPLIED,
                        null, new ResourceEnvelope(25, 25, 262_144_000L, 262_144_000L), false)),
                List.of(new ResizeProof.PodProof("api-1", "uid-1", "uid-1", true,
                        List.of(new ResizeProof.ContainerProof("api", "containerd://c1",
                                "containerd://c1", 0, 0, GUARANTEED_1CPU_1GI,
                                new ResourceEnvelope(25, 25, 262_144_000L, 262_144_000L))))))));
    }

    private void assertField(ApplyRequestDto request, String field) {
        VerticalApplyService.ApplyRefusedException refusal =
                refuse(principal(), readWriteAccount(), request);

        assertThat(refusal.code()).isEqualTo("K8S_INVALID_POLICY");
        assertThat(refusal.field()).isEqualTo(field);
    }

    private VerticalApplyService.ApplyRefusedException refuse(ClientUserDetails principal,
                                                              CloudAccount account,
                                                              ApplyRequestDto request) {
        return catchRefusal(() -> service().apply(principal, account, TARGET, REF, request));
    }

    /**
     * Hand-rolled rather than AssertJ's {@code catchThrowableOfType}, whose argument order changed
     * between versions -- this is unambiguous and says what it wants.
     */
    private static VerticalApplyService.ApplyRefusedException catchRefusal(Runnable call) {
        try {
            call.run();
        } catch (VerticalApplyService.ApplyRefusedException refused) {
            return refused;
        }
        throw new AssertionError("the call was expected to be refused, and was not");
    }

    private VerticalApplyService service() {
        return service(new WorkloadAutoscalingProperties(true, true, true));
    }

    private VerticalApplyService service(WorkloadAutoscalingProperties properties) {
        return new VerticalApplyService(properties, writePermission, List.of(provider), inventory,
                executor, policyService, METRICS, FIXED);
    }

    private static ApplyRequestDto body(boolean dryRun) {
        return new ApplyRequestDto(
                List.of(new ApplyRequestDto.Container("api", 25, 250L, 25, 250L)), "IN_PLACE", dryRun);
    }

    private static ClusterWorkloadInventory.Workload workload() {
        return workload(GUARANTEED_1CPU_1GI);
    }

    private void givenInventory(ClusterWorkloadInventory.Workload workload) {
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workload), new NodeCapacity(1930, 3_454_423_040L)));
    }

    private static ClusterWorkloadInventory.Workload holdWorkload(int replicas, boolean rollout,
                                                                  boolean locked, HpaView hpa) {
        return new ClusterWorkloadInventory.Workload(REF, replicas, rollout, locked, hpa, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-1"),
                        new WorkloadPods.Pod("api-2", "uid-2"))));
    }

    private static ClusterWorkloadInventory.Workload workload(ResourceEnvelope current) {
        return new ClusterWorkloadInventory.Workload(REF, 2, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", current, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-1"),
                        new WorkloadPods.Pod("api-2", "uid-2"))));
    }

    private static ClientUserDetails principal(String... authorities) {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        for (String authority : authorities.length == 0 ? new String[]{"ROLE_XAMOPS"} : authorities) {
            granted.add(new SimpleGrantedAuthority(authority));
        }
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
