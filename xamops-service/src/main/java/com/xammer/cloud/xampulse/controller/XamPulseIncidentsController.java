package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpIncident;
import com.xammer.cloud.xampulse.dto.XpIncidentAction;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.service.alert.IncidentService;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController("xpIncidentsController")
@RequestMapping("/api/xamops/xampulse/alerts/incidents")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseIncidentsController {

    private final IncidentService incidents;
    private final AuditService audit;

    public XamPulseIncidentsController(IncidentService incidents, AuditService audit) {
        this.incidents = incidents;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpPage<XpIncident> list(@AuthenticationPrincipal ClientUserDetails user, @RequestParam List<Long> accountIds,
                                   @RequestParam(required = false) String state,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return incidents.list(user, accountIds, state, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpIncident get(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id) {
        return incidents.get(user, id);
    }

    @PostMapping("/{id}/{action:ack|snooze|resolve|comment}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpIncident act(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id, @PathVariable String action,
                          @Valid @RequestBody(required = false) XpIncidentAction body, HttpServletRequest http) {
        XpIncident res = incidents.act(user, id, action, body);
        audit.record(user, res.accountId(), "incident." + action, "incident:" + id, XamPulseControllerSupport.ip(http));
        return res;
    }
}
