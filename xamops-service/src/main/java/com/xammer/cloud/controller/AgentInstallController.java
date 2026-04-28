package com.xammer.cloud.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the XamOps disk agent install script at a public URL — no auth required.
 * Customers run:
 *   curl -fsSL https://api.xamops.com/agentinstall/agent_install.sh | sudo bash -s -- -t <token>
 */
@RestController
public class AgentInstallController {

    private static final Logger logger = LoggerFactory.getLogger(AgentInstallController.class);

    /**
     * GET /agentinstall/agent_install.sh
     * Returns the disk agent bash installer. No authentication required.
     */
    @GetMapping(value = "/agentinstall/agent_install.sh", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getDiskAgentInstallScript() {
        return serveScript("scripts/disk_agent_install.sh");
    }

    /**
     * GET /agentinstall/gcp_disk_agent_install.sh
     * Returns the GCP disk agent bash installer. No authentication required.
     */
    @GetMapping(value = "/agentinstall/gcp_disk_agent_install.sh", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getGcpDiskAgentInstallScript() {
        return serveScript("scripts/gcp_disk_agent_install.sh");
    }

    private ResponseEntity<String> serveScript(String classpathResource) {
        try {
            ClassPathResource resource = new ClassPathResource(classpathResource);
            try (InputStream is = resource.getInputStream()) {
                String script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(script);
            }
        } catch (IOException e) {
            logger.error("Could not serve install script {}: {}", classpathResource, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("#!/usr/bin/env bash\necho 'Install script temporarily unavailable'\n");
        }
    }
}
