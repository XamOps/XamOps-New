package com.xammer.billops.repository;

import com.xammer.cloud.domain.TicketNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketNoteRepository extends JpaRepository<TicketNote, Long> {
    Optional<TicketNote> findByTicketId(Long ticketId);
}
