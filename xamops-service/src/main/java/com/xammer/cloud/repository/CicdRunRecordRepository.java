package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CicdRunRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CicdRunRecordRepository extends JpaRepository<CicdRunRecord, Long> {

    List<CicdRunRecord> findByUserIdAndProviderAndStartedAtAfterOrderByStartedAtDesc(
            Long userId, String provider, Instant since);

    Optional<CicdRunRecord> findByConfigIdAndProviderAndRunId(
            Long configId, String provider, String runId);

    @Query("SELECT r FROM CicdRunRecord r WHERE r.configId = :configId AND r.provider = :provider ORDER BY r.startedAt DESC")
    List<CicdRunRecord> findTopByConfigIdAndProvider(
            @Param("configId") Long configId,
            @Param("provider") String provider,
            Pageable pageable);

    @Query("SELECT r FROM CicdRunRecord r WHERE r.userId = :userId AND r.workflowName = :workflowName " +
           "AND r.provider = :provider AND r.status = 'success' ORDER BY r.startedAt DESC")
    List<CicdRunRecord> findTopSuccessRun(
            @Param("userId") Long userId,
            @Param("workflowName") String workflowName,
            @Param("provider") String provider,
            Pageable pageable);
}
