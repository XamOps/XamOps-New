package com.xammer.cloud.controller.autospotting;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.v2.StackCapabilityDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.aws.StackCapabilities;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
/**
 * {@code GET /accounts/{accountId}/stack-capabilities}: which AutoSpotting capabilities the account's
 * connection stack lacks, for the "Update stack" banner (plan-phase-21.md 21.5, AC-ONB-01). Reads the
 * stored {@code stack_template_version}; makes no AWS call and changes nothing. The account is
 * resolved through {@link CloudAccountAccessService#requireAccess} first.
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class StackCapabilityController {
    private final CloudAccountAccessService accountAccessService;
    public StackCapabilityController(CloudAccountAccessService accountAccessService) {
        this.accountAccessService = accountAccessService;
    }
    @GetMapping("/accounts/{accountId}/stack-capabilities")
    public ResponseEntity<StackCapabilityDto> capabilities(@PathVariable Long accountId,
                                                           @AuthenticationPrincipal ClientUserDetails userDetails) {
        CloudAccount account = accountAccessService.requireAccess(userDetails, accountId);
        if (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "AutoSpotting stack capabilities apply to AWS accounts only.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(describe(account));
    }
    static StackCapabilityDto describe(CloudAccount account) {
        boolean readWrite = account.getAccessType() != null && "read-write".equalsIgnoreCase(account.getAccessType().trim());
        String version = account.getStackTemplateVersion();
        String latest = StackCapabilities.latestVersion();
        if (!readWrite) {
            return new StackCapabilityDto(false, version, latest, false, List.of());
        }
        List<StackCapabilityDto.Missing> missing = StackCapabilities.missingForUpdate(version).stream()
                .map(c -> new StackCapabilityDto.Missing(c.name(), c.label())).toList();
        return new StackCapabilityDto(true, version, latest, !missing.isEmpty(), missing);
    }
}
