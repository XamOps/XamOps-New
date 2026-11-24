package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpChange;
import com.xammer.cloud.xampulse.dto.XpHostRow;
import com.xammer.cloud.xampulse.dto.XpHostSummary;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.dto.XpProcessRow;
import com.xammer.cloud.xampulse.service.insight.ChangeIntelligenceService;
import com.xammer.cloud.xampulse.service.query.HostQueryService;
import com.xammer.cloud.xampulse.service.query.ProcessQueryService;
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
import java.util.List;

@RestController("xpHostsController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseHostsController {

    private static final String INSTANCE_ID = "^[A-Za-z0-9._\\-]{1,64}$";

    private final HostQueryService hosts;
    private final ProcessQueryService processes;
    private final ChangeIntelligenceService changes;

    public XamPulseHostsController(HostQueryService hosts, ProcessQueryService processes, ChangeIntelligenceService changes) {
        this.hosts = hosts;
        this.processes = processes;
        this.changes = changes;
    }

    @GetMapping("/hosts")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpPage<XpHostRow> hosts(@AuthenticationPrincipal ClientUserDetails user,
                                   @RequestParam List<Long> accountIds,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String state,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size,
                                   @RequestParam(required = false) String sort) {
        return hosts.list(user, accountIds, q, state, page, size, sort);
    }

    @GetMapping("/hosts/{instanceId}/summary")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpHostSummary summary(@AuthenticationPrincipal ClientUserDetails user,
                                 @PathVariable String instanceId,
                                 @RequestParam Long accountId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!instanceId.matches(INSTANCE_ID)) {
            throw com.xammer.cloud.xampulse.exception.XamPulseException.notFound();
        }
        return hosts.summary(user, accountId, instanceId, from, to);
    }

    @GetMapping("/hosts/{instanceId}/processes")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpProcessRow> processes(@AuthenticationPrincipal ClientUserDetails user,
                                        @PathVariable String instanceId,
                                        @RequestParam Long accountId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        if (!instanceId.matches(INSTANCE_ID)) {
            throw com.xammer.cloud.xampulse.exception.XamPulseException.notFound();
        }
        return processes.topProcesses(user, accountId, instanceId, at);
    }

    /** Change Intelligence for one host: what changed, newest first, each with what the measurements did after it. */
    @GetMapping("/hosts/{instanceId}/changes")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpChange> hostChanges(@AuthenticationPrincipal ClientUserDetails user,
                                      @PathVariable String instanceId,
                                      @RequestParam Long accountId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!instanceId.matches(INSTANCE_ID)) {
            throw com.xammer.cloud.xampulse.exception.XamPulseException.notFound();
        }
        return changes.changes(user, accountId, instanceId, from, to);
    }

    /** Change Intelligence across an account's hosts (no effects: they are worked out on each host's page). */
    @GetMapping("/changes")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpChange> fleetChanges(@AuthenticationPrincipal ClientUserDetails user,
                                       @RequestParam Long accountId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return changes.changes(user, accountId, null, from, to);
    }
}
