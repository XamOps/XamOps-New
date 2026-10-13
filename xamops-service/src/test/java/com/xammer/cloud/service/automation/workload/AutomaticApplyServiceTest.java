package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduled auto-apply loop's hard limits, with the read service, the apply service, the
 * repositories and the actor resolver all mocked so nothing reaches AWS or a cluster.
 *
 * <p>The loop reads eligibility and calls {@link VerticalApplyService#apply(ClientUserDetails,
 * CloudAccount, ClusterTarget, com.xammer.cloud.service.automation.workload.WorkloadRef,
 * ApplyRequestDto, ActionTrigger)} with {@link ActionTrigger#AUTO}; every assertion here is about
 * <em>whether</em> and <em>which</em> it hands to apply, never about the gates apply itself runs.
 */
class AutomaticApplyServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);

    /** The 12-digit AWS account number the fixtures carry; it must never reach the logs. */
    private static final String AWS_ACCOUNT_NUMBER = "102227938259";

    private AutoApplyProperties autoApplyProperties;
    private WorkloadAutoscalingProperties autoscalingProperties;
    private VerticalAutomationReadService readService;
    private VerticalApplyService applyService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private CloudAccountRepository cloudAccountRepository;
    private SystemActorResolver systemActorResolver;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger serviceLogger;

    @BeforeEach
    void setUp() {
        // enabled master switch; interval 15, cluster cooldown 30, breaker threshold 2.
        autoApplyProperties = new AutoApplyProperties(true, 15, 30, 2);
        // The engine kill switch is permitted (enabled + ready + an executor present).
        autoscalingProperties = new WorkloadAutoscalingProperties(true, true, true);
        readService = mock(VerticalAutomationReadService.class);
        applyService = mock(VerticalApplyService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        systemActorResolver = mock(SystemActorResolver.class);

        when(clusterConfigRepository.findAll()).thenReturn(List.of(config(42L)));
        when(cloudAccountRepository.findById(3L)).thenReturn(Optional.of(readWriteAccount()));
        when(systemActorResolver.resolve(7L)).thenReturn(Optional.of(systemActor()));
        when(readService.read(any(), any())).thenReturn(response(eligibleWorkload()));
        when(applyService.apply(any(), any(), any(), any(), any(), any()))
                .thenReturn(accepted());

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        serviceLogger = context.getLogger(AutomaticApplyService.class);
        logs = new ListAppender<>();
        logs.setContext(context);
        logs.start();
        serviceLogger.addAppender(logs);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logs != null) {
            serviceLogger.detachAppender(logs);
        }
    }

    private String allLogText() {
        StringBuilder joined = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            joined.append(event.getFormattedMessage()).append('\n');
        }
        return joined.toString();
    }

    // ---------------------------------------------------------------- the global kill switch

    @Test
    @DisplayName("the master switch off means the loop does nothing: no read, no apply")
    void masterSwitchOffDoesNothing() {
        autoApplyProperties = new AutoApplyProperties(false, 15, 30, 2);

        service().runCycle();

        verifyNoInteractions(clusterConfigRepository, readService, applyService);
    }

    @Test
    @DisplayName("isAutoApplyPermitted() false means the loop does nothing, even with the master switch on")
    void engineKillSwitchOffDoesNothing() {
        autoscalingProperties = new WorkloadAutoscalingProperties(true, false, true);

        service().runCycle();

        verifyNoInteractions(clusterConfigRepository, readService, applyService);
    }

    // ---------------------------------------------------------------- selection + one per cluster

    @Test
    @DisplayName("one eligible AUTO workload is applied with trigger=AUTO and a non-dry-run body")
    void anEligibleWorkloadIsAppliedAsAuto() {
        service().runCycle();

        ArgumentCaptor<ApplyRequestDto> body = ArgumentCaptor.forClass(ApplyRequestDto.class);
        verify(applyService).apply(eq(systemActor()), any(), any(), any(), body.capture(),
                eq(ActionTrigger.AUTO));
        assertThat(body.getValue().dryRun()).isFalse();
        assertThat(body.getValue().applyMethod()).isEqualTo("IN_PLACE");
    }

    @Test
    @DisplayName("at most one workload is applied per cluster per cycle, even when several are eligible")
    void atMostOneApplyPerClusterPerCycle() {
        when(readService.read(any(), any())).thenReturn(response(
                eligibleWorkload("team-a", "api"),
                eligibleWorkload("team-a", "worker"),
                eligibleWorkload("team-b", "db")));

        service().runCycle();

        verify(applyService, times(1)).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("only mode==AUTO && autoApplyEligible is selected: RECOMMEND and ineligible are skipped")
    void onlyAutoAndEligibleIsSelected() {
        VerticalWorkloadDto recommend = workload("ns", "recommend-mode", "RECOMMEND", true, true);
        VerticalWorkloadDto ineligible = workload("ns", "not-eligible", "AUTO", false, true);
        when(readService.read(any(), any())).thenReturn(response(recommend, ineligible));

        service().runCycle();

        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the apply body contains only changed fields; equal fields are null")
    void theApplyBodyCarriesOnlyChangedFields() {
        // current: cpuReq 1000, memReq 1024, cpuLimit 1000, memLimit 1024
        // recommended: cpuReq 500 (changed), memReq 1024 (equal), cpuLimit null (leave), memLimit 2048 (changed)
        VerticalWorkloadDto.ResourcesDto current =
                new VerticalWorkloadDto.ResourcesDto(1000, 1024L, 1000, 1024L);
        VerticalWorkloadDto.ResourcesDto recommended =
                new VerticalWorkloadDto.ResourcesDto(500, 1024L, null, 2048L);
        when(readService.read(any(), any())).thenReturn(response(
                workloadWith("ns", "api", current, recommended)));

        service().runCycle();

        ArgumentCaptor<ApplyRequestDto> body = ArgumentCaptor.forClass(ApplyRequestDto.class);
        verify(applyService).apply(any(), any(), any(), any(), body.capture(), eq(ActionTrigger.AUTO));
        ApplyRequestDto.Container container = body.getValue().containers().get(0);
        assertThat(container.cpuRequestM()).as("changed").isEqualTo(500);
        assertThat(container.memRequestMi()).as("equal -> null, leave alone").isNull();
        assertThat(container.cpuLimitM()).as("not recommended -> null").isNull();
        assertThat(container.memLimitMi()).as("changed").isEqualTo(2048L);
    }

    // ---------------------------------------------------------------- skipped clusters

    @Test
    @DisplayName("a read-only connection is skipped without calling apply")
    void aReadOnlyConnectionIsSkipped() {
        CloudAccount readOnly = readWriteAccount();
        readOnly.setAccessType("read-only");
        when(cloudAccountRepository.findById(3L)).thenReturn(Optional.of(readOnly));

        service().runCycle();

        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a cluster whose read is not ready / access not configured is skipped")
    void aNotReadyClusterIsSkipped() {
        when(readService.read(any(), any())).thenReturn(responseWithGate("CONTROLLER_NOT_READY"));

        service().runCycle();

        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unreachable cluster (clusterAccess != CONFIGURED) is skipped")
    void anUnreachableClusterIsSkipped() {
        VerticalEngineResponse unreachable = new VerticalEngineResponse(
                engineStatus("UNREACHABLE"), List.of("CLUSTER_UNREACHABLE"),
                List.of(eligibleWorkload()));
        when(readService.read(any(), any())).thenReturn(unreachable);

        service().runCycle();

        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a demo-only tenant (no automation-capable actor) is skipped")
    void aTenantWithoutASystemActorIsSkipped() {
        when(systemActorResolver.resolve(7L)).thenReturn(Optional.empty());

        service().runCycle();

        verify(readService, never()).read(any(), any());
        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- per-cluster cooldown

    @Test
    @DisplayName("a second apply within the per-cluster cooldown is suppressed")
    void perClusterCooldownSuppressesASecondApply() {
        AutomaticApplyService service = service();

        service.runCycle();   // applies, stamps the cooldown at 12:00:00
        service.runCycle();   // 30-minute cooldown has not elapsed: suppressed

        verify(applyService, times(1)).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("after the per-cluster cooldown elapses, the next cycle applies again")
    void afterTheCooldownElapsesItAppliesAgain() {
        // A clock that advances 31 minutes between the two cycles' cooldown checks.
        MovableClock clock = new MovableClock(Instant.parse("2026-10-13T12:00:00Z"));
        AutomaticApplyService service = service(clock);

        service.runCycle();                       // applies, stamps 12:00
        clock.advanceMinutes(31);                 // past the 30-minute cooldown
        service.runCycle();                       // applies again

        verify(applyService, times(2)).apply(any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- circuit breaker

    @Test
    @DisplayName("the breaker opens after the threshold of consecutive failures and suppresses the cluster")
    void theCircuitBreakerOpensAndSuppressesTheCluster() {
        // Threshold is 2. Apply fails every time; cooldown is only stamped on success, so each cycle
        // reaches apply until the breaker trips.
        when(applyService.apply(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("the kubelet declined"));

        AutomaticApplyService service = service();
        service.runCycle();   // failure 1
        service.runCycle();   // failure 2 -> breaker opens
        service.runCycle();   // suppressed
        service.runCycle();   // suppressed

        verify(applyService, times(2)).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a success resets the consecutive-failure count, so one later failure does not trip the breaker")
    void aSuccessResetsTheFailureCount() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-13T12:00:00Z"));
        AutomaticApplyService service = service(clock);

        when(applyService.apply(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("transient"))   // cycle 1: failure 1
                .thenReturn(accepted())                          // cycle 2: success -> reset
                .thenThrow(new RuntimeException("transient"));   // cycle 3: failure 1 again, not 2

        service.runCycle();                 // fail (count 1)
        clock.advanceMinutes(31);
        service.runCycle();                 // success (count reset, cooldown stamped)
        clock.advanceMinutes(31);
        service.runCycle();                 // fail (count 1), breaker still closed
        clock.advanceMinutes(31);
        service.runCycle();                 // reaches apply again because breaker never opened

        verify(applyService, times(4)).apply(any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- observability (FEAT-003)

    @Test
    @DisplayName("an applied workload logs a per-decision line with correlationId and outcome")
    void anAppliedWorkloadLogsAPerDecisionLine() {
        service().runCycle();

        assertThat(allLogText())
                .contains("Auto-apply applied")
                .contains("team-a/overprovisioned-api")
                .contains("c0ffee")   // correlationId from the response
                .contains("APPLIED"); // the result's terminal outcome
    }

    @Test
    @DisplayName("every cycle ends with one summary line of its counts")
    void everyCycleLogsASummaryLine() {
        service().runCycle();

        List<String> summaries = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("Auto-apply cycle summary"))
                .toList();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0))
                .contains("1 scanned")
                .contains("1 eligible")
                .contains("1 applied")
                .contains("0 skipped")
                .contains("0 failed");
    }

    @Test
    @DisplayName("a skipped cluster logs a concrete reason, not a bare skip")
    void aSkippedClusterLogsAConcreteReason() {
        when(readService.read(any(), any())).thenReturn(responseWithGate("CONTROLLER_NOT_READY"));

        service().runCycle();

        assertThat(allLogText())
                .contains("Auto-apply skipped")
                .contains("CONTROLLER_NOT_READY");
    }

    @Test
    @DisplayName("a failed apply logs the refusal code / exception class in the per-decision line")
    void aFailedApplyLogsItsCode() {
        when(applyService.apply(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("the kubelet declined"));

        service().runCycle();

        assertThat(allLogText())
                .contains("Auto-apply failed")
                .contains("code RuntimeException");
    }

    @Test
    @DisplayName("no log line ever contains the 12-digit AWS account number")
    void noLogContainsTheAwsAccountNumber() {
        // Exercise an applied, a skipped and a failed path so every log line is produced.
        AutomaticApplyService applied = service();
        applied.runCycle();

        when(readService.read(any(), any())).thenReturn(responseWithGate("CONTROLLER_NOT_READY"));
        service().runCycle();

        when(readService.read(any(), any())).thenReturn(response(eligibleWorkload()));
        when(applyService.apply(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("the kubelet declined"));
        service().runCycle();

        assertThat(allLogText())
                .describedAs("logs must name clusters via describe(), never the account number")
                .doesNotContain(AWS_ACCOUNT_NUMBER)
                .contains("cluster xamops-test-1 (config 42, client 7)");
    }

    // ---------------------------------------------------------------- fixtures

    private AutomaticApplyService service() {
        return service(FIXED);
    }

    private AutomaticApplyService service(Clock clock) {
        return new AutomaticApplyService(autoApplyProperties, autoscalingProperties, readService,
                applyService, clusterConfigRepository, cloudAccountRepository, systemActorResolver,
                clock);
    }

    private static KubernetesClusterConfig config(Long id) {
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(id);
        config.setCloudAccountId(3L);
        config.setClusterName("xamops-test-1");
        config.setRegion("ap-south-1");
        return config;
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

    private static ClientUserDetails systemActor() {
        return new ClientUserDetails("root@example.com", "[PROTECTED]",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 7L, 10L, null);
    }

    private static VerticalEngineResponse.EngineStatus engineStatus(String clusterAccess) {
        return new VerticalEngineResponse.EngineStatus(true, true, clusterAccess, "DIRECT",
                new VerticalEngineResponse.ExecutionPathDetail(
                        new VerticalEngineResponse.PathAvailability(true, null),
                        new VerticalEngineResponse.AgentAvailability(false, "AGENT_NOT_INSTALLED", null)),
                new VerticalEngineResponse.MetricsSource(true, "prometheus"),
                VerticalEngineResponse.LastCycle.never());
    }

    private static VerticalEngineResponse response(VerticalWorkloadDto... workloads) {
        return new VerticalEngineResponse(engineStatus("CONFIGURED"), List.of(), List.of(workloads));
    }

    private static VerticalEngineResponse responseWithGate(String gate) {
        return new VerticalEngineResponse(engineStatus("CONFIGURED"), List.of(gate),
                List.of(eligibleWorkload()));
    }

    private static VerticalWorkloadDto eligibleWorkload() {
        return eligibleWorkload("team-a", "overprovisioned-api");
    }

    private static VerticalWorkloadDto eligibleWorkload(String namespace, String name) {
        return workload(namespace, name, "AUTO", true, true);
    }

    /** An AUTO + eligible workload whose single container recommends a change (1000m -> 500m CPU). */
    private static VerticalWorkloadDto workload(String namespace, String name, String mode,
                                                boolean eligible, boolean recommendsChange) {
        VerticalWorkloadDto.ResourcesDto current =
                new VerticalWorkloadDto.ResourcesDto(1000, 1024L, 1000, 1024L);
        VerticalWorkloadDto.ResourcesDto recommended = recommendsChange
                ? new VerticalWorkloadDto.ResourcesDto(500, 1024L, 500, 1024L)
                : current;
        return workloadWith(namespace, name, current, recommended, mode, eligible);
    }

    private static VerticalWorkloadDto workloadWith(String namespace, String name,
                                                    VerticalWorkloadDto.ResourcesDto current,
                                                    VerticalWorkloadDto.ResourcesDto recommended) {
        return workloadWith(namespace, name, current, recommended, "AUTO", true);
    }

    private static VerticalWorkloadDto workloadWith(String namespace, String name,
                                                    VerticalWorkloadDto.ResourcesDto current,
                                                    VerticalWorkloadDto.ResourcesDto recommended,
                                                    String mode, boolean eligible) {
        VerticalWorkloadDto.ContainerDto container = new VerticalWorkloadDto.ContainerDto(
                "api", "Guaranteed", current, recommended, List.of(), null);
        return new VerticalWorkloadDto(
                new VerticalWorkloadDto.WorkloadRefDto(namespace, "Deployment", name),
                new VerticalWorkloadDto.PolicyDto(mode, 15, 95, "72h", "IN_PLACE"),
                2, null, null, null, List.of(), List.of(container), null, eligible, null, null);
    }

    private static ApplyResponseDto accepted() {
        return new ApplyResponseDto("c0ffee", "ACCEPTED", false,
                new ApplyResponseDto.Result("IN_PLACE", "APPLIED", null, true, List.of(), List.of()));
    }

    /** A clock the test advances by hand, for the cooldown and breaker-reset timelines. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        private void advanceMinutes(long minutes) {
            this.now = this.now.plus(java.time.Duration.ofMinutes(minutes));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
