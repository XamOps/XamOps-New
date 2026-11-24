package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LabelAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Reads of the label audit trail. Nothing updates or deletes an entry. */
@Repository
public interface LabelAuditEventRepository extends JpaRepository<LabelAuditEvent, Long> {

    /**
     * One account's history, newest first, older than {@code beforeId}: the account's own events
     * plus the client-level ones (label create, edit, delete), which have neither an account nor an
     * account reference. A refused attempt naming an account that does not resolve has a reference
     * and no account, so it shows in no account's history.
     */
    @Query("SELECT e FROM LabelAuditEvent e WHERE e.clientId = :clientId AND e.id < :beforeId "
            + "AND (e.cloudAccountId = :accountId OR (e.cloudAccountId IS NULL AND e.accountRef IS NULL)) "
            + "ORDER BY e.id DESC")
    List<LabelAuditEvent> findForAccount(@Param("clientId") Long clientId,
                                         @Param("accountId") Long accountId,
                                         @Param("beforeId") Long beforeId,
                                         Pageable page);

    /** These events, if they are the client's. */
    @Query("SELECT e FROM LabelAuditEvent e WHERE e.clientId = :clientId AND e.id IN :ids ORDER BY e.id DESC")
    List<LabelAuditEvent> findForClient(@Param("clientId") Long clientId, @Param("ids") Collection<Long> ids);
}
