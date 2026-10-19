package com.xammer.billops.repository;

import com.xammer.cloud.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying; // <-- IMPORT
import org.springframework.data.jpa.repository.Query; // <-- IMPORT
import org.springframework.data.repository.query.Param; // <-- IMPORT
import java.util.List; // <-- IMPORT
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * Clears the creator back-reference for every ticket created by the given user.
     * creator_user_id is nullable, so detaching preserves the ticket history while
     * releasing the foreign key that blocks deleting the app_user row.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Ticket t SET t.creator = NULL WHERE t.creator.id = :userId")
    void detachCreator(@Param("userId") Long userId);

    // Lightweight list query — no replies join, just metadata + creator + client
    @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN FETCH t.creator LEFT JOIN FETCH t.client")
    List<Ticket> findAllForAdminList();

    // --- ADD THIS (Fixes Lazy Loading for getAllTickets) ---
    @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author")
    List<Ticket> findAllWithRepliesAndAuthors();

    // --- ADD THIS (For new category filter) ---
  @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author WHERE t.category = :category")
    List<Ticket> findAllByCategoryWithRepliesAndAuthors(@Param("category") String category);
    
    // --- ADD THIS (Fixes Lazy Loading for getTicketById) ---
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author WHERE t.id = :id")
    Ticket findByIdWithRepliesAndAuthors(@Param("id") Long id);

    /**
     * Transactional on purpose. Ticket.description is @Lob over a PostgreSQL oid column, i.e. a
     * real large object, and PostgreSQL refuses to read one in auto-commit mode. Spring Data only
     * wraps SimpleJpaRepository's own CRUD methods in a transaction -- custom @Query methods like
     * this one get none -- so without this the query fails with "Unable to access lob stream" /
     * "Large Objects may not be used in auto-commit mode". Hibernate 5 did not surface this;
     * Hibernate 7 does. The transaction here is what lets Hibernate materialise the large object
     * into a String, after which callers can read it normally.
     */
    @Transactional(readOnly = true)
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.creator " +
           "WHERE t.status != 'CLOSED' " +
           "AND t.lastAdminReplyAt IS NOT NULL " +
           "AND t.lastAdminReplyAt <= :cutoff " +
           "AND t.reminderSentAt IS NULL")
    List<Ticket> findTicketsNeedingReminder(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Returns the IDs of tickets whose original description (the first message of the chat)
     * contains the given term, case-insensitively. Used for chat-content search alongside the
     * reply-body search so matches in a ticket's opening message are also found.
     *
     * <p>Native query for the same reason as
     * {@code TicketReplyRepository.findTicketIdsByReplyMessageContaining}: Ticket.description is
     * a @Lob over a PostgreSQL {@code oid}, so the column holds a pointer into
     * {@code pg_largeobject}, not the text. An HQL LIKE compares the OID number, never the body.
     * {@code lo_get} + {@code convert_from} read and decode the large object so the LIKE runs
     * against the actual text; the {@code pg_largeobject_metadata} guard skips orphaned OIDs that
     * would otherwise make {@code lo_get} throw. @Transactional because large objects cannot be
     * read in auto-commit mode and Spring Data does not wrap custom @Query methods.
     */
    @Transactional(readOnly = true)
    @Query(value =
        "SELECT t.id FROM ticket t "
        + "WHERE EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = t.description) "
        + "AND lower(convert_from(lo_get(t.description), 'UTF8')) LIKE lower(concat('%', :term, '%'))",
        nativeQuery = true)
    List<Long> findTicketIdsByDescriptionContaining(@Param("term") String term);

    /**
     * Like {@link #findTicketIdsByDescriptionContaining} but also returns the matched description
     * text and when it was sent (the ticket's created_at — the opening message's timestamp) so the
     * UI can show a snippet with a timestamp. Each row is
     * {@code [id (BigInteger), description (String), created_at (Timestamp)]}.
     * Same native-query + large-object + @Transactional reasoning as the id-only variant.
     */
    @Transactional(readOnly = true)
    @Query(value =
        "SELECT t.id AS ticketId, convert_from(lo_get(t.description), 'UTF8') AS message, t.created_at AS createdAt "
        + "FROM ticket t "
        + "WHERE EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = t.description) "
        + "AND lower(convert_from(lo_get(t.description), 'UTF8')) LIKE lower(concat('%', :term, '%')) "
        + "ORDER BY t.id",
        nativeQuery = true)
    List<Object[]> findDescriptionMatches(@Param("term") String term);
}