package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpTrustPanel;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import com.xammer.cloud.xampulse.service.setup.TrustPanelService;
import com.xammer.cloud.xampulse.service.setup.XamPulseTemplateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The trust panel: shown before install and always reachable afterwards. */
@RestController("xpTrustController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseTrustController {

    private final XamPulseAccessGuard guard;
    private final XpAccountConfigRepository configs;
    private final TrustPanelService trust;
    private final XamPulseTemplateService templates;

    public XamPulseTrustController(XamPulseAccessGuard guard, XpAccountConfigRepository configs, TrustPanelService trust,
                                   XamPulseTemplateService templates) {
        this.guard = guard;
        this.configs = configs;
        this.trust = trust;
        this.templates = templates;
    }

    @GetMapping("/trust")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpTrustPanel trust(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId) {
        guard.require(user, accountId);
        return trust.panel(configs.findByCloudAccountId(accountId).orElse(null), templates.template(false).sha256());
    }
}
