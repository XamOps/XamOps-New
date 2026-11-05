package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpContainerRow;
import com.xammer.cloud.xampulse.dto.XpPage;
import com.xammer.cloud.xampulse.service.query.ContainerQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("xpContainersController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseContainersController {

    private final ContainerQueryService containers;

    public XamPulseContainersController(ContainerQueryService containers) {
        this.containers = containers;
    }

    @GetMapping("/containers")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpPage<XpContainerRow> containers(@AuthenticationPrincipal ClientUserDetails user,
                                             @RequestParam List<Long> accountIds,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return containers.list(user, accountIds, q, page, size);
    }
}
