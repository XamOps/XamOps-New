package com.xammer.cloud.repository;

import com.xammer.cloud.domain.TicketEmailMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketEmailMessageRepository extends JpaRepository<TicketEmailMessage, Long> {

    boolean existsByMessageId(String messageId);

    /**
     * Threading. An incoming reply names earlier messages of the thread in its In-Reply-To and
     * References headers; whichever of those we have already ingested tells us the ticket.
     *
     * <p>Ordered newest first so the closest ancestor wins when a long thread references several
     * messages we have seen.
     */
    @Query("SELECT m FROM TicketEmailMessage m "
            + "WHERE m.messageId IN :messageIds AND m.ticket IS NOT NULL "
            + "ORDER BY m.id DESC")
    List<TicketEmailMessage> findByMessageIdInWithTicket(@Param("messageIds") Collection<String> messageIds);

    /** The mail a ticket was opened from — the "detail about that mail" on the ticket page. */
    Optional<TicketEmailMessage> findFirstByTicket_IdAndStatusOrderByIdAsc(Long ticketId, String status);
}
