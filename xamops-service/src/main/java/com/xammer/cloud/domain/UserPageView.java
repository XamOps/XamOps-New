package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One visit to one page, with how long it lasted.
 *
 * Separate from {@link UserActivityEvent} because this is interval data: it has a start,
 * an end and a duration, and the aggregate that matters ({@code SUM(visible_ms) GROUP BY
 * route}) needs those as real indexed columns rather than fields inside a JSON blob.
 *
 * <p>Rows are written by the frontend, which is the only party that can observe them.
 * XamOps is a single-page app with client-side routing, so navigating between pages
 * frequently issues no HTTP request at all — the server never learns a page was entered
 * and never learns one was left. Dwell time is therefore not derivable from any
 * server-side signal, which is why instrumentation lives in
 * {@code RouteChangeHandler.tsx} and posts here in batches.
 */
@Entity
@Table(name = "user_page_view", indexes = {
        @Index(name = "idx_upv_user_entered", columnList = "user_id, entered_at DESC"),
        @Index(name = "idx_upv_user_route", columnList = "user_id, route")
})
@Data
@NoArgsConstructor
public class UserPageView {

    /** Routed away to another page within the app. */
    public static final String EXIT_NAVIGATE = "NAVIGATE";
    /** Tab backgrounded or window blurred. */
    public static final String EXIT_HIDDEN = "HIDDEN";
    /** Tab or browser closed. Delivered on a best-effort unload request. */
    public static final String EXIT_UNLOAD = "UNLOAD";
    /** Idle cap reached while the page was still open. */
    public static final String EXIT_TIMEOUT = "TIMEOUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_id")
    private Long clientId;

    /**
     * Normalised route pattern, e.g. {@code /sre/investigations/:id}. The client collapses
     * path parameters before sending; storing raw pathnames would make every detail page a
     * separate "page" and render the top-pages aggregate useless.
     */
    @Column(name = "route", nullable = false, length = 255)
    private String route;

    /** The pathname as actually visited, for drill-down. */
    @Column(name = "path", length = 512)
    private String path;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Column(name = "exited_at")
    private LocalDateTime exitedAt;

    /** Wall-clock milliseconds on the route, including time spent in a background tab. */
    @Column(name = "dwell_ms")
    private Integer dwellMs;

    /**
     * Milliseconds during which the tab was actually visible.
     *
     * This is the figure to report. {@link #dwellMs} counts a dashboard left open on a
     * second monitor overnight as a full night of engagement; this does not. The ratio
     * between the two indicates how much of the number to trust.
     */
    @Column(name = "visible_ms")
    private Integer visibleMs;

    @Column(name = "exit_reason", length = 20)
    private String exitReason;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    /**
     * The admin driving this session via "Login as", when applicable. Rows with this set
     * are excluded from the subject's own totals by default, so support activity is not
     * reported as customer engagement.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
