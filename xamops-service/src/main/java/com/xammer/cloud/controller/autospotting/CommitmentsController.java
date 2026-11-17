package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.dto.autospotting.RiSpCoverageResponse;
import com.xammer.cloud.dto.autospotting.v2.CommitmentSettingsDto;
import com.xammer.cloud.dto.autospotting.v2.CommitmentSettingsRequest;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The AutoSpotting v2 Commitments endpoints (plan-phase-14.md task 14.1, NO-06). They delegate to the
 * existing {@link AutoSpottingService} logic, so coverage and the stored values are the same as the
 * legacy {@code /analytics/risp-coverage/**} and {@code /settings/**} paths, which stay until Phase 36.
 * The shell calls only these.
 *
 * <ul>
 *   <li>{@code GET .../commitments/coverage}: the legacy coverage body.</li>
 *   <li>{@code GET .../commitments/settings}: the two stored RI/SP respect values.</li>
 *   <li>{@code PUT .../commitments/settings/risp-respect}: saves them. Allowed by the same PO-14
 *       authorization the plan confirm uses (the client owner under DEFAULT mode; a sub-user, a
 *       platform admin or a Login-As session is 403 {@code AS_FORBIDDEN_ROLE}). The CSRF/intent header is
 *       enforced ahead of the controller by the {@code permissions-common} filter, {@code Idempotency-Key}
 *       is required, and the change writes an audit row in the same transaction.</li>
 * </ul>
 * The account is resolved through {@link CloudAccountAccessService#requireAccess} first (the Phase A
 * ownership guard), so another client's account is 403.
 */
@RestController
@RequestMapping("/api/autospotting/v2/accounts/{accountId}/commitments")
public class CommitmentsController {

    private final CloudAccountAccessService accountAccessService;
    private final AutoSpottingService autoSpottingService;
    private final AutoSpottingAuthorization authorization;
    private final AutoSpottingAuditWriter auditWriter;
    private final IdempotencyService idempotency;

    public CommitmentsController(CloudAccountAccessService accountAccessService,
                                 AutoSpottingService autoSpottingService,
                                 AutoSpottingAuthorization authorization,
                                 AutoSpottingAuditWriter auditWriter,
                                 IdempotencyService idempotency) {
        this.accountAccessService = accountAccessService;
        this.autoSpottingService = autoSpottingService;
        this.authorization = authorization;
        this.auditWriter = auditWriter;
        this.idempotency = idempotency;
    }

    @GetMapping("/coverage")
    public ResponseEntity<RiSpCoverageResponse> coverage(@PathVariable Long accountId,
                                                         @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        requireAws(account);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId()).open()) {
            RiSpCoverageResponse body;
            try {
                body = autoSpottingService.getRiSpCoverage(account, false);
            } catch (RuntimeException e) {
                throw new AutoSpottingApiException(HttpStatus.BAD_GATEWAY, "CLOUD_READ_FAILED",
                        "XamOps couldn't read RI and Savings Plan coverage right now.", true, null, null);
            }
            return noStore(body);
        }
    }

    @GetMapping("/settings")
    public ResponseEntity<CommitmentSettingsDto> settings(@PathVariable Long accountId,
                                                          @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        requireAws(account);
        return noStore(read(account));
    }

    @Transactional
    @PutMapping(value = "/settings/risp-respect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommitmentSettingsDto> saveRispRespect(
            @PathVariable Long accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommitmentSettingsRequest request,
            @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        requireAws(account);
        idempotency.requireKey(idempotencyKey);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId()).open()) {
            // The same PO-14 matrix as a confirm: DEFAULT mode lets only the client owner change it.
            Decision decision = authorization.authorizeConfirm(user, account, OperationKind.ENABLE, null);
            if (!decision.allowed()) {
                throw new AutoSpottingApiException(decision.denyStatus(), decision.denyCode(), decision.denyDetail());
            }
            CommitmentSettingsDto before = read(account);
            autoSpottingService.updateRispRespectSettings(account, request.respectRiSpCommitments(),
                    request.riSpUtilizationThreshold());
            CommitmentSettingsDto after = read(account);
            auditWriter.write(AuditRecord.builder("SETTING_CHANGED", decision.actor(), "RISP_RESPECT", "OK")
                    .provider("AWS")
                    .clientId(account.getClient() == null ? null : account.getClient().getId())
                    .cloudAccountId(account.getId())
                    .beforeState(json(before))
                    .afterState(json(after))
                    .build());
            return ResponseEntity.ok(after);
        }
    }

    private CommitmentSettingsDto read(CloudAccount account) {
        Map<String, Object> stored = autoSpottingService.getSettings(account);
        boolean respect = Boolean.TRUE.equals(stored.get("respectRiSpCommitments"));
        Object threshold = stored.get("riSpUtilizationThreshold");
        return new CommitmentSettingsDto(respect, threshold instanceof Number n ? n.intValue() : 90);
    }

    private static String json(CommitmentSettingsDto d) {
        return "{\"respectRiSpCommitments\":" + d.respectRiSpCommitments()
                + ",\"riSpUtilizationThreshold\":" + d.riSpUtilizationThreshold() + "}";
    }

    private static void requireAws(CloudAccount account) {
        if (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "Commitments are available for AWS accounts only.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
