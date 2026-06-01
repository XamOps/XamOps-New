package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ExplainRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ExplainRequestRepository extends JpaRepository<ExplainRequest, String> {

    Optional<ExplainRequest> findFirstByAgentNameAndStatusOrderByCreatedAtAsc(String agentName, String status);

    @Modifying
    @Query("UPDATE ExplainRequest e SET e.status = 'EXPIRED' WHERE e.status IN ('PENDING','IN_FLIGHT') AND e.createdAt < :cutoff")
    int expireStaleRequests(@Param("cutoff") LocalDateTime cutoff);
}
