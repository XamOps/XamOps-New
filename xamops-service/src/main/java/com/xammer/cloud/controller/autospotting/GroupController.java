package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.GroupDetailDto;
import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import com.xammer.cloud.dto.autospotting.v2.GroupRowDto;
import com.xammer.cloud.dto.autospotting.v2.OperationDto;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupTransitionRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.aws.AwsErrors;
import com.xammer.cloud.service.autospotting.aws.AwsGroupLister;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.list.GroupListCache;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The AutoSpotting v2 group read endpoints (design-core.md §11, design-ux-ops.md §4; plan-phase-12.md
 * task 12.5). Reads only: nothing here calls a mutating cloud API or changes a group.
 *
 * <ul>
 *   <li>{@code GET /accounts/{accountId}/groups}: the list in LIST mode within
 *       {@code autospotting.list.budget} (default 20 s). A location that did not finish comes back in
 *       {@code failedLocations} with code {@code TIMEOUT} and {@code partial=true}. A complete list is
 *       cached briefly; {@code Cache-Control: no-cache} on the request bypasses the cache (AC-OBS-05).</li>
 *   <li>{@code GET /accounts/{accountId}/groups/{groupId}}: one group with its transitions and current
 *       operation. 404 when the group is not this account's (never leaking another tenant's).</li>
 * </ul>
 * The account is resolved through {@link CloudAccountAccessService#requireAccess} first (the Phase A
 * ownership guard). Responses are DTO records, never entities (FR-SEC-02, AC-SEC-04). An unpriced
 * group is "Not priced", never 0 (AC-PLN-09).
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class GroupController {

    private final CloudAccountAccessService accountAccessService;
    private final AwsGroupLister awsLister;
    private final GroupListCache listCache;
    private final SpotGroupRepository groups;
    private final SpotGroupTransitionRepository transitions;
    private final SpotOperationRepository operations;

    public GroupController(CloudAccountAccessService accountAccessService, AwsGroupLister awsLister,
                           GroupListCache listCache, SpotGroupRepository groups,
                           SpotGroupTransitionRepository transitions, SpotOperationRepository operations) {
        this.accountAccessService = accountAccessService;
        this.awsLister = awsLister;
        this.listCache = listCache;
        this.groups = groups;
        this.transitions = transitions;
        this.operations = operations;
    }

    @GetMapping("/accounts/{accountId}/groups")
    public ResponseEntity<GroupListDto> list(@PathVariable Long accountId,
                                             @RequestHeader(value = "Cache-Control", required = false) String cacheControl,
                                             @AuthenticationPrincipal ClientUserDetails userDetails) {
        CloudAccount account = accountAccessService.requireAccess(userDetails, accountId);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId()).open()) {
            requireAws(account);
            boolean refresh = cacheControl != null && cacheControl.toLowerCase(java.util.Locale.ROOT).contains("no-cache");
            if (refresh) {
                listCache.evict(account.getId());
            } else {
                Optional<GroupListDto> cached = listCache.get(account.getId());
                if (cached.isPresent()) {
                    return noStore(cached.get());
                }
            }
            GroupListDto list = awsLister.list(account);
            listCache.put(account.getId(), list); // a partial list is never stored
            return noStore(list);
        }
    }

    @GetMapping("/accounts/{accountId}/groups/{groupId}")
    public ResponseEntity<GroupDetailDto> detail(@PathVariable Long accountId, @PathVariable UUID groupId,
                                                 @AuthenticationPrincipal ClientUserDetails userDetails) {
        CloudAccount account = accountAccessService.requireAccess(userDetails, accountId);
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId())
                .groupId(groupId).open()) {
            SpotGroup row = groups.findById(groupId)
                    .filter(g -> account.getId() != null && account.getId().equals(g.getCloudAccountId()))
                    .orElseThrow(() -> new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Group not found."));
            requireAws(account);
            GroupRowDto head;
            try {
                head = awsLister.row(account, row.getGroupKey(), row).orElseGet(() -> rowFromSpine(row));
            } catch (RuntimeException e) {
                throw new AutoSpottingApiException(HttpStatus.BAD_GATEWAY, "CLOUD_READ_FAILED",
                        "XamOps couldn't read this group right now (" + AwsErrors.classify(e).name() + ").",
                        true, null, null);
            }
            List<GroupDetailDto.Transition> history = transitions.findByGroupIdOrderByIdAsc(groupId).stream()
                    .map(t -> new GroupDetailDto.Transition(
                            t.getFromState() == null ? null : t.getFromState().name(),
                            t.getToState() == null ? null : t.getToState().name(),
                            t.getReasonCode(), t.getActorType(), t.getOperationId(), t.getOccurredAt()))
                    .toList();
            OperationDto current = row.getCurrentOperationId() == null ? null
                    : operations.findById(row.getCurrentOperationId()).map(OperationDto::from).orElse(null);
            return ResponseEntity.ok(new GroupDetailDto(head,
                    row.getConfigMode() == null ? null : row.getConfigMode().name(),
                    row.getDriftStatus() == null ? null : row.getDriftStatus().name(),
                    row.getLastErrorCode(), history, current));
        }
    }

    /** The row the spine alone can show (the group is gone from the cloud). */
    private static GroupRowDto rowFromSpine(SpotGroup row) {
        String key = row.getGroupKey();
        int slash = key.indexOf('/');
        String location = slash > 0 ? key.substring(0, slash) : null;
        String name = slash > 0 ? key.substring(slash + 1) : key;
        return GroupRowDto.of(row.getId(), row.getProvider().name(), key, location, name, row.getState().name(),
                true, false, null, null, null, null, new EligibilityResult(List.of(), List.of(), List.of()), null, false);
    }

    private static void requireAws(CloudAccount account) {
        if (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "AutoSpotting groups are not available for this cloud yet.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
