package com.xammer.cloud.controller;

import com.xammer.cloud.domain.MattersAccessAudit;
import com.xammer.cloud.dto.matters.DspmAuditView;
import com.xammer.cloud.dto.matters.DspmClientOption;
import com.xammer.cloud.dto.matters.DspmMappingAdminView;
import com.xammer.cloud.dto.matters.DspmMappingRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.MattersAccessAuditRepository;
import com.xammer.cloud.service.matters.MattersAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal operator API for the DSPM add-on: the org → project mappings, credentials, entitlements, and
 * the audit trail.
 *
 * <p>Sits under the existing BillOps admin prefix and carries the same authority check as the rest of
 * that surface. Every method is restricted at the method level rather than relying on a path rule in
 * {@code SecurityConfig}, which ends in {@code anyRequest().authenticated()} — a path-level omission
 * there would expose customer credentials' metadata to any logged-in user.
 *
 * <p><b>The API key is never returned by any endpoint here.</b> It goes in through
 * {@link DspmMappingRequest} and comes back only as an environment, a key id and four characters.
 */
@RestController
@RequestMapping("/api/xamops/billops-admin/dspm")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
@RequiredArgsConstructor
public class DspmAdminController {

    private static final int MAX_AUDIT_PAGE_SIZE = 200;

    private final MattersAdminService adminService;
    private final MattersAccessAuditRepository auditRepository;
    private final ClientRepository clientRepository;

    // ── mappings ─────────────────────────────────────────────────────────────────────────────────

    /** Every mapping, for the admin table. */
    @GetMapping("/mappings")
    public ResponseEntity<List<DspmMappingAdminView>> listMappings() {
        return ResponseEntity.ok(adminService.listMappings());
    }

    /**
     * Clients for the mapping picker — id, name, entitlement, whether already mapped.
     *
     * A cheap two-query endpoint rather than the Accounts screen's client list, which fans out to the
     * cost APIs for every row.
     */
    @GetMapping("/clients")
    public ResponseEntity<List<DspmClientOption>> clientOptions() {
        return ResponseEntity.ok(adminService.listClientOptions());
    }

    /** One client's mapping, or 404 when none exists. */
    @GetMapping("/mappings/{clientId}")
    public ResponseEntity<DspmMappingAdminView> getMapping(@PathVariable Long clientId) {
        return adminService.getMapping(clientId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No DSPM mapping for client " + clientId));
    }

    /**
     * Creates or replaces a client's mapping.
     *
     * {@code PUT} on a client-scoped path, not {@code POST} to a collection: there is at most one mapping
     * per client, so the client id fully identifies the resource and a repeated submit is idempotent
     * rather than a duplicate-key conflict.
     *
     * {@code Cache-Control: no-store} because the request body carries a live credential and this
     * response echoes its metadata. The same precaution the Upwind onboarding endpoints take for
     * responses containing a sensor secret.
     */
    @PutMapping("/mappings/{clientId}")
    public ResponseEntity<DspmMappingAdminView> saveMapping(@PathVariable Long clientId,
                                                            @RequestBody DspmMappingRequest request) {
        DspmMappingAdminView view = adminService.saveMapping(clientId, request, currentOperator());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(view);
    }

    @DeleteMapping("/mappings/{clientId}")
    public ResponseEntity<Map<String, Object>> deleteMapping(@PathVariable Long clientId) {
        adminService.deleteMapping(clientId);
        return ResponseEntity.ok(Map.of("clientId", clientId, "deleted", true));
    }

    /**
     * Tests the stored credential against Matters and records the verdict.
     *
     * {@code POST} because it has side effects — it writes {@code connection_status} and an audit row —
     * even though it reads nothing the caller supplied.
     */
    @PostMapping("/mappings/{clientId}/verify")
    public ResponseEntity<DspmMappingAdminView> verify(@PathVariable Long clientId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(adminService.verifyMapping(clientId));
    }

    // ── entitlement ──────────────────────────────────────────────────────────────────────────────

    /**
     * Grants or withdraws the DSPM add-on.
     *
     * Takes an explicit {@code enabled} value rather than toggling, matching
     * {@code /xamsecure-onboarding} and {@code /notification-channel}. This gates a paid capability, and
     * a toggle makes a double-submitted request silently reverse the decision; sending the intended state
     * makes a retry idempotent.
     */
    @PostMapping("/clients/{clientId}/entitlement")
    public ResponseEntity<Map<String, Object>> setEntitlement(@PathVariable Long clientId,
                                                              @RequestBody Map<String, Object> body) {
        Object raw = body.get("enabled");
        if (!(raw instanceof Boolean enabled)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Body must be {\"enabled\": true|false}"));
        }

        adminService.setEntitlement(clientId, enabled);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("mattersDspmEnabled", enabled);
        return ResponseEntity.ok(response);
    }

    // ── audit trail ──────────────────────────────────────────────────────────────────────────────

    /**
     * The audit trail, newest first.
     *
     * @param clientId optional filter.
     * @param action   optional {@code MattersAccessAudit.ACTION_*} filter. Filtering to the mutations is
     *                 how an operator reviews configuration changes without the credential-use rows,
     *                 which dominate by volume.
     * @param outcome  optional {@code SUCCESS} / {@code FAILURE}.
     * @param days     optional lookback window.
     */
    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit(
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Instant since = (days == null || days <= 0)
                ? null
                : Instant.now().minus(days, ChronoUnit.DAYS);

        Page<MattersAccessAudit> rows = auditRepository.search(
                clientId,
                blankToNull(action),
                blankToNull(outcome),
                since,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_AUDIT_PAGE_SIZE)));

        // Client names resolved in one query rather than per row. The audit entity holds a bare clientId
        // with no association precisely so a deleted client cannot erase its own trail, which means the
        // name has to be joined here — and a row whose client is gone simply renders without one.
        Map<Long, String> names = clientNames(rows.getContent());

        List<DspmAuditView> views = rows.getContent().stream()
                .map(row -> DspmAuditView.from(row, names.get(row.getClientId())))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", views);
        response.put("page", rows.getNumber());
        response.put("pageSize", rows.getSize());
        response.put("total", rows.getTotalElements());
        response.put("totalPages", rows.getTotalPages());
        return ResponseEntity.ok(response);
    }

    /** The action names the audit filter accepts, so the admin UI need not hardcode them. */
    @GetMapping("/audit/actions")
    public ResponseEntity<List<String>> auditActions() {
        return ResponseEntity.ok(List.of(
                MattersAccessAudit.ACTION_CREDENTIAL_USED,
                MattersAccessAudit.ACTION_MAPPING_CREATED,
                MattersAccessAudit.ACTION_MAPPING_UPDATED,
                MattersAccessAudit.ACTION_MAPPING_DELETED,
                MattersAccessAudit.ACTION_MAPPING_VERIFIED,
                MattersAccessAudit.ACTION_KEY_ROTATED,
                MattersAccessAudit.ACTION_PROJECT_REPOINTED,
                MattersAccessAudit.ACTION_ENTITLEMENT_GRANTED,
                MattersAccessAudit.ACTION_ENTITLEMENT_WITHDRAWN));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private Map<Long, String> clientNames(List<MattersAccessAudit> rows) {
        List<Long> ids = rows.stream()
                .map(MattersAccessAudit::getClientId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            clientRepository.findAllById(ids)
                    .forEach(client -> names.put(client.getId(), client.getName()));
        }
        return names;
    }

    /** Username of the operator making the change, stamped onto the mapping for the audit trail. */
    private String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
