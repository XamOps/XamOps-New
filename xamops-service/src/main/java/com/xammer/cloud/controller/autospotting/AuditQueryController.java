package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.AuditEntryDto;
import com.xammer.cloud.repository.autospotting.AutoSpottingAuditRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/autospotting/v2/accounts/{accountId}/audit?groupId&from&to&limit} (plan-phase-14.md
 * task 14.2, PD-08): the Activity view's read of {@code autospotting_audit}, newest first, at most 200
 * rows (default 50). The range is whole UTC days, default the last 30 days, at most 92. A {@code groupId}
 * that is not this account's is 404, never another tenant's rows. Rows are {@link AuditEntryDto}s.
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class AuditQueryController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final CloudAccountAccessService accountAccessService;
    private final AutoSpottingAuditRepository audit;
    private final SpotGroupRepository groups;
    private final Clock clock;

    @Autowired
    public AuditQueryController(CloudAccountAccessService accountAccessService, AutoSpottingAuditRepository audit,
                                SpotGroupRepository groups) {
        this(accountAccessService, audit, groups, Clock.systemUTC());
    }

    AuditQueryController(CloudAccountAccessService accountAccessService, AutoSpottingAuditRepository audit,
                         SpotGroupRepository groups, Clock clock) {
        this.accountAccessService = accountAccessService;
        this.audit = audit;
        this.groups = groups;
        this.clock = clock;
    }

    @GetMapping("/accounts/{accountId}/audit")
    public ResponseEntity<List<AuditEntryDto>> audit(
            @PathVariable Long accountId,
            @RequestParam(value = "groupId", required = false) UUID groupId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(AnalyticsController.DEFAULT_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > AnalyticsController.MAX_DAYS) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "The range can be at most " + AnalyticsController.MAX_DAYS + " days.");
        }
        if (groupId != null) {
            SpotGroup g = groups.findById(groupId).orElse(null);
            if (g == null || !account.getId().equals(g.getCloudAccountId())) {
                throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Group not found.");
            }
        }
        var fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        var toInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        PageRequest page = PageRequest.of(0, limit == null ? DEFAULT_LIMIT : limit);
        var rows = groupId == null
                ? audit.findByCloudAccountIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                        account.getId(), fromInstant, toInstant, page)
                : audit.findByCloudAccountIdAndGroupIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                        account.getId(), groupId, fromInstant, toInstant, page);
        List<AuditEntryDto> body = rows.getContent().stream().map(AuditEntryDto::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
