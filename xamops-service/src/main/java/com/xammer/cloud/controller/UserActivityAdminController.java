package com.xammer.cloud.controller;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.UserActivityQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Serves the "Check Activity" window in BillOps admin → Accounts → Users.
 *
 * <p>Separate from {@code BillOpsAdminController}, which is already past 1,200 lines, but
 * mounted under the same path and guarded by the same authorities so the frontend sees one
 * coherent admin API.
 *
 * <p>Responses are built per request rather than read from the Redis cache that backs the
 * client list. The cached DTO is warmed on a schedule, and an activity window showing
 * timestamps minutes out of date would undermine the only thing it is for.
 */
@RestController
@RequestMapping("/api/xamops/billops-admin")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_BILLOPS_ADMIN_SUB') "
        + "or hasAuthority('ROLE_SUPER_ADMIN')")
public class UserActivityAdminController {

    private static final Logger log = LoggerFactory.getLogger(UserActivityAdminController.class);

    private final UserActivityQueryService activityQueryService;
    private final UserRepository userRepository;

    public UserActivityAdminController(UserActivityQueryService activityQueryService,
                                       UserRepository userRepository) {
        this.activityQueryService = activityQueryService;
        this.userRepository = userRepository;
    }

    /**
     * Activity summary for one user.
     *
     * @param days window for the time-bounded figures. The impersonation ledger and the
     *             unvisited-pages list ignore it: both are questions about all of history.
     */
    @GetMapping("/users/{userId}/activity")
    public ResponseEntity<?> getUserActivity(@PathVariable Long userId,
                                             @RequestParam(defaultValue = "90") int days) {
        Optional<User> target = userRepository.findById(userId);
        if (target.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // A full BillOps admin and a super admin see every tenant. A sub-admin is scoped, and
        // this window exposes addresses, devices and browsing history -- so it must not become
        // the one admin screen that forgets to check.
        if (!canRead(target.get())) {
            log.warn("Blocked cross-tenant activity read for user {}", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Not permitted to view activity for this user.");
        }

        try {
            UserActivitySummaryDto summary = activityQueryService.summarise(userId, days);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Failed to build activity summary for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Could not load activity for this user.");
        }
    }

    private boolean canRead(User target) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        boolean unrestricted = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN".equals(a.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (unrestricted) {
            return true;
        }
        // ROLE_BILLOPS_ADMIN_SUB: same tenant only.
        if (!(auth.getPrincipal() instanceof ClientUserDetails me)) {
            return false;
        }
        Long myClientId = me.getClientId();
        Long targetClientId = target.getClient() != null ? target.getClient().getId() : null;
        return myClientId != null && myClientId.equals(targetClientId);
    }
}
