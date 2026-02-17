package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SpotOptimizationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SpotOptimizationEventRepository extends JpaRepository<SpotOptimizationEvent, Long> {
    List<SpotOptimizationEvent> findByAccountIdOrderByEventTimeDesc(Long accountId);

    List<SpotOptimizationEvent> findTop10ByOrderByEventTimeDesc();

    List<SpotOptimizationEvent> findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(
            Long accountId, Instant start, Instant end);

    List<SpotOptimizationEvent> findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
            Long accountId, Instant start, Instant end, String status);

    List<SpotOptimizationEvent> findByAccountIdAndAsgNameAndEventTimeBetweenOrderByEventTimeDesc(
            Long accountId, String asgName, Instant start, Instant end);

    long countByAccountIdAndStatusAndEventTimeBetween(
            Long accountId, String status, Instant start, Instant end);
}
