package com.xammer.cloud.controller;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Google Workspace integration.
 *
 * All endpoints are scoped to the currently authenticated user's client/tenant.
 * Requires ROLE_XAMOPS or ROLE_SUPER_ADMIN.
 *
 * Endpoints:
 *   POST   /api/workspace/connect          – connect (or re-connect) a workspace
 *   GET    /api/workspace/status           – connection status + summary counts
 *   GET    /api/workspace/users            – all synced users (?status=ALL|ACTIVE|INACTIVE|SUSPENDED)
 *   GET    /api/workspace/licenses/summary – seat utilization numbers
 *   GET    /api/workspace/optimization     – inactive / suspended recommendations (?inactiveDays=30)
 *   POST   /api/workspace/sync             – re-sync users from Google
 *   DELETE /api/workspace/disconnect       – remove connection + all cached data
 */
@RestController
@RequestMapping("/api/workspace")
@PreAuthorize("hasAuthority('ROLE_XAMOPS') or hasAuthority('ROLE_SUPER_ADMIN')")
@Slf4j
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;

    public WorkspaceController(WorkspaceService workspaceService, UserRepository userRepository) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
    }

    // ─── Connect ─────────────────────────────────────────────────────────────

    /**
     * POST /api/workspace/connect
     *
     * Body (JSON):
     * {
     *   "domain":            "company.com",
     *   "adminEmail":        "admin@company.com",
     *   "serviceAccountKey": "{ ...full SA JSON... }"
     * }
     *
     * Validates the service account by making a live Directory API call, then
     * persists the connection and runs an initial user sync.
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody Map<String, String> body,
            Principal principal) {

        String domain = require(body, "domain");
        String adminEmail = require(body, "adminEmail");
        String serviceAccountKey = require(body, "serviceAccountKey");

        Long clientId = resolveClientId(principal);
        log.info("Workspace connect request: client={} domain={} admin={}", clientId, domain, adminEmail);

        workspaceService.connect(clientId, domain, adminEmail, serviceAccountKey);
        return ResponseEntity.ok(workspaceService.getStatus(clientId));
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    /**
     * GET /api/workspace/status
     *
     * Returns connection status and aggregate user counts.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(Principal principal) {
        Long clientId = resolveClientId(principal);
        return ResponseEntity.ok(workspaceService.getStatus(clientId));
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    /**
     * GET /api/workspace/users?status=ALL
     *
     * Returns the list of synced workspace users.
     * Optional ?status=ACTIVE|INACTIVE|SUSPENDED|ALL (default ALL)
     */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getUsers(
            @RequestParam(defaultValue = "ALL") String status,
            Principal principal) {

        Long clientId = resolveClientId(principal);
        return ResponseEntity.ok(workspaceService.getUsers(clientId, status));
    }

    // ─── License Summary ──────────────────────────────────────────────────────

    /**
     * GET /api/workspace/licenses/summary
     *
     * Returns seat utilization numbers.
     */
    @GetMapping("/licenses/summary")
    public ResponseEntity<Map<String, Object>> getLicenseSummary(Principal principal) {
        Long clientId = resolveClientId(principal);
        return ResponseEntity.ok(workspaceService.getLicenseSummary(clientId));
    }

    // ─── Optimization ─────────────────────────────────────────────────────────

    /**
     * GET /api/workspace/optimization?inactiveDays=30
     *
     * Returns optimisation recommendations (inactive users, suspended users with seats, never-logged-in).
     * Optional ?inactiveDays=30 (default 30) to adjust the inactivity threshold.
     */
    @GetMapping("/optimization")
    public ResponseEntity<List<Map<String, Object>>> getOptimization(
            @RequestParam(defaultValue = "30") int inactiveDays,
            Principal principal) {

        Long clientId = resolveClientId(principal);
        return ResponseEntity.ok(workspaceService.getOptimization(clientId, inactiveDays));
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/workspace/sync
     *
     * Re-fetches all users from Google Admin SDK and refreshes the cached data.
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(Principal principal) {
        Long clientId = resolveClientId(principal);
        log.info("Manual workspace sync triggered for client {}", clientId);
        return ResponseEntity.ok(workspaceService.sync(clientId));
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────

    /**
     * DELETE /api/workspace/disconnect
     *
     * Removes the workspace connection and all cached user data.
     */
    @DeleteMapping("/disconnect")
    public ResponseEntity<Void> disconnect(Principal principal) {
        Long clientId = resolveClientId(principal);
        log.info("Workspace disconnect request for client {}", clientId);
        workspaceService.disconnect(clientId);
        return ResponseEntity.noContent().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Long resolveClientId(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB"));
        return user.getClient().getId();
    }

    private String require(Map<String, String> body, String key) {
        String value = body.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value.trim();
    }
}
