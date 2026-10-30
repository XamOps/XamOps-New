package com.xammer.cloud.service.onboarding.purge;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.ClusterConnectTokenRepository;
import com.xammer.cloud.service.onboarding.ClusterDataPurgeParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Deletes {@code cluster_connect_token} rows for an offboarded cluster (design §4.5). The FK cascade
 * also covers these; the explicit delete reports a count and keeps purge self-contained.
 */
@Component
@Order(20)
public class ConnectTokenPurge implements ClusterDataPurgeParticipant {

    private final ClusterConnectTokenRepository tokenRepository;

    public ConnectTokenPurge(ClusterConnectTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Override
    public String name() {
        return "connectTokens";
    }

    @Override
    @Transactional
    public int purge(KubernetesClusterConfig config, CloudAccount owner) {
        List<ClusterConnectToken> all = tokenRepository.findAll().stream()
                .filter(t -> config.getId().equals(t.getClusterConfigId()))
                .toList();
        tokenRepository.deleteAll(all);
        return all.size();
    }
}
