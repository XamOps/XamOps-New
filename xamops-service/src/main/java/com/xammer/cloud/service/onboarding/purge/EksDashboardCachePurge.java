package com.xammer.cloud.service.onboarding.purge;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EksDashboardCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.EksDashboardCacheRepository;
import com.xammer.cloud.service.onboarding.ClusterDataPurgeParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Deletes {@code EksDashboardCache} rows for an offboarded cluster (design §4.5). These are keyed by
 * cluster name + account + region and are not FK-bound to the config row, so they must be removed
 * explicitly.
 */
@Component
@Order(5)
public class EksDashboardCachePurge implements ClusterDataPurgeParticipant {

    private final EksDashboardCacheRepository eksDashboardCacheRepository;

    public EksDashboardCachePurge(EksDashboardCacheRepository eksDashboardCacheRepository) {
        this.eksDashboardCacheRepository = eksDashboardCacheRepository;
    }

    @Override
    public String name() {
        return "eksDashboardRows";
    }

    @Override
    @Transactional
    public int purge(KubernetesClusterConfig config, CloudAccount owner) {
        // Match by cluster name within this account's rows (account id is the AWS number string).
        List<EksDashboardCache> rows = eksDashboardCacheRepository.findByClusterName(config.getClusterName());
        int count = 0;
        for (EksDashboardCache row : rows) {
            eksDashboardCacheRepository.delete(row);
            count++;
        }
        return count;
    }
}
