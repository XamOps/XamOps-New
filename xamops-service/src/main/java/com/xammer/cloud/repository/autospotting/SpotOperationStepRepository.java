package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotOperationStep;
import com.xammer.cloud.domain.autospotting.SpotOperationStepId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Finders for {@link SpotOperationStep} (plan-phase-03.md task 3.2), keyed by
 * {@link SpotOperationStepId}. The fenced step protocol's writes arrive in Phases 06 and 07.
 */
@Repository
public interface SpotOperationStepRepository extends JpaRepository<SpotOperationStep, SpotOperationStepId> {

    List<SpotOperationStep> findByOperationIdOrderBySeqAsc(UUID operationId);
}
