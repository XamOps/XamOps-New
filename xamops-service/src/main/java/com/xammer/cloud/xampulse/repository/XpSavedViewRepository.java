package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpSavedView;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpSavedViewRepository extends JpaRepository<XpSavedView, Long> {
    @Query("select v from XpSavedView v where v.cloudAccountId = :cloudAccountId and (v.ownerUserId = :userId or v.shared = true) order by v.name")
    List<XpSavedView> findVisible(@Param("cloudAccountId") Long cloudAccountId, @Param("userId") Long userId);
    Optional<XpSavedView> findByIdAndCloudAccountIdAndOwnerUserId(Long id, Long cloudAccountId, Long ownerUserId);
    void deleteByIdAndCloudAccountIdAndOwnerUserId(Long id, Long cloudAccountId, Long ownerUserId);
}
