package com.xammer.billops.repository;

import com.xammer.cloud.domain.UserActivityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Write-only from BillOps: the one caller is {@code ImpersonationTokenController}, recording
 * that an admin issued a "Login as" token. Reads of the activity trail are served by
 * xamops-service.
 */
@Repository
public interface UserActivityEventRepository extends JpaRepository<UserActivityEvent, Long> {
}
