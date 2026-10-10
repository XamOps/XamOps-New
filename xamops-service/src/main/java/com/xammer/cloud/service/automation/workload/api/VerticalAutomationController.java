package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.RefusalDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The workload-autoscaling (vertical rightsizing) read endpoint.
 *
 * <h2>Path</h2>
 *
 * {@code /api/eks/automation/vertical/**}, chosen for three reasons, all verified:
 *
 * <ul>
 *   <li>It matches the CloudK8s UI design's provisional contract exactly. That contract is a hard
 *       dependency of a parallel workflow, so the published contract has to match it rather than
 *       diverge.</li>
 *   <li>It is covered by {@code SecurityConfig}'s {@code anyRequest().authenticated()} and its
 *       {@code defaultAuthenticationEntryPointFor(401, /api/**)}, so an unauthenticated call returns
 *       <strong>401</strong> rather than a redirect — with <strong>no edit to
 *       {@code SecurityConfig}</strong>. {@code VerticalAutomationSecurityCoverageTest} asserts that
 *       no {@code permitAll} rule covers this prefix, so a future config change cannot silently
 *       publish it.</li>
 *   <li>It is <strong>not</strong> under {@code /api/xamops/k8s/…}, whose demo-router catch-all would
 *       intercept it in demo mode, and <strong>not</strong> under {@code /api/eks/autoscaling/**},
 *       which is the Go agent's policy surface owned by another controller.</li>
 * </ul>
 *
 * <h2>Tenancy</h2>
 *
 * {@code accountId} is the <strong>12-digit AWS account number</strong> and is resolved only through
 * {@link CloudAccountAccessService#requireAwsAccess}, which enforces entitlement. It is never used as
 * a primary key. The cluster config is then found by the entitled account's own id first and, for
 * legacy rows, by that same entitled row's AWS account number — so a config whose
 * {@code cloud_account_id} holds an AWS number belonging to a <em>different</em> tenant is
 * unreachable rather than mis-served.
 */
@RestController
@RequestMapping("/api/eks/automation/vertical")
public class VerticalAutomationController {

    private static final Logger logger = LoggerFactory.getLogger(VerticalAutomationController.class);

    /** Kubernetes cluster-name shape, also what EKS accepts. */
    private static final Pattern CLUSTER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9\\-_]{0,99}$");
    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern REGION = Pattern.compile("^[a-z0-9\\-]{3,32}$");

    private final CloudAccountAccessService accessService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final VerticalAutomationReadService readService;

    public VerticalAutomationController(CloudAccountAccessService accessService,
                                        KubernetesClusterConfigRepository clusterConfigRepository,
                                        VerticalAutomationReadService readService) {
        this.accessService = accessService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.readService = readService;
    }

    /**
     * Engine status, gate reasons and recommendations for one cluster.
     *
     * <p>Read-only. With the master switch off — the default — it makes no AWS call and no cluster
     * call, and {@code workloads} is empty.
     */
    @GetMapping("/{cluster}")
    public VerticalEngineResponse read(@AuthenticationPrincipal ClientUserDetails principal,
                                       @PathVariable("cluster") String cluster,
                                       @RequestParam("accountId") String accountId,
                                       @RequestParam("region") String region) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");

        // Throws AccessDeniedException -> 403 if the caller is not entitled to this AWS account.
        CloudAccount account = accessService.requireAwsAccess(principal, accountId);
        KubernetesClusterConfig config = requireClusterConfig(account, cluster);

        ClusterTarget target = new ClusterTarget(
                account.getClient() == null ? null : account.getClient().getId(),
                config.getId(), account.getAwsAccountId(), cluster, region);

        return readService.read(target, config);
    }

    /**
     * Finds the cluster config for an account the caller is already entitled to.
     *
     * <p>Two lookups, because {@code kubernetes_cluster_config.cloud_account_id} holds the
     * {@code cloud_account} primary key on modern rows and the AWS account number on legacy ones
     * (audit B4). <strong>Both candidate values are taken from the entitled row</strong>, so neither
     * lookup can reach another tenant's cluster. The platform-wide fix for that column is a separate
     * follow-up; this endpoint just refuses to guess.
     */
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
            throw new IllegalArgumentException(field);
        }
    }

    // ------------------------------------------------------------------ refusals

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<RefusalDto> onInvalidRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.getMessage(),
                "The " + e.getMessage() + " parameter is missing or malformed."));
    }

    /**
     * A missing {@code accountId} or {@code region} is the caller's mistake, so it is a 400 naming
     * the parameter.
     *
     * <p>Handled here rather than left to {@code GlobalExceptionHandler}, whose
     * {@code @ExceptionHandler(Exception.class)} catch-all would otherwise turn it into a
     * {@code 500 K8S_INTERNAL} — telling the caller the server is broken when the request was. A
     * controller-local handler wins over the advice, which is the precedent this codebase already
     * uses for typed refusals.
     */
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

    /**
     * Cluster-access failures carry one of two typed kinds, both of which are shared reason codes
     * with their own user-facing copy and remedy in the UI.
     */
    @ExceptionHandler(ClusterAccessException.class)
    ResponseEntity<RefusalDto> onClusterAccess(ClusterAccessException e) {
        logger.info("Vertical automation refused: {}", e.kind());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(RefusalDto.of("K8S_" + e.reasonCode(), e.getMessage()));
    }

    @ExceptionHandler(UsageUnavailableException.class)
    ResponseEntity<RefusalDto> onUsageUnavailable(UsageUnavailableException e) {
        logger.info("Vertical automation has no usage: {}", e.kind());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(RefusalDto.of("K8S_" + e.reasonCode(), e.getMessage()));
    }
}
