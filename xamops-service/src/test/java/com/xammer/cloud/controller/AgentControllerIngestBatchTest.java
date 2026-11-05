package com.xammer.cloud.controller;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
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
import com.xammer.cloud.service.sre.SreInvestigationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
import static org.mockito.Mockito.when;

/**
 * COR-14: a mixed agent payload (disk-usage + db batches) must persist ALL batch types, not drop
 * the db/sre/cluster batches once a disk-usage batch is seen. A disk-only payload still returns 200.
 */
class AgentControllerIngestBatchTest {

    private static final String ACCOUNT_ID = "111111111111";
    private static final String CLUSTER = "db-cluster-1";
    private static final String API_KEY = "global-key";

    private AgentIngestService agentIngestService;
    private DbAgentService dbAgentService;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        agentIngestService = mock(AgentIngestService.class);
        dbAgentService = mock(DbAgentService.class);
        controller = new AgentController(
                agentIngestService,
                dbAgentService,
                mock(KubernetesClusterConfigRepository.class),
                mock(AgentDataCacheRepository.class),
                mock(AgentEventRepository.class),
                mock(RedisCacheService.class),
                mock(CloudAccountRepository.class),
                mock(SreInvestigationService.class),
                mock(OpenObserveService.class),
                mock(ExplainRequestRepository.class),
                mock(AgentApiKeyService.class),
                mock(K8sAutomationPolicyNormalizer.class),
                mock(K8sAutomationAccessService.class),
                mock(DbOpsFeatureFlags.class));

        when(agentIngestService.authenticate(anyString(), anyString())).thenReturn(AgentPrincipal.legacyGlobal());
        when(agentIngestService.checkOwnership(any(), anyString(), anyString())).thenReturn(null);
    }

    private HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        return req;
    }

    private AgentIngestPayload.DataBatch batch(String collector, List<Object> items) {
        AgentIngestPayload.DataBatch b = new AgentIngestPayload.DataBatch();
        b.setCollector(collector);
        b.setItems(items);
        return b;
    }

    private AgentIngestPayload payload(List<AgentIngestPayload.DataBatch> batches) {
        AgentIngestPayload p = new AgentIngestPayload();
        p.setClusterName(CLUSTER);
        p.setCloudAccountId(ACCOUNT_ID);
        p.setBatches(batches);
        return p;
    }

    @Test
    void mixedPayloadStillPersistsDbBatch() {
        DbAgentConfig cfg = new DbAgentConfig();
        cfg.setId(7L);
        cfg.setAgentName("agent-1");
        when(dbAgentService.findDbAgent(ACCOUNT_ID, CLUSTER)).thenReturn(Optional.of(cfg));
        when(dbAgentService.processIngest(any(), any(), anyString())).thenReturn(AgentIngestResponse.success());

        List<Object> diskItems = new ArrayList<>();
        Map<String, Object> mount = new java.util.HashMap<>();
        mount.put("instanceId", "i-abc");
        diskItems.add(mount);

        AgentIngestPayload payload = payload(List.of(
                batch("disk-usage", diskItems),
                batch("db-instance-metrics", List.of())));

        ResponseEntity<AgentIngestResponse> resp =
                controller.ingestData("Bearer " + API_KEY, payload, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The db batch is NOT dropped by the disk-usage short-circuit.
        verify(dbAgentService).processIngest(any(), any(), anyString());
    }

    @Test
    void diskOnlyPayloadReturns200WithoutDbPath() {
        List<Object> diskItems = new ArrayList<>();
        Map<String, Object> mount = new java.util.HashMap<>();
        mount.put("instanceId", "i-xyz");
        diskItems.add(mount);

        AgentIngestPayload payload = payload(List.of(batch("disk-usage", diskItems)));

        ResponseEntity<AgentIngestResponse> resp =
                controller.ingestData("Bearer " + API_KEY, payload, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dbAgentService, never()).processIngest(any(), any(), anyString());
    }
}
