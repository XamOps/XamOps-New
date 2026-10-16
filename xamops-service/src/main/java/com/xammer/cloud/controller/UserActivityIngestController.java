package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.UserActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Receives page-visit batches from the browser.
 *
 * <p>Page dwell time cannot be observed server-side. XamOps is a single-page app with
 * client-side routing, so moving between pages usually issues no HTTP request at all — the
 * server never sees a page entered and never sees one left. The browser is the only party
 * that can time a visit, which is why this endpoint exists and why the timings are supplied
 * rather than measured here.
 *
 * <p><b>Attribution comes from the security context, never from the payload.</b> The body
 * carries no user id. Accepting one would let any authenticated caller write arbitrary
 * activity history against any other account, which is both a privacy problem and a way to
 * poison the very reports this data feeds.
 *
 * <p>Timings arriving from a browser are advisory: a client can lie, and a suspended laptop
 * or a clock change can produce nonsense without anyone lying. The service clamps every
 * duration to a sane ceiling before persisting. This data is for product insight, not for
 * billing or enforcement.
 */
@RestController
@RequestMapping("/api/xamops/activity")
public class UserActivityIngestController {

    private static final Logger log = LoggerFactory.getLogger(UserActivityIngestController.class);

    /**
     * Cap on visits accepted per request.
     *
     * The client batches, so a normal post carries a handful. A much larger batch means a
     * long offline spell being flushed, or someone probing the endpoint; either way there is
     * no reason to accept an unbounded list.
     */
    private static final int MAX_BATCH = 200;

    private final UserActivityService userActivityService;

    public UserActivityIngestController(UserActivityService userActivityService) {
        this.userActivityService = userActivityService;
    }

    /**
     * Records a batch of completed page visits for the calling user.
     *
     * Returns 204 rather than an error when nothing could be stored. This is fire-and-forget
     * telemetry sent on navigation and on page unload, frequently via {@code keepalive}, so
     * the caller is usually not in a position to react and a failure must never surface to
     * someone who was only trying to change page.
     */
    @PostMapping("/page-views")
    public ResponseEntity<Void> recordPageViews(@RequestBody PageViewBatch batch,
                                                HttpServletRequest request) {
        try {
            ClientUserDetails principal = currentPrincipal();
            if (principal == null || principal.getId() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (batch == null || batch.visits() == null || batch.visits().isEmpty()) {
                return ResponseEntity.noContent().build();
            }
            if (batch.visits().size() > MAX_BATCH) {
                log.warn("Rejecting oversized page-view batch of {} from user {}",
                        batch.visits().size(), principal.getId());
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            }

            // Non-null whenever a BillOps admin is driving this session through "Login as".
            // Those rows are retained but excluded from the user's own figures.
            Long actorUserId = (Long) request.getAttribute(ImpersonationJwtFilter.ATTR_ACTOR_USER_ID);

            userActivityService.recordPageViews(
                    principal.getId(),
                    principal.getClientId(),
                    actorUserId,
                    sessionId(request),
                    toInputs(batch.visits()));

            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.warn("Failed to ingest page views: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        }
    }

    private List<UserActivityService.PageViewInput> toInputs(List<PageViewPayload> visits) {
        List<UserActivityService.PageViewInput> out = new ArrayList<>(visits.size());
        for (PageViewPayload v : visits) {
            if (v == null || v.route() == null || v.route().isBlank() || v.enteredAt() == null) {
                continue;
            }
            out.add(new UserActivityService.PageViewInput(
                    v.route(),
                    v.path(),
                    toLocal(v.enteredAt()),
                    toLocal(v.exitedAt()),
                    v.dwellMs(),
                    v.visibleMs(),
                    v.exitReason()));
        }
        return out;
    }

    /**
     * Converts the client's epoch milliseconds to a server-zone timestamp.
     *
     * The browser sends epoch millis rather than a formatted local time deliberately: an
     * instant is unambiguous, whereas a wall-clock string from a user in another timezone
     * would land in the same column as everyone else's and quietly skew any
     * grouping by hour or day.
     */
    private LocalDateTime toLocal(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private String sessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getId() == null) {
            // Impersonated requests are stateless by design — the filter never loads a
            // session — so page views during "Login as" carry no session id and simply do not
            // group into a session.
            return null;
        }
        return UserActivityService.hash(session.getId());
    }

    private ClientUserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof ClientUserDetails details) {
            return details;
        }
        return null;
    }

    /** Request envelope. Carries no user id on purpose — see the class comment. */
    public record PageViewBatch(List<PageViewPayload> visits) {
    }

    /**
     * One completed visit as reported by the browser.
     *
     * @param route      normalised router pattern, e.g. {@code /sre/investigations/:id}
     * @param path       the pathname actually visited
     * @param enteredAt  epoch millis the route was entered
     * @param exitedAt   epoch millis the route was left
     * @param dwellMs    wall-clock time on the route
     * @param visibleMs  the portion of that time the tab was visible
     * @param exitReason NAVIGATE, HIDDEN, UNLOAD or TIMEOUT
     */
    public record PageViewPayload(
            String route,
            String path,
            Long enteredAt,
            Long exitedAt,
            Integer dwellMs,
            Integer visibleMs,
            String exitReason) {
    }

    /** Diagnostic echo, mirroring the existing /debug/last-login endpoint's purpose. */
    @PostMapping("/page-views/echo")
    public ResponseEntity<Map<String, Object>> echo(HttpServletRequest request) {
        ClientUserDetails principal = currentPrincipal();
        return ResponseEntity.ok(Map.of(
                "userId", principal != null && principal.getId() != null ? principal.getId() : -1,
                "impersonatedBy", String.valueOf(
                        request.getAttribute(ImpersonationJwtFilter.ATTR_ACTOR_USER_ID)),
                "hasSession", request.getSession(false) != null));
    }
}
