package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UserPageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Aggregates over page visits.
 *
 * Every query here filters {@code actor_user_id IS NULL}. Rows with that column set were
 * produced by a BillOps admin driving the account through "Login as", and counting them
 * would report the support team's browsing as the customer's own engagement. The
 * impersonated rows are still retained — they are the record of what an admin looked at
 * inside someone else's account — they are simply not the subject's own activity.
 */
@Repository
public interface UserPageViewRepository extends JpaRepository<UserPageView, Long> {

    Page<UserPageView> findByUserIdOrderByEnteredAtDesc(Long userId, Pageable pageable);

    List<UserPageView> findByUserIdAndEnteredAtAfterOrderByEnteredAtDesc(
            Long userId, LocalDateTime since);

    /**
     * Time spent per page, the window's headline figure.
     *
     * Returns visible time, wall time, visit count and the mean visit length per route.
     * The last of those is what separates a page someone bounces off forty times from one
     * they study three times — identical totals, opposite conclusions.
     */
    @Query(value = "SELECT route, "
            + "       SUM(COALESCE(visible_ms, 0)) AS visible_ms, "
            + "       SUM(COALESCE(dwell_ms, 0))   AS dwell_ms, "
            + "       COUNT(*)                      AS visits, "
            + "       AVG(COALESCE(visible_ms, 0)) AS avg_visible_ms, "
            + "       MAX(entered_at)               AS last_visit "
            + "FROM user_page_view "
            + "WHERE user_id = :userId AND entered_at >= :since AND actor_user_id IS NULL "
            + "GROUP BY route "
            + "ORDER BY visible_ms DESC", nativeQuery = true)
    List<Object[]> aggregateByRoute(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Visible time per calendar day, driving the engagement heatmap and trend. */
    @Query(value = "SELECT DATE(entered_at) AS day, "
            + "       SUM(COALESCE(visible_ms, 0)) AS visible_ms, "
            + "       COUNT(*) AS visits "
            + "FROM user_page_view "
            + "WHERE user_id = :userId AND entered_at >= :since AND actor_user_id IS NULL "
            + "GROUP BY DATE(entered_at) ORDER BY day", nativeQuery = true)
    List<Object[]> aggregatePerDay(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Totals across the window: visible time, wall time and visit count. */
    @Query(value = "SELECT COALESCE(SUM(visible_ms), 0), "
            + "       COALESCE(SUM(dwell_ms), 0), "
            + "       COUNT(*) "
            + "FROM user_page_view "
            + "WHERE user_id = :userId AND entered_at >= :since AND actor_user_id IS NULL",
            nativeQuery = true)
    List<Object[]> aggregateTotals(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * Distinct routes this user has ever opened, across all time.
     *
     * Used to derive the inverse — which parts of the product they have never touched.
     * "This customer has never opened Rightsizing" is the most actionable thing in the
     * window, and it is only answerable by subtracting this set from the route inventory.
     * Not time-windowed on purpose: a page visited once a year ago is still not unvisited.
     */
    @Query(value = "SELECT DISTINCT route FROM user_page_view "
            + "WHERE user_id = :userId AND actor_user_id IS NULL", nativeQuery = true)
    List<String> findDistinctRoutesEverVisited(@Param("userId") Long userId);

    /** The route each session opened on, for "where do they start". */
    @Query(value = "SELECT route, COUNT(*) AS starts FROM ( "
            + "  SELECT session_id, route, "
            + "         ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY entered_at) AS rn "
            + "  FROM user_page_view "
            + "  WHERE user_id = :userId AND entered_at >= :since "
            + "    AND actor_user_id IS NULL AND session_id IS NOT NULL "
            + ") first_views WHERE rn = 1 "
            + "GROUP BY route ORDER BY starts DESC", nativeQuery = true)
    List<Object[]> findEntryRoutes(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Visits an admin made while impersonating this user, for the access ledger. */
    @Query(value = "SELECT actor_user_id, route, COUNT(*) AS visits, "
            + "       SUM(COALESCE(visible_ms, 0)) AS visible_ms, MAX(entered_at) AS last_visit "
            + "FROM user_page_view "
            + "WHERE user_id = :userId AND actor_user_id IS NOT NULL AND entered_at >= :since "
            + "GROUP BY actor_user_id, route ORDER BY visible_ms DESC", nativeQuery = true)
    List<Object[]> aggregateImpersonatedByRoute(@Param("userId") Long userId,
                                                @Param("since") LocalDateTime since);
}
