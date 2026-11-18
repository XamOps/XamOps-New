package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.dto.autospotting.ledger.ContractDto;
import com.xammer.cloud.dto.autospotting.ledger.ContractRequests;
import com.xammer.cloud.dto.autospotting.ledger.GoLiveDto;
import com.xammer.cloud.dto.autospotting.ledger.RecomputeDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand;
import com.xammer.cloud.service.autospotting.ledger.contract.ContractService;
import com.xammer.cloud.service.autospotting.ledger.contract.GoLiveService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * BillOps admin endpoints for AutoSpotting contracts and go-live (design-ledger.md §11.4, plan-phase-15.md
 * task 15.3) under {@code /api/xamops/billops-admin/autospotting}. These are records only: nothing is
 * invoiced while billing stays off. The statements, close, adjustment and dispute endpoints ship in
 * Phases 32 and 33.
 *
 * <p><strong>Role gate.</strong> {@code ROLE_BILLOPS_ADMIN} or {@code ROLE_SUPER_ADMIN} only, and the
 * G1 D3 pattern on top ({@code @autoSpottingAdminAccess.permits}): a Login-As session without super
 * admin and the OTP-exempt demo accounts are denied. An XamOps admin without the BillOps role, a
 * customer and anonymous callers are denied by the role check. Every non-GET additionally refuses any
 * impersonated session (403 {@code AS_FORBIDDEN_ROLE}), needs {@code Idempotency-Key}, and takes a 1 to
 * 500 character {@code reason} (422 otherwise). The CSRF/intent header and Origin checks run ahead of
 * the controller in the {@code permissions-common} filter. Each change writes an audit row in the same
 * transaction. Bodies and responses are DTO records, never entities.
 */
@RestController
@RequestMapping("/api/xamops/billops-admin/autospotting")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')"
        + " and @autoSpottingAdminAccess.permits(authentication)")
public class BillOpsAutoSpottingAdminController {

    private final ContractService contractService;
    private final GoLiveService goLiveService;
    private final IdempotencyService idempotencyService;
    private final LedgerRecomputeCommand recomputeCommand;

    public BillOpsAutoSpottingAdminController(ContractService contractService, GoLiveService goLiveService,
                                              IdempotencyService idempotencyService,
                                              LedgerRecomputeCommand recomputeCommand) {
        this.contractService = contractService;
        this.goLiveService = goLiveService;
        this.idempotencyService = idempotencyService;
        this.recomputeCommand = recomputeCommand;
    }

    @GetMapping("/clients/{clientId}/contracts")
    public ResponseEntity<List<ContractDto>> contracts(@PathVariable long clientId) {
        Instant now = Instant.now();
        return ResponseEntity.ok(contractService.list(clientId).stream()
                .map(c -> ContractDto.from(c, now)).toList());
    }

    @PostMapping("/clients/{clientId}/contracts")
    public ResponseEntity<ContractDto> createContract(@PathVariable long clientId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                                      String idempotencyKey,
                                                      @RequestBody ContractRequests.Create body,
                                                      @AuthenticationPrincipal ClientUserDetails user) {
        requireNotImpersonated();
        idempotencyService.requireKey(idempotencyKey);
        ContractService.CreateCommand cmd = new ContractService.CreateCommand(body.sharePct(),
                body.monthlyCap(), body.capCurrency(), body.discountDeclaration(), body.declaredDiscountPct(),
                body.effectiveFrom(), body.effectiveTo(), body.reason());
        var saved = contractService.create(clientId, cmd, actor(user), label(user));
        return ResponseEntity.status(HttpStatus.CREATED).body(ContractDto.from(saved, Instant.now()));
    }

    @PostMapping("/contracts/{id}/end")
    public ResponseEntity<ContractDto> endContract(@PathVariable UUID id,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   String idempotencyKey,
                                                   @RequestBody ContractRequests.End body,
                                                   @AuthenticationPrincipal ClientUserDetails user) {
        requireNotImpersonated();
        idempotencyService.requireKey(idempotencyKey);
        var saved = contractService.end(id, body.effectiveTo(), actor(user), body.reason());
        return ResponseEntity.ok(ContractDto.from(saved, Instant.now()));
    }

    /**
     * Read-only recompute of one ledger entry from its stored inputs and calc version, diffing every
     * money field and the statement lines that cite it (design-ledger.md §14.1, AC-LED-17).
     */
    @GetMapping("/entries/{id}/recompute")
    public ResponseEntity<RecomputeDto> recompute(@PathVariable UUID id) {
        return ResponseEntity.ok(RecomputeDto.from(recomputeCommand.recompute(id)));
    }
    @GetMapping("/golive")
    public ResponseEntity<List<GoLiveDto>> goLives() {
        return ResponseEntity.ok(goLiveService.list().stream().map(GoLiveDto::from).toList());
    }

    @PostMapping("/golive/{provider}")
    public ResponseEntity<GoLiveDto> setGoLive(@PathVariable String provider,
                                               @RequestHeader(value = "Idempotency-Key", required = false)
                                               String idempotencyKey,
                                               @RequestBody ContractRequests.GoLive body,
                                               @AuthenticationPrincipal ClientUserDetails user) {
        requireNotImpersonated();
        idempotencyService.requireKey(idempotencyKey);
        Provider parsed = GoLiveService.parseProvider(provider);
        var saved = goLiveService.set(parsed, body.goLiveAt(), actor(user), label(user), body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(GoLiveDto.from(saved));
    }

    /** Every non-GET refuses an impersonated (Login-As) session. */
    private static void requireNotImpersonated() {
        if (ImpersonationContext.getImpersonatedUserId() != null) {
            throw AutoSpottingApiException.forbiddenRole();
        }
    }

    private static Actor actor(ClientUserDetails user) {
        if (user == null) {
            return Actor.operator(null, null);
        }
        String role = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).findFirst().orElse(null);
        return Actor.operator(user.getId(), role);
    }

    private static String label(ClientUserDetails user) {
        return user == null || user.getId() == null ? "billops-admin" : String.valueOf(user.getId());
    }
}
