package com.xammer.billops.repository;

import com.xammer.billops.domain.SupportTicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupportTicketStatusRepository extends JpaRepository<SupportTicketStatus, Long> {
    Optional<SupportTicketStatus> findByUserId(Long userId);
}
