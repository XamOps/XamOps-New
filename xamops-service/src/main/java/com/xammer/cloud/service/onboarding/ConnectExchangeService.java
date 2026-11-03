package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.onboarding.ConnectExchangeRequest;
import com.xammer.cloud.dto.onboarding.ConnectExchangeResponse;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The connect-token exchange decision table (design §4.3): validates a bearer connect token in the
 * exact order the design specifies, each refusal audited {@code TOKEN_REFUSED} with its code and
 * (per the table) without consuming the token, and on success atomically consumes the token and
 * issues/rotates the purpose's key, updating stored state.
 *
 * <p>{@code CLUSTER_MISMATCH}/{@code ACCOUNT_MISMATCH} are wrong-context guards, not authentication
 * (F-23): the token is the only bearer secret. The {@code PHASE1_NOT_HEALTHY} capability check is
 * supplied by a collaborator ({@link Phase1HealthCheck}) so this service stays independent of the
 * capabilities assembly; when unset, AUTOMATION health is treated as healthy (the session API wires
 * the real check in BP-12).</p>
 */
@Service
public class ConnectExchangeService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectExchangeService.class);

    /** Refusal codes and their HTTP status (design §4.3 table). */
    public enum RefusalCode {
        TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
        TOKEN_REVOKED(HttpStatus.GONE),
        TOKEN_EXPIRED(HttpStatus.GONE),
        TOKEN_USED(HttpStatus.CONFLICT),
        CLUSTER_MISMATCH(HttpStatus.CONFLICT),
        ACCOUNT_MISMATCH(HttpStatus.CONFLICT),
        PHASE_MISMATCH(HttpStatus.CONFLICT),
        PERMISSIONS_CHANGED(HttpStatus.CONFLICT),
        NODE_SCALER_CHANGED(HttpStatus.CONFLICT),
        PHASE1_NOT_HEALTHY(HttpStatus.CONFLICT),
        INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

        private final HttpStatus status;

        RefusalCode(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    /** Capability health gate for AUTOMATION exchanges (agent.status == CONNECTED). */
    public interface Phase1HealthCheck {
        boolean connected(KubernetesClusterConfig config);
    }

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final ConnectTokenService tokenService;
    private final ClusterKeyService keyService;
    private final OnboardingAuditService audit;
    private final Clock clock;
    private final Phase1HealthCheck phase1HealthCheck;

    @Value("${xamops.server.url:https://api.xamops.com}")
    private String serverUrl;

    public ConnectExchangeService(KubernetesClusterConfigRepository clusterConfigRepository,
                                  ConnectTokenService tokenService,
                                  ClusterKeyService keyService,
                                  OnboardingAuditService audit,
                                  Clock clock,
                                  Optional<Phase1HealthCheck> phase1HealthCheck) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.tokenService = tokenService;
        this.keyService = keyService;
        this.audit = audit;
        this.clock = clock;
        this.phase1HealthCheck = phase1HealthCheck.orElse(config -> true);
    }

    /** Phase strings → the token purposes that belong to that phase. */
    private static boolean phaseMatchesPurpose(String phase, String purpose) {
        if ("READ_ONLY".equals(phase)) {
            return ConnectTokenService.PURPOSE_CONNECT_READ_ONLY.equals(purpose)
                    || ConnectTokenService.PURPOSE_ROTATE_DATA_KEY.equals(purpose);
        }
        if ("AUTOMATION".equals(phase)) {
            return ConnectTokenService.PURPOSE_ENABLE_AUTOMATION.equals(purpose)
                    || ConnectTokenService.PURPOSE_ROTATE_AUTOMATION_KEY.equals(purpose);
        }
        return false;
    }

    private static boolean isAutomationPurpose(String purpose) {
        return ConnectTokenService.PURPOSE_ENABLE_AUTOMATION.equals(purpose)
                || ConnectTokenService.PURPOSE_ROTATE_AUTOMATION_KEY.equals(purpose);
    }

    /** AWS numeric ids lose leading zeros across numeric types — compare normalised (AgentPrincipal rule). */
    static String normaliseAccount(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            String stripped = trimmed.replaceFirst("^0+", "");
            return stripped.isEmpty() ? "0" : stripped;
        }
        return trimmed;
    }

    /**
     * Run the exchange. Throws {@link OnboardingException} with the refusal code/status on any
     * failure (auditing {@code TOKEN_REFUSED} first); returns the success response otherwise.
     */
    @Transactional
    public ConnectExchangeResponse exchange(String rawToken, ConnectExchangeRequest req, String fromIp) {
        // Header shape (TOKEN_INVALID) — before any DB lookup.
        if (!ConnectTokenService.hasValidShape(rawToken)) {
            throw refuse(RefusalCode.TOKEN_INVALID, null, req, fromIp, "Malformed connect token");
        }
        Optional<ClusterConnectToken> found = tokenService.findByRaw(rawToken);
        if (found.isEmpty()) {
            throw refuse(RefusalCode.TOKEN_INVALID, null, req, fromIp, "Unknown connect token");
        }
        ClusterConnectToken token = found.get();
        Instant now = Instant.now(clock);

        // State rows (do not consume).
        if (token.getRevokedAt() != null) {
            throw refuse(RefusalCode.TOKEN_REVOKED, token, req, fromIp, "Token revoked");
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw refuse(RefusalCode.TOKEN_EXPIRED, token, req, fromIp, "Token expired");
        }
        if (token.getUsedAt() != null) {
            throw refuse(RefusalCode.TOKEN_USED, token, req, fromIp, "Token already used");
        }

        KubernetesClusterConfig config = clusterConfigRepository.findById(token.getClusterConfigId())
                .orElseThrow(() -> refuse(RefusalCode.INTERNAL, token, req, fromIp, "Token has no cluster"));

        // Wrong-context guards (do not consume).
        if (req.getClusterName() == null || !req.getClusterName().equals(config.getClusterName())) {
            throw refuse(RefusalCode.CLUSTER_MISMATCH, token, req, fromIp, "Cluster name does not match the token");
        }
        String owningAwsNumber = String.valueOf(config.getCloudAccountId());
        if (!normaliseAccount(req.getCloudAccountId()).equals(normaliseAccount(owningAwsNumber))) {
            throw refuse(RefusalCode.ACCOUNT_MISMATCH, token, req, fromIp, "Account does not match the token");
        }
        if (!phaseMatchesPurpose(req.getPhase(), token.getPurpose())) {
            throw refuse(RefusalCode.PHASE_MISMATCH, token, req, fromIp, "Phase does not match the token purpose");
        }

        // AUTOMATION-specific guards (do not consume).
        if (isAutomationPurpose(token.getPurpose())) {
            if (config.getAutomationConsentAt() == null
                    || token.getPermissionHash() == null
                    || config.getAutomationPermissionHash() == null
                    || !token.getPermissionHash().equals(config.getAutomationPermissionHash())) {
                throw refuse(RefusalCode.PERMISSIONS_CHANGED, token, req, fromIp,
                        "Consent cleared or permissions changed; reopen Enable automation");
            }
            String detectedScaler = req.getDetected() == null ? null : req.getDetected().getNodeScaler();
            if (nodeScalerChanged(detectedScaler, token.getNodeScaler(), config.getNodeScaler())) {
                throw refuse(RefusalCode.NODE_SCALER_CHANGED, token, req, fromIp,
                        "Node scaler changed since consent; reopen Enable automation");
            }
            if (!phase1HealthCheck.connected(config)) {
                throw refuse(RefusalCode.PHASE1_NOT_HEALTHY, token, req, fromIp,
                        "The read-only data agent is not CONNECTED");
            }
        }

        // Atomic single-use consume (AC-4). 0 rows ⇒ lost the race ⇒ TOKEN_USED.
        if (!tokenService.consume(token.getId(), fromIp)) {
            throw refuse(RefusalCode.TOKEN_USED, token, req, fromIp, "Token already used");
        }

        return succeed(token, config, now);
    }

    /**
     * NODE_SCALER_CHANGED (F-16): the installer's detection must match the token snapshot AND the
     * current config value (when that is set and not UNKNOWN).
     */
    public static boolean nodeScalerChanged(String detected, String tokenSnapshot, String configValue) {
        if (detected == null) {
            return false; // nothing detected to contradict
        }
        if (tokenSnapshot != null && !detected.equals(tokenSnapshot)) {
            return true;
        }
        return configValue != null && !"UNKNOWN".equals(configValue) && !detected.equals(configValue);
    }

    private ConnectExchangeResponse succeed(ClusterConnectToken token, KubernetesClusterConfig config, Instant now) {
        String purpose = token.getPurpose();
        boolean automation = isAutomationPurpose(purpose);

        String key;
        String keyKind;
        switch (purpose) {
            case ConnectTokenService.PURPOSE_CONNECT_READ_ONLY -> {
                key = keyService.getOrCreateDataKey(config);
                keyKind = "DATA";
            }
            case ConnectTokenService.PURPOSE_ROTATE_DATA_KEY -> {
                key = keyService.rotateDataKey(config);
                keyKind = "DATA";
            }
            case ConnectTokenService.PURPOSE_ENABLE_AUTOMATION -> {
                key = keyService.getOrCreateAutomationKey(config);
                keyKind = "AUTOMATION";
            }
            case ConnectTokenService.PURPOSE_ROTATE_AUTOMATION_KEY -> {
                key = keyService.rotateAutomationKey(config);
                keyKind = "AUTOMATION";
            }
            default -> throw new OnboardingException("INTERNAL", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown token purpose");
        }

        config.setLastExchangeAt(now);
        config.setOnboardingFeatures(token.getFeatures());
        if (token.getIdentityMode() != null) {
            config.setIdentityMode(token.getIdentityMode());
        }
        if (automation) {
            // A successful ENABLE_AUTOMATION exchange clears the disable cut-off (§3.1a).
            config.setAutomationDisabledAt(null);
        }
        clusterConfigRepository.save(config);

        audit.record(null, config.getCloudAccountId(), config.getClusterName(), config.getId(),
                "TOKEN_EXCHANGED", OnboardingAuditService.ACTOR_INSTALLER, null,
                detail(token, "purpose", purpose));
        audit.record(null, config.getCloudAccountId(), config.getClusterName(), config.getId(),
                ConnectTokenService.PURPOSE_CONNECT_READ_ONLY.equals(purpose)
                        || ConnectTokenService.PURPOSE_ENABLE_AUTOMATION.equals(purpose)
                        ? "KEY_ISSUED" : "KEY_ROTATED",
                OnboardingAuditService.ACTOR_INSTALLER, null,
                "{\"keyKind\":\"" + keyKind + "\"}");

        ConnectExchangeResponse resp = new ConnectExchangeResponse();
        resp.setAgentKey(key);
        resp.setKeyKind(keyKind);
        resp.setServerUrl(serverUrl);
        resp.setClusterName(config.getClusterName());
        resp.setCloudAccountId(String.valueOf(config.getCloudAccountId()));
        resp.setFeatures(token.getFeatures() == null || token.getFeatures().isBlank()
                ? java.util.List.of()
                : java.util.List.of(token.getFeatures().split(",")));
        return resp;
    }

    private OnboardingException refuse(RefusalCode code, ClusterConnectToken token,
                                       ConnectExchangeRequest req, String fromIp, String message) {
        Long accountPk = null;
        String clusterName = req == null ? null : req.getClusterName();
        Long configId = null;
        if (token != null) {
            configId = token.getClusterConfigId();
        }
        try {
            audit.record(null, accountPk, clusterName == null ? "?" : clusterName, configId,
                    "TOKEN_REFUSED", OnboardingAuditService.ACTOR_INSTALLER, null,
                    "{\"code\":\"" + code.name() + "\""
                            + (token != null ? ",\"tokenId\":" + token.getId() : "")
                            + "}");
        } catch (RuntimeException auditFailure) {
            logger.warn("Failed to audit TOKEN_REFUSED {}: {}", code, auditFailure.toString());
        }
        logger.warn("connect/exchange refused {} prefix={} ip={}", code,
                token != null ? token.getTokenPrefix() : "n/a", fromIp);
        return new OnboardingException(code.name(), code.status(), message);
    }

    private static String detail(ClusterConnectToken token, String key, String value) {
        return "{\"" + key + "\":\"" + value + "\",\"tokenId\":" + token.getId() + "}";
    }
}
