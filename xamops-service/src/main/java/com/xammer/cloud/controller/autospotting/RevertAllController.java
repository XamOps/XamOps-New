package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AccountDeletionGuard;
import com.xammer.cloud.service.autospotting.core.AccountDeletionGuard.Check;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.plan.RevertAllService;
import com.xammer.cloud.service.autospotting.core.plan.RevertAllService.Batch;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Revert all" and the account-deletion check (design-core.md section 7.5, PO-21, AC-CMP-03). Every route goes
 * through the tenancy guard first ({@link CloudAccountAccessService#requireAccess}); errors flow through
 * {@link AutoSpottingProblemAdvice}.
 *
 * <ul>
 *   <li>{@code GET /accounts/{accountId}/deletion-check}: the groups that block deletion (PO-21) and the groups
 *       that stay converted if the account is deleted anyway (PO-21a, open). The deletion dialog reads it.</li>
 *   <li>{@code POST /accounts/{accountId}/revert-all} (Idempotency-Key): one REVERT plan per revertable group,
 *       sharing a batch id. 201 with the batch.</li>
 *   <li>{@code GET /accounts/{accountId}/revert-all/{batchId}}: the batch with each group's state and
 *       operation status ("n of m reverted").</li>
 *   <li>{@code POST /accounts/{accountId}/revert-all/{batchId}/confirm}: confirms each plan through the normal
 *       confirm path. 202 with each group's operation id or error code; one failure never stops the rest.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class RevertAllController {

    /** The batch confirm body. {@code reason} is needed when a platform admin confirms (PO-14). */
    public record BatchConfirmRequest(List<String> acknowledgedWarningIds, String reason) {
    }

    private final CloudAccountAccessService accountAccessService;
    private final RevertAllService revertAll;
    private final AccountDeletionGuard deletionGuard;
    private final IdempotencyService idempotency;

    public RevertAllController(CloudAccountAccessService accountAccessService, RevertAllService revertAll,
                               AccountDeletionGuard deletionGuard, IdempotencyService idempotency) {
        this.accountAccessService = accountAccessService;
        this.revertAll = revertAll;
        this.deletionGuard = deletionGuard;
        this.idempotency = idempotency;
    }

    @GetMapping("/accounts/{accountId}/deletion-check")
    public ResponseEntity<Check> deletionCheck(@PathVariable Long accountId,
                                               @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        return ResponseEntity.ok(deletionGuard.check(account.getId()));
    }

    @PostMapping("/accounts/{accountId}/revert-all")
    public ResponseEntity<Batch> create(@PathVariable Long accountId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                        @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        String key = idempotency.requireKey(idemKey);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId()).open()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(revertAll.create(account, user, key));
        }
    }

    @GetMapping("/accounts/{accountId}/revert-all/{batchId}")
    public ResponseEntity<Batch> get(@PathVariable Long accountId, @PathVariable UUID batchId,
                                     @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        return ResponseEntity.ok(revertAll.get(account, batchId));
    }

    @PostMapping(value = "/accounts/{accountId}/revert-all/{batchId}/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Batch> confirm(@PathVariable Long accountId, @PathVariable UUID batchId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                         @RequestBody BatchConfirmRequest request,
                                         @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        idempotency.requireKey(idemKey);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId()).open()) {
            List<String> acks = request == null || request.acknowledgedWarningIds() == null
                    ? List.of() : request.acknowledgedWarningIds();
            Batch batch = revertAll.confirm(account, user, batchId, acks, request == null ? null : request.reason());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(batch);
        }
    }
}
