package com.xammer.cloud.service.onboarding.purge;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.service.onboarding.ClusterDataPurgeParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes {@code agent_data_cache} rows for an offboarded cluster (design §4.5). The FK cascade on
 * config delete also covers these, but an explicit delete lets the offboard report a count.
 */
@Component
@Order(10)
public class AgentDataCachePurge implements ClusterDataPurgeParticipant {

    private final AgentDataCacheRepository agentDataCacheRepository;

    public AgentDataCachePurge(AgentDataCacheRepository agentDataCacheRepository) {
        this.agentDataCacheRepository = agentDataCacheRepository;
    }

    @Override
    public String name() {
        return "agentDataRows";
    }

    @Override
    @Transactional
    public int purge(KubernetesClusterConfig config, CloudAccount owner) {
        int count = agentDataCacheRepository.findByClusterConfigId(config.getId()).size();
        agentDataCacheRepository.deleteByClusterConfigId(config.getId());
        return count;
    }
}
