package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.RefusalDto;
import com.xammer.cloud.service.automation.workload.api.dto.SparklinesDto;
import com.xammer.cloud.service.automation.workload.api.dto.UsageSeriesDto;
import com.xammer.cloud.service.automation.workload.usage.PromQl;
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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The read-only usage history endpoints behind the Automation charts (FEAT-004).
 *
 * <ul>
 *   <li>{@code GET /api/eks/automation/usage/{cluster}?accountId&region&scope&resource&window
 *       [&namespace&kind&name][&from&to&step]} — CPU or memory over a window for the cluster, every
 *       namespace or one workload ({@link UsageSeriesDto}).</li>
 *   <li>{@code GET /api/eks/automation/usage/{cluster}/sparklines?accountId&region&resource&window=72h}
 *       — one series pair per analysed workload ({@link SparklinesDto}).</li>
 * </ul>
 *
 * <p>GET only: nothing here can change a cluster. The path sits beside {@code /vertical/{cluster}},
 * on {@code SecurityConfig}'s authenticated catch-all (an unauthenticated call is a 401), outside the
 * demo router's {@code /api/xamops/k8s/**} and the agent's {@code /api/eks/autoscaling/**}.
 *
 * <h2>Tenancy and validation, as endpoint 1</h2>
 *
 * Every parameter is checked before any lookup ({@code 400 K8S_INVALID_REQUEST} with {@code field},
 * logged at DEBUG); then {@code accountId} — the 12-digit AWS account number — is resolved only
 * through {@link CloudAccountAccessService#requireAwsAccess} (403), the cluster config by the entitled
 * account's own id and then its AWS number for legacy rows (404 {@code K8S_CLUSTER_NOT_REGISTERED}),
 * and the target is the canonical {@code client.id} plus {@code kubernetes_cluster_config.id}.
 */
@RestController
@RequestMapping("/api/eks/automation/usage")
public class AutomationUsageController {

    private static final Logger logger = LoggerFactory.getLogger(AutomationUsageController.class);

    private static final Pattern CLUSTER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9\\-_]{0,99}$");
    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern REGION = Pattern.compile("^[a-z0-9\\-]{3,32}$");
    private static final Pattern K8S_NAME = Pattern.compile("^[a-z0-9]([a-z0-9.\\-]{0,251}[a-z0-9])?$");
    private static final Pattern K8S_KIND = Pattern.compile("^[A-Za-z]{1,32}$");
    /** The kinds the engine analyses, and so the only kinds with a selector to read history by. */
    private static final Set<String> WORKLOAD_KINDS = Set.of("Deployment", "StatefulSet");

    private final CloudAccountAccessService accessService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AutomationUsageService usageService;

    public AutomationUsageController(CloudAccountAccessService accessService,
                                     KubernetesClusterConfigRepository clusterConfigRepository,
                                     AutomationUsageService usageService) {
        this.accessService = accessService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.usageService = usageService;
    }

    /**
     * Usage over a window.
     *
     * <p>{@code scope}: {@code cluster | namespace | workload}. {@code namespace}, {@code kind} and
     * {@code name} are required with {@code workload} and rejected otherwise. {@code window}:
     * {@code 24h | 72h | 168h}, or {@code custom} with {@code from}, {@code to} (ISO-8601) and
     * {@code step} for {@code scope=workload} only.
     */
    @GetMapping("/{cluster}")
    public UsageSeriesDto usage(@AuthenticationPrincipal ClientUserDetails principal,
                                @PathVariable("cluster") String cluster,
                                @RequestParam("accountId") String accountId,
                                @RequestParam("region") String region,
                                @RequestParam("scope") String scope,
                                @RequestParam("resource") String resource,
                                @RequestParam("window") String window,
                                @RequestParam(value = "namespace", required = false) String namespace,
                                @RequestParam(value = "kind", required = false) String kind,
                                @RequestParam(value = "name", required = false) String name,
                                @RequestParam(value = "from", required = false) String from,
                                @RequestParam(value = "to", required = false) String to,
                                @RequestParam(value = "step", required = false) String step) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");
        AutomationUsageService.Scope parsedScope = scope(scope);
        PromQl.Resource parsedResource = resource(resource);
        WorkloadRef workload = workload(parsedScope, namespace, kind, name);
        UsageWindow parsedWindow = window(parsedScope, window, from, to, step);

        ClusterTarget target = target(principal, accountId.trim(), cluster.trim(), region.trim());
        return usageService.usage(target,
                new AutomationUsageService.UsageRequest(parsedScope, parsedResource, parsedWindow, workload));
    }

    /** Busiest-pod use and request per analysed workload, 72 h at 1 h. */
    @GetMapping("/{cluster}/sparklines")
    public SparklinesDto sparklines(@AuthenticationPrincipal ClientUserDetails principal,
                                    @PathVariable("cluster") String cluster,
                                    @RequestParam("accountId") String accountId,
                                    @RequestParam("region") String region,
                                    @RequestParam("resource") String resource,
                                    @RequestParam("window") String window) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");
        PromQl.Resource parsedResource = resource(resource);
        if (!AutomationUsageService.SPARKLINE_WINDOW.equals(window)) {
            throw new InvalidParameterException("window", "The sparklines have one window: 72h.");
        }

        ClusterTarget target = target(principal, accountId.trim(), cluster.trim(), region.trim());
        return usageService.sparklines(target, parsedResource);
    }

    // ------------------------------------------------------------------ parameters

    private static AutomationUsageService.Scope scope(String value) {
        return switch (value) {
            case "cluster" -> AutomationUsageService.Scope.CLUSTER;
            case "namespace" -> AutomationUsageService.Scope.NAMESPACE;
            case "workload" -> AutomationUsageService.Scope.WORKLOAD;
            default -> throw new InvalidParameterException("scope", "scope must be cluster, namespace or workload.");
        };
    }

    private static PromQl.Resource resource(String value) {
        return switch (value) {
            case "cpu" -> PromQl.Resource.CPU;
            case "memory" -> PromQl.Resource.MEMORY;
            default -> throw new InvalidParameterException("resource", "resource must be cpu or memory.");
        };
    }

    private static WorkloadRef workload(AutomationUsageService.Scope scope, String namespace, String kind,
                                        String name) {
        if (scope != AutomationUsageService.Scope.WORKLOAD) {
            rejectPresent("namespace", namespace);
            rejectPresent("kind", kind);
            rejectPresent("name", name);
            return null;
        }
        requirePresent("namespace", namespace, "scope=workload");
        requirePresent("kind", kind, "scope=workload");
        requirePresent("name", name, "scope=workload");
        requireShape(namespace, K8S_NAME, "namespace");
        requireShape(kind, K8S_KIND, "kind");
        requireShape(name, K8S_NAME, "name");
        if (!WORKLOAD_KINDS.contains(kind.trim())) {
            throw new InvalidParameterException("kind", "kind must be Deployment or StatefulSet.");
        }
        return WorkloadRef.of(namespace.trim(), kind.trim(), name.trim());
    }

    private static void rejectPresent(String field, String value) {
        if (value != null) {
            throw new InvalidParameterException(field,
                    "namespace, kind and name are only accepted with scope=workload.");
        }
    }

    private static UsageWindow window(AutomationUsageService.Scope scope, String window, String from, String to,
                                      String step) {
        if (!UsageWindow.CUSTOM.equals(window)) {
            Optional<UsageWindow> fixed = UsageWindow.fixed(window);
            if (fixed.isEmpty()) {
                throw new InvalidParameterException("window", "window must be 24h, 72h, 168h or custom.");
            }
            for (String[] parameter : new String[][] {{"from", from}, {"to", to}, {"step", step}}) {
                if (parameter[1] != null) {
                    throw new InvalidParameterException(parameter[0],
                            "from, to and step are only accepted with window=custom.");
                }
            }
            return fixed.get();
        }
        if (scope != AutomationUsageService.Scope.WORKLOAD) {
            throw new InvalidParameterException("window", "A custom window is only available with scope=workload.");
        }
        Instant start = instant("from", from);
        Instant end = instant("to", to);
        int stepSeconds = step(step);
        if (!start.isBefore(end)) {
            throw new InvalidParameterException("from", "from must be before to.");
        }
        if (Duration.between(start, end).compareTo(UsageWindow.MAX_CUSTOM_SPAN) > 0) {
            throw new InvalidParameterException("from", "A custom window can span at most 7 days.");
        }
        if (end.isAfter(Instant.now().plus(UsageWindow.FUTURE_TOLERANCE))) {
            throw new InvalidParameterException("to", "to cannot be in the future.");
        }
        long points = UsageWindow.points(start, end, stepSeconds);
        if (points > UsageWindow.MAX_POINTS) {
            throw new InvalidParameterException("step", "That window at a " + stepSeconds + "s step is " + points
                    + " points per series, and at most " + UsageWindow.MAX_POINTS + " are allowed. Use a"
                    + " larger step.");
        }
        return UsageWindow.custom(start, end, stepSeconds);
    }

    /** An ISO-8601 instant ({@code Z} or an offset), to the whole second. */
    private static Instant instant(String field, String value) {
        requirePresent(field, value, "window=custom");
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed).truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant().truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException notAnOffsetTime) {
                throw new InvalidParameterException(field,
                        field + " must be an ISO-8601 time, like 2026-10-14T10:00:00Z.");
            }
        }
    }

    private static int step(String value) {
        requirePresent("step", value, "window=custom");
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new InvalidParameterException("step", "step must be 60, 300, 1800 or 3600.");
        }
        if (!UsageWindow.CUSTOM_STEPS.contains(parsed)) {
            throw new InvalidParameterException("step", "step must be 60, 300, 1800 or 3600.");
        }
        return parsed;
    }

    private static void requirePresent(String field, String value, String with) {
        if (value == null || value.isBlank()) {
            throw new InvalidParameterException(field, field + " is required with " + with + ".");
        }
    }

    private static void requireShape(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            throw new ParameterShapeException(field);
        }
    }

    // ------------------------------------------------------------------ tenancy

    private ClusterTarget target(ClientUserDetails principal, String accountId, String cluster, String region) {
        // Throws AccessDeniedException -> 403 if the caller is not entitled to this AWS account.
        CloudAccount account = accessService.requireAwsAccess(principal, accountId);
        KubernetesClusterConfig config = requireClusterConfig(account, cluster);
        return new ClusterTarget(account.getClient() == null ? null : account.getClient().getId(),
                config.getId(), account.getAwsAccountId(), cluster, region);
    }

    /** As {@code VerticalAutomationController}: both candidate keys come off the entitled row. */
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

    // ------------------------------------------------------------------ refusals

    @ExceptionHandler(ParameterShapeException.class)
    ResponseEntity<RefusalDto> onMalformed(ParameterShapeException e) {
        logger.debug("Usage request refused: field {} is malformed", e.field());
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.field(),
                "The " + e.field() + " parameter is missing or malformed."));
    }

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<RefusalDto> onInvalid(InvalidParameterException e) {
        logger.debug("Usage request refused: field {}: {}", e.field(), e.getMessage());
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.field(), e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<RefusalDto> onMissingParameter(MissingServletRequestParameterException e) {
        logger.debug("Usage request refused: parameter {} is missing", e.getParameterName());
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

    /** A workload the cluster does not have: {@code 404 K8S_INVALID_REQUEST} naming the field. */
    @ExceptionHandler(UnknownTargetException.class)
    ResponseEntity<RefusalDto> onUnknownTarget(UnknownTargetException e) {
        logger.debug("Usage request for an unknown target: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(RefusalDto.invalidRequest(e.field(), e.getMessage()));
    }

    /**
     * A parameter that is well-formed but not acceptable here, with the rule in words. Kept apart from
     * {@link ParameterShapeException}, whose copy can only say "missing or malformed".
     */
    static final class InvalidParameterException extends RuntimeException {

        private final String field;

        InvalidParameterException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
