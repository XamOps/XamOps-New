package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.*;
import com.xammer.cloud.xampulse.service.stream.TailTicketData;
import com.xammer.cloud.xampulse.service.stream.TailTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController("xpLogsController")
@RequestMapping("/api/xamops/xampulse/logs")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseLogsController {

    private final LogQueryService search;
    private final LogFacetService facets;
    private final LogPatternService patterns;
    private final LogContextService context;
    private final QueryScopeFactory scopes;
    private final TailTicketService tickets;
    private final AuditService audit;
    private final XamPulseProperties props;

    public XamPulseLogsController(LogQueryService search, LogFacetService facets, LogPatternService patterns,
                                  LogContextService context, QueryScopeFactory scopes, TailTicketService tickets,
                                  AuditService audit, XamPulseProperties props) {
        this.search = search;
        this.facets = facets;
        this.patterns = patterns;
        this.context = context;
        this.scopes = scopes;
        this.tickets = tickets;
        this.audit = audit;
        this.props = props;
    }

    @PostMapping("/search")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpLogSearchResponse search(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpLogSearchRequest req,
                                      HttpServletRequest http) {
        XpLogSearchResponse res = search.search(user, req);
        audit.record(user, req.scope().accountIds().get(0), "logs.search", AuditService.describeQuery(req.query()),
                XamPulseControllerSupport.ip(http));
        return res;
    }

    @PostMapping("/histogram")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpLogHistogramResponse histogram(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpLogHistogramRequest req) {
        return search.histogram(user, req);
    }

    @PostMapping("/facets")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpLogFacetsResponse facets(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpLogFacetsRequest req) {
        return facets.facets(user, req);
    }

    @PostMapping("/patterns")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpLogPatternsResponse patterns(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpLogPatternsRequest req) {
        return patterns.patterns(user, req);
    }

    @GetMapping("/context")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpLogContextResponse context(@AuthenticationPrincipal ClientUserDetails user,
                                        @RequestParam Long accountId, @RequestParam String id,
                                        @RequestParam(defaultValue = "20") int before,
                                        @RequestParam(defaultValue = "20") int after,
                                        @RequestParam(defaultValue = "true") boolean sameSource) {
        return context.context(user, accountId, id, before, after, sameSource);
    }

    @PostMapping("/tail/tickets")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpTailTicket tailTicket(@AuthenticationPrincipal ClientUserDetails user, @Valid @RequestBody XpTailTicketRequest req,
                                   HttpServletRequest http) {
        QueryScope scope = scopes.build(user, req.scope()); // authorises every account and bounds the range
        FilterDslParser.parse(req.query());                  // reject a bad filter now, not after the upgrade
        Long actor = XamPulseControllerSupport.impersonationActor(http);
        String ticket = tickets.issue(new TailTicketData(user.getId(), scope.accountIds(), req.query() == null ? "" : req.query(), Instant.now(), actor));
        return new XpTailTicket(ticket, props.getWs().getTailUrl() + "?ticket=" + ticket, (int) com.xammer.cloud.xampulse.service.stream.TailTicketService.TTL.toSeconds());
    }
}
