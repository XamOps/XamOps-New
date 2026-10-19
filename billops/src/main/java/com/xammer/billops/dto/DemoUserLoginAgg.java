package com.xammer.billops.dto;

import java.time.LocalDateTime;

/**
 * Spring Data projection for the per-user aggregate of {@code user_activity_event} LOGIN rows,
 * produced by {@code UserActivityEventRepository.aggregateLoginsByUserIds}. Backs the BillOps admin
 * "Demo Users" view; read-only, no column is added to any mapping (NFR2).
 */
public interface DemoUserLoginAgg {

    Long getUserId();

    LocalDateTime getFirstSeen();

    LocalDateTime getLastLogin();

    String getAuthProvider();

    long getLoginCount();
}
