package com.xammer.cloud.controller;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.onboarding.ConnectExchangeRequest;
import com.xammer.cloud.dto.onboarding.ConnectExchangeResponse;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ConnectRateLimiter;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.onboarding.ConnectExchangeService;
import com.xammer.cloud.service.onboarding.OnboardingAuditService;
import com.xammer.cloud.service.onboarding.OnboardingException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Public installer/agent connect protocol (design §4.3), under the already-{@code permitAll}
 * {@code /api/v1/agent/connect/**} path. Every endpoint is rate-limited per caller IP
 * ({@link ConnectRateLimiter}, 20/min, fail-open). The bearer secret is the only authentication:
 * preflight/exchange carry a connect token; report carries the freshly issued agent key.
 */
@RestController
@RequestMapping("/api/v1/agent/connect")
public class AgentConnectController {

    private static final Logger logger = LoggerFactory.getLogger(AgentConnectController.class);

    private static final Pattern ROLE_ARN =
            Pattern.compile("^arn:aws:iam::\\d{12}:role/xamops/[\\w+=,.@-]{1,64}$");

    private final ConnectRateLimiter rateLimiter;
    private final ConnectExchangeService exchangeService;
    private final AgentIngestService agentIngestService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final OnboardingAuditService audit;

    public AgentConnectController(ConnectRateLimiter rateLimiter,
                                  ConnectExchangeService exchangeService,
                                  AgentIngestService agentIngestService,
                                  KubernetesClusterConfigRepository clusterConfigRepository,
                                  OnboardingAuditService audit) {
        this.rateLimiter = rateLimiter;
        this.exchangeService = exchangeService;
        this.agentIngestService = agentIngestService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.audit = audit;
    }

    private String bearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7).trim();
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean rateLimited(HttpServletRequest request) {
        return !rateLimiter.allow(clientIp(request));
    }

    private static ResponseEntity<Map<String, Object>> tooMany() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "RATE_LIMITED", "message", "Too many connect requests; slow down."));
    }

    /**
     * {@code POST /preflight} — stores the installer's preflight report (PREFLIGHT_INSTALLER) and
     * returns 204. Token is read but not consumed. Request shape is lenient (checks ≤ 50 entries).
     */
    @PostMapping("/preflight")
    public ResponseEntity<?> preflight(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        if (rateLimited(request)) {
            return tooMany();
        }
        String token = bearer(authHeader);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "TOKEN_INVALID", "message", "Missing connect token"));
        }
        // The token is read but not consumed here; we only record the installer's preflight.
        String clusterName = body == null ? "?" : String.valueOf(body.getOrDefault("clusterName", "?"));
        logger.info("connect/preflight received for cluster {}", clusterName);
        // Best-effort audit; detail carries no secret.
        try {
            audit.record(null, null, clusterName, null, "PREFLIGHT_INSTALLER",
                    OnboardingAuditService.ACTOR_INSTALLER, null,
                    "{\"checks\":" + checksCount(body) + "}");
        } catch (RuntimeException e) {
            logger.debug("preflight audit skipped: {}", e.toString());
        }
        return ResponseEntity.noContent().build();
    }

    private static int checksCount(Map<String, Object> body) {
        if (body == null) {
            return 0;
        }
        Object checks = body.get("checks");
        return (checks instanceof List<?> list) ? list.size() : 0;
    }

    /** {@code POST /exchange} — the connect-token exchange (design §4.3). */
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @RequestBody ConnectExchangeRequest req,
                                      HttpServletRequest request) {
        if (rateLimited(request)) {
            return tooMany();
        }
        String token = bearer(authHeader);
        try {
            ConnectExchangeResponse resp = exchangeService.exchange(token, req, clientIp(request));
            return ResponseEntity.ok(resp);
        } catch (OnboardingException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("error", e.getCode(), "message", e.getMessage()));
        }
    }

    /**
     * {@code POST /report} — the installer reports IAM outcome using the freshly issued agent key.
     * Validates role ARNs and stores stack/identity/role facts; audits INSTALL_REPORTED.
     */
    @PostMapping("/report")
    public ResponseEntity<?> report(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        if (rateLimited(request)) {
            return tooMany();
        }
        String apiKey = bearer(authHeader);
        AgentPrincipal principal = apiKey == null ? null : agentIngestService.authenticate(apiKey, clientIp(request));
        if (principal == null || principal.getScope() != AgentPrincipal.Scope.CLUSTER) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "UNAUTHORIZED", "message", "A cluster agent key is required"));
        }
        String clusterName = (String) body.get("clusterName");
        String cloudAccountId = body.get("cloudAccountId") == null ? null : String.valueOf(body.get("cloudAccountId"));
        String denied = agentIngestService.checkOwnership(principal, cloudAccountId, clusterName);
        if (denied != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "FORBIDDEN", "message", denied));
        }

        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(toAccountPk(cloudAccountId), clusterName);
        if (configOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "CLUSTER_NOT_REGISTERED", "message", "No config row"));
        }
        KubernetesClusterConfig config = configOpt.get();

        @SuppressWarnings("unchecked")
        Map<String, Object> iam = (Map<String, Object>) body.get("iam");
        if (iam != null) {
            String readRoleArn = (String) iam.get("readRoleArn");
            String writeRoleArn = (String) iam.get("writeRoleArn");
            boolean automation = principal.getRole() == AgentPrincipal.AgentRole.AUTOMATION;

            if (readRoleArn != null && (automation || !ROLE_ARN.matcher(readRoleArn).matches())) {
                return invalidReport();
            }
            if (writeRoleArn != null && (!automation || !ROLE_ARN.matcher(writeRoleArn).matches())) {
                return invalidReport();
            }
            if (iam.get("stackName") != null) {
                config.setIamStackName((String) iam.get("stackName"));
            }
            if (iam.get("identityMode") != null) {
                config.setIdentityMode((String) iam.get("identityMode"));
            }
            if (readRoleArn != null) {
                config.setReadRoleArn(readRoleArn);
            }
            if (writeRoleArn != null) {
                config.setWriteRoleArn(writeRoleArn);
            }
            clusterConfigRepository.save(config);
        }

        audit.record(null, config.getCloudAccountId(), config.getClusterName(), config.getId(),
                "INSTALL_REPORTED", OnboardingAuditService.ACTOR_INSTALLER, null,
                "{\"result\":\"" + body.getOrDefault("result", "SUCCEEDED") + "\"}");
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static ResponseEntity<Map<String, Object>> invalidReport() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "INVALID_REPORT", "message", "Role ARN failed validation"));
    }

    private static Long toAccountPk(String cloudAccountId) {
        try {
            return cloudAccountId == null ? null : Long.parseLong(cloudAccountId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
