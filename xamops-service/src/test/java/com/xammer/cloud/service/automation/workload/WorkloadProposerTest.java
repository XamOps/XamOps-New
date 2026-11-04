package com.xammer.cloud.service.automation.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.proposal.ApprovalModeReader;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import com.xammer.cloud.service.automation.proposal.ProposalService.NewProposal;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
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

/** The Java-side proposer (design 3.3c): gates, selection, the dry-run probe, and what it stores. */
class WorkloadProposerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private K8sAutomationReadinessProperties readiness;
    private WorkloadAutoscalingProperties autoscaling;
    private VerticalAutomationReadService readService;
    private VerticalApplyService applyService;
    private KubernetesClusterConfigRepository clusters;
    private CloudAccountRepository accounts;
    private SystemActorResolver actors;
    private ProposalService proposalService;
    private ApprovalModeReader modeReader;
    private WorkloadProposer proposer;

    @BeforeEach
    void setUp() {
        readiness = new K8sAutomationReadinessProperties();
        readiness.setManualApproval(true);
        autoscaling = new WorkloadAutoscalingProperties(true, true, true);
        readService = mock(VerticalAutomationReadService.class);
        applyService = mock(VerticalApplyService.class);
        clusters = mock(KubernetesClusterConfigRepository.class);
        accounts = mock(CloudAccountRepository.class);
        actors = mock(SystemActorResolver.class);
        proposalService = mock(ProposalService.class);
        modeReader = mock(ApprovalModeReader.class);
        when(modeReader.storedMode(42L)).thenReturn("MANUAL");
        when(clusters.findByAutomationApprovalMode("MANUAL")).thenReturn(List.of(config()));
        when(accounts.findById(3L)).thenReturn(Optional.of(account("read-write")));
        when(actors.resolve(7L)).thenReturn(Optional.of(actor()));
        when(readService.read(any(), any(), any())).thenReturn(response(workload("api", "AUTO", true, 500, 220)));
        when(applyService.apply(any(), any(), any(), any(), any(), any())).thenReturn(proof());
        when(proposalService.upsertWorkload(any(), any(), any())).thenReturn(ProposalService.UpsertResult.ACCEPTED);
        build();
    }

    private void build() {
        AutomaticApplyService autoApply = new AutomaticApplyService(new AutoApplyProperties(false, 15, 15, 3),
                autoscaling, readService, applyService, clusters, accounts, actors);
        proposer = new WorkloadProposer(readiness, autoscaling, readService, applyService, clusters, autoApply, actors,
                proposalService, modeReader);
    }

    @Test
    void aProbeIsADryRunWithTheProposalTriggerAndItsResultIsStoredAsAPendingOffer() throws Exception {
        assertThat(proposer.proposeForManualClusters()).isEqualTo(1);

        ArgumentCaptor<ApplyRequestDto> request = ArgumentCaptor.forClass(ApplyRequestDto.class);
        verify(applyService).apply(any(), any(), any(), any(), request.capture(), eq(ActionTrigger.PROPOSAL));
        assertThat(request.getValue().dryRun()).isTrue();
        assertThat(request.getValue().applyMethod()).isEqualTo("IN_PLACE");
        assertThat(request.getValue().containers()).hasSize(1);

        ArgumentCaptor<NewProposal> np = ArgumentCaptor.forClass(NewProposal.class);
        verify(proposalService).upsertWorkload(eq(42L), eq(7L), np.capture());
        NewProposal p = np.getValue();
        assertThat(p.verdict()).isEqualTo("ALLOWED");
        assertThat(p.dedupeKey()).isEqualTo("WORKLOAD_RESIZE:IN_PLACE:vertical-engine:shop/Deployment/api");
        assertThat(p.targetRef()).isEqualTo("shop/Deployment/api");
        assertThat(p.planFingerprint()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(p.dryRun()).isFalse();
        JsonNode params = JSON.readTree(p.paramsJson());
        assertThat(params.get("method").asText()).isEqualTo("IN_PLACE");
        assertThat(params.at("/current/0/container").asText()).isEqualTo("app");
        assertThat(params.at("/current/0/cpuRequestM").asInt()).isEqualTo(500);
        assertThat(params.at("/recommended/0/cpuRequestM").asInt()).isEqualTo(220);
        assertThat(params.at("/current/0/cpuLimitM").isNull()).as("unset is JSON null").isTrue();
        assertThat(JSON.readTree(p.evidenceJson()).get("proof").get("result").get("terminal").asText()).isEqualTo("DRY_RUN");
    }

    @Test
    void aRefusedProbeIsStoredAsAShownRefusalWithItsCode() {
        when(applyService.apply(any(), any(), any(), any(), any(), any())).thenThrow(
                new VerticalApplyService.ApplyRefusedException(HttpStatus.CONFLICT, "COOLDOWN_ACTIVE", null, "cooling down"));
        proposer.proposeForManualClusters();

        ArgumentCaptor<NewProposal> np = ArgumentCaptor.forClass(NewProposal.class);
        verify(proposalService).upsertWorkload(eq(42L), eq(7L), np.capture());
        assertThat(np.getValue().verdict()).isEqualTo("REFUSED");
        assertThat(np.getValue().refusalCodes()).containsExactly("COOLDOWN_ACTIVE");
        assertThat(np.getValue().refusalReason()).isEqualTo("cooling down");
    }

    @Test
    void anUnexpectedFailureMakesNoProposal() {
        when(applyService.apply(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        assertThat(proposer.proposeForManualClusters()).isZero();
        verify(proposalService, never()).upsertWorkload(any(), any(), any());
    }

    @Test
    void theFingerprintDependsOnWhatTheReviewerSeesAndNotOnTheCycle() {
        when(readService.read(any(), any(), any())).thenReturn(response(workload("api", "AUTO", true, 500, 220)));
        proposer.proposeForManualClusters();
        proposer.proposeForManualClusters();
        when(readService.read(any(), any(), any())).thenReturn(response(workload("api", "AUTO", true, 500, 200)));
        proposer.proposeForManualClusters();
        ArgumentCaptor<NewProposal> np = ArgumentCaptor.forClass(NewProposal.class);
        verify(proposalService, times(3)).upsertWorkload(any(), any(), np.capture());
        List<NewProposal> all = np.getAllValues();
        assertThat(all.get(0).planFingerprint()).isEqualTo(all.get(1).planFingerprint());
        assertThat(all.get(0).planFingerprint()).isNotEqualTo(all.get(2).planFingerprint());
    }

    @Test
    void onlyAutoModeEligibleWorkloadsWithAChangeAreProbedAndAtMost25() {
        List<VerticalWorkloadDto> workloads = new ArrayList<>();
        workloads.add(workload("recommend-only", "RECOMMEND", true, 500, 220));
        workloads.add(workload("not-eligible", "AUTO", false, 500, 220));
        workloads.add(workload("no-change", "AUTO", true, 500, 500));
        for (int i = 0; i < 30; i++) {
            workloads.add(workload(String.format("auto-%02d", i), "AUTO", true, 500, 220));
        }
        when(readService.read(any(), any(), any())).thenReturn(response(workloads.toArray(new VerticalWorkloadDto[0])));
        assertThat(proposer.proposeForManualClusters()).isEqualTo(25);
        verify(applyService, times(25)).apply(any(), any(), any(), any(), any(), eq(ActionTrigger.PROPOSAL));
    }

    @Test
    void aClusterThatIsNotManualOrReadOnlyOrHasNoActorIsSkipped() {
        when(modeReader.storedMode(42L)).thenReturn("AUTONOMOUS");
        assertThat(proposer.proposeForManualClusters()).isZero();
        when(modeReader.storedMode(42L)).thenReturn("MANUAL");

        when(accounts.findById(3L)).thenReturn(Optional.of(account("read-only")));
        assertThat(proposer.proposeForManualClusters()).isZero();
        when(accounts.findById(3L)).thenReturn(Optional.of(account("read-write")));

        when(actors.resolve(7L)).thenReturn(Optional.empty());
        assertThat(proposer.proposeForManualClusters()).isZero();
        verifyNoInteractions(applyService);
    }

    @Test
    void theCycleNeedsTheManualApprovalKeyAndTheEngineButNotTheAutoApplyMasterSwitch() {
        readiness.setManualApproval(false);
        proposer.runCycle();
        verifyNoInteractions(clusters);

        readiness.setManualApproval(true);
        autoscaling = new WorkloadAutoscalingProperties(true, false, true); // engine not ready
        build();
        proposer.runCycle();
        verifyNoInteractions(clusters);

        // The AutoApplyProperties master switch is false in build(): Manual still proposes.
        autoscaling = new WorkloadAutoscalingProperties(true, true, true);
        build();
        proposer.runCycle();
        verify(clusters).findByAutomationApprovalMode("MANUAL");
        verify(proposalService).upsertWorkload(any(), any(), any());
    }

    // ------------------------------------------------------------------ fixtures

    private static KubernetesClusterConfig config() {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(42L);
        c.setCloudAccountId(3L);
        c.setClusterName("xamops-test-1");
        c.setRegion("ap-south-1");
        return c;
    }

    private static CloudAccount account(String accessType) {
        Client client = new Client();
        client.setId(7L);
        CloudAccount a = new CloudAccount();
        a.setId(3L);
        a.setProvider("AWS");
        a.setAwsAccountId("102227938259");
        a.setAccessType(accessType);
        a.setClient(client);
        return a;
    }

    private static ClientUserDetails actor() {
        return new ClientUserDetails("root@example.com", "[PROTECTED]",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 7L, 10L, null);
    }

    private static VerticalWorkloadDto workload(String name, String mode, boolean eligible, int cpuNow, int cpuRec) {
        VerticalWorkloadDto.ContainerDto container = new VerticalWorkloadDto.ContainerDto("app", "Burstable",
                new VerticalWorkloadDto.ResourcesDto(cpuNow, 256L, null, 512L),
                new VerticalWorkloadDto.ResourcesDto(cpuRec, 256L, null, 512L), List.of(), null);
        return new VerticalWorkloadDto(new VerticalWorkloadDto.WorkloadRefDto("shop", "Deployment", name),
                new VerticalWorkloadDto.PolicyDto(mode, 15, 95, "72h", "IN_PLACE"),
                2, null, null, null, List.of(), List.of(container), null, eligible, null, null);
    }

    private static VerticalEngineResponse response(VerticalWorkloadDto... workloads) {
        return new VerticalEngineResponse(
                new VerticalEngineResponse.EngineStatus(true, true, "CONFIGURED", "DIRECT",
                        new VerticalEngineResponse.ExecutionPathDetail(
                                new VerticalEngineResponse.PathAvailability(true, null),
                                new VerticalEngineResponse.AgentAvailability(false, "AGENT_NOT_INSTALLED", null)),
                        new VerticalEngineResponse.MetricsSource(true, "prometheus"),
                        VerticalEngineResponse.LastCycle.never()),
                List.of(), List.of(workloads));
    }

    private static ApplyResponseDto proof() {
        return new ApplyResponseDto("corr-1", "ACCEPTED", true,
                new ApplyResponseDto.Result("IN_PLACE", "DRY_RUN", "dry run", false, List.of(), List.of()));
    }
}
