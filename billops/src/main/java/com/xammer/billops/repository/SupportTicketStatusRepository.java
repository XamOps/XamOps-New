package com.xammer.billops.repository;

import com.xammer.billops.domain.SupportTicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface SupportTicketStatusRepository extends JpaRepository<SupportTicketStatus, Long> {
    Optional<SupportTicketStatus> findByUserId(Long userId);

    /** Removes the support status row keyed by this user when the user is deleted. */
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
