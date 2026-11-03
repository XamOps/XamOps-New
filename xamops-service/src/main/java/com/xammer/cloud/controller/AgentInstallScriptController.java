package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Serves the cluster onboarding script as plain text so a customer can run one command instead of
 * pasting twenty.
 *
 * <p>There is a single script for every cloud. AKS, EKS and GKE differ in roughly 15% of the work
 * — credentials, account id, storage class, cluster discovery — while the five Helm installs, the
 * agent manifests and verification are identical. Serving one file with the provider substituted
 * per endpoint keeps the customer-facing command short without letting the copies drift apart.
 *
 * <p><b>Why this is unauthenticated.</b> The command has to run from Azure Cloud Shell, AWS
 * CloudShell or GCP Cloud Shell, none of which has a XamOps session to authenticate a download
 * with. Putting the
 * script behind {@code /api/xamops/**} would make {@code curl | bash} impossible. This is safe
 * because the script contains no secrets: the server URL and API key are supplied as command-line
 * flags, which the setup guide fills in for the environment the user is signed in to. Nothing here
 * reveals anything a customer could not already read in the guide.
 *
 * <p>Contrast with {@link AgentInstallConfigController}, which does return the API key and is
 * therefore deliberately session-protected.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/install")
public class AgentInstallScriptController {

    private static final String SCRIPT = "agent-install/install-k8s.sh";

    /**
     * The script's provider-default assignment, substituted here rather than passed as a flag so the
     * one-liner in the guide stays short and cannot be pasted with the wrong cloud.
     *
     * <p>Deliberately the whole {@code ${PROVIDER:-...}} expression and not the bare
     * {@code __PROVIDER__} placeholder. The script uses that placeholder a second time, as the
     * case-arm sentinel meaning "never substituted, so nobody picked a cloud". Replacing the bare
     * token rewrote that arm too — {@code eks.sh} came out with {@code eks|"") PROVIDER="aks"},
     * which matched the value just baked in and forced every EKS install back to AKS. Anchoring on
     * the assignment leaves the sentinel arm literal, so it still catches standalone runs of the
     * unsubstituted file.
     */
    private static final String PROVIDER_TOKEN = "${PROVIDER:-__PROVIDER__}";

    /**
     * Phase-2 automation markers. The installer ships inert copies ({@code __AUTOMATION_RBAC__},
     * {@code __AUTOMATION_NETPOL__}, {@code __AUTOMATION_IMAGE__}); the phase-2 flow refuses to run
     * until they are substituted. The RBAC and NetworkPolicy bodies are the byte-exact slices of the
     * autoscaler manifest extracted into these resources, so there is one source of truth and
     * {@code setup-guide-validation.yml} asserts the served copies match that manifest.
     */
    private static final String AUTOMATION_RBAC_TOKEN = "__AUTOMATION_RBAC__";
    private static final String AUTOMATION_NETPOL_TOKEN = "__AUTOMATION_NETPOL__";
    private static final String AUTOMATION_IMAGE_TOKEN = "${AUTOMATION_IMAGE:-__AUTOMATION_IMAGE__}";
    private static final String AUTOMATION_RBAC_RESOURCE = "agent-install/automation-rbac.yaml";
    private static final String AUTOMATION_NETPOL_RESOURCE = "agent-install/automation-netpol.yaml";

    /** Pinned automation (consolidation) agent image, substituted into the installer. */
    @Value("${xamops.k8s.onboarding.automation-image:docker.io/xamops/automation:v2.1.0}")
    private String automationImage;

    /** GET /api/v1/agent/install/aks.sh */
    @GetMapping(value = {"/aks.sh", "/aks"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> aksInstallScript() {
        return serve("aks", "xamops-install-aks.sh");
    }

    /** GET /api/v1/agent/install/eks.sh */
    @GetMapping(value = {"/eks.sh", "/eks"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> eksInstallScript() {
        return serve("eks", "xamops-install-eks.sh");
    }

    /** GET /api/v1/agent/install/gke.sh */
    @GetMapping(value = {"/gke.sh", "/gke"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> gkeInstallScript() {
        return serve("gke", "xamops-install-gke.sh");
    }

    /** The Phase-2 IAM CFN template, served verbatim (design §4.3, §5.2). */
    private static final String EKS_CLUSTER_ROLES = "agent-install/eks-cluster-roles.yml";

    /**
     * Cached template body + its SHA-256, computed once on first read. The installer verifies the
     * body against the {@code templateSha256} returned by the exchange before deploying IAM, so the
     * hash it reads from {@code X-Content-SHA256} here must match that value exactly.
     */
    private volatile String cachedTemplate;
    private volatile String cachedTemplateSha256;

    /**
     * GET /api/v1/agent/install/eks-cluster-roles.yml — the Phase-2 IAM template, verbatim, with an
     * {@code X-Content-SHA256} header the installer checks before applying the stack.
     */
    @GetMapping(value = "/eks-cluster-roles.yml", produces = "text/yaml")
    public ResponseEntity<String> eksClusterRolesTemplate() {
        String body;
        String sha;
        try {
            body = loadTemplate();
            sha = cachedTemplateSha256;
        } catch (IOException e) {
            log.error("Failed to read {}", EKS_CLUSTER_ROLES, e);
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("# XamOps IAM template is unavailable on this server.\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/yaml")
                .header("X-Content-SHA256", sha)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"eks-cluster-roles.yml\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(body);
    }

    /** Load and memoise the template body and its SHA-256 (design §4.3 exposes the hash to the exchange). */
    public String loadTemplate() throws IOException {
        String body = cachedTemplate;
        if (body != null) {
            return body;
        }
        ClassPathResource resource = new ClassPathResource(EKS_CLUSTER_ROLES);
        try (InputStream in = resource.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        cachedTemplate = body;
        cachedTemplateSha256 = sha256Hex(body);
        return body;
    }

    /** The template's SHA-256, for {@code ConnectExchangeService} to stamp into {@code iam.templateSha256}. */
    public String templateSha256() {
        try {
            loadTemplate();
        } catch (IOException e) {
            log.error("Failed to read {} for sha", EKS_CLUSTER_ROLES, e);
            return null;
        }
        return cachedTemplateSha256;
    }

    private static String sha256Hex(String body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Both the extensioned and bare paths are mapped: the {@code .sh} suffix is what users expect
     * in a {@code curl} URL, and the bare form guards against any future change to suffix-pattern
     * handling silently 404ing the documented one.
     */
    /**
     * Replace a marker line with a classpath resource body. The marker is left untouched (and a
     * warning logged) if the resource is missing, so a standalone run still fails closed rather than
     * the server emitting a half-substituted script. The body's trailing newline is trimmed so it
     * sits on the marker's own line inside the installer's quoted heredoc.
     */
    private String substituteMarker(String body, String token, String resource, String provider) {
        if (!body.contains(token)) {
            return body;
        }
        try {
            ClassPathResource res = new ClassPathResource(resource);
            try (InputStream in = res.getInputStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                while (content.endsWith("\n")) {
                    content = content.substring(0, content.length() - 1);
                }
                return body.replace(token, content);
            }
        } catch (IOException e) {
            log.error("Could not load {} to substitute {} for provider {}", resource, token, provider, e);
            return body;
        }
    }

    private ResponseEntity<String> serve(String provider, String filename) {
        ClassPathResource resource = new ClassPathResource(SCRIPT);
        if (!resource.exists()) {
            // A packaging mistake, not a client error — surface it loudly in the log rather than
            // letting customers see a bare 404 they cannot act on.
            log.error("Install script is missing from the classpath; check the build packaged "
                    + "src/main/resources/{}", SCRIPT);
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("# XamOps install script is unavailable on this server.\n"
                            + "# Contact support and quote: missing " + SCRIPT + "\n"
                            + "exit 1\n");
        }

        try (InputStream in = resource.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            if (!body.contains(PROVIDER_TOKEN)) {
                // The script and this controller have to agree on the token. Failing loudly beats
                // serving a script that silently defaults to the wrong cloud.
                log.error("Install script does not contain {} — cannot pin the provider", PROVIDER_TOKEN);
                return ResponseEntity.status(500)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("# XamOps install script could not be prepared for provider "
                                + provider + ".\nexit 1\n");
            }
            body = body.replace(PROVIDER_TOKEN, "${PROVIDER:-" + provider + "}");

            // Phase-2 markers. Substituted for every provider so the served file is internally
            // consistent; the phase-2 flow itself is gated to EKS in the script. The manifest
            // bodies are trimmed of a trailing newline so they drop cleanly onto the marker line
            // inside the quoted heredoc.
            body = body.replace(AUTOMATION_IMAGE_TOKEN, "${AUTOMATION_IMAGE:-" + automationImage + "}");
            body = substituteMarker(body, AUTOMATION_RBAC_TOKEN, AUTOMATION_RBAC_RESOURCE, provider);
            body = substituteMarker(body, AUTOMATION_NETPOL_TOKEN, AUTOMATION_NETPOL_RESOURCE, provider);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    // Named so `curl -O` produces a sensible file, without forcing a download for
                    // anyone who just wants to read it in a browser first.
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(body);
        } catch (IOException e) {
            log.error("Failed to read install script {}", SCRIPT, e);
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("# XamOps install script could not be read.\nexit 1\n");
        }
    }
}
