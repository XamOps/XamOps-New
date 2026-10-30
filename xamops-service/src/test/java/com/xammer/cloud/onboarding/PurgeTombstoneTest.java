package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.ClusterOffboardingTombstoneRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.onboarding.ClusterDataPurgeParticipant;
import com.xammer.cloud.service.onboarding.ClusterDataPurger;
import com.xammer.cloud.service.onboarding.ClusterTombstoneGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BP-13 (design §2.2, §4.3, §4.5, F-14): the tombstone blocks BOTH ingest creators (k8s-agent and
 * DB-agent); the purger runs participants and reports per-participant counts; a participant failure
 * aborts with its name (idempotent retry).
 */
class PurgeTombstoneTest {

    private AgentIngestService ingest;
    private KubernetesClusterConfigRepository clusterRepo;
    private CloudAccountRepository cloudAccountRepo;
    private ClusterOffboardingTombstoneRepository tombstoneRepo;

    @BeforeEach
    void setUp() {
        clusterRepo = mock(KubernetesClusterConfigRepository.class);
        cloudAccountRepo = mock(CloudAccountRepository.class);
        tombstoneRepo = mock(ClusterOffboardingTombstoneRepository.class);
        ingest = new AgentIngestService(clusterRepo, mock(AgentDataCacheRepository.class),
                mock(AgentEventRepository.class), cloudAccountRepo);
        ReflectionTestUtils.setField(ingest, "clusterTombstoneGuard", new ClusterTombstoneGuard(tombstoneRepo));

        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId("111111111111");
        account.setProvider("AWS");
        when(cloudAccountRepo.findByAwsAccountId("111111111111")).thenReturn(List.of(account));
    }

    @Test
    void tombstone_blocksKubernetesAgentCreator() {
        when(tombstoneRepo.existsByCloudAccountPkAndClusterName(7L, "prod-eks")).thenReturn(true);
        KubernetesClusterConfig r = ingest.findOrAutoRegisterClusterConfig("prod-eks", "111111111111");
        assertThat(r).isNull();
        verify(clusterRepo, never()).save(any());
    }

    @Test
    void tombstone_blocksDbAgentCreator() {
        when(tombstoneRepo.existsByCloudAccountPkAndClusterName(7L, "prod-eks")).thenReturn(true);
        KubernetesClusterConfig r = ingest.findOrCreateDbOnlyClusterConfig("prod-eks", "111111111111");
        assertThat(r).isNull();
        verify(clusterRepo, never()).save(any());
    }

    @Test
    void noTombstone_kubernetesCreatorProceeds() {
        when(tombstoneRepo.existsByCloudAccountPkAndClusterName(7L, "prod-eks")).thenReturn(false);
        when(clusterRepo.findByAgentApiKey(anyString())).thenReturn(Optional.empty());
        when(clusterRepo.findByCloudAccountIdAndClusterName(eq(7L), eq("prod-eks"))).thenReturn(Optional.empty());
        when(clusterRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        KubernetesClusterConfig r = ingest.findOrAutoRegisterClusterConfig("prod-eks", "111111111111");
        assertThat(r).isNotNull();
        verify(clusterRepo).save(any());
    }

    @Test
    void purger_runsEveryParticipant_reportsCounts() {
        ClusterDataPurgeParticipant a = stub("agentDataRows", 3);
        ClusterDataPurgeParticipant b = stub("connectTokens", 2);
        ClusterDataPurger purger = new ClusterDataPurger(List.of(a, b));
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        Map<String, Integer> deleted = purger.purge(config, new CloudAccount());
        assertThat(deleted).containsEntry("agentDataRows", 3).containsEntry("connectTokens", 2);
    }

    @Test
    void purger_participantFailure_abortsWithName() {
        ClusterDataPurgeParticipant ok = stub("agentDataRows", 1);
        ClusterDataPurgeParticipant boom = new ClusterDataPurgeParticipant() {
            @Override public String name() { return "connectTokens"; }
            @Override public int purge(KubernetesClusterConfig c, CloudAccount o) { throw new RuntimeException("db down"); }
        };
        ClusterDataPurger purger = new ClusterDataPurger(List.of(ok, boom));
        assertThatThrownBy(() -> purger.purge(new KubernetesClusterConfig(), new CloudAccount()))
                .isInstanceOf(ClusterDataPurger.PurgeFailedException.class)
                .satisfies(e -> assertThat(((ClusterDataPurger.PurgeFailedException) e).getParticipant())
                        .isEqualTo("connectTokens"));
    }

    private static ClusterDataPurgeParticipant stub(String name, int removed) {
        return new ClusterDataPurgeParticipant() {
            @Override public String name() { return name; }
            @Override public int purge(KubernetesClusterConfig c, CloudAccount o) { return removed; }
        };
    }
}
