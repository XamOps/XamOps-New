package com.xammer.billops.repository;

import com.xammer.cloud.domain.TicketReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {

    /**
     * Clears the author back-reference on every reply written by the given user.
     * ticket_reply.user_id is nullable, so detaching keeps the reply text intact while
     * releasing the foreign key that would otherwise block deleting the app_user row.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TicketReply r SET r.author = NULL WHERE r.author.id = :userId")
    void detachAuthor(@Param("userId") Long userId);

    /**
     * Fetches all replies belonging to the same ticket as the given reply, with their
     * authors eagerly loaded (so roles/ids can be read outside a transaction). Used to
     * decide whether a reply has been "seen" (a later reply from a different author).
     */
    @Query("SELECT r FROM TicketReply r JOIN FETCH r.author "
            + "WHERE r.ticket.id = (SELECT r2.ticket.id FROM TicketReply r2 WHERE r2.id = :replyId)")
    List<TicketReply> findSiblingsByReplyId(@Param("replyId") Long replyId);

    /**
     * Returns the IDs of tickets that have at least one non-deleted reply whose message
     * contains the given term (case-insensitive). Used for chat-content search.
     *
     * <p>This MUST be a native query. {@code ticket_reply.message} is a @Lob mapped to a
     * PostgreSQL {@code oid} column — the column stores only a numeric pointer into
     * {@code pg_largeobject}, not the text. An HQL {@code LIKE} (even with a cast) therefore
     * compares against the OID number and never the message body, so it silently matches
     * nothing. {@code lo_get(oid)} reads the large object's bytes and {@code convert_from(..,'UTF8')}
     * decodes them to text, which is what the LIKE actually needs to run against.
     *
     * <p>The {@code pg_largeobject_metadata} guard skips rows whose large object no longer
     * exists; {@code lo_get} throws on a missing OID, which would abort the whole scan.
     *
     * <p>@Transactional is required because PostgreSQL refuses to read a large object in
     * auto-commit mode, and Spring Data does not wrap custom @Query methods in a transaction.
     */
    @Transactional(readOnly = true)
    @Query(value =
        "SELECT DISTINCT r.ticket_id FROM ticket_reply r "
        + "WHERE r.is_deleted = false "
        + "AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = r.message) "
        + "AND lower(convert_from(lo_get(r.message), 'UTF8')) LIKE lower(concat('%', :term, '%'))",
        nativeQuery = true)
    List<Long> findTicketIdsByReplyMessageContaining(@Param("term") String term);

    /**
     * Like {@link #findTicketIdsByReplyMessageContaining} but also returns the matched message
     * text and when it was sent so the UI can show a snippet with a timestamp. Each row is
     * {@code [ticket_id (BigInteger), message (String), created_at (Timestamp)]} for every
     * non-deleted reply that matches; a ticket with several matching replies yields several rows.
     * Ordered by ticket then reply id so the newest-first grouping is stable.
     *
     * <p>Same native-query + large-object + @Transactional reasoning as the id-only variant.
     */
    @Transactional(readOnly = true)
    @Query(value =
        "SELECT r.ticket_id AS ticketId, convert_from(lo_get(r.message), 'UTF8') AS message, r.created_at AS createdAt "
        + "FROM ticket_reply r "
        + "WHERE r.is_deleted = false "
        + "AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = r.message) "
        + "AND lower(convert_from(lo_get(r.message), 'UTF8')) LIKE lower(concat('%', :term, '%')) "
        + "ORDER BY r.ticket_id, r.id",
        nativeQuery = true)
    List<Object[]> findReplyMatches(@Param("term") String term);
}
