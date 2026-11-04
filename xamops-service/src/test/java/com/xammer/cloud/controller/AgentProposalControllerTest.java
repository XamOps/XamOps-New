package com.xammer.cloud.controller;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ClaimRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.automation.proposal.ProposalException;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may call the agent proposal endpoints (design 3.8, AC-B15): only a CLUSTER-scoped principal
 * authenticated by the cluster's automation key, and the config comes only from that key. Anything
 * else is refused before any proposal state is touched.
 */
class AgentProposalControllerTest {

    private static final String KEY = "automation-key";

    private AgentIngestService ingest;
    private KubernetesClusterConfigRepository clusters;
    private ProposalService proposalService;
    private AgentProposalController controller;
    private KubernetesClusterConfig config;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ingest = mock(AgentIngestService.class);
        clusters = mock(KubernetesClusterConfigRepository.class);
        proposalService = mock(ProposalService.class);
        controller = new AgentProposalController(ingest, clusters, proposalService);
        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");
        config.setCloudAccountId(7L);
        when(clusters.findByAutomationAgentApiKey(KEY)).thenReturn(Optional.of(config));
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn(null);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    }

    private void authenticatedAs(AgentPrincipal principal) {
        when(ingest.authenticate(eq(KEY), anyString())).thenReturn(principal);
    }

    private static AgentPrincipal automation() {
        return AgentPrincipal.cluster("111122223333", "prod-eks", AgentPrincipal.AgentRole.AUTOMATION);
    }

    private ResponseEntity<?> claim() {
        return controller.claim("Bearer " + KEY, "prod-eks", 111122223333L, new ClaimRequest("c-1"), request);
    }

    private void assertRefused(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProposalException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    void theLegacyGlobalKeyIsRefusedOnEveryEndpoint() {
        authenticatedAs(AgentPrincipal.legacyGlobal());
        assertRefused(this::claim, HttpStatus.FORBIDDEN, "AGENT_KEY_LEGACY");
        assertRefused(() -> controller.submit("Bearer " + KEY, "prod-eks", 1L, mock(BatchRequest.class), request),
                HttpStatus.FORBIDDEN, "AGENT_KEY_LEGACY");
        assertRefused(() -> controller.dispatched("Bearer " + KEY, "prod-eks", 1L, request),
                HttpStatus.FORBIDDEN, "AGENT_KEY_LEGACY");
        assertRefused(() -> controller.outcome("Bearer " + KEY, "prod-eks", 1L, "id", mock(OutcomeRequest.class), request),
                HttpStatus.FORBIDDEN, "AGENT_KEY_LEGACY");
        verifyNoInteractions(proposalService);
    }

    @Test
    void anAccountScopedKeyIsRefused() {
        authenticatedAs(AgentPrincipal.account("111122223333"));
        assertRefused(this::claim, HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
        verifyNoInteractions(proposalService);
    }

    @Test
    void aClusterReadOnlyDataKeyIsRefused() {
        authenticatedAs(AgentPrincipal.cluster("111122223333", "prod-eks"));
        assertRefused(this::claim, HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
        assertRefused(() -> controller.submit("Bearer " + KEY, "prod-eks", 1L, mock(BatchRequest.class), request),
                HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
        assertRefused(() -> controller.outcome("Bearer " + KEY, "prod-eks", 1L, "id", mock(OutcomeRequest.class), request),
                HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
        verifyNoInteractions(proposalService);
    }

    @Test
    void aMissingHeaderOrUnknownKeyIs401() {
        assertRefused(() -> controller.claim(null, "prod-eks", 1L, new ClaimRequest("c"), request),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        when(ingest.authenticate(eq("nope"), anyString())).thenReturn(null);
        assertRefused(() -> controller.claim("Bearer nope", "prod-eks", 1L, new ClaimRequest("c"), request),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertRefused(() -> controller.claim("Basic abc", "prod-eks", 1L, new ClaimRequest("c"), request),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        verifyNoInteractions(proposalService);
    }

    @Test
    void anAutomationKeyForADifferentClusterThanTheQueryNamesIsRefused() {
        authenticatedAs(automation());
        assertRefused(() -> controller.claim("Bearer " + KEY, "other-eks", 111122223333L, new ClaimRequest("c"), request),
                HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn("not authorised");
        assertRefused(this::claim, HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
        verifyNoInteractions(proposalService);
    }

    @Test
    void aKeyThatDoesNotResolveThroughTheAutomationLookupIsRefusedNotNotFound() {
        authenticatedAs(automation());
        when(clusters.findByAutomationAgentApiKey(KEY)).thenReturn(Optional.empty());
        assertRefused(this::claim, HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION");
    }

    @Test
    void theConfigThatActsIsAlwaysTheOneTheKeyResolvedTo() {
        authenticatedAs(automation());
        when(proposalService.claim(config, new ClaimRequest("c-1"))).thenReturn(Optional.empty());
        assertThat(claim().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        BatchRequest batch = mock(BatchRequest.class);
        when(proposalService.acceptBatch(config, batch)).thenReturn(new BatchResponse(0, List.of(), List.of()));
        assertThat(controller.submit("Bearer " + KEY, "prod-eks", 111122223333L, batch, request).getBody().accepted()).isZero();
    }

    @Test
    void anOversizedBodyIs413BeforeAnythingIsAccepted() {
        authenticatedAs(automation());
        when(request.getContentLengthLong()).thenReturn(2L * 1024 * 1024 + 1);
        assertRefused(() -> controller.submit("Bearer " + KEY, "prod-eks", 111122223333L, mock(BatchRequest.class), request),
                HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE");
        when(request.getContentLengthLong()).thenReturn(1024L * 1024 + 1);
        assertRefused(() -> controller.outcome("Bearer " + KEY, "prod-eks", 111122223333L, "id", mock(OutcomeRequest.class), request),
                HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE");
        verifyNoInteractions(proposalService);
    }

    @Test
    void aRateLimitedResponseCarriesRetryAfter() {
        ResponseEntity<java.util.Map<String, Object>> r = controller.handleProposal(
                new ProposalException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "slow down"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(r.getBody()).containsEntry("code", "RATE_LIMITED");
    }
}
