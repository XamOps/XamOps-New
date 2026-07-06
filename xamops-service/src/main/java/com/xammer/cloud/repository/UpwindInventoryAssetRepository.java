package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindInventoryAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindInventoryAssetRepository extends JpaRepository<UpwindInventoryAsset, Long> {

    Optional<UpwindInventoryAsset> findByUpwindId(String upwindId);

    Page<UpwindInventoryAsset> findByCloudAccountIdInOrderByNameAsc(List<String> accountIds, Pageable pageable);

    Page<UpwindInventoryAsset> findByCloudAccountIdInAndAssetCategoryOrderByNameAsc(
            List<String> accountIds, String assetCategory, Pageable pageable);

    @Query("SELECT a.assetCategory, COUNT(a) FROM UpwindInventoryAsset a " +
           "WHERE a.cloudAccountId IN :accountIds AND a.status = 'ACTIVE' GROUP BY a.assetCategory")
    List<Object[]> countByCategory(@Param("accountIds") List<String> accountIds);

    @Modifying
    @Query("UPDATE UpwindInventoryAsset a SET a.status = 'RESOLVED' " +
           "WHERE a.syncedAt < :runStart AND a.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);
}
