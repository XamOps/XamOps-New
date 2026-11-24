package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.v2.RegionCoverageDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventStore;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /accounts/{accountId}/region-coverage}: the event pipeline's coverage of the account per region,
 * for the AWS account view (plan-phase-22.md 22.4, FR-EVT-02). Reads the table the {@code coverage:aws} job
 * writes; makes no AWS call and changes nothing. The account is resolved through
 * {@link CloudAccountAccessService#requireAccess} first.
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class RegionCoverageController {

    private final CloudAccountAccessService accountAccessService;
    private final AwsEventStore store;

    public RegionCoverageController(CloudAccountAccessService accountAccessService, AwsEventStore store) {
        this.accountAccessService = accountAccessService;
        this.store = store;
    }

    @GetMapping("/accounts/{accountId}/region-coverage")
    public ResponseEntity<RegionCoverageDto> coverage(@PathVariable Long accountId,
                                                      @AuthenticationPrincipal ClientUserDetails userDetails) {
        CloudAccount account = accountAccessService.requireAccess(userDetails, accountId);
        if (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "Region coverage applies to AWS accounts only.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(describe(account, store));
    }

    static RegionCoverageDto describe(CloudAccount account, AwsEventStore store) {
        boolean readWrite = account.getAccessType() != null && "read-write".equalsIgnoreCase(account.getAccessType().trim());
        if (!readWrite) {
            return new RegionCoverageDto(false, java.util.List.of());
        }
        return new RegionCoverageDto(true, store.coverage(account.getId()).stream()
                .map(r -> new RegionCoverageDto.Region(r.region(), r.status(), r.checkedAt(), r.lastEventAt()))
                .toList());
    }
}
