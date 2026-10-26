package com.xammer.cloud.service;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.automation.workload.usage.store.UsageSampleCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The usage rollup must never affect an agent push: whatever {@link UsageSampleCapture} does,
 * {@code processIngest} stores the batches, logs {@code push_success} and reports success.
 */
class AgentIngestRollupIsolationTest {

    private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 10, 24, 10, 0, 0);

    private final KubernetesClusterConfigRepository clusterRepository = mock(KubernetesClusterConfigRepository.class);
    private final AgentDataCacheRepository cacheRepository = mock(AgentDataCacheRepository.class);
    private final AgentEventRepository eventRepository = mock(AgentEventRepository.class);
    private final CloudAccountRepository accountRepository = mock(CloudAccountRepository.class);
    private final UsageSampleCapture capture = mock(UsageSampleCapture.class);

    private final Map<String, Object> k8sResources = Map.of("pods", List.of(), "podMetrics", List.of());

    @BeforeEach
    void setUp() {
        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a throwing capture leaves the push successful: cache saved, push_success logged")
    void throwingCaptureDoesNotFailIngest() {
        doThrow(new RuntimeException("rollup exploded")).when(capture).submit(any(), any(), any(), any());
        AgentIngestService service = new AgentIngestService(clusterRepository, cacheRepository, eventRepository,
                accountRepository, capture);

        AgentIngestResponse response = service.processIngest(cluster(), payload(), "10.0.0.1");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResults()).containsKeys("k8s-resources", "prometheus-metrics");
        assertThat(response.getResults().values()).allMatch(AgentIngestResponse.BatchResult::isSuccess);

        ArgumentCaptor<AgentDataCache> saved = ArgumentCaptor.forClass(AgentDataCache.class);
        verify(cacheRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AgentDataCache::getDataType)
                .containsExactly("k8s-resources", "prometheus-metrics");

        AgentEvent event = loggedEvent();
        assertThat(event.getEventType()).isEqualTo("push_success");
        assertThat(event.getItemsReceived()).isEqualTo(2);
        verify(clusterRepository).save(any(KubernetesClusterConfig.class));
    }

    @Test
    @DisplayName("capture is invoked exactly once, only for the k8s-resources batch, with its item and collectedAt")
    void captureOnlyForK8sResources() {
        AgentIngestService service = new AgentIngestService(clusterRepository, cacheRepository, eventRepository,
                accountRepository, capture);

        service.processIngest(cluster(), payload(), "10.0.0.1");

        verify(capture, times(1)).submit(any(), any(), any(), any());
        verify(capture).submit(eq(7L), eq(3L), eq(k8sResources), eq(COLLECTED_AT));
    }

    @Test
    @DisplayName("the 4-arg constructor (no capture) still ingests successfully")
    void noCapture() {
        AgentIngestService service = new AgentIngestService(clusterRepository, cacheRepository, eventRepository,
                accountRepository);

        AgentIngestResponse response = service.processIngest(cluster(), payload(), "10.0.0.1");

        assertThat(response.isSuccess()).isTrue();
        verify(cacheRepository, times(2)).save(any(AgentDataCache.class));
        assertThat(loggedEvent().getEventType()).isEqualTo("push_success");
    }

    private AgentEvent loggedEvent() {
        ArgumentCaptor<AgentEvent> events = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventRepository, atLeastOnce()).save(events.capture());
        assertThat(events.getAllValues()).hasSize(1);
        return events.getValue();
    }

    private static KubernetesClusterConfig cluster() {
        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setId(7L);
        cfg.setCloudAccountId(3L);
        cfg.setClusterName("prod-eks");
        return cfg;
    }

    private AgentIngestPayload payload() {
        AgentIngestPayload.DataBatch k8s = new AgentIngestPayload.DataBatch();
        k8s.setCollector("k8s-resources");
        k8s.setItems(List.of(k8sResources));
        k8s.setCollectedAt(COLLECTED_AT);

        AgentIngestPayload.DataBatch prometheus = new AgentIngestPayload.DataBatch();
        prometheus.setCollector("prometheus-metrics");
        prometheus.setItems(List.of(Map.of("utilization", Map.of())));
        prometheus.setCollectedAt(COLLECTED_AT);

        AgentIngestPayload payload = new AgentIngestPayload();
        payload.setAgentVersion("1.2.3");
        payload.setClusterName("prod-eks");
        payload.setBatches(List.of(k8s, prometheus));
        return payload;
    }
}
