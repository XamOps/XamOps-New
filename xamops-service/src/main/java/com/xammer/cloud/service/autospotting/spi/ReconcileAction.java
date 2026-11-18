package com.xammer.cloud.service.autospotting.spi;

/**
 * An action a provider's {@code reconcile} returns for the core to execute (design-core.md §9.2,
 * including DR-19's additions). {@code reconcile} is a pure decision with no I/O; the core executes
 * these under the group lease, or — during the read-only health pass (§4.7) — only the three safe
 * ones ({@link #ENQUEUE_FALLBACK}, {@link #REQUEST_COMPENSATION}, {@link #ALERT}).
 *
 * <ul>
 *   <li>{@link #ENQUEUE_FALLBACK}, {@link #REQUEST_COMPENSATION}, {@link #MARK_DRIFT},
 *       {@link #MARK_GONE}, {@link #ALERT}: the original §9.2 set.</li>
 *   <li>{@link #MARK_DEGRADED} (ACTIVE or FALLBACK → DEGRADED), {@link #MARK_RECOVERED}
 *       (DEGRADED → ACTIVE or FALLBACK, target from {@code config_mode}),
 *       {@link #UPDATE_PAUSED_FROM} (a paused group's {@code paused_from_state} between the stable
 *       states of its {@code config_mode}), {@link #PAUSE} and {@link #UNPAUSE} (switch (d)/(e)
 *       engaged or released, applied first in the sweep): DR-19.</li>
 * </ul>
 */
public enum ReconcileAction {
    ENQUEUE_FALLBACK,
    REQUEST_COMPENSATION,
    MARK_DRIFT,
    MARK_GONE,
    ALERT,
    MARK_DEGRADED,
    MARK_RECOVERED,
    UPDATE_PAUSED_FROM,
    PAUSE,
    UNPAUSE,
    /** A FALLBACK group looks ready to leave FALLBACK (capacity back); the core applies dwell, backoff and the daily cap. */
    ENQUEUE_FALLBACK_EXIT
}
