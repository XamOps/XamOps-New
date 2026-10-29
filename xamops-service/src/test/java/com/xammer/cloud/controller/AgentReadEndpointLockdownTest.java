package com.xammer.cloud.controller;

import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.AgentApiKeyService;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.OpenObserveService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.sre.SreInvestigationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-10.2 / F-25 (AC-18): the agent read endpoints
 * {@code GET /api/v1/agent/status/{id}}, {@code /status?…} and
 * {@code /events/{clusterConfigId}} require an agent key whose principal owns the cluster.
 * An unauthenticated caller gets 401; a valid key for a different tenant gets 403; neither
 * reveals the telemetry (or, for the by-name variant, whether the cluster exists).
 */
class AgentReadEndpointLockdownTest {

    private static final String KEY = "xamops_owning-key";
    private static final long AWS = 111111111111L;

    private AgentController controller;
    private AgentIngestService ingest;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private AgentDataCacheRepository cacheRepository;
    private AgentEventRepository eventRepository;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ingest = mock(AgentIngestService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
        cacheRepository = mock(AgentDataCacheRepository.class);
        eventRepository = mock(AgentEventRepository.class);

        K8sAutomationAccessService accessService = new K8sAutomationAccessService(
                mock(CloudAccountAccessService.class), cloudAccountRepository, clusterConfigRepository,
                mock(UserRepository.class), mock(UserTabAccessRepository.class),
                mock(com.xammer.cloud.security.DemoClientMembership.class));

        controller = new AgentController(ingest, mock(DbAgentService.class), clusterConfigRepository, cacheRepository,
                eventRepository, mock(RedisCacheService.class), cloudAccountRepository,
                mock(SreInvestigationService.class), mock(OpenObserveService.class),
                mock(ExplainRequestRepository.class), mock(AgentApiKeyService.class),
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()), accessService);

        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(AWS);
        config.setClusterName("prod-eks");
        when(clusterConfigRepository.findById(100L)).thenReturn(Optional.of(config));
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(AWS, "prod-eks"))
                .thenReturn(Optional.of(config));
    }

    @Test
    void status_byId_unauthenticated_is401() {
        when(ingest.authenticate(any(), any())).thenReturn(null);
        ResponseEntity<Map<String, Object>> r = controller.getAgentStatus(null, 100L, request);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void status_byId_crossTenant_is403() {
        when(ingest.authenticate(eq(KEY), anyString()))
                .thenReturn(AgentPrincipal.cluster("999999999999", "other-eks"));
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn("not your cluster");
        ResponseEntity<Map<String, Object>> r = controller.getAgentStatus("Bearer " + KEY, 100L, request);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void status_byId_owningAgent_is200() {
        when(ingest.authenticate(eq(KEY), anyString()))
                .thenReturn(AgentPrincipal.cluster(String.valueOf(AWS), "prod-eks"));
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn(null);
        when(cacheRepository.findByClusterConfigId(100L)).thenReturn(List.of());
        when(eventRepository.findRecentEvents(eq(100L), any())).thenReturn(List.of());
        when(eventRepository.findLastSuccessfulPush(eq(100L), any())).thenReturn(List.of());
        ResponseEntity<Map<String, Object>> r = controller.getAgentStatus("Bearer " + KEY, 100L, request);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void events_unauthenticated_is401() {
        when(ingest.authenticate(any(), any())).thenReturn(null);
        ResponseEntity<?> r = controller.getAgentEvents(null, 100L, 50, request);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void events_crossTenant_is403() {
        when(ingest.authenticate(eq(KEY), anyString()))
                .thenReturn(AgentPrincipal.cluster("999999999999", "other-eks"));
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn("not your cluster");
        ResponseEntity<?> r = controller.getAgentEvents("Bearer " + KEY, 100L, 50, request);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void statusByName_unauthenticated_doesNotRevealExistence_is401() {
        // Even a known (account, cluster) pair must authenticate first.
        when(ingest.authenticate(any(), any())).thenReturn(null);
        ResponseEntity<Map<String, Object>> r =
                controller.getAgentStatusByCluster(null, "prod-eks", AWS, request);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
