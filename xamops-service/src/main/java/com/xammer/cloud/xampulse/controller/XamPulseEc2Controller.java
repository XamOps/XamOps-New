package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpCloudWatchView;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.service.insight.ThrottleRadarService;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.aws.CloudWatchViewService;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The AWS side of the EC2 instance page (no agent needed): facts, status checks, scheduled events and findings
 * from free EC2 APIs, and CloudWatch data that is fetched only when the user presses Refresh.
 */
@RestController("xpEc2Controller")
@RequestMapping("/api/xamops/xampulse/hosts/{instanceId}")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseEc2Controller {

    private static final String INSTANCE_ID = "^i-[0-9a-f]{8,17}$";

    private final XamPulseAccessGuard guard;
    private final QueryScopeFactory scopes;
    private final Ec2FactsService facts;
    private final CloudWatchViewService cloudWatch;
    private final AuditService audit;
    private final ThrottleRadarService radar;

    public XamPulseEc2Controller(XamPulseAccessGuard guard, QueryScopeFactory scopes, Ec2FactsService facts,
                                 CloudWatchViewService cloudWatch, AuditService audit, ThrottleRadarService radar) {
        this.guard = guard;
        this.scopes = scopes;
        this.facts = facts;
        this.cloudWatch = cloudWatch;
        this.audit = audit;
        this.radar = radar;
    }

    @GetMapping("/aws")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpEc2Facts aws(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId,
                          @RequestParam Long accountId, @RequestParam(required = false) String region) {
        return facts.facts(awsAccount(user, accountId, instanceId), instanceId, region);
    }

    /** Earlier fetches from the XamOps store. Free: never calls CloudWatch. */
    @GetMapping("/cloudwatch")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpCloudWatchView stored(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId,
                                   @RequestParam Long accountId, @RequestParam(required = false) String region,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        awsAccount(user, accountId, instanceId);
        QueryScope scope = scopes.build(user, List.of(accountId), from, to);
        return cloudWatch.stored(scope, instanceId, region, null);
    }

    /** Fetches from CloudWatch now (billed to the customer's AWS account) and keeps the result. Audited. */
    @PostMapping("/cloudwatch/refresh")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpCloudWatchView refresh(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId,
                                    @RequestParam Long accountId, @RequestParam(required = false) String region,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                    HttpServletRequest http) {
        CloudAccount account = awsAccount(user, accountId, instanceId);
        QueryScope scope = scopes.build(user, List.of(accountId), from, to);
        XpCloudWatchView view = cloudWatch.refresh(account, scope, instanceId, region);
        if ("REFRESH".equals(view.source())) {
            audit.record(user, accountId, "cloudwatch.refresh",
                    "instance=" + instanceId + ",metrics=" + view.metricsRequested(), XamPulseControllerSupport.ip(http));
        }
        return view;
    }

    /**
     * Throttle Radar: limits hit in the window (AWS network allowances, EBS caps, Spot notices, kernel pressure,
     * conntrack, container CPU limits), from agent data and already-stored CloudWatch points. Calls no billed API;
     * the free EC2 facts (cached) only sharpen the wording, and their absence never hides a finding.
     */
    @GetMapping("/limits")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpHostLimits limits(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId,
                               @RequestParam Long accountId, @RequestParam(required = false) String region,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        CloudAccount account = awsAccount(user, accountId, instanceId);
        Optional<XpEc2Facts> f;
        try {
            f = Optional.of(facts.facts(account, instanceId, region));
        } catch (RuntimeException e) {
            f = Optional.empty();
        }
        return radar.limits(user, accountId, instanceId, from, to, f);
    }

    private CloudAccount awsAccount(ClientUserDetails user, Long accountId, String instanceId) {
        if (instanceId == null || !instanceId.matches(INSTANCE_ID)) {
            throw XamPulseException.notFound();
        }
        CloudAccount account = guard.require(user, accountId);
        if (account.getAwsAccountId() == null || (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider()))) {
            throw XamPulseException.notFound();
        }
        return account;
    }
}
