package com.xammer.billops.repository;

import com.xammer.billops.dto.DemoUserLoginAgg;
import com.xammer.cloud.domain.UserActivityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * From BillOps this is still write-only on the write side: the one writer is
 * {@code ImpersonationTokenController}, recording that an admin issued a "Login as" token. It now
 * also serves a <b>read-only aggregate</b> of {@code LOGIN} rows for the admin "Demo Users" view
 * ({@code DemoUsersAdminController}); the broader activity trail is still served by xamops-service.
 */
@Repository
public interface UserActivityEventRepository extends JpaRepository<UserActivityEvent, Long> {

    /**
     * Per-user aggregate of {@code LOGIN} rows for the given users: earliest/latest timestamps, a
     * single auth-provider pick and the login count. {@code max(authProvider)} is a pragmatic
     * single-value pick — a demo user signs in with one provider, so every LOGIN row carries the
     * same label; when two providers were used over time it returns the lexicographically greater
     * one, which is acceptable for a display column. Non-LOGIN actions are ignored.
     * @param userIds the demo-client user ids to aggregate; an empty collection returns no rows.
     * @return one projection per user that has at least one LOGIN row.
     */
    @Query("""
            select e.userId as userId,
                   min(e.createdAt) as firstSeen,
                   max(e.createdAt) as lastLogin,
                   max(e.authProvider) as authProvider,
                   count(e) as loginCount
            from UserActivityEvent e
            where e.action = 'LOGIN' and e.userId in :userIds
            group by e.userId
            """)
    List<DemoUserLoginAgg> aggregateLoginsByUserIds(@Param("userIds") Collection<Long> userIds);
}
