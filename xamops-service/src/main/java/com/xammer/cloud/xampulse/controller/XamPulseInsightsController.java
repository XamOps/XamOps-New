package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpForecast;
import com.xammer.cloud.xampulse.dto.XpInsight;
import com.xammer.cloud.xampulse.service.insight.InsightFeedService;
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

@RestController("xpInsightsController")
@RequestMapping("/api/xamops/xampulse/insights")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseInsightsController {

    private final InsightFeedService insights;

    public XamPulseInsightsController(InsightFeedService insights) {
        this.insights = insights;
    }

    @GetMapping("/feed")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpInsight> feed(@AuthenticationPrincipal ClientUserDetails user, @RequestParam List<Long> accountIds,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return insights.feed(user, accountIds, from, to);
    }

    @GetMapping("/forecasts")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpForecast> forecasts(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId) {
        return insights.forecasts(user, accountId);
    }
}
