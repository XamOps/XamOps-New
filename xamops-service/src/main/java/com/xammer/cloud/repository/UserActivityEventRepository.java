package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UserActivityEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserActivityEventRepository extends JpaRepository<UserActivityEvent, Long> {

    /** Timeline feed for one user, newest first. Served by idx_uae_user_ts. */
    Page<UserActivityEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<UserActivityEvent> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId, LocalDateTime since);

    long countByUserIdAndActionAndCreatedAtAfter(Long userId, String action, LocalDateTime since);

    /**
     * Earliest recorded event, which the window presents as "first seen".
     *
     * Worth noting this is the first event <em>since tracking began</em>, not the account's
     * creation date — {@code app_user} has no {@code created_at} column, so a true signup
     * date is not available anywhere.
     */
    @Query("SELECT MIN(e.createdAt) FROM UserActivityEvent e WHERE e.userId = :userId")
    LocalDateTime findFirstEventAt(@Param("userId") Long userId);

    /** Distinct calendar days carrying at least one event, for the "active days" figure. */
    @Query(value = "SELECT COUNT(DISTINCT DATE(created_at)) FROM user_activity_event "
            + "WHERE user_id = :userId AND created_at >= :since", nativeQuery = true)
    long countActiveDays(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Logins per calendar day, for the heatmap and the trend chart. */
    @Query(value = "SELECT DATE(created_at) AS day, COUNT(*) AS logins "
            + "FROM user_activity_event "
            + "WHERE user_id = :userId AND action = 'LOGIN' AND created_at >= :since "
            + "GROUP BY DATE(created_at) ORDER BY day", nativeQuery = true)
    List<Object[]> countLoginsPerDay(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Logins bucketed by hour of day (0-23), answering "when does this person work". */
    @Query(value = "SELECT EXTRACT(HOUR FROM created_at) AS hr, COUNT(*) AS logins "
            + "FROM user_activity_event "
            + "WHERE user_id = :userId AND action = 'LOGIN' AND created_at >= :since "
            + "GROUP BY hr ORDER BY hr", nativeQuery = true)
    List<Object[]> countLoginsPerHour(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Which mechanism they actually use, as opposed to the one configured on the account. */
    @Query(value = "SELECT COALESCE(auth_provider, 'UNKNOWN') AS provider, COUNT(*) AS logins "
            + "FROM user_activity_event "
            + "WHERE user_id = :userId AND action = 'LOGIN' AND created_at >= :since "
            + "GROUP BY provider ORDER BY logins DESC", nativeQuery = true)
    List<Object[]> countLoginsByProvider(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Distinct source addresses, with first and last sighting. */
    @Query(value = "SELECT ip_address, COUNT(*) AS hits, MAX(created_at) AS last_seen "
            + "FROM user_activity_event "
            + "WHERE user_id = :userId AND ip_address IS NOT NULL AND created_at >= :since "
            + "GROUP BY ip_address ORDER BY hits DESC", nativeQuery = true)
    List<Object[]> countByIpAddress(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** User-agent strings for the device breakdown. Parsed in the service, not in SQL. */
    @Query(value = "SELECT user_agent, COUNT(*) AS hits "
            + "FROM user_activity_event "
            + "WHERE user_id = :userId AND user_agent IS NOT NULL AND created_at >= :since "
            + "GROUP BY user_agent", nativeQuery = true)
    List<Object[]> countByUserAgent(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * Every "Login as" against this user, newest first.
     *
     * Not time-windowed: the point of this list is that it is complete. An admin entering a
     * customer account is exactly the event nobody should have to pick a date range to find.
     */
    List<UserActivityEvent> findByUserIdAndActionOrderByCreatedAtDesc(Long userId, String action);

    /**
     * Closed sessions, as LOGIN/LOGOUT pairs sharing a session id.
     *
     * Only covers sessions that ended with a deliberate sign-out. Closing a tab produces no
     * logout event, so this undercounts — the service reports the sample size alongside the
     * average so the figure is not read as covering every session.
     */
    @Query(value = "SELECT li.session_id, li.created_at AS login_at, lo.created_at AS logout_at "
            + "FROM user_activity_event li "
            + "JOIN user_activity_event lo "
            + "  ON lo.session_id = li.session_id AND lo.user_id = li.user_id "
            + " AND lo.action = 'LOGOUT' AND lo.created_at > li.created_at "
            + "WHERE li.user_id = :userId AND li.action = 'LOGIN' "
            + "  AND li.session_id IS NOT NULL AND li.created_at >= :since "
            + "ORDER BY li.created_at DESC", nativeQuery = true)
    List<Object[]> findClosedSessions(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
