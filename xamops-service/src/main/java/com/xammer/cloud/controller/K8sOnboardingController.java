package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.ClusterOffboardingTombstoneRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.onboarding.ClusterKeyService;
import com.xammer.cloud.service.onboarding.ConnectTokenService;
import com.xammer.cloud.service.onboarding.OnboardingAuditService;
import com.xammer.cloud.service.onboarding.OnboardingException;
import com.xammer.cloud.service.onboarding.OnboardingFeatureCatalog;
import com.xammer.cloud.service.onboarding.OnboardingPreflightService;
import com.xammer.cloud.service.onboarding.PermissionManifestService;
import com.xammer.cloud.service.onboarding.ScriptCommandRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The onboarding session API (design §4.1, §4.2), under {@code /api/xamops/k8s/onboarding}. Session
 * authenticated; tenancy resolved only through {@link K8sAutomationAccessService#resolveAccounts}.
 * Gated by {@code xamops.k8s.onboarding.enabled}. Phase 1 read-only operations only; the per-cluster
 * gate (OQ-6) is out of scope (FEAT-003).
 *
 * <p>This controller deliberately does NOT open any Phase-2 acting path: enabling automation only
 * issues a token and records consent; nothing flips a readiness key or mutates a live cluster.</p>
 */
@RestController
@RequestMapping("/api/xamops/k8s/onboarding")
public class K8sOnboardingController {

    private static final Logger logger = LoggerFactory.getLogger(K8sOnboardingController.class);

    private static final Pattern CLUSTER_NAME = Pattern.compile("^[0-9A-Za-z][A-Za-z0-9_-]{0,99}$");
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d{1,2}$");

    private final K8sAutomationAccessService accessService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final ClusterOffboardingTombstoneRepository tombstoneRepository;
    private final ConnectTokenService tokenService;
    private final ClusterKeyService keyService;
    private final OnboardingPreflightService preflightService;
    private final PermissionManifestService permissionManifestService;
    private final OnboardingFeatureCatalog featureCatalog;
    private final ScriptCommandRenderer commandRenderer;
    private final OnboardingAuditService audit;

    @Value("${xamops.k8s.onboarding.enabled:true}")
    private boolean onboardingEnabled;

    /**
     * OQ-1 is undecided: tier names, prices, and whether a paid feature is selectable without a plan
     * are not settled. The catalog still carries a placeholder {@code tier} per feature, but the API
     * does NOT surface it to the UI until a commercial model exists. Default false in every profile;
     * flip to true once real tier data is in place. Display-only: a feature's selectability is driven
     * by {@code availability}, never by this flag.
     */
    @Value("${xamops.onboarding.show-tiers:false}")
    private boolean showTiers;

    public K8sOnboardingController(K8sAutomationAccessService accessService,
                                   KubernetesClusterConfigRepository clusterConfigRepository,
                                   ClusterOffboardingTombstoneRepository tombstoneRepository,
                                   ConnectTokenService tokenService,
                                   ClusterKeyService keyService,
                                   OnboardingPreflightService preflightService,
                                   PermissionManifestService permissionManifestService,
                                   OnboardingFeatureCatalog featureCatalog,
                                   ScriptCommandRenderer commandRenderer,
                                   OnboardingAuditService audit) {
        this.accessService = accessService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.tokenService = tokenService;
        this.keyService = keyService;
        this.preflightService = preflightService;
        this.permissionManifestService = permissionManifestService;
        this.featureCatalog = featureCatalog;
        this.commandRenderer = commandRenderer;
        this.audit = audit;
    }

    // ── Validation / tenancy ────────────────────────────────────────────────

    private void requireEnabled() {
        if (!onboardingEnabled) {
            throw new OnboardingException("FEATURE_DISABLED", HttpStatus.NOT_FOUND, "Onboarding is disabled");
        }
    }

    private void validateCluster(String clusterName) {
        if (clusterName == null || !CLUSTER_NAME.matcher(clusterName).matches()) {
            throw new OnboardingException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Invalid cluster name");
        }
    }

    private void validateAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new OnboardingException("K8S_NO_ACCOUNT_ACCESS", HttpStatus.FORBIDDEN, "No access to account");
        }
    }

    /** Resolve the entitled accounts for this user+account (403 via resolveAccounts when not owned). */
    private List<CloudAccount> accounts(ClientUserDetails user, String accountId) {
        validateAccount(accountId);
        List<CloudAccount> accounts = accessService.resolveAccounts(user, accountId);
        if (accounts == null || accounts.isEmpty()) {
            throw new OnboardingException("K8S_NO_ACCOUNT_ACCESS", HttpStatus.FORBIDDEN, "No access to account");
        }
        return accounts;
    }

    private Optional<KubernetesClusterConfig> findConfig(List<CloudAccount> accounts, String clusterName) {
        for (CloudAccount a : accounts) {
            Optional<KubernetesClusterConfig> c = clusterConfigRepository
                    .findByCloudAccountIdAndClusterName(a.getId(), clusterName);
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping("/clusters/{clusterName}")
    public ResponseEntity<Map<String, Object>> status(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        requireEnabled();
        validateCluster(clusterName);
        List<CloudAccount> accounts = accounts(user, accountId);
        Optional<KubernetesClusterConfig> config = findConfig(accounts, clusterName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterName", clusterName);
        body.put("registered", config.isPresent());
        config.ifPresent(c -> {
            body.put("region", c.getRegion());
            body.put("tokens", tokenSummaries(c.getId()));
        });
        return ResponseEntity.ok(body);
    }

    @PostMapping("/clusters/{clusterName}/preflight")
    public ResponseEntity<Map<String, Object>> preflight(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody Map<String, String> body) {
        requireEnabled();
        validateCluster(clusterName);
        List<CloudAccount> accounts = accounts(user, accountId);
        String region = body.get("region");
        String phase = body.getOrDefault("phase", "READ_ONLY");
        if (region == null || !REGION.matcher(region).matches()) {
            throw new OnboardingException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Invalid region");
        }
        KubernetesClusterConfig config = findConfig(accounts, clusterName).orElse(null);
        OnboardingPreflightService.Result result =
                preflightService.run(accounts.get(0), config, clusterName, region, phase);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranAt", result.ranAt().toString());
        out.put("checks", result.checks());
        out.put("blocking", result.blocking());
        out.put("identityMode", result.identityMode());
        out.put("nodeScaler", result.nodeScaler());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/permissions")
    public ResponseEntity<Map<String, Object>> permissions(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam String accountId,
            @RequestParam String clusterName,
            @RequestParam String region,
            @RequestParam String phase,
            @RequestParam(defaultValue = "true") boolean createReadRole,
            @RequestParam(required = false) String features) {
        requireEnabled();
        validateCluster(clusterName);
        List<CloudAccount> accounts = accounts(user, accountId);
        List<String> featureList = parseFeatures(features);
        boolean accountReadWrite = accounts.stream().anyMatch(K8sAutomationAccessService::isReadWrite);
        boolean pfComputePass = false; // conservative without a live probe (ships inert)
        boolean nodeRemoval = permissionManifestService.nodeRemoval(
                nodeScalerOf(accounts, clusterName), featureList, pfComputePass);
        Map<String, Object> manifest = permissionManifestService.manifest(phase, clusterName, region,
                accounts.get(0).getAwsAccountId(), null, nodeScalerOf(accounts, clusterName),
                featureList, nodeRemoval, List.of(), List.of());

        Map<String, Object> out = new LinkedHashMap<>(manifest);
        out.put("features", featureCatalogView(featureList, accountReadWrite, pfComputePass));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/clusters/{clusterName}/connect-token")
    @Transactional
    public ResponseEntity<Map<String, Object>> connectToken(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestBody Map<String, Object> body) {
        requireEnabled();
        validateCluster(clusterName);
        List<CloudAccount> accounts = accounts(user, accountId);

        String region = (String) body.get("region");
        if (region == null || !REGION.matcher(region).matches()) {
            throw new OnboardingException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Invalid region");
        }
        String phase = (String) body.getOrDefault("phase", "READ_ONLY");
        boolean createReadRole = !Boolean.FALSE.equals(body.get("createReadRole"));
        List<String> features = normaliseFeatures(body.get("features"), phase);

        // Advisory lock + pre-register the config row if absent (design §4.2 step 4). Clear any
        // tombstone so a deliberate re-connect works.
        CloudAccount owner = accounts.get(0);
        lockAccountCluster(owner.getId(), clusterName);
        KubernetesClusterConfig config = findConfig(accounts, clusterName).orElseGet(() ->
                preRegister(owner, clusterName, region));
        tombstoneRepository.clear(owner.getId(), clusterName);

        String purpose = "AUTOMATION".equals(phase)
                ? ConnectTokenService.PURPOSE_ENABLE_AUTOMATION
                : ConnectTokenService.PURPOSE_CONNECT_READ_ONLY;

        ConnectTokenService.IssuedToken issued =
                tokenService.issue(config.getId(), purpose, user == null ? 0L : user.getId(),
                        ConnectTokenService.DEFAULT_TTL_SECONDS);
        ClusterConnectToken row = issued.row();
        row.setFeatures(String.join(",", features));
        row.setCreateReadRole("AUTOMATION".equals(phase)
                ? config.getReadRoleArn() != null
                : createReadRole);
        clusterConfigRepository.save(config);

        audit.record(user == null ? null : user.getClientId(), owner.getId(), clusterName, config.getId(),
                "TOKEN_ISSUED", OnboardingAuditService.ACTOR_USER, user == null ? null : user.getId(),
                "{\"purpose\":\"" + purpose + "\"}");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", issued.rawToken());
        out.put("tokenPrefix", row.getTokenPrefix());
        out.put("expiresAt", row.getExpiresAt().toString());
        out.put("ttlSeconds", ConnectTokenService.DEFAULT_TTL_SECONDS);
        out.put("purpose", purpose);
        out.put("command", commandRenderer.render(purpose, clusterName, region, issued.rawToken(), createReadRole));
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @DeleteMapping("/clusters/{clusterName}/keys/{agent}")
    @Transactional
    public ResponseEntity<Void> revokeKey(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable String clusterName,
            @PathVariable String agent,
            @RequestParam String accountId) {
        requireEnabled();
        validateCluster(clusterName);
        List<CloudAccount> accounts = accounts(user, accountId);
        KubernetesClusterConfig config = findConfig(accounts, clusterName)
                .orElseThrow(() -> new OnboardingException("CLUSTER_NOT_REGISTERED", HttpStatus.NOT_FOUND, "No config"));
        if ("data".equals(agent)) {
            keyService.revokeDataKey(config);
        } else if ("automation".equals(agent)) {
            keyService.revokeAutomationKey(config);
            config.setAutomationVerifiedAt(null);
            clusterConfigRepository.save(config);
        } else {
            throw new OnboardingException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Unknown agent");
        }
        audit.record(user == null ? null : user.getClientId(), accounts.get(0).getId(), clusterName,
                config.getId(), "KEY_REVOKED", OnboardingAuditService.ACTOR_USER,
                user == null ? null : user.getId(), "{\"agent\":\"" + agent + "\"}");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/clusters/{clusterName}/disable-automation")
    @Transactional
    public ResponseEntity<Map<String, Object>> disableAutomation(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        requireEnabled();
        validateCluster(clusterName);
        List<CloudAccount> accounts = accounts(user, accountId);
        KubernetesClusterConfig config = findConfig(accounts, clusterName)
                .orElseThrow(() -> new OnboardingException("CLUSTER_NOT_REGISTERED", HttpStatus.NOT_FOUND, "No config"));

        keyService.revokeAutomationKey(config);
        config.setAutomationConsentAt(null);
        config.setAutomationConsentBy(null);
        config.setAutomationVerifiedAt(null);
        config.setAutomationIdentitySeenAt(null);
        config.setWriteRoleArn(null);
        config.setAutomationDisabledAt(Instant.now());
        config.setAutoscalingEnabled(false);
        clusterConfigRepository.save(config);

        audit.record(user == null ? null : user.getClientId(), accounts.get(0).getId(), clusterName,
                config.getId(), "AUTOMATION_DISABLED", OnboardingAuditService.ACTOR_USER,
                user == null ? null : user.getId(), "{\"disabled\":true}");

        return ResponseEntity.ok(Map.of(
                "command", commandRenderer.renderReadOnly(clusterName, config.getRegion(), false)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private KubernetesClusterConfig preRegister(CloudAccount owner, String clusterName, String region) {
        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setCloudAccountId(owner.getId());
        cfg.setClusterName(clusterName);
        cfg.setRegion(region);
        cfg.setKubeconfigYaml("");
        cfg.setDataSourceMode("agent");
        cfg.setAgentStatus("pending");
        return clusterConfigRepository.save(cfg);
    }

    private void lockAccountCluster(Long accountPk, String clusterName) {
        try {
            clusterConfigRepository.lockAccountCluster(accountPk, clusterName);
        } catch (RuntimeException e) {
            logger.debug("advisory lock skipped ({})", e.getClass().getSimpleName());
        }
    }

    private List<Map<String, Object>> tokenSummaries(Long configId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String purpose : List.of(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY,
                ConnectTokenService.PURPOSE_ENABLE_AUTOMATION)) {
            tokenService.findByClusterAndPurpose(configId, purpose).stream()
                    .max((a, b) -> a.getIssuedAt().compareTo(b.getIssuedAt()))
                    .ifPresent(t -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("purpose", t.getPurpose());
                        m.put("prefix", t.getTokenPrefix());
                        m.put("status", statusOf(t));
                        m.put("issuedAt", t.getIssuedAt().toString());
                        m.put("expiresAt", t.getExpiresAt().toString());
                        m.put("usedAt", t.getUsedAt() == null ? null : t.getUsedAt().toString());
                        out.add(m);
                    });
        }
        return out;
    }

    private static String statusOf(ClusterConnectToken t) {
        if (t.getRevokedAt() != null) {
            return "REVOKED";
        }
        if (t.getUsedAt() != null) {
            return "EXCHANGED";
        }
        if (!t.getExpiresAt().isAfter(Instant.now())) {
            return "EXPIRED";
        }
        return "ISSUED";
    }

    private List<String> parseFeatures(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(OnboardingFeatureCatalog.COST_INSIGHTS);
        }
        List<String> out = new ArrayList<>();
        out.add(OnboardingFeatureCatalog.COST_INSIGHTS);
        for (String f : csv.split(",")) {
            String id = f.trim();
            if (!id.isEmpty() && featureCatalog.isKnown(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> normaliseFeatures(Object raw, String phase) {
        List<String> requested = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                requested.add(String.valueOf(o));
            }
        }
        List<String> out = new ArrayList<>();
        out.add(OnboardingFeatureCatalog.COST_INSIGHTS); // always on
        for (String id : requested) {
            if (featureCatalog.isKnown(id) && !out.contains(id)) {
                out.add(id);
            } else if (!featureCatalog.isKnown(id)) {
                throw new OnboardingException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Unknown feature " + id);
            }
        }
        if ("AUTOMATION".equals(phase)
                && !out.contains(OnboardingFeatureCatalog.NODE_CONSOLIDATION)
                && !out.contains(OnboardingFeatureCatalog.WORKLOAD_AUTOSCALING)) {
            throw new OnboardingException("INVALID_REQUEST", HttpStatus.BAD_REQUEST,
                    "Automation needs at least one phase-2 feature");
        }
        return out;
    }

    private List<Map<String, Object>> featureCatalogView(List<String> selected, boolean rw, boolean pfCompute) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OnboardingFeatureCatalog.FeatureInfo info : featureCatalog.all()) {
            OnboardingFeatureCatalog.Availability av = featureCatalog.availability(info.id(), rw, pfCompute);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", info.id());
            m.put("label", info.label());
            // OQ-1: suppress the placeholder tier until a commercial model is decided. The catalog
            // plumbing stays intact (info.tier() is still here); only its DISPLAY is gated. When
            // xamops.onboarding.show-tiers is true, the real tier name rides along for the UI.
            if (showTiers) {
                m.put("tier", info.tier().name());
            }
            m.put("phase", info.phase());
            m.put("available", av.available());
            m.put("reason", av.reason());
            m.put("selected", selected.contains(info.id()));
            out.add(m);
        }
        return out;
    }

    private String nodeScalerOf(List<CloudAccount> accounts, String clusterName) {
        return findConfig(accounts, clusterName).map(KubernetesClusterConfig::getNodeScaler).orElse(null);
    }
}
