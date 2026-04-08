package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    /** All PENDING events for a specific product, oldest-first (for batch processing) */
    List<UsageEvent> findByProductCodeAndStatusOrderByCreatedAtAsc(String productCode, String status);

    /** All PENDING events across all products — used by the hourly scheduler */
    @Query("SELECT e FROM UsageEvent e WHERE e.status = 'PENDING' ORDER BY e.productCode, e.createdAt ASC")
    List<UsageEvent> findAllPending();

    /** Pending events older than the cutoff (i.e. events the scheduler missed last cycle) */
    @Query("SELECT e FROM UsageEvent e WHERE e.status = 'PENDING' AND e.createdAt <= :cutoff ORDER BY e.createdAt ASC")
    List<UsageEvent> findPendingOlderThan(@Param("cutoff") LocalDateTime cutoff);

    List<UsageEvent> findByProductCodeAndCustomerIdentifierOrderByCreatedAtDesc(
            String productCode, String customerIdentifier);

    long countByProductCodeAndStatus(String productCode, String status);

    @Query("SELECT COUNT(e) FROM UsageEvent e WHERE e.productCode = :productCode AND e.status = 'PENDING'")
    long countPendingByProductCode(@Param("productCode") String productCode);

    @Query("SELECT COUNT(e) FROM UsageEvent e WHERE e.productCode = :productCode AND e.status = 'FAILED'")
    long countFailedByProductCode(@Param("productCode") String productCode);

    @Query("SELECT SUM(e.quantity) FROM UsageEvent e WHERE e.productCode = :productCode " +
           "AND e.dimension = :dimension AND e.status = 'SUBMITTED' AND e.createdAt >= :since")
    Long sumSubmittedQuantity(@Param("productCode") String productCode,
                              @Param("dimension") String dimension,
                              @Param("since") LocalDateTime since);

    /** Buyer usage view: aggregate submitted quantity per dimension for a given customer. */
    @Query("SELECT e.dimension, SUM(e.quantity) FROM UsageEvent e " +
           "WHERE e.productCode = :productCode AND e.customerIdentifier = :customerId " +
           "AND e.status = 'SUBMITTED' AND e.createdAt >= :since " +
           "GROUP BY e.dimension")
    List<Object[]> sumByDimensionForCustomer(@Param("productCode") String productCode,
                                              @Param("customerId") String customerId,
                                              @Param("since") LocalDateTime since);
}
