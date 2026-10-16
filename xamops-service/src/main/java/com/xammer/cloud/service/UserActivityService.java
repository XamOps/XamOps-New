package com.xammer.cloud.service;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.domain.UserPageView;
import com.xammer.cloud.repository.UserActivityEventRepository;
import com.xammer.cloud.repository.UserPageViewRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes the activity trail.
 *
 * <p><b>Every public method here swallows its own failures.</b> These calls sit on the
 * login, logout and impersonation paths, and an audit insert failing must never be the
 * reason a user cannot sign in. The existing {@code lastLoginAt} writes in
 * {@code CustomAuthenticationSuccessHandler} and {@code LoginOtpController} already take
 * this stance, and the same reasoning applies with more force here, because this service
 * is called from more places.
 *
 * <p>Consequence worth stating plainly: the trail is best-effort, not guaranteed. It is
 * an operational record, not a compliance-grade ledger, and a dropped row leaves no trace.
 */
@Service
public class UserActivityService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);

    /** Column is varchar(512); longer agents are truncated rather than dropped. */
    private static final int USER_AGENT_MAX = 512;
    private static final int ROUTE_MAX = 255;
    private static final int PATH_MAX = 512;

    /**
     * Upper bound on a single page visit, 4 hours.
     *
     * A visit longer than this is a tab someone left open, not someone reading. The client
     * caps idle visits itself, but a clock change or a suspended laptop can still produce an
     * absurd figure, and one bad row distorts a SUM far more than it distorts an average.
     */
    private static final int MAX_VISIT_MS = 4 * 60 * 60 * 1000;

    private final UserActivityEventRepository eventRepository;
    private final UserPageViewRepository pageViewRepository;

    public UserActivityService(UserActivityEventRepository eventRepository,
                               UserPageViewRepository pageViewRepository) {
        this.eventRepository = eventRepository;
        this.pageViewRepository = pageViewRepository;
    }

    // ───────────────────────────────── auth events ─────────────────────────────────

    /** Records a successful sign-in. {@code provider} is one of the PROVIDER_* constants. */
    public void recordLogin(User user, String provider, HttpServletRequest request) {
        if (user == null) {
            return;
        }
        UserActivityEvent event = base(user, UserActivityEvent.ACTION_LOGIN, request);
        event.setAuthProvider(provider);
        save(event);
    }

    /**
     * Records a rejected sign-in attempt.
     *
     * Takes a user id rather than a {@link User} because the common failure is an unknown
     * username, where there is nobody to look up. Those attempts are dropped: with no user
     * to attribute them to they would be unreachable from a per-user window, and collecting
     * them would turn this table into a log of guessed addresses.
     */
    public void recordLoginFailed(Long userId, Long clientId, String provider,
                                 String reason, HttpServletRequest request) {
        if (userId == null) {
            return;
        }
        UserActivityEvent event = new UserActivityEvent();
        event.setUserId(userId);
        event.setClientId(clientId);
        event.setAction(UserActivityEvent.ACTION_LOGIN_FAILED);
        event.setAuthProvider(provider);
        event.setOutcome(UserActivityEvent.OUTCOME_FAILURE);
        event.setFailureReason(truncate(reason, 255));
        applyRequestContext(event, request);
        save(event);
    }

    /**
     * Records an explicit sign-out.
     *
     * Must be called before the session is invalidated, or the session id is already gone
     * and the event cannot be paired with its login.
     */
    public void recordLogout(Long userId, Long clientId, HttpServletRequest request) {
        if (userId == null) {
            return;
        }
        UserActivityEvent event = new UserActivityEvent();
        event.setUserId(userId);
        event.setClientId(clientId);
        event.setAction(UserActivityEvent.ACTION_LOGOUT);
        applyRequestContext(event, request);
        save(event);
    }

    /**
     * Records a BillOps admin minting a "Login as" token for {@code targetUserId}.
     *
     * {@code userId} is the account being entered and {@code actorUserId} the admin
     * entering it, so the event surfaces in the subject's own activity window. Before this
     * existed, impersonation left no trace at all: the token grants eight hours of full
     * access and nothing recorded that it had been issued.
     */
    public void recordImpersonationStarted(Long targetUserId, Long clientId, Long actorUserId,
                                           String grantedRole, HttpServletRequest request) {
        if (targetUserId == null) {
            return;
        }
        UserActivityEvent event = new UserActivityEvent();
        event.setUserId(targetUserId);
        event.setClientId(clientId);
        event.setAction(UserActivityEvent.ACTION_IMPERSONATION_STARTED);
        event.setActorUserId(actorUserId);
        event.setMetadata(grantedRole == null ? null
                : "{\"grantedRole\":\"" + grantedRole.replace("\"", "") + "\"}");
        applyRequestContext(event, request);
        save(event);
    }

    // ───────────────────────────────── page visits ─────────────────────────────────

    /**
     * Persists a batch of completed page visits.
     *
     * Batched because the alternative is an HTTP request per navigation. Visits arrive
     * already closed — the client owns the timing, since it is the only party that can see
     * a route change or a tab being hidden.
     *
     * @param actorUserId the impersonating admin, or null for the user's own browsing
     * @return how many rows were written
     */
    public int recordPageViews(Long userId, Long clientId, Long actorUserId,
                               String sessionId, List<PageViewInput> visits) {
        if (userId == null || visits == null || visits.isEmpty()) {
            return 0;
        }
        try {
            List<UserPageView> rows = visits.stream()
                    .filter(v -> v != null && v.route() != null && !v.route().isBlank())
                    .filter(v -> v.enteredAt() != null)
                    .map(v -> toRow(userId, clientId, actorUserId, sessionId, v))
                    .toList();
            if (rows.isEmpty()) {
                return 0;
            }
            pageViewRepository.saveAll(rows);
            return rows.size();
        } catch (Exception e) {
            log.warn("Failed to record {} page view(s) for user {}: {}",
                    visits.size(), userId, e.getMessage());
            return 0;
        }
    }

    private UserPageView toRow(Long userId, Long clientId, Long actorUserId,
                               String sessionId, PageViewInput v) {
        UserPageView row = new UserPageView();
        row.setUserId(userId);
        row.setClientId(clientId);
        row.setActorUserId(actorUserId);
        row.setSessionId(sessionId);
        row.setRoute(truncate(v.route(), ROUTE_MAX));
        row.setPath(truncate(v.path(), PATH_MAX));
        row.setEnteredAt(v.enteredAt());
        row.setExitedAt(v.exitedAt());
        row.setExitReason(truncate(v.exitReason(), 20));

        int dwell = clampDuration(v.dwellMs());
        int visible = clampDuration(v.visibleMs());
        // Visible time is a subset of wall time by definition. A client that reports
        // otherwise has a clock problem; trusting it would let the headline figure exceed
        // the time the page was even open.
        row.setDwellMs(dwell);
        row.setVisibleMs(Math.min(visible, dwell));
        return row;
    }

    private int clampDuration(Integer raw) {
        if (raw == null || raw < 0) {
            return 0;
        }
        return Math.min(raw, MAX_VISIT_MS);
    }

    /** One completed page visit as reported by the browser. */
    public record PageViewInput(
            String route,
            String path,
            LocalDateTime enteredAt,
            LocalDateTime exitedAt,
            Integer dwellMs,
            Integer visibleMs,
            String exitReason) {
    }

    // ───────────────────────────────── internals ─────────────────────────────────

    private UserActivityEvent base(User user, String action, HttpServletRequest request) {
        UserActivityEvent event = new UserActivityEvent();
        event.setUserId(user.getId());
        // Touches the lazy Client association, so callers must still be inside the
        // transaction or persistence context that loaded the user.
        try {
            event.setClientId(user.getClient() != null ? user.getClient().getId() : null);
        } catch (Exception e) {
            log.debug("Could not resolve client for user {}: {}", user.getId(), e.getMessage());
        }
        event.setAction(action);
        applyRequestContext(event, request);
        return event;
    }

    private void applyRequestContext(UserActivityEvent event, HttpServletRequest request) {
        if (request == null) {
            return;
        }
        try {
            event.setIpAddress(clientIp(request));
            event.setUserAgent(truncate(request.getHeader("User-Agent"), USER_AGENT_MAX));
            event.setSessionId(hashedSessionId(request));
        } catch (Exception e) {
            log.debug("Could not capture request context: {}", e.getMessage());
        }
    }

    /**
     * Resolves the caller's address, preferring the proxy chain.
     *
     * Every deployed profile sets {@code server.forward-headers-strategy=native} and runs
     * behind a load balancer, so {@code getRemoteAddr()} alone yields the balancer's own
     * address for every user. The left-most entry in {@code X-Forwarded-For} is the
     * original client.
     *
     * <p>That header is client-supplied and therefore spoofable. Acceptable here: this value
     * is displayed for operational context, never used for an access decision.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first, 45);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp.trim(), 45);
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    /**
     * SHA-256 of the servlet session id.
     *
     * Hashed rather than stored raw so that read access to this table does not hand over
     * live session identifiers. Pairing a login with its logout only needs equality, which
     * a hash preserves.
     */
    static String hashedSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getId() == null) {
            return null;
        }
        return hash(session.getId());
    }

    /** Exposed so callers holding only a raw session id can hash it the same way. */
    public static String hash(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; unreachable in practice.
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void save(UserActivityEvent event) {
        try {
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to record activity event {} for user {}: {}",
                    event.getAction(), event.getUserId(), e.getMessage());
        }
    }
}
