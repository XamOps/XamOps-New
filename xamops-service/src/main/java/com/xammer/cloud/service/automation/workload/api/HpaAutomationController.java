package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaHistoryDto;
import com.xammer.cloud.service.automation.workload.api.dto.RefusalDto;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The HPA (horizontal) apply-by-click endpoints — mirroring {@link VerticalAutomationController}.
 *
 * <h2>Path</h2>
 *
 * {@code /api/eks/automation/hpa/**}, chosen for the same three verified reasons the vertical path
 * is: it is covered by {@code SecurityConfig}'s authenticated catch-all (so an unauthenticated call
 * is a {@code 401}, no config edit), it is not under the demo-router catch-all, and it is not the Go
 * agent's autoscaling surface.
 *
 * <h2>Two endpoints, two opt-ins</h2>
 *
 * <ul>
 *   <li>{@code POST .../{cluster}/{namespace}/{name}/apply} — tune an existing HPA's bounds / CPU
 *       target (D1/D2/D3a).</li>
 *   <li>{@code POST .../{cluster}/{namespace}/{kind}/{name}/create} — D4, a separate verb and a
 *       separate opt-in because creating an HPA where none exists is a different, higher-trust act
 *       than tuning one. It is <strong>not implemented in this task</strong> and answers a typed
 *       {@code CONTROLLER_NOT_READY} refusal rather than a bare 403, so the UI can disable the
 *       control and say why.</li>
 *   <li>{@code GET .../{cluster}/{namespace}/{name}/history} — read-only replica, bound and CPU
 *       history of one HPA from kube-state-metrics (FEAT-004), gated by the horizontal master
 *       switch rather than by an apply opt-in, because it changes nothing.</li>
 * </ul>
 *
 * <h2>Tenancy</h2>
 *
 * Identical to the vertical controller: {@code accountId} is the 12-digit AWS account number,
 * resolved only through {@link CloudAccountAccessService#requireAwsAccess}, and the cluster config is
 * found by the entitled account's own id first, then by its AWS number for legacy rows.
 */
@RestController
@RequestMapping("/api/eks/automation/hpa")
public class HpaAutomationController {

    private static final Logger logger = LoggerFactory.getLogger(HpaAutomationController.class);

    private static final Pattern CLUSTER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9\\-_]{0,99}$");
    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern REGION = Pattern.compile("^[a-z0-9\\-]{3,32}$");
    private static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([a-z0-9.\\-]{0,251}[a-z0-9])?$");
    private static final Pattern K8S_KIND = Pattern.compile("^[A-Za-z]{1,32}$");

    private final CloudAccountAccessService accessService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final HpaApplyService applyService;
    private final HpaHistoryService historyService;

    public HpaAutomationController(CloudAccountAccessService accessService,
                                   KubernetesClusterConfigRepository clusterConfigRepository,
                                   HpaApplyService applyService,
                                   HpaHistoryService historyService) {
        this.accessService = accessService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.applyService = applyService;
        this.historyService = historyService;
    }

    /**
     * One HPA's history (FEAT-004): replicas against its bounds, CPU against its target, time at its
     * maximum and replica changes, from kube-state-metrics. Read-only; see {@link HpaHistoryService}.
     *
     * <p>{@code name} is the HPA's own name. {@code window}: {@code 24h | 72h | 168h}. An HPA that
     * neither Prometheus nor the cluster knows is {@code 404 K8S_INVALID_REQUEST} with
     * {@code field: name}; a source failure is a {@code 200} with {@code unavailable}.
     */
    @GetMapping("/{cluster}/{namespace}/{name}/history")
    public HpaHistoryDto history(@AuthenticationPrincipal ClientUserDetails principal,
                                 @PathVariable("cluster") String cluster,
                                 @PathVariable("namespace") String namespace,
                                 @PathVariable("name") String name,
                                 @RequestParam("accountId") String accountId,
                                 @RequestParam("region") String region,
                                 @RequestParam("window") String window) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(namespace, K8S_NAME, "namespace");
        requireShape(name, K8S_NAME, "name");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");
        UsageWindow parsedWindow = UsageWindow.fixed(window)
                .orElseThrow(() -> new ParameterShapeException("window"));

        CloudAccount account = accessService.requireAwsAccess(principal, accountId.trim());
        KubernetesClusterConfig config = requireClusterConfig(account, cluster.trim());
        ClusterTarget target = targetFor(account, config, cluster.trim(), region.trim());

        return historyService.history(target, namespace.trim(), name.trim(), parsedWindow);
    }

    /**
     * Apply one HPA bounds/target change to one workload.
     *
     * <p>Answers {@code 202} with the contract's {@code {correlationId, state, dryRun}} plus an
     * additive {@code applied} carrying the bounds the patch set. Every refusal is a typed body with
     * the contract's {@code code}; see {@link HpaApplyService} for the gate order.
     */
    @PostMapping("/{cluster}/{namespace}/{name}/apply")
    public ResponseEntity<HpaApplyResponseDto> apply(
            @AuthenticationPrincipal ClientUserDetails principal,
            @PathVariable("cluster") String cluster,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name,
            @RequestParam("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestBody(required = false) HpaApplyRequestDto body) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(namespace, K8S_NAME, "namespace");
        requireShape(name, K8S_NAME, "name");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");

        CloudAccount account = accessService.requireAwsAccess(principal, accountId);
        KubernetesClusterConfig config = requireClusterConfig(account, cluster);
        ClusterTarget target = targetFor(account, config, cluster, region);

        HpaApplyResponseDto response = applyService.apply(principal, account, target,
                WorkloadRef.of(namespace, "Deployment", name), body);
        return ResponseEntity.accepted().body(response);
    }

    /**
     * D4 — recommend-created HPA for a workload that has none.
     *
     * <p>A separate RBAC verb and a separate opt-in from {@link #apply}. The engine only ever
     * <em>recommends</em> a new HPA (never auto-creates), and the create write is a higher-trust
     * capability deferred past this task. It therefore answers a typed {@code CONTROLLER_NOT_READY}
     * refusal — not an empty 403 — so the UI can disable the control and name the reason.
     */
    @PostMapping("/{cluster}/{namespace}/{kind}/{name}/create")
    public ResponseEntity<RefusalDto> create(
            @AuthenticationPrincipal ClientUserDetails principal,
            @PathVariable("cluster") String cluster,
            @PathVariable("namespace") String namespace,
            @PathVariable("kind") String kind,
            @PathVariable("name") String name,
            @RequestParam("accountId") String accountId,
            @RequestParam("region") String region) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(namespace, K8S_NAME, "namespace");
        requireShape(kind, K8S_KIND, "kind");
        requireShape(name, K8S_NAME, "name");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");

        // Entitlement and cluster resolution are enforced exactly as the apply path does, so this
        // endpoint can never become an oracle for which clusters exist under another tenant.
        CloudAccount account = accessService.requireAwsAccess(principal, accountId);
        requireClusterConfig(account, cluster);

        logger.info("HPA create refused for {}/{}/{}: creating a new HPA is not available in this"
                + " release", namespace, kind, name);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(RefusalDto.gate(
                ReasonCode.CONTROLLER_NOT_READY,
                "Creating a new HPA for " + namespace + "/" + name + " is a recommend-only decision"
                        + " in this release: XamOps proposes the bounds and target but does not"
                        + " create the HPA automatically."));
    }

    private ClusterTarget targetFor(CloudAccount account, KubernetesClusterConfig config,
                                    String cluster, String region) {
        return new ClusterTarget(
                account.getClient() == null ? null : account.getClient().getId(),
                config.getId(), account.getAwsAccountId(), cluster, region);
    }

    private KubernetesClusterConfig requireClusterConfig(CloudAccount account, String cluster) {
        Optional<KubernetesClusterConfig> byPrimaryKey = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(account.getId(), cluster);
        if (byPrimaryKey.isPresent()) {
            return byPrimaryKey.get();
        }
        Long legacyKey = numericAwsAccountId(account);
        if (legacyKey != null) {
            Optional<KubernetesClusterConfig> byAwsNumber = clusterConfigRepository
                    .findByCloudAccountIdAndClusterName(legacyKey, cluster);
            if (byAwsNumber.isPresent()) {
                return byAwsNumber.get();
            }
        }
        logger.info("No registered cluster config for cluster {} under an entitled account", cluster);
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, RefusalDto.CLUSTER_NOT_REGISTERED);
    }

    private static Long numericAwsAccountId(CloudAccount account) {
        String awsAccountId = account.getAwsAccountId();
        if (awsAccountId == null || !AWS_ACCOUNT_ID.matcher(awsAccountId.trim()).matches()) {
            return null;
        }
        try {
            return Long.parseLong(awsAccountId.trim());
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    private static void requireShape(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            throw new ParameterShapeException(field);
        }
    }

    // ------------------------------------------------------------------ refusals

    @ExceptionHandler(ParameterShapeException.class)
    ResponseEntity<RefusalDto> onInvalidRequest(ParameterShapeException e) {
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.field(),
                "The " + e.field() + " parameter is missing or malformed."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<RefusalDto> onMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.getParameterName(),
                "The " + e.getParameterName() + " parameter is required."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<RefusalDto> onNotRegistered(ResponseStatusException e) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RefusalDto.of(
                    RefusalDto.CLUSTER_NOT_REGISTERED,
                    "This cluster isn't registered with XamOps yet. Install the XamOps agent first."));
        }
        return ResponseEntity.status(e.getStatusCode())
                .body(RefusalDto.of(RefusalDto.INTERNAL, "Something went wrong."));
    }

    /** An HPA neither Prometheus nor the cluster knows: {@code 404 K8S_INVALID_REQUEST}, field named. */
    @ExceptionHandler(UnknownTargetException.class)
    ResponseEntity<RefusalDto> onUnknownTarget(UnknownTargetException e) {
        logger.debug("HPA history for an unknown target: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(RefusalDto.invalidRequest(e.field(), e.getMessage()));
    }

    @ExceptionHandler(ClusterAccessException.class)
    ResponseEntity<RefusalDto> onClusterAccess(ClusterAccessException e) {
        logger.info("HPA automation refused: {}", e.kind());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(RefusalDto.of("K8S_" + e.reasonCode(), e.getMessage()));
    }

    @ExceptionHandler(UsageUnavailableException.class)
    ResponseEntity<RefusalDto> onUsageUnavailable(UsageUnavailableException e) {
        logger.info("HPA automation has no usage: {}", e.kind());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(RefusalDto.of("K8S_" + e.reasonCode(), e.getMessage()));
    }

    /**
     * Every apply gate and every re-validation refusal, each carrying its own contract {@code code}
     * and — on a {@code 400} — the offending {@code field}. Reuses the vertical path's typed
     * exception so the two controllers map refusals identically.
     */
    @ExceptionHandler(VerticalApplyService.ApplyRefusedException.class)
    ResponseEntity<RefusalDto> onApplyRefused(VerticalApplyService.ApplyRefusedException e) {
        logger.info("HPA apply refused: {}{}", e.code(),
                e.field() == null ? "" : " (field " + e.field() + ")");
        return ResponseEntity.status(e.status()).body(e.body());
    }
}
