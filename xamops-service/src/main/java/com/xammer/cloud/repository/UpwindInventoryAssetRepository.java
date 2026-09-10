package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindInventoryAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindInventoryAssetRepository extends JpaRepository<UpwindInventoryAsset, Long> {

    Optional<UpwindInventoryAsset> findByUpwindId(String upwindId);

    /**
     * Looks an asset up by its cloud resource id — the key findings join on. Findings
     * reference the cloud resource, not Upwind's internal asset id.
     */
    Optional<UpwindInventoryAsset> findByCloudResourceId(String cloudResourceId);

    /**
     * The Inventory table, ACTIVE only.
     *
     * The status filter is not optional: every other inventory read has it (countByCategory,
     * countBySubCategory, riskRollup, the identity and elevated-privilege lists), so without it
     * the table total disagreed with the category rail and the Assets tile on the same screen —
     * measured on UAT as 2,136 against 1,867, the difference being archived assets.
     */
    @Query("SELECT a FROM UpwindInventoryAsset a WHERE a.cloudAccountId IN :accountIds " +
           "AND a.status = 'ACTIVE' ORDER BY a.name ASC")
    Page<UpwindInventoryAsset> findActiveByAccounts(@Param("accountIds") List<String> accountIds,
                                                    Pageable pageable);

    @Query("SELECT a FROM UpwindInventoryAsset a WHERE a.cloudAccountId IN :accountIds " +
           "AND a.status = 'ACTIVE' AND a.assetCategory = :assetCategory ORDER BY a.name ASC")
    Page<UpwindInventoryAsset> findActiveByAccountsAndCategory(
            @Param("accountIds") List<String> accountIds,
            @Param("assetCategory") String assetCategory, Pageable pageable);

    /** Live rows, for the archive safety guard (see UpwindSyncService#archiveIsSafe). */
    @Query("SELECT COUNT(a) FROM UpwindInventoryAsset a WHERE a.status = 'ACTIVE'")
    long countActive();

    @Query("SELECT a.assetCategory, COUNT(a) FROM UpwindInventoryAsset a " +
           "WHERE a.cloudAccountId IN :accountIds AND a.status = 'ACTIVE' GROUP BY a.assetCategory")
    List<Object[]> countByCategory(@Param("accountIds") List<String> accountIds);

    @Query("SELECT a.assetSubCategory, COUNT(a) FROM UpwindInventoryAsset a " +
           "WHERE a.cloudAccountId IN :accountIds AND a.status = 'ACTIVE' AND a.assetCategory = :category " +
           "GROUP BY a.assetSubCategory")
    List<Object[]> countBySubCategory(@Param("accountIds") List<String> accountIds,
                                      @Param("category") String category);

    /**
     * Sensor coverage: which protection mechanism covers each asset, per account and sub-category.
     *
     * Rows are {@code [cloudAccountId, assetSubCategory, protectedBy, count]}. {@code protectedBy}
     * is nullable and the null case matters — it means the catalog carried no verdict for that
     * asset, which is NOT the same as the explicit {@code none}. Measured 2026-09-07 across three
     * accounts: {@code sensor}, {@code cloud_scanner}, {@code none} and null all occur, and null
     * is the majority on accounts with no sensor deployed. Callers must keep the two apart or
     * they will report unscanned assets as deliberately unprotected.
     *
     * Grouped rather than counted per combination so one query answers the whole view.
     */
    @Query("SELECT a.cloudAccountId, a.assetSubCategory, a.protectedBy, COUNT(a) "
           + "FROM UpwindInventoryAsset a "
           + "WHERE a.cloudAccountId IN :accountIds AND a.status = 'ACTIVE' "
           + "AND (:category IS NULL OR a.assetCategory = :category) "
           + "GROUP BY a.cloudAccountId, a.assetSubCategory, a.protectedBy")
    List<Object[]> coverageBreakdown(@Param("accountIds") List<String> accountIds,
                                     @Param("category") String category);

    @Transactional
    @Modifying
    @Query("UPDATE UpwindInventoryAsset a SET a.status = 'RESOLVED' " +
           "WHERE a.syncedAt < :runStart AND a.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);

    /**
     * Distinct cloud accounts Upwind has LIVE assets for. Endpoint and SBOM fetches are
     * per-account query-parameter calls, so the sync needs the account list.
     *
     * Excludes RESOLVED rows. Without that filter a disconnected account never left the sync
     * loop: its archived assets kept it in this list for ever, so every 6-hourly run spent an
     * inventory search and an endpoint walk on an account Upwind no longer reports anything
     * for — permanently, and once per account ever disconnected.
     */
    @Query("SELECT DISTINCT a.cloudAccountId FROM UpwindInventoryAsset a " +
           "WHERE a.cloudAccountId IS NOT NULL AND a.status <> 'RESOLVED'")
    List<String> findDistinctCloudAccountIds();

    /**
     * Every identity asset Upwind knows about — IAM users/roles/groups/policies and
     * Kubernetes service accounts, roles and bindings.
     *
     * This is the authoritative identity roster. An earlier claim that inventory exposed
     * only a single identity asset was measured against the flat
     * {@code /inventory/assets/search}; the catalog endpoint returns 1,076 identity assets
     * across our two accounts (verified live 2026-08-04), so the Identities view no longer
     * has to be inferred from whatever happens to be failing a check.
     */
    @Query("SELECT a FROM UpwindInventoryAsset a WHERE a.cloudAccountId IN :accountIds " +
           "AND a.status = 'ACTIVE' AND a.assetCategory = 'identity' ORDER BY a.name ASC")
    List<UpwindInventoryAsset> findIdentityAssets(@Param("accountIds") List<String> accountIds);

    /**
     * Assets Upwind flagged as holding elevated privilege. This is the real privilege signal
     * from the inventory catalog's {@code high_privilege_risk.has_elevated_privilege} — the
     * Identities view previously had none and could only list the subjects of failing checks.
     */
    @Query("SELECT a FROM UpwindInventoryAsset a WHERE a.cloudAccountId IN :accountIds " +
           "AND a.status = 'ACTIVE' AND a.hasElevatedPrivilege = TRUE ORDER BY a.name ASC")
    List<UpwindInventoryAsset> findElevatedPrivilegeAssets(@Param("accountIds") List<String> accountIds);

    /**
     * Data-classification and exposure rollup across the client's active assets:
     * [assets, pii, phi, pci, elevatedPrivilege, eolTechnology, internetExposed].
     * Counted in one pass because the Inventory header shows all of them together.
     */
    @Query("SELECT COUNT(a), " +
           "SUM(CASE WHEN a.sensitivePiiAtRest = TRUE OR a.sensitivePiiInTransit = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN a.sensitivePhiAtRest = TRUE OR a.sensitivePhiInTransit = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN a.sensitivePciAtRest = TRUE OR a.sensitivePciInTransit = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN a.hasElevatedPrivilege = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN a.hasEolTechnology = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN a.internetExposureType IS NOT NULL THEN 1 ELSE 0 END) " +
           "FROM UpwindInventoryAsset a " +
           "WHERE a.cloudAccountId IN :accountIds AND a.status = 'ACTIVE'")
    List<Object[]> riskRollup(@Param("accountIds") List<String> accountIds);
}
