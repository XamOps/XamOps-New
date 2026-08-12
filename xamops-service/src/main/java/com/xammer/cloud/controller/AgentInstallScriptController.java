package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the cluster onboarding scripts as plain text so a customer can run one command
 * instead of pasting twenty.
 *
 * <p><b>Why this is unauthenticated.</b> The command has to run from Azure Cloud Shell, which
 * has no XamOps session to authenticate a download with. Putting the script behind
 * {@code /api/xamops/**} would make {@code curl | bash} impossible. This is safe because the
 * script contains no secrets: the server URL and API key are supplied as command-line flags,
 * which the setup guide fills in for the environment the user is signed in to. Nothing here
 * reveals anything a customer could not already read in the guide.
 *
 * <p>Contrast with {@link AgentInstallConfigController}, which does return the API key and is
 * therefore deliberately session-protected.
 *
 * <p>The script is the single source of truth for what the AKS guide installs. It lives in
 * {@code resources/agent-install/} rather than under {@code xamops-agent/} so that this module
 * is self-contained and a Docker build of xamops-service alone can still package it.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/install")
public class AgentInstallScriptController {

    private static final String AKS_SCRIPT = "agent-install/install-aks.sh";

    /**
     * GET /api/v1/agent/install/aks.sh
     *
     * <p>Both the extensioned and bare paths are mapped: the {@code .sh} suffix is what users
     * expect in a {@code curl} URL, and the bare form guards against any future change to
     * suffix-pattern handling silently 404ing the documented one.
     */
    @GetMapping(value = {"/aks.sh", "/aks"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> aksInstallScript() {
        return serve(AKS_SCRIPT, "install-aks.sh");
    }

    private ResponseEntity<String> serve(String classpathLocation, String filename) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            // A packaging mistake, not a client error — surface it loudly in the log rather
            // than letting customers see a bare 404 they cannot act on.
            log.error("Install script {} is missing from the classpath; check the build packaged "
                    + "src/main/resources/{}", filename, classpathLocation);
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("# XamOps install script is unavailable on this server.\n"
                            + "# Contact support and quote: missing " + classpathLocation + "\n"
                            + "exit 1\n");
        }

        try (InputStream in = resource.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    // Named so `curl -O` produces a sensible file, without forcing a download
                    // for anyone who just wants to read it in a browser first.
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(body);
        } catch (IOException e) {
            log.error("Failed to read install script {}", classpathLocation, e);
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("# XamOps install script could not be read.\nexit 1\n");
        }
    }
}
