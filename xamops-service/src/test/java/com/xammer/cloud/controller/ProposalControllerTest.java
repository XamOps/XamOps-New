package com.xammer.cloud.controller;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApprovalModeResponse;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApproveRequest;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.Counts;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ManualListAvailability;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalListDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.automation.proposal.ProposalException;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import com.xammer.cloud.service.automation.proposal.ProposalService.DecisionResult;
import com.xammer.cloud.service.automation.workload.ProposalExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The user API's HTTP contract (design 3.8): statuses, the uniform refusal body, and what happens after the commit. */
class ProposalControllerTest {

    private ProposalService service;
    private ProposalExecutionService execution;
    private MockMvc mvc;
    private K8sAutomationRequestContext ctx;

    @BeforeEach
    void setUp() {
        service = mock(ProposalService.class);
        execution = mock(ProposalExecutionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProposalController(service, execution)).build();
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");
        ClientUserDetails user = mock(ClientUserDetails.class);
        when(user.getUsername()).thenReturn("ann");
        ctx = new K8sAutomationRequestContext(user, List.of(), config);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCtx(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.requestAttr(K8sAutomationRequestContext.ATTRIBUTE, ctx).contentType(MediaType.APPLICATION_JSON);
    }

    private static final String APPROVE = "{\"expectedVersion\":0,\"planFingerprint\":\"sha256:x\"}";

    @Test
    void theFirstApproveIs202AndAReplayIs200AlreadyDecided() throws Exception {
        when(service.approve(any(), eq("p1"), any())).thenReturn(new DecisionResult("p1", "APPROVED", false, false));
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/p1/approve")).content(APPROVE))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.alreadyDecided").value(false));

        when(service.approve(any(), eq("p1"), any())).thenReturn(new DecisionResult("p1", "APPROVED", true, false));
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/p1/approve")).content(APPROVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDecided").value(true));
        verify(execution, never()).submit(any(), any(), any());
    }

    @Test
    void aWorkloadApprovalHandsTheRowToTheJavaExecutorAfterTheCommit() throws Exception {
        when(service.approve(any(), eq("w1"), any())).thenReturn(new DecisionResult("w1", "APPROVED", false, true));
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/w1/approve")).content(APPROVE))
                .andExpect(status().isAccepted());
        verify(execution).submit(100L, "w1", "ann");
    }

    @Test
    void aBusyExecutorIs503ExecutorBusy() throws Exception {
        when(service.approve(any(), eq("w1"), any())).thenReturn(new DecisionResult("w1", "APPROVED", false, true));
        doThrow(new ProposalException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "EXECUTOR_BUSY", "busy"))
                .when(execution).submit(any(), any(), any());
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/w1/approve")).content(APPROVE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXECUTOR_BUSY"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void conflictsAndNotFoundUseTheUniformRefusalBody() throws Exception {
        when(service.approve(any(), eq("p1"), any())).thenThrow(ProposalException.conflict("VERSION_CONFLICT", "lost"));
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/p1/approve")).content(APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("lost"));

        when(service.detail(any(), eq("other"))).thenThrow(ProposalException.notFound());
        mvc.perform(withCtx(get("/api/eks/autoscaling/prod-eks/proposals/other")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theAccountScopeGateRefusalOfAWorkloadApprovalIsA403WithItsCode() throws Exception {
        when(service.approve(any(), eq("w1"), any())).thenThrow(
                K8sAutomationRefusedException.of(K8sAutomationReason.READ_ONLY_CONNECTION));
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/w1/approve")).content(APPROVE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("K8S_READ_ONLY_CONNECTION"));
    }

    @Test
    void aMalformedBodyIs400() throws Exception {
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/p1/approve")).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"));
    }

    @Test
    void rejectIs200() throws Exception {
        when(service.reject(any(), eq("p1"), any())).thenReturn(new DecisionResult("p1", "REJECTED", false, false));
        mvc.perform(withCtx(post("/api/eks/autoscaling/prod-eks/proposals/p1/reject")).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void listPassesItsFiltersThrough() throws Exception {
        when(service.list(any(), eq("PENDING"), eq(10), eq("cur"))).thenReturn(new ProposalListDto("OK", "MANUAL", "active",
                new ManualListAvailability(true, null, true), new Counts(1, 0, 0, 0), List.of(), null, Instant.EPOCH, null));
        mvc.perform(withCtx(get("/api/eks/autoscaling/prod-eks/proposals?status=PENDING&limit=10&cursor=cur")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"))
                .andExpect(jsonPath("$.counts.pending").value(1))
                .andExpect(jsonPath("$.manual.available").value(true))
                .andExpect(jsonPath("$.lastAgentCheckIn").doesNotExist());
    }

    @Test
    void theApprovalModeEndpointReturnsTheEffectiveState() throws Exception {
        when(service.setApprovalMode(any(), eq("MANUAL"))).thenReturn(
                new ApprovalModeResponse("MANUAL", "switching", Instant.parse("2026-11-01T10:00:00Z")));
        mvc.perform(withCtx(put("/api/eks/autoscaling/prod-eks/approval-mode")).content("{\"mode\":\"MANUAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MANUAL"))
                .andExpect(jsonPath("$.effective").value("switching"));

        when(service.setApprovalMode(any(), eq("MANUAL"))).thenThrow(
                ProposalException.conflict("K8S_AGENT_TOO_OLD", "update the agent"));
        mvc.perform(withCtx(put("/api/eks/autoscaling/prod-eks/approval-mode")).content("{\"mode\":\"MANUAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("K8S_AGENT_TOO_OLD"));
    }
}
