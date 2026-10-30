package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.dto.autospotting.v2.ConfigDto;
import com.xammer.cloud.dto.autospotting.v2.OperationDto;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The AutoSpotting v2 read endpoints (design-ux-ops.md §2.1/§5.2, design-core.md §11; plan-phase-09.md
 * task 9.3). Customer-facing reads only — no mutating v2 path ships here (that is Phases 11+). Every
 * response is a DTO record in {@code dto.autospotting.v2}, never a JPA entity (FR-SEC-02, AC-SEC-04,
 * pinned by {@code AutoSpottingArchitectureTest}). Errors go through {@link AutoSpottingProblemAdvice}
 * as {@code application/problem+json}.
 *
 * <ul>
 *   <li>{@code GET /config}: {@code shellEnabled}, per-provider readiness, {@code dryRun} and the
 *       engaged switch names. Any authenticated user may read it; it carries no tenant data.</li>
 *   <li>{@code GET /accounts/{accountId}/operations/{operationId}}: the async view of an operation
 *       (FR-SPN-05, AC-SPN-07). {@code accountId} is the {@code CloudAccount} PK, resolved through
 *       {@link CloudAccountAccessService#requireAccess} (the Phase A ownership guard), so a user of
 *       another client gets 403 (never 404 leaking existence, never 200). An operation that does not
 *       belong to the resolved account is 404.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class AutoSpottingV2Controller {

    private final CloudAccountAccessService accountAccessService;
    private final SpotOperationRepository operations;
    private final AutoSpottingSwitchProperties switchProperties;
    private final AutoSpottingFeatureFlags featureFlags;
    private final SwitchService switchService;
    private final boolean shellEnabled;
    private final boolean globalEnabledProperty;

    public AutoSpottingV2Controller(CloudAccountAccessService accountAccessService,
                                    SpotOperationRepository operations,
                                    AutoSpottingSwitchProperties switchProperties,
                                    AutoSpottingFeatureFlags featureFlags,
                                    SwitchService switchService,
                                    @Value("${autospotting.ui.shell-enabled:false}") boolean shellEnabled,
                                    @Value("${autospotting.enabled:false}") boolean globalEnabledProperty) {
        this.accountAccessService = accountAccessService;
        this.operations = operations;
        this.switchProperties = switchProperties;
        this.featureFlags = featureFlags;
        this.switchService = switchService;
        this.shellEnabled = shellEnabled;
        this.globalEnabledProperty = globalEnabledProperty;
    }

    /** The runtime config the SPA reads (design-ux-ops.md §2.1, §10.2). Carries no tenant data. */
    @GetMapping("/config")
    public ResponseEntity<ConfigDto> config() {
        SwitchState state = switchService.current();
        Map<String, ConfigDto.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("AWS", providerConfig(Provider.AWS, state));
        providers.put("GCP", providerConfig(Provider.GCP, state));
        // Engaged DB switch scope keys, sorted for a stable response.
        List<String> engaged = List.copyOf(new TreeSet<>(state.engagedScopeKeys()));
        ConfigDto dto = new ConfigDto(shellEnabled, switchProperties.mutationsDryRun(), providers, engaged);
        return ResponseEntity.ok(dto);
    }

    /**
     * The async view of an operation (FR-SPN-05, AC-SPN-07). The account is resolved and ownership
     * enforced first; then the op is loaded and must belong to that account, else 404.
     */
    @GetMapping("/accounts/{accountId}/operations/{operationId}")
    public ResponseEntity<OperationDto> operation(@PathVariable Long accountId,
                                                  @PathVariable UUID operationId,
                                                  @AuthenticationPrincipal ClientUserDetails userDetails) {
        CloudAccount account = accountAccessService.requireAccess(userDetails, accountId);
        Optional<SpotOperation> found = operations.findById(operationId);
        SpotOperation op = found
                .filter(o -> account.getId() != null && account.getId().equals(o.getCloudAccountId()))
                .orElseThrow(() -> new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Operation not found."));
        return ResponseEntity.ok(OperationDto.from(op));
    }

    private ConfigDto.ProviderConfig providerConfig(Provider provider, SwitchState state) {
        boolean aws = provider == Provider.AWS;
        // enabled = master property AND global property AND NOT (global or cloud db engaged) — the
        // effective per-cloud master the UI should treat as "AutoSpotting is on for this cloud".
        boolean enabled = globalEnabledProperty
                && switchProperties.cloudEnabled(aws)
                && !state.globalEngaged()
                && !state.cloudEngaged(aws);
        boolean conversionsProperty = aws
                ? featureFlags.isAwsConversionsEnabled()
                : featureFlags.isGcpConversionsEnabled();
        boolean conversionsEnabled = conversionsProperty && !state.conversionsEngaged(aws);
        boolean allowlistOnly = switchProperties.conversionsAllowlistOnly(aws);
        boolean v2MutationsEnabled = switchProperties.v2MutationsEnabled(aws);
        // Beta label (PO-25 default): a cloud stays Beta until its restore program is live. Removed
        // in Phase 35/36; the safe default here is beta=true whenever v2 mutations are not enabled.
        boolean beta = !v2MutationsEnabled;
        return new ConfigDto.ProviderConfig(enabled, conversionsEnabled, allowlistOnly, v2MutationsEnabled, beta);
    }
}
