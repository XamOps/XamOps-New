package com.xammer.billops.repository;

import com.xammer.cloud.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query; // <-- IMPORT
import org.springframework.data.repository.query.Param; // <-- IMPORT
import java.util.List; // <-- IMPORT
import java.time.LocalDateTime;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

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

    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.creator " +
           "WHERE t.status != 'CLOSED' " +
           "AND t.lastAdminReplyAt IS NOT NULL " +
           "AND t.lastAdminReplyAt <= :cutoff " +
           "AND t.reminderSentAt IS NULL")
    List<Ticket> findTicketsNeedingReminder(@Param("cutoff") LocalDateTime cutoff);
}