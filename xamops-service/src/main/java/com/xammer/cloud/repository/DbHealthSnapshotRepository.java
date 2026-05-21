package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbHealthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DbHealthSnapshotRepository extends JpaRepository<DbHealthSnapshot, Long> {

    Optional<DbHealthSnapshot> findFirstByAccountIdAndInstanceIdOrderByComputedAtDesc(
        Long accountId, String instanceId);

    List<DbHealthSnapshot> findByAccountIdAndInstanceIdAndComputedAtAfterOrderByComputedAtAsc(
        Long accountId, String instanceId, LocalDateTime after);

    @Query("SELECT s FROM DbHealthSnapshot s WHERE s.accountId = :accountId " +
           "AND s.computedAt = (SELECT MAX(s2.computedAt) FROM DbHealthSnapshot s2 " +
           "WHERE s2.accountId = :accountId AND s2.instanceId = s.instanceId)")
    List<DbHealthSnapshot> findLatestPerInstance(@Param("accountId") Long accountId);

    @Modifying
    @Query("DELETE FROM DbHealthSnapshot s WHERE s.computedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
