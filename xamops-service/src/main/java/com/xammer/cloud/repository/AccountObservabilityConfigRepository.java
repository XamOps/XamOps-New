package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountObservabilityConfigRepository extends JpaRepository<AccountObservabilityConfig, Long> {

    Optional<AccountObservabilityConfig> findByClientIdAndProviderAccountId(Long clientId, String providerAccountId);

    Optional<AccountObservabilityConfig> findByClientIdAndCloudAccountId(Long clientId, Long cloudAccountId);

    List<AccountObservabilityConfig> findByClientId(Long clientId);
}
