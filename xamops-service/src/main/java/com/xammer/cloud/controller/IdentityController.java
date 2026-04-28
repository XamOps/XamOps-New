package com.xammer.cloud.controller;

import com.xammer.cloud.dto.identity.*;
import com.xammer.cloud.exception.AuthentikUnauthorizedException;
import com.xammer.cloud.service.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * REST API for the AccessOps Identity Management module.
 *
 * All endpoints require the user to be authenticated (Spring Security session).
 * IdentityService resolves the tenant from the authenticated principal — no
 * tenant ID is accepted from the frontend to prevent tenant spoofing.
 *
 * Authentik API calls are made exclusively from IdentityService.
 * Tokens are never present in any response body, log line, or error message.
 */
@RestController
@RequestMapping("/api/xamops/identity")
public class IdentityController {

    private static final Logger log = LoggerFactory.getLogger(IdentityController.class);

    private final IdentityService identityService;

    public IdentityController(IdentityService identityService) {
        this.identityService = identityService;
    }

    // ── Users ──────────────────────────────────────────────────────────────────

    /**
     * GET /api/xamops/identity/users
     * List all users for this tenant (filtered by group_prefix server-side).
     */
    @GetMapping("/users")
    public ResponseEntity<List<AuthentikUserDto>> listUsers(Principal principal) {
        return ResponseEntity.ok(identityService.listUsers(principal));
    }

    /**
     * POST /api/xamops/identity/users/invite
     * Invite a new user: create in Authentik + add to group + send invite email.
     */
    @PostMapping("/users/invite")
    public ResponseEntity<AuthentikUserDto> inviteUser(
            Principal principal,
            @Valid @RequestBody InviteUserRequest request) {
        AuthentikUserDto created = identityService.inviteUser(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PATCH /api/xamops/identity/users/{authentikUserId}/deactivate
     * Deactivate a user (set is_active=false). AWS access auto-revoked via SCIM.
     * Users are never deleted per Authentik best practice.
     */
    @PatchMapping("/users/{authentikUserId}/deactivate")
    public ResponseEntity<AuthentikUserDto> deactivateUser(
            Principal principal,
            @PathVariable Long authentikUserId) {
        AuthentikUserDto updated = identityService.deactivateUser(principal, authentikUserId);
        return ResponseEntity.ok(updated);
    }

    // ── Groups ─────────────────────────────────────────────────────────────────

    /**
     * GET /api/xamops/identity/groups
     * List all groups for this tenant, enriched with AWS account context.
     */
    @GetMapping("/groups")
    public ResponseEntity<List<AuthentikGroupDto>> listGroups(Principal principal) {
        return ResponseEntity.ok(identityService.listGroups(principal));
    }

    /**
     * POST /api/xamops/identity/groups/{groupId}/users
     * Add a user to a group → AWS SSO access granted automatically via SCIM.
     */
    @PostMapping("/groups/{groupId}/users")
    public ResponseEntity<Void> addUserToGroup(
            Principal principal,
            @PathVariable String groupId,
            @Valid @RequestBody GroupMembershipRequest request) {
        identityService.addUserToGroup(principal, groupId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/xamops/identity/groups/{groupId}/users/{userId}
     * Remove a user from a group → AWS SSO access revoked automatically via SCIM.
     */
    @DeleteMapping("/groups/{groupId}/users/{userId}")
    public ResponseEntity<Void> removeUserFromGroup(
            Principal principal,
            @PathVariable String groupId,
            @PathVariable Long userId) {
        identityService.removeUserFromGroup(principal, groupId, userId);
        return ResponseEntity.noContent().build();
    }

    // ── Audit Log ──────────────────────────────────────────────────────────────

    /**
     * GET /api/xamops/identity/audit?page=0&size=50
     * Paginated audit log for this tenant.
     */
    @GetMapping("/audit")
    public ResponseEntity<Page<IdentityAuditLogDto>> listAuditLogs(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(identityService.listAuditLogs(principal, page, Math.min(size, 100)));
    }

    // ── Exception Handlers ─────────────────────────────────────────────────────

    @ExceptionHandler(AuthentikUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleAuthentikUnauthorized(AuthentikUnauthorizedException e) {
        log.error("Identity service unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Identity service unavailable. Please contact XamOps support."));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handleSecurityException(SecurityException e) {
        log.warn("Access control violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Access denied."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.error("Identity operation failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
