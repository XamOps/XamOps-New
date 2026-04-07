package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AsgSpotConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface AsgSpotConfigRepository extends JpaRepository<AsgSpotConfig, Long> {
    Optional<AsgSpotConfig> findByAccountIdAndRegionAndAsgName(Long accountId, String region, String asgName);

    @Modifying
    @Transactional
    void deleteByAccountId(Long accountId);
}
