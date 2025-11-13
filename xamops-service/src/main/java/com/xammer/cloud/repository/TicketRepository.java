package com.xammer.cloud.repository;

import com.xammer.cloud.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query; // --- ADDED IMPORT ---
import org.springframework.data.repository.query.Param; // --- ADDED IMPORT ---
import org.springframework.stereotype.Repository;
import java.util.Optional;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional; // --- ADDED IMPORT ---

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // --- START: ADDED METHOD TO FIX 500 ERROR ---
    /**
     * Finds a ticket by its ID, and eagerly fetches its replies and the author of each reply.
     * This prevents LazyInitializationException when accessing reply.getAuthor().
     */
    // @Query("SELECT t FROM Ticket t " +
    //        "LEFT JOIN FETCH t.replies r " +
    //        "LEFT JOIN FETCH r.author " +
    //        "WHERE t.id = :ticketId")
    // Optional<Ticket> findByIdWithRepliesAndAuthors(@Param("ticketId") Long ticketId);
    @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author WHERE t.category = :category")
    List<Ticket> findAllByCategoryWithRepliesAndAuthors(@Param("category") String category);
    @Query("SELECT t FROM Ticket t LEFT JOIN FETCH t.replies r LEFT JOIN FETCH r.author WHERE t.id = :id")
Optional<Ticket> findByIdWithRepliesAndAuthors(@Param("id") Long id);

    
    // --- END: ADDED METHOD ---
}