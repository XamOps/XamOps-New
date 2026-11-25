package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpMetricCatalogEntry;
import com.xammer.cloud.xampulse.dto.XpMetricQueryRequest;
import com.xammer.cloud.xampulse.dto.XpMetricQueryResponse;
import com.xammer.cloud.xampulse.service.query.MetricCatalogService;
import com.xammer.cloud.xampulse.service.query.MetricQueryService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController("xpMetricsController")
@RequestMapping("/api/xamops/xampulse/metrics")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseMetricsController {

    private final MetricQueryService metrics;
    private final MetricCatalogService catalog;

    public XamPulseMetricsController(MetricQueryService metrics, MetricCatalogService catalog) {
        this.metrics = metrics;
        this.catalog = catalog;
    }

    @PostMapping("/query")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpMetricQueryResponse query(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpMetricQueryRequest req) {
        return metrics.query(user, req);
    }

    @GetMapping("/catalog")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpMetricCatalogEntry> catalog(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId,
                                              @RequestParam(required = false) String q) {
        return catalog.catalog(user, accountId, q);
    }
}
