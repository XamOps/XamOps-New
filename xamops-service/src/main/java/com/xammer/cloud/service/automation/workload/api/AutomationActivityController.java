package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository.ActivityCursor;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.ActivityPageDto;
import com.xammer.cloud.service.automation.workload.api.dto.ActivitySummaryDto;
import com.xammer.cloud.service.automation.workload.api.dto.RefusalDto;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The activity feed: {@code GET /api/eks/automation/activity/{cluster}} — read-only, one tenant's
 * registered cluster, newest first, filterable by feature and result, cursor-paged — and its per-day
 * summary, {@code GET /api/eks/automation/activity/{cluster}/summary}.
 *
 * <h2>Path and tenancy</h2>
 *
 * Under {@code /api/eks/automation/}, for the reasons {@link VerticalAutomationController} gives:
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()} covers it with no edit (asserted by
 * {@code VerticalAutomationSecurityCoverageTest}), and it is neither under the demo router's
 * {@code /api/xamops/k8s/} catch-all nor the agent's {@code /api/eks/autoscaling/**}. Tenancy is
 * the read endpoint's exactly: {@code accountId} is the 12-digit AWS number, resolved only through
 * {@link CloudAccountAccessService#requireAwsAccess}; the cluster config is then found from that
 * entitled row; and the rows are read by {@code client_id} and {@code cluster_config_id}, never by
 * {@code cloud_account_id}.
 *
 * <h2>Parameters</h2>
 *
 * {@code feature} (csv of {@code VERTICAL,HPA}), {@code result} (csv of
 * {@code APPLIED,PUT_BACK,REFUSED,TEST_RUN,FAILED}), {@code q} (part of a name, at most 100
 * characters), {@code from} / {@code to} (ISO-8601, at most 90 days apart), {@code limit}
 * (1–200, default 50) and {@code cursor} (the previous page's {@code nextCursor}). The summary takes
 * {@code days} (1–30, default 7) and {@code feature}. A malformed one is a
 * {@code 400 K8S_INVALID_REQUEST} naming it.
 */
@RestController
@RequestMapping("/api/eks/automation/activity")
public class AutomationActivityController {

    private static final Logger logger = LoggerFactory.getLogger(AutomationActivityController.class);

    private static final Pattern CLUSTER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9\\-_]{0,99}$");
    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern REGION = Pattern.compile("^[a-z0-9\\-]{3,32}$");
    private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,4}$");
    private static final Pattern DAYS = Pattern.compile("^[0-9]{1,2}$");

    private final CloudAccountAccessService accessService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AutomationActivityService activityService;
    private final Clock clock;

    @Autowired
    public AutomationActivityController(CloudAccountAccessService accessService,
                                        KubernetesClusterConfigRepository clusterConfigRepository,
                                        AutomationActivityService activityService) {
        this(accessService, clusterConfigRepository, activityService, Clock.systemUTC());
    }

    AutomationActivityController(CloudAccountAccessService accessService,
                                 KubernetesClusterConfigRepository clusterConfigRepository,
                                 AutomationActivityService activityService, Clock clock) {
        this.accessService = accessService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.activityService = activityService;
        this.clock = clock;
    }

    @GetMapping("/{cluster}")
    public ActivityPageDto read(@AuthenticationPrincipal ClientUserDetails principal,
                                @PathVariable("cluster") String cluster,
                                @RequestParam("accountId") String accountId,
                                @RequestParam("region") String region,
                                @RequestParam(value = "feature", required = false) String feature,
                                @RequestParam(value = "result", required = false) String result,
                                @RequestParam(value = "q", required = false) String q,
                                @RequestParam(value = "from", required = false) String from,
                                @RequestParam(value = "to", required = false) String to,
                                @RequestParam(value = "limit", required = false) String limit,
                                @RequestParam(value = "cursor", required = false) String cursor) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");
        AutomationActivityService.Query query = new AutomationActivityService.Query(
                csv(feature, AutomationActivityService.Feature.class, "feature"),
                csv(result, AutomationActivityService.Result.class, "result"),
                nameQuery(q), instant(from, "from"), instant(to, "to"), limit(limit), cursor(cursor));
        requireRange(query.from(), query.to());

        return activityService.page(target(principal, accountId, cluster, region), query);
    }

    /**
     * Changes per India Standard Time day over the last {@code days} days, by result and feature,
     * counted from the same rows and with the same mapping as the feed.
     */
    @GetMapping("/{cluster}/summary")
    public ActivitySummaryDto summary(@AuthenticationPrincipal ClientUserDetails principal,
                                      @PathVariable("cluster") String cluster,
                                      @RequestParam("accountId") String accountId,
                                      @RequestParam("region") String region,
                                      @RequestParam(value = "days", required = false) String days,
                                      @RequestParam(value = "feature", required = false) String feature) {
        requireShape(cluster, CLUSTER_NAME, "cluster");
        requireShape(accountId, AWS_ACCOUNT_ID, "accountId");
        requireShape(region, REGION, "region");
        int parsedDays = days(days);
        Set<AutomationActivityService.Feature> features =
                csv(feature, AutomationActivityService.Feature.class, "feature");

        return activityService.summary(target(principal, accountId, cluster, region), features, parsedDays);
    }

    /** Tenancy exactly as endpoint 1's: the entitled account, then its registered cluster. */
    private ClusterTarget target(ClientUserDetails principal, String accountId, String cluster, String region) {
        // Throws AccessDeniedException -> 403 K8S_NO_ACCOUNT_ACCESS if the caller is not entitled.
        CloudAccount account = accessService.requireAwsAccess(principal, accountId);
        KubernetesClusterConfig config = requireClusterConfig(account, cluster);

        return new ClusterTarget(
                account.getClient() == null ? null : account.getClient().getId(),
                config.getId(), account.getAwsAccountId(), cluster, region);
    }

    // ---------------------------------------------------------------- parameters

    private static <E extends Enum<E>> Set<E> csv(String value, Class<E> type, String field) {
        Set<E> parsed = EnumSet.noneOf(type);
        if (value == null || value.isBlank()) {
            return parsed;
        }
        for (String part : value.split(",", -1)) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            try {
                parsed.add(Enum.valueOf(type, name));
            } catch (IllegalArgumentException unknown) {
                throw new ParameterShapeException(field);
            }
        }
        return parsed;
    }

    private static String nameQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.length() > AutomationActivityService.MAX_QUERY_LENGTH
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new ParameterShapeException("q");
        }
        return trimmed;
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeException notAnInstant) {
            try {
                return OffsetDateTime.parse(value.trim()).toInstant();
            } catch (DateTimeException notAnOffsetTime) {
                throw new ParameterShapeException(field);
            }
        }
    }

    private static int limit(String value) {
        if (value == null || value.isBlank()) {
            return AutomationActivityService.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(value.trim()).matches()) {
            throw new ParameterShapeException("limit");
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 1 || parsed > AutomationActivityService.MAX_LIMIT) {
            throw new ParameterShapeException("limit");
        }
        return parsed;
    }

    private static int days(String value) {
        if (value == null || value.isBlank()) {
            return AutomationActivityService.DEFAULT_SUMMARY_DAYS;
        }
        if (!DAYS.matcher(value.trim()).matches()) {
            throw new ParameterShapeException("days");
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 1 || parsed > AutomationActivityService.MAX_SUMMARY_DAYS) {
            throw new ParameterShapeException("days");
        }
        return parsed;
    }

    private static ActivityCursor cursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AutomationActivityService.decodeCursor(value.trim());
        } catch (IllegalArgumentException unreadable) {
            throw new ParameterShapeException("cursor");
        }
    }

    /** {@code from} not after {@code to}, and at most 90 days before it (or before now, with no {@code to}). */
    private void requireRange(Instant from, Instant to) {
        if (from == null) {
            return;
        }
        Instant end = to == null ? clock.instant() : to;
        if (to != null && from.isAfter(to)) {
            throw new InvalidRangeException("from", "from must not be after to.");
        }
        if (Duration.between(from, end).compareTo(AutomationActivityService.MAX_RANGE) > 0) {
            throw new InvalidRangeException(to == null ? "from" : "to",
                    "from and to may be at most " + AutomationActivityService.MAX_RANGE.toDays()
                            + " days apart.");
        }
    }

    private static void requireShape(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            throw new ParameterShapeException(field);
        }
    }

    /** The read endpoint's lookup: the entitled account's own id first, then its legacy AWS number. */
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

    /** A range that is well formed but not allowed, with the reason in words. */
    static final class InvalidRangeException extends RuntimeException {

        private final String field;

        InvalidRangeException(String field, String message) {
            super(message);
            this.field = field;
        }
    }

    // ---------------------------------------------------------------- refusals

    @ExceptionHandler(ParameterShapeException.class)
    ResponseEntity<RefusalDto> onInvalidRequest(ParameterShapeException e) {
        logger.debug("Activity feed request refused: parameter {} is malformed", e.field());
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.field(),
                "The " + e.field() + " parameter is missing or malformed."));
    }

    @ExceptionHandler(InvalidRangeException.class)
    ResponseEntity<RefusalDto> onInvalidRange(InvalidRangeException e) {
        logger.debug("Activity feed request refused: {}", e.getMessage());
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.field, e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<RefusalDto> onMissingParameter(MissingServletRequestParameterException e) {
        logger.debug("Activity feed request refused: parameter {} is missing", e.getParameterName());
        return ResponseEntity.badRequest().body(RefusalDto.invalidRequest(e.getParameterName(),
                "The " + e.getParameterName() + " parameter is required."));
    }

    /**
     * An account the caller is not entitled to. The same message for "unknown" and "not yours", so
     * the endpoint is not an oracle for which account ids exist.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<RefusalDto> onNoAccountAccess(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(RefusalDto.of(RefusalDto.NO_ACCOUNT_ACCESS,
                "You don't have access to this account."));
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

    /** The activity log could not be read: a server fault, reported as one. */
    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    ResponseEntity<RefusalDto> onStoreUnavailable(RuntimeException e) {
        logger.error("Could not read the activity log: {}", e.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RefusalDto.of(RefusalDto.INTERNAL,
                "XamOps couldn't read its activity log just now."));
    }
}
