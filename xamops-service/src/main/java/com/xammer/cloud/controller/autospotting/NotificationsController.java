package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.autospotting.AutoSpottingNotification;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.autospotting.NotificationStore;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingEmailBuilder;
import com.xammer.cloud.service.autospotting.notify.NotificationEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The in-app side of the AutoSpotting notices and the one email opt-out (design-ux-ops.md sections 7.1, 7.3,
 * PO-24). The bell reads the outbox for the caller's client: the REST read is what makes the in-app notice
 * reliable, so it never depends on a push reaching this replica. Reads are scoped to the caller's own client
 * and marked read per user. Muting AutoSpotting email never hides a notice here.
 *
 * <p>{@code PUT /notification-preferences} flips {@code app_user.autospotting_email_enabled}. It silences
 * non-mandatory email only; failure notices are always sent, and the response says so.
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class NotificationsController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    /** A notice as the bell shows it. {@code mandatory} marks the ones email opt-out never silences. */
    public record NotificationDto(long id, String eventType, String title, String summary, UUID groupId,
                                  long cloudAccountId, boolean mandatory, boolean read, Instant createdAt) {
    }

    public record PreferencesDto(boolean autospottingEmailEnabled, String note) {
    }

    public record PreferencesRequest(Boolean autospottingEmailEnabled) {
    }

    private static final String NOTE = "Failure notices are always sent.";

    private final NotificationStore store;
    private final AutoSpottingEmailBuilder builder;
    private final UserRepository users;

    public NotificationsController(NotificationStore store, AutoSpottingEmailBuilder builder,
                                   UserRepository users) {
        this.store = store;
        this.builder = builder;
        this.users = users;
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<NotificationDto>> list(
            @RequestParam(value = "unread", defaultValue = "false") boolean unread,
            @RequestParam(value = "limit", required = false) Integer limit,
            @AuthenticationPrincipal ClientUserDetails user) {
        requireUser(user);
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        List<AutoSpottingNotification> rows = store.listForClient(user.getClientId(), unread ? user.getId() : null,
                limit == null ? DEFAULT_LIMIT : limit);
        List<NotificationDto> dtos = rows.stream().map(n -> toDto(n, user.getId())).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping(value = "/notifications/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable long id, @AuthenticationPrincipal ClientUserDetails user) {
        requireUser(user);
        // Idempotent: a repeat or a notice that isn't this client's is the same 204 (no existence leak).
        store.markRead(id, user.getClientId(), user.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notification-preferences")
    public ResponseEntity<PreferencesDto> preferences(@AuthenticationPrincipal ClientUserDetails user) {
        requireUser(user);
        User row = users.findById(user.getId()).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found."));
        return ResponseEntity.ok(new PreferencesDto(row.isAutospottingEmailEnabled(), NOTE));
    }

    @PutMapping(value = "/notification-preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreferencesDto> setPreferences(@RequestBody PreferencesRequest request,
                                                         @AuthenticationPrincipal ClientUserDetails user) {
        requireUser(user);
        if (request == null || request.autospottingEmailEnabled() == null) {
            throw new AutoSpottingApiException(HttpStatus.BAD_REQUEST, "CHECK_FAILED",
                    "autospottingEmailEnabled is required.");
        }
        User row = users.findById(user.getId()).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found."));
        row.setAutospottingEmailEnabled(request.autospottingEmailEnabled());
        users.save(row);
        return ResponseEntity.ok(new PreferencesDto(row.isAutospottingEmailEnabled(), NOTE));
    }

    private NotificationDto toDto(AutoSpottingNotification n, Long userId) {
        boolean read = n.readBy() != null && userId != null
                && n.readBy().matches(".*(\\[|,)\\s*" + userId + "\\s*(,|\\]).*");
        NotificationEvent event = NotificationEvent.valueOf(n.eventType());
        return new NotificationDto(n.id(), n.eventType(), builder.title(n), builder.summary(n), n.groupId(),
                n.cloudAccountId(), event.mandatory(), read, n.createdAt());
    }

    private static void requireUser(ClientUserDetails user) {
        if (user == null || user.getId() == null || user.getClientId() == null) {
            throw new AutoSpottingApiException(HttpStatus.FORBIDDEN, "AS_FORBIDDEN_ROLE",
                    "You are not allowed to perform this action.");
        }
    }
}
