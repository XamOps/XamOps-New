package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpAccountRegionRepository extends JpaRepository<XpAccountRegion, Long> {
    List<XpAccountRegion> findByCloudAccountId(Long cloudAccountId);
    Optional<XpAccountRegion> findByCloudAccountIdAndRegion(Long cloudAccountId, String region);
}
