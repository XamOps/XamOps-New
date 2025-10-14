package com.xammer.cloud.repository;

import com.xammer.cloud.domain.TicketReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {
    // Custom query methods can be added here if needed in the future
}