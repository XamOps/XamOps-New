package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CapacityNotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CapacityNotificationHistoryRepository extends JpaRepository<CapacityNotificationHistory, Long> {

    /**
     * Check if a notification has already been sent for a specific capacity and threshold
     */
    @Query("SELECT n FROM CapacityNotificationHistory n WHERE n.cidrId = :cidrId AND n.thresholdPercent = :threshold ORDER BY n.notifiedAt DESC")
    List<CapacityNotificationHistory> findByCidrIdAndThreshold(@Param("cidrId") String cidrId,
                                                                @Param("threshold") Integer threshold);

    /**
     * Get the most recent notification for a capacity and threshold
     */
    @Query("SELECT n FROM CapacityNotificationHistory n WHERE n.cidrId = :cidrId AND n.thresholdPercent = :threshold ORDER BY n.notifiedAt DESC")
    Optional<CapacityNotificationHistory> findMostRecentNotification(@Param("cidrId") String cidrId,
                                                                      @Param("threshold") Integer threshold);

    /**
     * Find all notifications for a specific user
     */
    List<CapacityNotificationHistory> findByUserId(Long userId);

    /**
     * Find notifications sent within a time range
     */
    @Query("SELECT n FROM CapacityNotificationHistory n WHERE n.notifiedAt >= :startTime AND n.notifiedAt <= :endTime ORDER BY n.notifiedAt DESC")
    List<CapacityNotificationHistory> findByNotifiedAtBetween(@Param("startTime") Instant startTime,
                                                               @Param("endTime") Instant endTime);

    /**
     * Delete old notification history (cleanup for capacities older than X days)
     */
    void deleteByNotifiedAtBefore(Instant cutoffDate);
}
