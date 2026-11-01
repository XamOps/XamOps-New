package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.dto.autospotting.v2.ConfirmRequest;
import com.xammer.cloud.dto.autospotting.v2.PlanDto;
import com.xammer.cloud.dto.autospotting.v2.PlanRequest;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmCommand;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmResult;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.core.plan.PlanService.CreateCommand;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The v2 plan endpoints (design-core.md §7.1, §7.4, §11; plan-phase-11.md task 11.5). All three go
 * through the tenancy guard first ({@link CloudAccountAccessService#requireAccess}); the CSRF filter
 * enforces {@code Content-Type: application/json} and the {@code X-XamOps-Intent} header for the
 * non-GET routes (core §11). Errors flow through {@link AutoSpottingProblemAdvice} as
 * {@code application/problem+json}; responses are DTO records, never JPA entities (AC-SEC-04).
 *
 * <ul>
 *   <li>{@code POST /accounts/{accountId}/plans} — 201 {@link PlanDto}. {@code Idempotency-Key}
 *       required (400 when missing).</li>
 *   <li>{@code GET /accounts/{accountId}/plans/{planId}} — the plan (404 when it is not this
 *       account's).</li>
 *   <li>{@code POST /accounts/{accountId}/plans/{planId}/confirm} — 202 {@code {operationId,
 *       status}}. {@code Idempotency-Key} required.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/autospotting/v2")
@Validated
public class PlanController {

    private final CloudAccountAccessService accountAccessService;
    private final PlanService planService;
    private final ConfirmService confirmService;
    private final IdempotencyService idempotency;
    private final com.xammer.cloud.repository.autospotting.SpotPlanRepository plans;

    public PlanController(CloudAccountAccessService accountAccessService, PlanService planService,
                          ConfirmService confirmService, IdempotencyService idempotency,
                          com.xammer.cloud.repository.autospotting.SpotPlanRepository plans) {
        this.accountAccessService = accountAccessService;
        this.planService = planService;
        this.confirmService = confirmService;
        this.idempotency = idempotency;
        this.plans = plans;
    }

    /** Create a plan (201). {@code Idempotency-Key} required; a replay returns the existing plan. */
    @PostMapping(value = "/accounts/{accountId}/plans", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PlanDto> createPlan(@PathVariable Long accountId,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                              @Valid @RequestBody PlanRequest request,
                                              @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        String key = idempotency.requireKey(idemKey);
        OperationKind kind = parseKind(request.kind());
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder()
                .cloudAccountId(account.getId()).open()) {
            String canonical = canonicalPlanBody(kind, request);
            CreateCommand cmd = new CreateCommand(kind, request.groupId(), request.groupRef(),
                    request.profileId(), false, key, idempotency.hash(canonical), actorName(user));
            SpotPlan plan = planService.createPlan(account, user, cmd);
            return ResponseEntity.status(HttpStatus.CREATED).body(PlanDto.from(plan));
        }
    }

    /** Read a plan. 404 (opaque) when it is not this account's. */
    @GetMapping("/accounts/{accountId}/plans/{planId}")
    public ResponseEntity<PlanDto> getPlan(@PathVariable Long accountId,
                                           @PathVariable UUID planId,
                                           @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        SpotPlan plan = plans.findById(planId)
                .filter(p -> account.getId().equals(p.getCloudAccountId()))
                .orElseThrow(() -> new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Plan not found."));
        return ResponseEntity.ok(PlanDto.from(plan));
    }

    /** Confirm a plan (202). {@code Idempotency-Key} required; a second confirm returns the same op. */
    @PostMapping(value = "/accounts/{accountId}/plans/{planId}/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirmPlan(
            @PathVariable Long accountId,
            @PathVariable UUID planId,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Valid @RequestBody ConfirmRequest request,
            @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        String key = idempotency.requireKey(idemKey);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder()
                .cloudAccountId(account.getId()).open()) {
            String canonical = canonicalConfirmBody(planId, request);
            ConfirmCommand cmd = new ConfirmCommand(request.planVersion(), request.acknowledgedWarningIds(),
                    request.fullSpotOptIn(), request.reason(), dryRun, key, idempotency.hash(canonical));
            ConfirmResult result = confirmService.confirm(account, user, planId, cmd);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "operationId", result.operationId(),
                    "status", result.status()));
        }
    }

    private OperationKind parseKind(String raw) {
        try {
            return OperationKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AutoSpottingApiException(HttpStatus.BAD_REQUEST, "CHECK_FAILED",
                    "Unknown plan kind.");
        }
    }

    private static String canonicalPlanBody(OperationKind kind, PlanRequest r) {
        return "{\"kind\":\"" + kind.name() + "\",\"groupId\":" + json(r.groupId())
                + ",\"groupRef\":" + json(r.groupRef()) + ",\"profileId\":" + json(r.profileId()) + "}";
    }

    private static String canonicalConfirmBody(UUID planId, ConfirmRequest r) {
        return "{\"planId\":\"" + planId + "\",\"planVersion\":" + r.planVersion()
                + ",\"acks\":" + r.acknowledgedWarningIds() + ",\"fullSpotOptIn\":" + r.fullSpotOptIn() + "}";
    }

    private static String json(Object v) {
        return v == null ? "null" : "\"" + String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String actorName(ClientUserDetails user) {
        if (user == null) {
            return "system";
        }
        return user.getUsername() != null ? user.getUsername() : String.valueOf(user.getId());
    }
}
