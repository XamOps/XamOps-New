package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.service.platform.RunbookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only runbooks over SSM (fixed scripts, audited). The catalogue says whether they are turned on. */
@RestController("xpRunbookController")
@RequestMapping("/api/xamops/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseRunbookController {

    public record Catalog(boolean enabled, List<RunbookService.Runbook> runbooks) {}

    private final RunbookService runbooks;

    public XamPulseRunbookController(RunbookService runbooks) {
        this.runbooks = runbooks;
    }

    /** Every runbook with the exact command it runs, so nobody has to guess what happens on the host. */
    @GetMapping("/runbooks")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public Catalog catalog() {
        return new Catalog(runbooks.enabled(), RunbookService.CATALOG);
    }

    @PostMapping("/hosts/{instanceId}/runbooks/{runbookId}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public RunbookService.Started run(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId,
                                      @PathVariable String runbookId, @RequestParam Long accountId, HttpServletRequest http) {
        return runbooks.run(user, accountId, instanceId, runbookId, XamPulseControllerSupport.ip(http));
    }

    @GetMapping("/hosts/{instanceId}/runbooks/commands/{commandId}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public RunbookService.Result result(@AuthenticationPrincipal ClientUserDetails user, @PathVariable String instanceId,
                                        @PathVariable String commandId, @RequestParam Long accountId) {
        return runbooks.result(user, accountId, instanceId, commandId);
    }
}
