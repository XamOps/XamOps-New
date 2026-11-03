package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchRequest;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchService;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Archive search over objects that have aged out of the hot store into the customer's S3 bucket.
 *
 * <p>DEFERRED: running a search over the archive is not built. This endpoint serves the cost/scope ESTIMATE only
 * ({@link ArchiveSearchService#estimate}); it never scans archived objects or returns log rows.
 */
@RestController("xpArchiveSearchController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseArchiveSearchController {

    private final XamPulseAccessGuard guard;
    private final XpAccountConfigRepository configs;
    private final ArchiveSearchService search;

    public XamPulseArchiveSearchController(XamPulseAccessGuard guard, XpAccountConfigRepository configs,
                                           ArchiveSearchService search) {
        this.guard = guard;
        this.configs = configs;
        this.search = search;
    }

    @PostMapping("/archive-searches")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpArchiveEstimate estimate(@AuthenticationPrincipal ClientUserDetails user,
                                      @Valid @RequestBody XpArchiveSearchRequest request) {
        XpScope scope = request.scope();
        if (scope.accountIds() == null || scope.accountIds().size() != 1) {
            throw XamPulseException.invalid("An archive search targets exactly one account.");
        }
        Long accountId = scope.accountIds().get(0);
        // Authorise first: a foreign or missing account is the same 404 envelope as everywhere else.
        CloudAccount account = guard.require(user, accountId);
        if (account.getAwsAccountId() == null || (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider()))) {
            throw XamPulseException.notFound();
        }
        XpAccountConfig cfg = configs.findByCloudAccountId(account.getId()).orElseThrow(XamPulseException::notSetUp);
        return search.estimate(cfg, account.getAwsAccountId(), scope.from(), scope.to(), Instant.now());
    }
}
