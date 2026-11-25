package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.setup.XamPulseTemplateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public install script for hosts that cannot use Systems Manager: {@code curl -fsSL .../agentinstall/xampulse.sh | sudo bash}.
 * It is the same script the SSM document runs, with the two public URLs filled in. It contains no secret: the agent
 * proves who it is later with its own instance-profile credentials. Not cached, so a script change takes effect at once.
 */
@RestController("xpInstallController")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseInstallController {

    private static final MediaType SHELL = MediaType.parseMediaType("text/x-shellscript;charset=UTF-8");
    private final XamPulseTemplateService templates;

    public XamPulseInstallController(XamPulseTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping(value = "/agentinstall/xampulse.sh")
    public ResponseEntity<String> linux() {
        String script;
        try {
            script = templates.installScript(templates.releaseBaseUrl(), templates.desiredStateUrl());
        } catch (XamPulseException e) {
            return ResponseEntity.status(503).contentType(MediaType.TEXT_PLAIN).body("XamPulse install is not configured on this server.\n");
        }
        return ResponseEntity.ok().contentType(SHELL).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"xampulse.sh\"").body(script);
    }

    @GetMapping(value = "/agentinstall/xampulse.ps1")
    public ResponseEntity<String> windows() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).cacheControl(CacheControl.noStore()).body(templates.windowsPlaceholderScript());
    }
}
