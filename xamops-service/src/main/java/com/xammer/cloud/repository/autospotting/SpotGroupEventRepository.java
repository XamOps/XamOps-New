package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotGroupEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Finders for {@link SpotGroupEvent} (plan-phase-03.md task 3.2). {@code ux_sge_dedupe} keeps the
 * same fact idempotent; the recording writers arrive with the event pipelines in B5/B6.
 */
@Repository
public interface SpotGroupEventRepository extends JpaRepository<SpotGroupEvent, Long> {

    List<SpotGroupEvent> findByGroupIdOrderByOccurredAtDesc(UUID groupId);

    /**
     * Counts for the Analytics view (plan-phase-14.md task 14.2): rows of {@code [groupId, eventType,
     * instanceType, lifecycle, failureClass, count]} for the given groups in {@code [from, to)}.
     */
    @Query("select e.groupId, e.eventType, e.instanceType, e.lifecycle, e.failureClass, count(e) "
            + "from SpotGroupEvent e where e.groupId in :groupIds and e.occurredAt >= :from and e.occurredAt < :to "
            + "group by e.groupId, e.eventType, e.instanceType, e.lifecycle, e.failureClass")
    List<Object[]> countByGroupAndType(@Param("groupIds") Collection<UUID> groupIds,
                                       @Param("from") Instant from, @Param("to") Instant to);

    /** The preemptions of a group since {@code from} (the GCP Preemptions figure, design-gcp.md §8.3). */
    long countByGroupIdAndEventTypeAndOccurredAtGreaterThanEqual(UUID groupId, String eventType, Instant from);

    /** True when the event pipeline has recorded anything for these groups, ever (a metric has a source). */
    boolean existsByGroupIdIn(Collection<UUID> groupIds);
}
