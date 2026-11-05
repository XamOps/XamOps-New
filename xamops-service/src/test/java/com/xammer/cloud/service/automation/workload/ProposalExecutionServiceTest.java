package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.domain.AutomationProposal;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AutomationProposalRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.proposal.ProposalException;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import com.xammer.cloud.service.automation.workload.api.PreApplyCheck;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Executing an approved WORKLOAD_RESIZE in the Java backend (design 3.4): the same chain, the same diff, and the stale check. */
class ProposalExecutionServiceTest {

    private static final String PARAMS = "{\"namespace\":\"shop\",\"kind\":\"Deployment\",\"name\":\"api\",\"method\":\"IN_PLACE\","
            + "\"current\":[{\"container\":\"app\",\"cpuRequestM\":500,\"cpuLimitM\":null,\"memRequestMi\":256,\"memLimitMi\":512}],"
            + "\"recommended\":[{\"container\":\"app\",\"cpuRequestM\":220,\"cpuLimitM\":null,\"memRequestMi\":256,\"memLimitMi\":512}]}";

    private AutomationProposalRepository proposals;
    private KubernetesClusterConfigRepository clusters;
    private CloudAccountRepository accounts;
    private SystemActorResolver actors;
    private VerticalApplyService applyService;
    private ProposalService proposalService;
    private ProposalExecutionService service;
    private AutomationProposal row;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        proposals = mock(AutomationProposalRepository.class);
        clusters = mock(KubernetesClusterConfigRepository.class);
        accounts = mock(CloudAccountRepository.class);
        actors = mock(SystemActorResolver.class);
        applyService = mock(VerticalApplyService.class);
        proposalService = mock(ProposalService.class);
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setCloudAccountId(3L);
        config.setClusterName("xamops-test-1");
        config.setRegion("ap-south-1");
        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setAwsAccountId("102227938259");
        account.setAccessType("read-write");
        account.setClient(client);
        when(clusters.findById(42L)).thenReturn(Optional.of(config));
        when(accounts.findById(3L)).thenReturn(Optional.of(account));
        when(actors.resolve(7L)).thenReturn(Optional.of(new ClientUserDetails("root@example.com", "[PROTECTED]",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 7L, 10L, null)));
        row = row("p1", PARAMS);
        when(proposals.findByIdAndClusterConfigId("p1", 42L)).thenReturn(Optional.of(row));
        AutomaticApplyService autoApply = new AutomaticApplyService(new AutoApplyProperties(false, 15, 15, 3),
                new WorkloadAutoscalingProperties(true, true, true), mock(
                        com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService.class),
                applyService, clusters, accounts, actors);
        ObjectProvider<ProposalExecutionService> self = mock(ObjectProvider.class);
        service = new ProposalExecutionService(proposals, clusters, autoApply, actors, applyService, proposalService, self);
        when(self.getObject()).thenReturn(service);
    }

    private static AutomationProposal row(String id, String params) {
        AutomationProposal p = new AutomationProposal();
        p.setId(id);
        p.setClusterConfigId(42L);
        p.setStatus("DISPATCHED");
        p.setParamsJson(params);
        return p;
    }

    private static ApplyResponseDto applied(String terminal) {
        return new ApplyResponseDto("corr-1", "ACCEPTED", false,
                new ApplyResponseDto.Result("IN_PLACE", terminal, "d", true, List.of(), List.of()));
    }

    @Test
    void anAppliedResizeRunsTheFullChainWithTheApprovedTriggerTheDiffBodyAndAStaleCheck() {
        when(applyService.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(applied("APPLIED"));

        service.execute(42L, "p1", "ann");

        ArgumentCaptor<ApplyRequestDto> request = ArgumentCaptor.forClass(ApplyRequestDto.class);
        ArgumentCaptor<PreApplyCheck> check = ArgumentCaptor.forClass(PreApplyCheck.class);
        verify(applyService).apply(any(), any(), any(), eq(WorkloadRef.of("shop", "Deployment", "api")), request.capture(),
                eq(ActionTrigger.APPROVED), check.capture());
        assertThat(request.getValue().dryRun()).isFalse();
        assertThat(request.getValue().applyMethod()).isEqualTo("IN_PLACE");
        // the per-field diff: only the CPU request changed, the rest are left alone
        assertThat(request.getValue().containers()).containsExactly(
                new ApplyRequestDto.Container("app", 220, null, null, null));
        assertThat(check.getValue()).isNotNull();
        verify(proposalService).finishWorkload(eq(42L), eq("p1"), eq("EXECUTED"), eq("applied"), isNull(),
                eq("succeeded"), eq(true), any(), eq("corr-1"));
    }

    @Test
    void anUnconfirmedApplyIsFailedNeverReportedAsExecuted() {
        when(applyService.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(applied("PARTIALLY_APPLIED"));
        service.execute(42L, "p1", "ann");
        verify(proposalService).finishWorkload(eq(42L), eq("p1"), eq("FAILED"), eq("PARTIALLY_APPLIED"), isNull(),
                eq("failed"), eq(false), any(), eq("corr-1"));
    }

    @Test
    void aStaleLiveResourceSetIsReportedStale() {
        when(applyService.apply(any(), any(), any(), any(), any(), any(), any())).thenThrow(
                new VerticalApplyService.ApplyRefusedException(HttpStatus.CONFLICT, PreApplyCheck.STALE_CODE, null, "changed"));
        service.execute(42L, "p1", "ann");
        verify(proposalService).finishWorkload(eq(42L), eq("p1"), eq("STALE"), eq("changed"), isNull(), eq("stale"),
                eq(false), isNull(), isNull());
    }

    @Test
    void anyOtherGateRefusalIsAnExecutionStageRefusal() {
        when(applyService.apply(any(), any(), any(), any(), any(), any(), any())).thenThrow(
                new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN, "K8S_CONTROLLER_NOT_READY", null, "not ready"));
        service.execute(42L, "p1", "ann");
        verify(proposalService).finishWorkload(eq(42L), eq("p1"), eq("REFUSED"),
                eq("K8S_CONTROLLER_NOT_READY: not ready"), eq("EXECUTION"), eq("refused"), eq(false), isNull(), isNull());
    }

    @Test
    void badParamsFailWithNoPatch() {
        for (String bad : List.of("{not json", "{\"namespace\":\"shop\"}",
                PARAMS.replace("\"recommended\":[{", "\"recommended\":[{\"container\":\"app\"},{"))) {
            row.setParamsJson(bad);
            service.execute(42L, "p1", "ann");
        }
        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any(), any());
        verify(proposalService, org.mockito.Mockito.times(3)).finishWorkload(eq(42L), eq("p1"), eq("FAILED"),
                eq("BAD_PARAMS"), isNull(), eq("failed"), eq(false), isNull(), isNull());
    }

    @Test
    void aProposalThatNoLongerDiffersIsStaleAndNothingIsPatched() {
        row.setParamsJson(PARAMS.replace("\"cpuRequestM\":220", "\"cpuRequestM\":500"));
        service.execute(42L, "p1", "ann");
        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any(), any());
        verify(proposalService).finishWorkload(eq(42L), eq("p1"), eq("STALE"), eq("no longer differs from current"),
                isNull(), eq("stale"), eq(false), isNull(), isNull());
    }

    @Test
    void aMissingActorOrAccountFailsInsteadOfInventingAnIdentity() {
        when(actors.resolve(7L)).thenReturn(Optional.empty());
        service.execute(42L, "p1", "ann");
        verify(proposalService).finishWorkload(eq(42L), eq("p1"), eq("FAILED"), eq("NO_ACTOR_OR_ACCOUNT"), isNull(),
                eq("failed"), eq(false), isNull(), isNull());
        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRowThatIsNotDispatchedIsLeftAlone() {
        row.setStatus("EXECUTED");
        service.execute(42L, "p1", "ann");
        verify(applyService, never()).apply(any(), any(), any(), any(), any(), any(), any());
        verify(proposalService, never()).finishWorkload(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void anUnexpectedFailureOnThePoolThreadFailsTheRowInsteadOfLeavingItDispatched() {
        when(applyService.apply(any(), any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        service.submit(42L, "p1", "ann");
        verify(proposalService, timeout(3000)).finishWorkload(eq(42L), eq("p1"), eq("FAILED"), eq("EXECUTION_ERROR"),
                isNull(), eq("failed"), eq(false), isNull(), isNull());
    }

    @Test
    void aSaturatedPoolPutsTheRowBackAndAnswersExecutorBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(applyService.apply(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return applied("APPLIED");
        });
        // 4 threads plus a queue of 20 accept 24; the 25th is rejected
        for (int i = 0; i < 24; i++) {
            String id = "q" + i;
            when(proposals.findByIdAndClusterConfigId(id, 42L)).thenReturn(Optional.of(row(id, PARAMS)));
            service.submit(42L, id, "ann");
        }
        assertThatThrownBy(() -> service.submit(42L, "q24", "ann")).isInstanceOfSatisfying(ProposalException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo("EXECUTOR_BUSY");
        });
        verify(proposalService).undoDispatch(42L, "q24");
        release.countDown();
        service.shutdown();
    }
}
