package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpAgentFleet;
import com.xammer.cloud.xampulse.service.agent.AgentFleetService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent fleet and per-agent health for one account. */
@RestController("xpAgentFleetController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseAgentFleetController {

    private final AgentFleetService fleet;

    public XamPulseAgentFleetController(AgentFleetService fleet) {
        this.fleet = fleet;
    }

    @GetMapping("/accounts/{accountId}/agents")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpAgentFleet agents(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long accountId) {
        return fleet.fleet(user, accountId);
    }
}
