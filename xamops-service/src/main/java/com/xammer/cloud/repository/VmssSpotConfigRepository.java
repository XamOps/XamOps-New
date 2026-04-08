package com.xammer.cloud.repository;

import com.xammer.cloud.domain.VmssSpotConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface VmssSpotConfigRepository extends JpaRepository<VmssSpotConfig, Long> {
    Optional<VmssSpotConfig> findByAccountIdAndResourceGroupAndVmssName(
            Long accountId, String resourceGroup, String vmssName);

    List<VmssSpotConfig> findByAccountId(Long accountId);

    List<VmssSpotConfig> findByEnabledTrue();

    @Modifying
    @Transactional
    void deleteByAccountId(Long accountId);
}
