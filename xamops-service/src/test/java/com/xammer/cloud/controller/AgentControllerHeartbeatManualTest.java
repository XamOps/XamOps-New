package com.xammer.cloud.controller;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.AgentApiKeyService;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.OpenObserveService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.automation.proposal.ManualBookkeeping;
import com.xammer.cloud.service.automation.proposal.ManualModeServePolicy;
import com.xammer.cloud.service.sre.SreInvestigationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The heartbeat's part in Manual mode (design 3.2, 3.8): its wholesale {@code save(config)} must stay
 * exactly what it was (and can never write the guarded columns, which the repository test proves), and
 * the leader's advertised features go through the separate {@code recordAgentFeatures} statement only
 * for a CLUSTER+AUTOMATION principal that is the leader.
 */
class AgentControllerHeartbeatManualTest {

    private AgentIngestService ingest;
    private KubernetesClusterConfigRepository clusters;
    private AgentController controller;
    private KubernetesClusterConfig config;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ingest = mock(AgentIngestService.class);
        clusters = mock(KubernetesClusterConfigRepository.class);
        controller = new AgentController(ingest, mock(DbAgentService.class), clusters, mock(AgentDataCacheRepository.class),
                mock(AgentEventRepository.class), mock(RedisCacheService.class), mock(CloudAccountRepository.class),
                mock(SreInvestigationService.class), mock(OpenObserveService.class),
                mock(ExplainRequestRepository.class), mock(AgentApiKeyService.class),
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()),
                mock(K8sAutomationAccessService.class), new DbOpsFeatureFlags(false));
        ManualBookkeeping bookkeeping = new ManualBookkeeping(clusters);
        controller.setManualSupport(new ManualModeServePolicy(
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()), bookkeeping), bookkeeping);
        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn(null);
        when(ingest.findOrAutoRegisterClusterConfig("prod-eks", "111122223333")).thenReturn(config);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    }

    private void beat(AgentPrincipal principal, Map<String, Object> extra) {
        when(ingest.authenticate(eq("k"), anyString())).thenReturn(principal);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("clusterName", "prod-eks",
                "cloudAccountId", "111122223333", "agentVersion", "1.0.0-autoscaler"));
        body.putAll(extra);
        assertThat(controller.heartbeat("Bearer k", body, request).getStatusCode().value()).isEqualTo(200);
    }

    private static AgentPrincipal automation() {
        return AgentPrincipal.cluster("111122223333", "prod-eks", AgentPrincipal.AgentRole.AUTOMATION);
    }

    @Test
    void aLeaderAutomationBeatSavesTheRowAsBeforeAndAdvertisesItsFeaturesWithAStatementOfItsOwn() {
        beat(automation(), Map.of("leader", true, "features", List.of("manual-v1"), "drainTimeoutSeconds", 1800));

        verify(clusters).save(config);
        ArgumentCaptor<java.time.Instant> at = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(clusters).recordAgentFeatures(eq(100L), eq("manual-v1"), at.capture(), eq(1800));
        assertThat(config.getAutomationIdentitySeenAt()).as("the existing identity stamp is unchanged").isNotNull();
    }

    @Test
    void aStandbyOrOlderAgentBeatWritesNoFeatures() {
        beat(automation(), Map.of("leader", false, "features", List.of("manual-v1")));
        beat(automation(), Map.of("features", List.of("manual-v1")));
        verify(clusters, never()).recordAgentFeatures(anyLong(), any(), any(), any());
        verify(clusters, org.mockito.Mockito.times(2)).save(config);
    }

    @Test
    void theAutoscalerVersionSuffixFallbackStampsIdentityButNeverAdvertisesFeatures() {
        beat(AgentPrincipal.cluster("111122223333", "prod-eks"),
                Map.of("leader", true, "features", List.of("manual-v1"), "drainTimeoutSeconds", 1200));
        assertThat(config.getAutomationIdentitySeenAt()).as("the fallback still stamps identity").isNotNull();
        verify(clusters, never()).recordAgentFeatures(anyLong(), any(), any(), any());
    }

    @Test
    void aFailingFeatureWriteNeverFailsTheHeartbeat() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db"))
                .when(clusters).recordAgentFeatures(anyLong(), any(), any(), any());
        beat(automation(), Map.of("leader", true, "features", List.of("manual-v1")));
        verify(clusters).save(config);
    }
}
