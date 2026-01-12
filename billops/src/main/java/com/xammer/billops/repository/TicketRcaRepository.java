package com.xammer.billops.repository;

import com.xammer.billops.domain.TicketRca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketRcaRepository extends JpaRepository<TicketRca, Long> {
    // Basic CRUD operations are provided automatically.
    // You can add custom finders here later if needed (e.g., findByTicketId).
}