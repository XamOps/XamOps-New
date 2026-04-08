package com.xammer.cloud.repository;

import com.xammer.cloud.domain.IsvMarketplaceCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IsvMarketplaceCustomerRepository extends JpaRepository<IsvMarketplaceCustomer, Long> {

    Optional<IsvMarketplaceCustomer> findByProductCodeAndCustomerIdentifier(
            String productCode, String customerIdentifier);

    List<IsvMarketplaceCustomer> findByProductCode(String productCode);

    List<IsvMarketplaceCustomer> findByProductCodeAndStatus(String productCode, String status);

    @Query("SELECT COUNT(c) FROM IsvMarketplaceCustomer c WHERE c.productCode = :productCode AND c.status = 'ACTIVE'")
    long countActiveByProductCode(@Param("productCode") String productCode);

    @Query("SELECT COUNT(c) FROM IsvMarketplaceCustomer c WHERE c.productCode = :productCode")
    long countTotalByProductCode(@Param("productCode") String productCode);

    /** Buyer usage view: find all subscriptions (across all ISV products) for one AWS account. */
    List<IsvMarketplaceCustomer> findByCustomerAwsAccountId(String customerAwsAccountId);

    /** Entitlement scheduler: active customers with no entitlement confirmation since the cutoff. */
    @Query("SELECT c FROM IsvMarketplaceCustomer c WHERE c.status = 'ACTIVE' " +
           "AND (c.lastEntitlementCheck IS NULL OR c.lastEntitlementCheck < :cutoff)")
    List<IsvMarketplaceCustomer> findActiveCustomersNotCheckedSince(
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * Analytics: cumulative active customer count per registration month.
     * Returns Object[]{yearMonth String, count Long}
     */
    @Query("SELECT SUBSTRING(CAST(c.registeredAt AS string), 1, 7), COUNT(c) " +
           "FROM IsvMarketplaceCustomer c WHERE c.productCode = :productCode " +
           "GROUP BY SUBSTRING(CAST(c.registeredAt AS string), 1, 7) " +
           "ORDER BY SUBSTRING(CAST(c.registeredAt AS string), 1, 7) ASC")
    List<Object[]> countRegistrationsByMonth(@Param("productCode") String productCode);
}
