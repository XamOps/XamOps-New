package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LabelAuditChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Reads of the label audit trail. Nothing updates or deletes an entry. */
@Repository
public interface LabelAuditChangeRepository extends JpaRepository<LabelAuditChange, Long> {

    List<LabelAuditChange> findByEventIdInOrderByIdAsc(Collection<Long> eventIds);

    /**
     * The client's events that changed one resource or service, newest first, older than
     * {@code beforeId}. {@code targetIds} carries every id form the target may be stored under.
     */
    @Query("SELECT c.eventId FROM LabelAuditChange c, LabelAuditEvent e "
            + "WHERE e.id = c.eventId AND e.clientId = :clientId AND c.cloudAccountId = :accountId "
            + "AND c.targetType = :targetType AND c.targetId IN :targetIds AND c.eventId < :beforeId "
            + "GROUP BY c.eventId ORDER BY c.eventId DESC")
    List<Long> findEventIdsForTarget(@Param("clientId") Long clientId,
                                     @Param("accountId") Long accountId,
                                     @Param("targetType") String targetType,
                                     @Param("targetIds") Collection<String> targetIds,
                                     @Param("beforeId") Long beforeId,
                                     Pageable page);
}
