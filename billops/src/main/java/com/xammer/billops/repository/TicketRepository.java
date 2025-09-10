package com.xammer.billops.repository;

import com.xammer.billops.domain.Ticket; // Assuming this domain class exists
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {
    // You can add custom query methods here later
}