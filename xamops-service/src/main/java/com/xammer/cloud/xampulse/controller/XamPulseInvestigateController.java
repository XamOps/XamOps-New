package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpCostPerf;
import com.xammer.cloud.xampulse.dto.XpExposure;
import com.xammer.cloud.xampulse.dto.XpInvestigation;
import com.xammer.cloud.xampulse.dto.XpPeers;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.insight.CostPerformanceService;
import com.xammer.cloud.xampulse.service.insight.ExposureService;
import com.xammer.cloud.xampulse.service.insight.InvestigationService;
import com.xammer.cloud.xampulse.service.insight.PeerOutlierService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Investigate (every usual explanation checked against measurements), the fleet-twin peer comparison, the exposure
 * check (listening sockets against security groups) and cost against use. None of them calls a billed API.
 */
@RestController("xpInvestigateController")
@RequestMapping("/api/xamops/xampulse/hosts/{instanceId}")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseInvestigateController {

    private static final String INSTANCE_ID = "^[A-Za-z0-9._\\-]{1,64}$";

    private final InvestigationService investigations;
    private final PeerOutlierService peers;
    private final ExposureService exposure;
    private final CostPerformanceService cost;

    public XamPulseInvestigateController(InvestigationService investigations, PeerOutlierService peers, ExposureService exposure,
                                         CostPerformanceService cost) {
        this.investigations = investigations;
        this.peers = peers;
        this.exposure = exposure;
        this.cost = cost;
    }

    private static void check(String instanceId) {
        if (instanceId == null || !instanceId.matches(INSTANCE_ID)) {
            throw XamPulseException.notFound();
        }
    }

    @GetMapping("/investigate")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpInvestigation investigate(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId, @RequestParam Long accountId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        check(instanceId);
        return investigations.investigate(user, accountId, instanceId, from, to);
    }

    @GetMapping("/peers")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpPeers peers(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId, @RequestParam Long accountId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        check(instanceId);
        return peers.peers(user, accountId, instanceId, from, to);
    }

    @GetMapping("/exposure")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpExposure exposure(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId, @RequestParam Long accountId) {
        check(instanceId);
        return exposure.exposure(user, accountId, instanceId);
    }

    @GetMapping("/cost")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpCostPerf cost(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId, @RequestParam Long accountId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        check(instanceId);
        return cost.view(user, accountId, instanceId, from, to);
    }
}
