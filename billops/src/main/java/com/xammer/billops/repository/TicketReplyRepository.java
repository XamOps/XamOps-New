package com.xammer.billops.repository;

import com.xammer.cloud.domain.TicketReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {

    /**
     * Fetches all replies belonging to the same ticket as the given reply, with their
     * authors eagerly loaded (so roles/ids can be read outside a transaction). Used to
     * decide whether a reply has been "seen" (a later reply from a different author).
     */
    @Query("SELECT r FROM TicketReply r JOIN FETCH r.author "
            + "WHERE r.ticket.id = (SELECT r2.ticket.id FROM TicketReply r2 WHERE r2.id = :replyId)")
    List<TicketReply> findSiblingsByReplyId(@Param("replyId") Long replyId);

    /**
     * Returns the IDs of tickets that have at least one non-deleted reply whose
     * message contains the given term (case-insensitive). Used for reply-body search.
     */
    @Query("SELECT DISTINCT r.ticket.id FROM TicketReply r "
            + "WHERE r.deleted = false AND lower(r.message) LIKE lower(concat('%', :term, '%'))")
    List<Long> findTicketIdsByReplyMessageContaining(@Param("term") String term);
}
