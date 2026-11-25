package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpEvent;
import com.xammer.cloud.xampulse.service.query.EventQueryService;
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

@RestController("xpEventsController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseEventsController {

    private final EventQueryService events;

    public XamPulseEventsController(EventQueryService events) {
        this.events = events;
    }

    @GetMapping("/events")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpEvent> events(@AuthenticationPrincipal ClientUserDetails user,
                                @RequestParam List<Long> accountIds,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                @RequestParam(required = false) List<String> types) {
        return events.events(user, accountIds, from, to, types);
    }
}
