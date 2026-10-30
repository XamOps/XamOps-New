package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.dto.autospotting.v2.OperationDto;
import com.xammer.cloud.dto.autospotting.v2.ReasonRequest;
import com.xammer.cloud.dto.autospotting.v2.SwitchChangeRequest;
import com.xammer.cloud.dto.autospotting.v2.SwitchDto;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.lifecycle.OperatorGroupService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchOperatorService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The AutoSpotting operator API (design-core.md §6.6, plan-phase-09.md task 9.4) under
 * {@code /api/xamops/autospotting-admin}. Super admins and XamOps admins engage switches and pause
 * or resume groups at runtime, without a deploy. The dashboard reads moved in Phase 02 stay on
 * {@code AutoSpottingAdminController} in the same namespace (and keep allowing BillOps admin);
 * allowlist endpoints follow in Phase 15, DLQ endpoints in Phase 22.
 *
 * <p><strong>Role gate (G1 decision D3 pattern).</strong> The class {@code @PreAuthorize} requires
 * {@code ROLE_SUPER_ADMIN} or {@code ROLE_XAMOPS_ADMIN} <em>and</em>
 * {@code @autoSpottingAdminAccess.permits}, which denies a Login-As session without super-admin and
 * the OTP-exempt demo accounts (the same guard the dashboard reads use). A BillOps admin is denied
 * (these are not dashboard reads), and a customer/anonymous caller is denied by the roles. On top of
 * the class gate, every non-GET additionally refuses any impersonated session
 * ({@link #requireNotImpersonated()} → 403 {@code AS_FORBIDDEN_ROLE}), so even an impersonated super
 * admin may read but never write. The CSRF/intent header and Origin checks are enforced ahead of the
 * controller by the {@code permissions-common} filter; {@code Idempotency-Key} is required on every
 * non-GET, and the 1-to-500-character {@code reason} is validated on the body (422 on violation).
 * Each change writes an {@code OPERATOR} audit row with the before and after in the same transaction.
 *
 * <p>Errors are RFC 9457 {@code application/problem+json} through {@link AutoSpottingProblemAdvice}.
 * Responses are DTO records, never JPA entities (AC-SEC-04).
 */
@RestController
@RequestMapping("/api/xamops/autospotting-admin")
@PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_XAMOPS_ADMIN')"
        + " and @autoSpottingAdminAccess.permits(authentication)")
public class AutoSpottingOperatorController {

    /** The operator ops page is capped here (design-core.md §6.6 "paginated, max 200"). */
    private static final int MAX_PAGE_SIZE = 200;

    private final SwitchOperatorService switchOperatorService;
    private final OperatorGroupService operatorGroupService;
    private final SpotOperationRepository operations;
    private final com.xammer.cloud.service.autospotting.core.IdempotencyService idempotencyService;

    public AutoSpottingOperatorController(SwitchOperatorService switchOperatorService,
                                          OperatorGroupService operatorGroupService,
                                          SpotOperationRepository operations,
                                          com.xammer.cloud.service.autospotting.core.IdempotencyService idempotencyService) {
        this.switchOperatorService = switchOperatorService;
        this.operatorGroupService = operatorGroupService;
        this.operations = operations;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/switches")
    public ResponseEntity<List<SwitchDto>> switches() {
        List<SwitchDto> dtos = switchOperatorService.list().stream().map(SwitchDto::from).toList();
        return ResponseEntity.ok(dtos);
    }

    @PutMapping("/switches/{scopeKey}")
    public ResponseEntity<SwitchDto> setSwitch(@PathVariable String scopeKey,
                                               @RequestHeader(value = "Idempotency-Key", required = false)
                                               String idempotencyKey,
                                               @Valid @RequestBody SwitchChangeRequest body,
                                               @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireNotImpersonated();
        requireIdempotencyKey(idempotencyKey);
        SwitchOperatorService.SwitchView view = switchOperatorService.setEngaged(
                scopeKey, body.engaged(), body.expectedVersion(), operatorActor(userDetails), body.reason());
        return ResponseEntity.ok(SwitchDto.from(view));
    }

    @GetMapping("/operations")
    public ResponseEntity<Map<String, Object>> operations(
            @RequestParam(value = "waitReason", required = false) String waitReason,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), capped);
        Page<OperationDto> result = operations
                .findOperatorOps(normalize(waitReason), normalize(provider), pageable)
                .map(OperationDto::from);
        Map<String, Object> body = Map.of(
                "operations", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/groups/{groupId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable UUID groupId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                                      String idempotencyKey,
                                                      @Valid @RequestBody ReasonRequest body,
                                                      @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireNotImpersonated();
        requireIdempotencyKey(idempotencyKey);
        GroupState state = operatorGroupService.pause(groupId, operatorActor(userDetails), body.reason());
        return ResponseEntity.ok(Map.of("groupId", groupId, "state", state.name()));
    }

    @PostMapping("/groups/{groupId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable UUID groupId,
                                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                                       String idempotencyKey,
                                                       @Valid @RequestBody ReasonRequest body,
                                                       @AuthenticationPrincipal ClientUserDetails userDetails) {
        requireNotImpersonated();
        requireIdempotencyKey(idempotencyKey);
        GroupState state = operatorGroupService.resume(groupId, operatorActor(userDetails), body.reason());
        return ResponseEntity.ok(Map.of("groupId", groupId, "state", state.name()));
    }

    // ── gates shared by every non-GET ───────────────────────────────────────

    /** Every non-GET refuses an impersonated (Login-As) session (design-core.md §6.6). */
    private static void requireNotImpersonated() {
        if (ImpersonationContext.getImpersonatedUserId() != null) {
            throw AutoSpottingApiException.forbiddenRole();
        }
    }

    /**
     * {@code Idempotency-Key} is required on every operator non-GET (design-core.md §6.6, §11, §3.7).
     * A missing key is 400 ({@code IDEMPOTENCY_KEY_REQUIRED}), enforced by {@link com.xammer.cloud.service.autospotting.core.IdempotencyService#requireKey}.
     */
    private void requireIdempotencyKey(String key) {
        idempotencyService.requireKey(key);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The {@code OPERATOR} audit actor built from the authenticated principal. */
    private static Actor operatorActor(ClientUserDetails userDetails) {
        if (userDetails == null) {
            return Actor.operator(null, null);
        }
        String role = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse(null);
        return Actor.operator(userDetails.getId(), role);
    }
}
