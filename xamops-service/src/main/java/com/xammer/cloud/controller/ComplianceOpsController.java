package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.compliance.ComplianceControlOwner;
import com.xammer.cloud.domain.compliance.ComplianceException;
import com.xammer.cloud.domain.compliance.ComplianceRemediation;
import com.xammer.cloud.domain.compliance.ComplianceScan;
import com.xammer.cloud.dto.compliance.ComplianceReport;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.compliance.ComplianceGovernanceService;
import com.xammer.cloud.service.compliance.ComplianceOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ComplianceOps read + governance API.
 *
 * Scan triggering deliberately stays on the existing provider security controllers
 * ({@code /api/xamops/security/prowler/scan} and the GCP/Azure equivalents) so there is
 * still one way to start a scan. This controller owns the record: what the last scan
 * found, how it compares to previous scans, and the human decisions layered on top.
 */
@RestController
@RequestMapping("/api/xamops/compliance")
public class ComplianceOpsController {

    private static final Logger logger = LoggerFactory.getLogger(ComplianceOpsController.class);

    private static final int DEFAULT_TREND_DAYS = 90;

    private final ComplianceOpsService complianceOpsService;
    private final ComplianceGovernanceService governanceService;
    private final CloudAccountRepository cloudAccountRepository;
    private final CloudAccountAccessService cloudAccountAccessService;

    public ComplianceOpsController(ComplianceOpsService complianceOpsService,
            ComplianceGovernanceService governanceService,
            CloudAccountRepository cloudAccountRepository,
            CloudAccountAccessService cloudAccountAccessService) {
        this.complianceOpsService = complianceOpsService;
        this.governanceService = governanceService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cloudAccountAccessService = cloudAccountAccessService;
    }

    // ── Authorization ───────────────────────────────────────────────────────

    /**
     * Every endpoint here is addressed by an {@code accountId} the caller supplies, and
     * the compliance tables key on that string directly. Without this check any signed-in
     * user could name another customer's account and read its control failures, resource
     * evidence and remediation history, or approve and revoke that customer's waivers.
     *
     * The rule itself lives in {@link CloudAccountAccessService}, the same one
     * {@code CloudShellSocketHandler} uses, so account entitlement is answered
     * identically wherever it is asked.
     *
     * An unresolvable account is rejected with the same error as a forbidden one. Saying
     * "no such account" would confirm which account ids exist.
     */
    private void requireAccess(String accountId, ClientUserDetails userDetails) {
        CloudAccount account = accountId == null || accountId.isBlank()
                ? null
                : resolveAccount(accountId);

        if (account == null || !cloudAccountAccessService.canAccess(userDetails, account)) {
            logger.warn("Blocked ComplianceOps access: user={}, client={}, requestedAccount={}",
                    userDetails == null ? null : userDetails.getId(),
                    userDetails == null ? null : userDetails.getClientId(),
                    accountId);
            throw new AccessDeniedException("Not authorized for this account");
        }
    }

    /** Mirrors the provider-aware lookup used by CloudlistController and ResourceLabelService. */
    private CloudAccount resolveAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseGet(() -> cloudAccountRepository
                        .findByAwsAccountIdOrGcpProjectId(accountId, accountId)
                        .stream()
                        .findFirst()
                        .orElse(null));
    }

    // ── Read ────────────────────────────────────────────────────────────────

    /** The whole page in one call. Aggregation is server-side by design. */
    @GetMapping("/report")
    public ResponseEntity<ComplianceReport> getReport(@RequestParam String accountId,
            @RequestParam(defaultValue = "" + DEFAULT_TREND_DAYS) int trendDays,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(accountId, userDetails);
        return ResponseEntity.ok(complianceOpsService.getReport(accountId, trendDays));
    }

    @GetMapping("/trend")
    public ResponseEntity<List<ComplianceReport.TrendPoint>> getTrend(@RequestParam String accountId,
            @RequestParam(defaultValue = "" + DEFAULT_TREND_DAYS) int days,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(accountId, userDetails);
        return ResponseEntity.ok(complianceOpsService.buildTrend(accountId, days));
    }

    /** Scan history including failed runs — the "was this account actually scanned" audit. */
    @GetMapping("/scans")
    public ResponseEntity<List<ComplianceScan>> getScans(@RequestParam String accountId,
            @RequestParam(defaultValue = "30") int limit,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(accountId, userDetails);
        return ResponseEntity.ok(complianceOpsService.getScanHistory(accountId, limit));
    }

    // ── Exceptions ──────────────────────────────────────────────────────────

    @GetMapping("/exceptions")
    public ResponseEntity<List<ComplianceException>> listExceptions(@RequestParam String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(accountId, userDetails);
        return ResponseEntity.ok(governanceService.listExceptions(accountId));
    }

    @PostMapping("/exceptions")
    public ResponseEntity<?> requestException(@RequestBody ExceptionRequest body, Principal principal,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(body.accountId, userDetails);
        try {
            return ResponseEntity.ok(governanceService.requestException(
                    body.accountId, body.checkId, body.resourceUid, body.justification,
                    body.compensatingControl, actor(principal, body.requestedBy),
                    parseInstant(body.expiresAt)));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PatchMapping("/exceptions/{id}/approve")
    public ResponseEntity<?> approveException(@PathVariable Long id,
            @RequestBody(required = false) ActorRequest body, Principal principal,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(governanceService.findExceptionAccountId(id), userDetails);
        try {
            return ResponseEntity.ok(governanceService.decideException(
                    id, true, actor(principal, body == null ? null : body.actor)));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PatchMapping("/exceptions/{id}/reject")
    public ResponseEntity<?> rejectException(@PathVariable Long id,
            @RequestBody(required = false) ActorRequest body, Principal principal,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(governanceService.findExceptionAccountId(id), userDetails);
        try {
            return ResponseEntity.ok(governanceService.decideException(
                    id, false, actor(principal, body == null ? null : body.actor)));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PatchMapping("/exceptions/{id}/revoke")
    public ResponseEntity<?> revokeException(@PathVariable Long id,
            @RequestBody(required = false) ActorRequest body, Principal principal,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(governanceService.findExceptionAccountId(id), userDetails);
        try {
            String reason = body == null ? null : body.reason;
            return ResponseEntity.ok(governanceService.revokeException(
                    id, reason, actor(principal, body == null ? null : body.actor)));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    @GetMapping("/owners")
    public ResponseEntity<List<ComplianceControlOwner>> listOwners(@RequestParam String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(accountId, userDetails);
        return ResponseEntity.ok(governanceService.listOwners(accountId));
    }

    @PostMapping("/owners")
    public ResponseEntity<?> setOwner(@RequestBody OwnerRequest body, Principal principal,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(body.accountId, userDetails);
        try {
            return ResponseEntity.ok(governanceService.setOwner(body.accountId, body.checkId,
                    body.ownerName, body.ownerEmail, body.team, body.notes,
                    actor(principal, body.actor)));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // ── Remediation ─────────────────────────────────────────────────────────

    @GetMapping("/remediations")
    public ResponseEntity<List<ComplianceRemediation>> listRemediations(@RequestParam String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(accountId, userDetails);
        return ResponseEntity.ok(governanceService.listRemediations(accountId));
    }

    @PostMapping("/remediations")
    public ResponseEntity<?> createRemediation(@RequestBody RemediationRequest body, Principal principal,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(body.accountId, userDetails);
        try {
            return ResponseEntity.ok(governanceService.createRemediation(body.accountId, body.checkId,
                    body.resourceUid, body.assigneeName, body.assigneeEmail, body.ticketRef,
                    parseInstant(body.dueAt), body.notes, actor(principal, body.actor)));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PatchMapping("/remediations/{id}")
    public ResponseEntity<?> updateRemediation(@PathVariable Long id,
            @RequestBody RemediationRequest body,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireAccess(governanceService.findRemediationAccountId(id), userDetails);
        try {
            return ResponseEntity.ok(governanceService.updateRemediation(id, body.status,
                    body.assigneeName, body.assigneeEmail, body.ticketRef,
                    parseInstant(body.dueAt), body.notes));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The signed-in user wins over anything the client sends. A waiver's approver is the
     * one field on this page an auditor will actually check, so it is not client input.
     */
    private String actor(Principal principal, String fallback) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return fallback != null && !fallback.isBlank() ? fallback : "unknown";
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Expected an ISO-8601 instant, got: " + value);
        }
    }

    private ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        logger.warn("ComplianceOps request rejected: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    // ── Request bodies ──────────────────────────────────────────────────────

    public static class ExceptionRequest {
        public String accountId;
        public String checkId;
        public String resourceUid;
        public String justification;
        public String compensatingControl;
        public String requestedBy;
        /** ISO-8601 instant. Required — exceptions cannot be open-ended. */
        public String expiresAt;
    }

    public static class ActorRequest {
        public String actor;
        public String reason;
    }

    public static class OwnerRequest {
        public String accountId;
        public String checkId;
        public String ownerName;
        public String ownerEmail;
        public String team;
        public String notes;
        public String actor;
    }

    public static class RemediationRequest {
        public String accountId;
        public String checkId;
        public String resourceUid;
        public String status;
        public String assigneeName;
        public String assigneeEmail;
        public String ticketRef;
        public String dueAt;
        public String notes;
        public String actor;
    }
}
