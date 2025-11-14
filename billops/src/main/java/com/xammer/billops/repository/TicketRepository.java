package com.xammer.billops.repository;

import com.xammer.cloud.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query; // <-- IMPORT
import org.springframework.data.repository.query.Param; // <-- IMPORT
import java.util.List; // <-- IMPORT

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // --- ADD THIS (Fixes Lazy Loading for getAllTickets) ---
    @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author")
    List<Ticket> findAllWithRepliesAndAuthors();

    // --- ADD THIS (For new category filter) ---
  @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author WHERE t.category = :category")
    List<Ticket> findAllByCategoryWithRepliesAndAuthors(@Param("category") String category);
    
    // --- ADD THIS (Fixes Lazy Loading for getTicketById) ---
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author WHERE t.id = :id")
    Ticket findByIdWithRepliesAndAuthors(@Param("id") Long id);
}