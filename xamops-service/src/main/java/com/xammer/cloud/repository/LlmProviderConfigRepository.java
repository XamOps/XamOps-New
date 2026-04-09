package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, Long> {

    List<LlmProviderConfig> findByClientIdOrderByCreatedAtDesc(Long clientId);

    Optional<LlmProviderConfig> findByProxyEndpointId(String proxyEndpointId);

    Optional<LlmProviderConfig> findByClientIdAndProvider(Long clientId, String provider);

    boolean existsByClientIdAndProvider(Long clientId, String provider);
}
