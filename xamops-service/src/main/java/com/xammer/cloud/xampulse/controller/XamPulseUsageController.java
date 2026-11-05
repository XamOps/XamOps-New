package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpUsage;
import com.xammer.cloud.xampulse.service.query.UsageMeteringService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("xpUsageController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseUsageController {

    private final UsageMeteringService usage;

    public XamPulseUsageController(UsageMeteringService usage) {
        this.usage = usage;
    }

    @GetMapping("/usage")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpUsage usage(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId,
                         @RequestParam(defaultValue = "7d") String range) {
        return usage.usage(user, accountId, range);
    }
}
