package com.xammer.billops.repository;

import com.xammer.cloud.domain.TicketEmailMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Read access to the inbound-mail ledger. Rows are written by xamops-service, which owns
 * ingestion; BillOps only shows the mail a ticket came from.
 */
@Repository
public interface TicketEmailMessageRepository extends JpaRepository<TicketEmailMessage, Long> {

    Optional<TicketEmailMessage> findFirstByTicket_IdAndStatusOrderByIdAsc(Long ticketId, String status);
}
