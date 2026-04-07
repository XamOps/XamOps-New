package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MeteringRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeteringRecordRepository extends JpaRepository<MeteringRecord, Long> {

    List<MeteringRecord> findByProductCodeOrderBySubmittedAtDesc(String productCode);

    List<MeteringRecord> findByProductCodeAndStatusOrderBySubmittedAtDesc(String productCode, String status);

    List<MeteringRecord> findByProductCodeOrderBySubmittedAtDesc(String productCode, Pageable pageable);

    List<MeteringRecord> findByProductCodeAndCustomerIdentifierOrderBySubmittedAtDesc(
            String productCode, String customerIdentifier);

    @Query("SELECT COUNT(r) FROM MeteringRecord r WHERE r.productCode = :productCode AND r.status = 'FAILED'")
    long countFailedByProductCode(@Param("productCode") String productCode);

    @Query("SELECT SUM(r.quantity) FROM MeteringRecord r WHERE r.productCode = :productCode " +
           "AND r.dimension = :dimension AND r.status != 'FAILED' " +
           "AND CAST(r.submittedAt AS string) LIKE CONCAT(:yearMonth, '%')")
    Long sumReportedQuantityByDimensionAndMonth(@Param("productCode") String productCode,
                                                @Param("dimension") String dimension,
                                                @Param("yearMonth") String yearMonth);

    @Query("SELECT COUNT(r) FROM MeteringRecord r WHERE r.productCode = :productCode")
    long countTotalByProductCode(@Param("productCode") String productCode);

    /**
     * Analytics: total quantity submitted per dimension per month (YYYY-MM prefix).
     * Returns Object[]{yearMonth String, dimension String, totalQty Long}
     */
    @Query("SELECT SUBSTRING(CAST(r.submittedAt AS string), 1, 7), r.dimension, SUM(r.quantity) " +
           "FROM MeteringRecord r WHERE r.productCode = :productCode AND r.status != 'FAILED' " +
           "GROUP BY SUBSTRING(CAST(r.submittedAt AS string), 1, 7), r.dimension " +
           "ORDER BY SUBSTRING(CAST(r.submittedAt AS string), 1, 7) ASC")
    List<Object[]> sumByDimensionByMonth(@Param("productCode") String productCode);

    /**
     * Analytics: SUCCESS vs FAILED counts per month.
     * Returns Object[]{yearMonth String, status String, count Long}
     */
    @Query("SELECT SUBSTRING(CAST(r.submittedAt AS string), 1, 7), r.status, COUNT(r) " +
           "FROM MeteringRecord r WHERE r.productCode = :productCode " +
           "GROUP BY SUBSTRING(CAST(r.submittedAt AS string), 1, 7), r.status " +
           "ORDER BY SUBSTRING(CAST(r.submittedAt AS string), 1, 7) ASC")
    List<Object[]> countByStatusByMonth(@Param("productCode") String productCode);
}
