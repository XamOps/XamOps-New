package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpContainerRow;
import com.xammer.cloud.xampulse.dto.XpContainerSummary;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.query.ContainerDetailService;
import com.xammer.cloud.xampulse.service.query.ContainerQueryService;
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

@RestController("xpContainersController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseContainersController {

    private static final String INSTANCE_ID = "^[A-Za-z0-9._\\-]{1,64}$";

    private final ContainerQueryService containers;
    private final ContainerDetailService detail;

    public XamPulseContainersController(ContainerQueryService containers, ContainerDetailService detail) {
        this.containers = containers;
        this.detail = detail;
    }

    @GetMapping("/containers")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpPage<XpContainerRow> containers(@AuthenticationPrincipal ClientUserDetails user,
                                             @RequestParam List<Long> accountIds,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size,
                                             @RequestParam(defaultValue = "-restarts") String sort) {
        return containers.list(user, accountIds, q, page, size, sort);
    }

    /** Container 360: one container by name on one host, through every redeploy in the window. */
    @GetMapping("/containers/{instanceId}/{name}/summary")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpContainerSummary summary(@AuthenticationPrincipal ClientUserDetails user,
                                      @PathVariable String instanceId, @PathVariable String name,
                                      @RequestParam Long accountId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!instanceId.matches(INSTANCE_ID) || !ContainerDetailService.NAME.matcher(name).matches()) {
            throw XamPulseException.notFound();
        }
        return detail.summary(user, accountId, instanceId, name, from, to);
    }
}
