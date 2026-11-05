package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP-3 (design §4.3): the automation-agent cluster key authenticates as a CLUSTER principal with
 * role AUTOMATION, resolved via {@code findByAutomationAgentApiKey} after the data-key tier. The
 * data key still resolves as role DATA.
 */
class AgentAutomationKeyAuthTest {

    private static final String DATA_KEY = "xamops_data-key";
    private static final String AUTOMATION_KEY = "xamops_automation-key";

    private AgentIngestService service;
    private KubernetesClusterConfigRepository clusterRepo;
    private CloudAccountRepository cloudAccountRepo;

    @BeforeEach
    void setUp() {
        clusterRepo = mock(KubernetesClusterConfigRepository.class);
        cloudAccountRepo = mock(CloudAccountRepository.class);
        service = new AgentIngestService(
                clusterRepo, mock(AgentDataCacheRepository.class),
                mock(AgentEventRepository.class), cloudAccountRepo);

        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId("111111111111");
        account.setProvider("AWS");
        when(cloudAccountRepo.findById(7L)).thenReturn(Optional.of(account));

        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(7L);
        config.setClusterName("prod-eks");
        config.setAgentApiKey(DATA_KEY);
        config.setAutomationAgentApiKey(AUTOMATION_KEY);

        when(clusterRepo.findByAgentApiKey(DATA_KEY)).thenReturn(Optional.of(config));
        when(clusterRepo.findByAgentApiKey(AUTOMATION_KEY)).thenReturn(Optional.empty());
        when(clusterRepo.findByAutomationAgentApiKey(AUTOMATION_KEY)).thenReturn(Optional.of(config));
    }

    @Test
    void automationKey_resolvesClusterPrincipalWithAutomationRole() {
        AgentPrincipal p = service.authenticate(AUTOMATION_KEY, "10.0.0.1");
        assertThat(p).isNotNull();
        assertThat(p.getScope()).isEqualTo(AgentPrincipal.Scope.CLUSTER);
        assertThat(p.getRole()).isEqualTo(AgentPrincipal.AgentRole.AUTOMATION);
        assertThat(p.getClusterName()).isEqualTo("prod-eks");
        assertThat(p.toString()).contains("/AUTOMATION");
    }

    @Test
    void dataKey_resolvesClusterPrincipalWithDataRole() {
        AgentPrincipal p = service.authenticate(DATA_KEY, "10.0.0.1");
        assertThat(p).isNotNull();
        assertThat(p.getScope()).isEqualTo(AgentPrincipal.Scope.CLUSTER);
        assertThat(p.getRole()).isEqualTo(AgentPrincipal.AgentRole.DATA);
    }
}
