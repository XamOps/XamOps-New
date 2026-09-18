package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SecurityNotification;
import com.xammer.cloud.domain.SecurityNotificationPreference;
import com.xammer.cloud.repository.SecurityNotificationPreferenceRepository;
import com.xammer.cloud.repository.SecurityNotificationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.security.SecurityFindingNotifier;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The findings inbox and its preferences.
 *
 * <p>
 * Tenant comes from the authenticated principal, never from a query parameter. Most AWS
 * controllers here take {@code accountId} as a parameter, which is fine for data the caller
 * already has to know an account id to be looking at — but a notification feed is per-client and
 * an {@code ?clientId=} parameter would be an invitation to read someone else's security
 * notifications. {@code XamSecureController} is the precedent for principal-based resolution.
 */
@RestController
@RequestMapping("/api/xamops/security/notifications")
public class SecurityNotificationController {

    private static final Logger logger = LoggerFactory.getLogger(SecurityNotificationController.class);

    /** Ceiling on the feed size, so a caller cannot ask for the whole history in one request. */
    private static final int MAX_LIMIT = 200;

    private final SecurityNotificationRepository notifications;
    private final SecurityNotificationPreferenceRepository preferences;
    private final SecurityFindingNotifier notifier;

    public SecurityNotificationController(SecurityNotificationRepository notifications,
            SecurityNotificationPreferenceRepository preferences,
            SecurityFindingNotifier notifier) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.notifier = notifier;
    }

    // ── Feed ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<SecurityNotification>> list(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam(defaultValue = "50") int limit) {

        Long clientId = clientIdOrNull(user);
        if (clientId == null) {
            return ResponseEntity.ok(List.of());
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ResponseEntity.ok(
                notifications.findForClient(clientId, PageRequest.of(0, capped)));
    }

    /**
     * Unread count for the bell.
     *
     * <p>
     * Its own endpoint rather than a length taken from the list above, because the frontend
     * polls this one frequently and the list rarely — a count is an indexed aggregate, while
     * fetching fifty rows every few seconds to compute it is not.
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> unreadCount(
            @AuthenticationPrincipal ClientUserDetails user) {

        Map<String, Object> body = new HashMap<>();
        Long clientId = clientIdOrNull(user);
        body.put("unread", clientId == null ? 0L : notifications.countByClientIdAndReadFalse(clientId));
        return ResponseEntity.ok(body);
    }

    /**
     * Marks one notification read.
     *
     * <p>
     * The client id is passed into the update so it lands in the {@code WHERE} clause. A request
     * carrying another client's notification id therefore updates zero rows rather than theirs,
     * and the response says so — checking ownership first and then updating by id alone is the
     * same vulnerability with an extra query.
     */
    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Map<String, Object>> markRead(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long id) {

        Long clientId = clientIdOrNull(user);
        if (clientId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "No client context"));
        }
        int updated = notifications.markRead(id, clientId);
        if (updated == 0) {
            logger.debug("Notification {} not marked read for client {} — absent or not theirs",
                    id, clientId);
        }
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Object>> markAllRead(
            @AuthenticationPrincipal ClientUserDetails user) {

        Long clientId = clientIdOrNull(user);
        if (clientId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "No client context"));
        }
        return ResponseEntity.ok(Map.of("updated", notifications.markAllRead(clientId)));
    }

    // ── Preferences ─────────────────────────────────────────────────────────

    /** Current preferences, or the defaults when the client has never changed them. */
    @GetMapping("/preferences")
    public ResponseEntity<SecurityNotificationPreference> getPreferences(
            @AuthenticationPrincipal ClientUserDetails user) {

        Long clientId = clientIdOrNull(user);
        if (clientId == null) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(notifier.preferencesFor(clientId));
    }

    /**
     * Updates preferences.
     *
     * <p>
     * Fields are read individually from the body rather than binding the entity, so the client
     * id can only ever come from the principal. Binding the entity would let a request set
     * {@code clientId} and write another tenant's preferences — including switching their
     * security email off.
     */
    @PutMapping("/preferences")
    @Transactional
    public ResponseEntity<SecurityNotificationPreference> updatePreferences(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody Map<String, Object> body) {

        Long clientId = clientIdOrNull(user);
        if (clientId == null) {
            return ResponseEntity.status(403).build();
        }

        SecurityNotificationPreference prefs = preferences.findById(clientId)
                .orElseGet(() -> SecurityNotificationPreference.defaults(clientId));
        prefs.setClientId(clientId);

        if (body.containsKey("emailEnabled")) {
            prefs.setEmailEnabled(Boolean.TRUE.equals(body.get("emailEnabled")));
        }
        if (body.containsKey("inboxEnabled")) {
            prefs.setInboxEnabled(Boolean.TRUE.equals(body.get("inboxEnabled")));
        }
        if (body.get("severityThreshold") != null) {
            // Normalised rather than stored verbatim: an unrecognised value would otherwise be
            // persisted and then silently match nothing, leaving the client with no
            // notifications and no error.
            prefs.setSeverityThreshold(
                    SecuritySeverity.normalize(String.valueOf(body.get("severityThreshold"))));
        }
        if (body.get("digestSeverityThreshold") != null) {
            // Separate knob from severityThreshold above: that one gates the immediate
            // per-finding email, this one gates the periodic cloud alert digest. Same
            // normalisation, for the same reason.
            prefs.setDigestSeverityThreshold(
                    SecuritySeverity.normalize(String.valueOf(body.get("digestSeverityThreshold"))));
        }
        if (body.get("maxEmailsPerHour") != null) {
            try {
                int max = Integer.parseInt(String.valueOf(body.get("maxEmailsPerHour")));
                // Clamped rather than rejected. Zero means unlimited in the notifier, which is
                // not what someone typing 0 into a "max emails" box intends.
                prefs.setMaxEmailsPerHour(Math.min(Math.max(max, 1), 60));
            } catch (NumberFormatException e) {
                logger.debug("Ignoring non-numeric maxEmailsPerHour from client {}", clientId);
            }
        }
        prefs.setUpdatedAt(Instant.now());

        return ResponseEntity.ok(preferences.save(prefs));
    }

    private Long clientIdOrNull(ClientUserDetails user) {
        return user == null ? null : user.getClientId();
    }
}
