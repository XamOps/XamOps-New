package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotGroupTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Finders for {@link SpotGroupTransition} (plan-phase-03.md task 3.2). The ledger reads a group's
 * transitions in {@code id} order (DR-01, ledger §6.2). Rows are append-only and monotonic by the
 * database triggers.
 */
@Repository
public interface SpotGroupTransitionRepository extends JpaRepository<SpotGroupTransition, Long> {

    List<SpotGroupTransition> findByGroupIdOrderByIdAsc(UUID groupId);
}
