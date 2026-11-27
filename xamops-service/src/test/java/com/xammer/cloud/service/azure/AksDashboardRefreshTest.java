package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AksDashboardCache;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.azure.AksDashboardDto;
import com.xammer.cloud.repository.AksDashboardCacheRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.service.AgentDataTransformerService;
import com.xammer.cloud.service.AgentK8sDashboardMapper;
import com.xammer.cloud.service.PrometheusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The AKS dashboard is cached in the DB and refreshed nightly, so a refresh must never replace a
 * good copy with a degraded one, a deleted cluster must leave the cache, and what the page shows
 * (status, agent liveness, currency) must match what Azure and the agent actually report.
 */
class AksDashboardRefreshTest {

    private static final String SUB = "sub-1";
    private static final String CLUSTER = "aks-1";
    private static final String RG = "rg-1";

    @Nested
    @DisplayName("syncClusterDashboard")
    class Sync {

        private AksDashboardCacheRepository repo;
        private AksDashboardService dashboards;
        private AksDashboardSyncService sync;

        @BeforeEach
        void setUp() {
            repo = mock(AksDashboardCacheRepository.class);
            dashboards = mock(AksDashboardService.class);
            sync = new AksDashboardSyncService(repo, dashboards);
            ReflectionTestUtils.setField(sync, "self", sync);
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        private AksDashboardCache cachedRow() {
            AksDashboardCache row = new AksDashboardCache(CLUSTER, SUB, RG,
                    "{\"clusterName\":\"aks-1\",\"location\":\"centralindia\",\"connected\":true}");
            row.setLastSyncedAt(LocalDateTime.of(2026, 11, 20, 4, 55));
            return row;
        }

        private AksDashboardDto partial() {
            AksDashboardDto dto = new AksDashboardDto();
            dto.setClusterName(CLUSTER);
            dto.setPartialReason("Azure did not return the cluster details (HTTP 429)");
            return dto;
        }

        @Test
        @DisplayName("an incomplete refresh returns the previous copy and does not overwrite it")
        void incompleteRefreshKeepsThePreviousCopy() {
            when(dashboards.getDashboard(SUB, CLUSTER, RG)).thenReturn(partial());
            AksDashboardCache row = cachedRow();
            when(repo.findByClusterNameAndSubscriptionIdAndResourceGroup(CLUSTER, SUB, RG)).thenReturn(Optional.of(row));

            AksDashboardDto shown = sync.syncClusterDashboard(SUB, CLUSTER, RG);

            assertThat(shown.getLocation()).isEqualTo("centralindia");
            assertThat(shown.getLastSyncedAt()).isEqualTo(row.getLastSyncedAt());
            assertThat(shown.getRefreshError()).contains("HTTP 429").contains("last complete copy");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("an incomplete first build is returned but not cached, so the next request retries")
        void incompleteFirstBuildIsNotCached() {
            when(dashboards.getDashboard(SUB, CLUSTER, RG)).thenReturn(partial());
            when(repo.findByClusterNameAndSubscriptionIdAndResourceGroup(CLUSTER, SUB, RG)).thenReturn(Optional.empty());

            AksDashboardDto shown = sync.syncClusterDashboard(SUB, CLUSTER, RG);

            assertThat(shown.getRefreshError()).contains("HTTP 429");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("a cluster deleted in Azure has its cached copy removed and the 404 surfaces")
        void deletedClusterIsRemoved() {
            when(dashboards.getDashboard(SUB, CLUSTER, RG))
                    .thenThrow(new AksDashboardService.ClusterNotFoundException(CLUSTER, RG));
            AksDashboardCache row = cachedRow();
            when(repo.findByClusterNameAndSubscriptionIdAndResourceGroup(CLUSTER, SUB, RG)).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> sync.syncClusterDashboard(SUB, CLUSTER, RG))
                    .isInstanceOf(AksDashboardService.ClusterNotFoundException.class);
            verify(repo).delete(row);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("a complete build is saved")
        void completeBuildIsSaved() {
            AksDashboardDto fresh = new AksDashboardDto();
            fresh.setClusterName(CLUSTER);
            when(dashboards.getDashboard(SUB, CLUSTER, RG)).thenReturn(fresh);
            when(repo.findByClusterNameAndSubscriptionIdAndResourceGroup(CLUSTER, SUB, RG)).thenReturn(Optional.empty());

            AksDashboardDto shown = sync.syncClusterDashboard(SUB, CLUSTER, RG);

            assertThat(shown).isSameAs(fresh);
            assertThat(shown.getRefreshError()).isNull();
            verify(repo).save(any(AksDashboardCache.class));
        }

        @Test
        @DisplayName("the partial flag is never written into the cached JSON")
        void partialReasonIsNotSerialized() throws Exception {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            assertThat(mapper.writeValueAsString(partial())).doesNotContain("partialReason");
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("a stopped cluster reads Stopped, not Succeeded")
        void stoppedIsShown() {
            assertThat(AksDashboardService.displayStatus("Succeeded", "Stopped")).isEqualTo("Stopped");
            assertThat(AksDashboardService.displayStatus(null, "Stopped")).isEqualTo("Stopped");
        }

        @Test
        @DisplayName("a running or transitioning cluster keeps its provisioning state")
        void otherStatesAreKept() {
            assertThat(AksDashboardService.displayStatus("Succeeded", "Running")).isEqualTo("Succeeded");
            assertThat(AksDashboardService.displayStatus("Stopping", "Running")).isEqualTo("Stopping");
            assertThat(AksDashboardService.displayStatus("Starting", "Stopped")).isEqualTo("Starting");
            assertThat(AksDashboardService.displayStatus(null, null)).isEqualTo("Unknown");
        }
    }

    @Nested
    @DisplayName("getDashboard")
    class Build {

        private AzureClientProvider clients;
        private CloudAccountRepository accounts;
        private KubernetesClusterRepository clusters;
        private PrometheusService prometheus;
        private KubernetesClusterConfigRepository configs;
        private AksDashboardService service;

        @BeforeEach
        void setUp() {
            clients = mock(AzureClientProvider.class);
            accounts = mock(CloudAccountRepository.class);
            clusters = mock(KubernetesClusterRepository.class);
            prometheus = mock(PrometheusService.class);
            configs = mock(KubernetesClusterConfigRepository.class);
            service = new AksDashboardService(clients, accounts, clusters, prometheus, configs,
                    mock(AgentDataTransformerService.class), mock(AgentK8sDashboardMapper.class));

            CloudAccount account = new CloudAccount();
            account.setId(7L);
            when(accounts.findByAzureSubscriptionId(SUB)).thenReturn(Optional.of(account));
            when(clients.getAzureClient(SUB)).thenThrow(new RuntimeException("ARM unavailable"));
        }

        private void prometheusConnected() {
            KubernetesCluster saved = new KubernetesCluster();
            saved.setClusterName(CLUSTER);
            saved.setPrometheusUrl("http://prom:9090");
            when(clusters.findByCloudAccount_IdAndClusterName(7L, CLUSTER)).thenReturn(Optional.of(saved));
        }

        @Test
        @DisplayName("an Azure failure other than 404 flags the build as partial")
        void transientArmFailureIsPartial() {
            when(clusters.findByCloudAccount_IdAndClusterName(7L, CLUSTER)).thenReturn(Optional.empty());
            when(configs.findByCloudAccountIdAndClusterName(7L, CLUSTER)).thenReturn(Optional.empty());

            AksDashboardDto dto = service.getDashboard(SUB, CLUSTER, RG);

            assertThat(dto.getPartialReason()).contains("ARM unavailable");
        }

        @Test
        @DisplayName("USD cost figures are restated in the subscription's billing currency")
        void costsAreInTheBillingCurrency() {
            prometheusConnected();
            when(configs.findByCloudAccountIdAndClusterName(7L, CLUSTER)).thenReturn(Optional.empty());
            when(prometheus.getClusterDailyCost("http://prom:9090", CLUSTER)).thenReturn(10.0);
            AzureBillingCurrencyService billing = mock(AzureBillingCurrencyService.class);
            when(billing.forSubscription(SUB)).thenReturn(new AzureBillingCurrencyService.BillingCurrency("INR", 95.0));
            ReflectionTestUtils.setField(service, "billingCurrencyService", billing);

            AksDashboardDto dto = service.getDashboard(SUB, CLUSTER, RG);

            assertThat(dto.getCurrency()).isEqualTo("INR");
            assertThat(dto.getCostMetrics().getDailyCost()).isCloseTo(950.0, within(1e-9));
        }

        @Test
        @DisplayName("without a billing currency service the figures stay USD and say so")
        void costsDefaultToUsd() {
            prometheusConnected();
            when(configs.findByCloudAccountIdAndClusterName(7L, CLUSTER)).thenReturn(Optional.empty());
            when(prometheus.getClusterDailyCost("http://prom:9090", CLUSTER)).thenReturn(10.0);

            AksDashboardDto dto = service.getDashboard(SUB, CLUSTER, RG);

            assertThat(dto.getCurrency()).isEqualTo("USD");
            assertThat(dto.getCostMetrics().getDailyCost()).isCloseTo(10.0, within(1e-9));
        }

        @Test
        @DisplayName("a cached copy is marked stale once the agent stops checking in")
        void agentLivenessIsReadNow() {
            when(clusters.findByCloudAccount_IdAndClusterName(7L, CLUSTER)).thenReturn(Optional.empty());
            KubernetesClusterConfig config = new KubernetesClusterConfig();
            config.setId(3L);
            config.setClusterName(CLUSTER);
            when(configs.findByCloudAccountIdAndClusterName(7L, CLUSTER)).thenReturn(Optional.of(config));

            AksDashboardDto cached = new AksDashboardDto();
            cached.setClusterName(CLUSTER);
            cached.setConnected(true);

            config.setLastAgentCheckin(LocalDateTime.now().minusHours(2));
            service.refreshAgentLiveness(cached, SUB);
            assertThat(cached.isAgentStale()).isTrue();
            assertThat(cached.getAgentLastSeenAt()).isEqualTo(config.getLastAgentCheckin());

            config.setLastAgentCheckin(LocalDateTime.now().minusMinutes(2));
            service.refreshAgentLiveness(cached, SUB);
            assertThat(cached.isAgentStale()).isFalse();
        }
    }
}
