package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotGroupEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Finders for {@link SpotGroupEvent} (plan-phase-03.md task 3.2). {@code ux_sge_dedupe} keeps the
 * same fact idempotent; the recording writers arrive with the event pipelines in B5/B6.
 */
@Repository
public interface SpotGroupEventRepository extends JpaRepository<SpotGroupEvent, Long> {

    List<SpotGroupEvent> findByGroupIdOrderByOccurredAtDesc(UUID groupId);
}
