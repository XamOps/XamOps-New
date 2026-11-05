package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpHealthScore;
import com.xammer.cloud.xampulse.dto.XpOverview;
import com.xammer.cloud.xampulse.service.query.OverviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController("xpOverviewController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseOverviewController {

    private final OverviewService overview;

    public XamPulseOverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/overview")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpOverview overview(@AuthenticationPrincipal ClientUserDetails user,
                               @RequestParam List<Long> accountIds,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return overview.overview(user, accountIds, from, to);
    }

    @GetMapping("/health-score")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpHealthScore healthScore(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId) {
        return overview.healthScore(user, accountId);
    }
}
