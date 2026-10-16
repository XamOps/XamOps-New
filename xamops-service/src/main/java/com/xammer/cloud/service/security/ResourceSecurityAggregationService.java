package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.compliance.ComplianceReport;
import com.xammer.cloud.dto.security.ResourceSecurityFindings;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ProductError;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ResourceSecurityFinding;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ResourceSecurityGroup;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.SecurityDeepLink;
import com.xammer.cloud.domain.UpwindPostureFinding;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import com.xammer.cloud.repository.UpwindPostureFindingRepository;
import com.xammer.cloud.repository.UpwindVulnerabilityFindingRepository;
import com.xammer.cloud.service.PerformanceInsightsService;
import com.xammer.cloud.service.ResourceIdResolver;
import com.xammer.cloud.service.ResourceIdResolver.CanonicalResource;
import com.xammer.cloud.service.compliance.ComplianceOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads what every SecOps surface knows about one resource and returns it grouped by product.
 *
 * <h2>Entitlement boundary</h2>
 * This service is only ever reached through {@code CurInsightsController}'s
 * {@code /resource-security} handler, which runs {@code denyIfNotEntitled(accountId)} on the request
 * thread <b>before</b> {@code insightCache.getOrCompute} invokes {@link #aggregate}. So the supplier
 * only ever runs for an account the caller holds; it resolves that one {@link CloudAccount} once and
 * reuses it across all four product reads, each of which needs the account in a different form (the
 * internal row id for Security Center and ComplianceOps, the 12-digit AWS id for Performance and for
 * XamSecure/Upwind, whose rows are keyed by that 12-digit id and the resource ARN).
 *
 * <h2>Per-product isolation</h2>
 * Each product read is wrapped in its own try/catch. A product that throws becomes an
 * {@code errors[]} entry rather than failing the whole response — one surface being down must not
 * blank the others. The Performance read in particular can trigger a cold multi-region scan; its
 * try/catch is also what time-boxes that into an error entry instead of a 500.
 *
 * <h2>Best-effort ComplianceOps match</h2>
 * ComplianceOps resources are keyed by Prowler's {@code resource_uid}, which equals a CUR ARN for
 * most AWS resource types but is not guaranteed to in general. The match here is therefore
 * best-effort: a resource type whose {@code resource_uid} is not the ARN simply does not focus, which
 * is not a bug.
 */
@Service
public class ResourceSecurityAggregationService {

    private static final Logger logger =
            LoggerFactory.getLogger(ResourceSecurityAggregationService.class);

    /** The widest identifier either inventory column holds; mirrors the resolver's own bound. */
    public static final int MAX_RESOURCE_ID_CHARS = 2048;

    /**
     * ComplianceOps resource rows do not depend on the trend window, but {@code getReport} requires
     * one; the ComplianceOps page's own default is 90 days.
     */
    private static final int COMPLIANCE_TREND_DAYS = 90;

    static final String PRODUCT_SECURITY_CENTER = "SECURITY_CENTER";
    static final String PRODUCT_COMPLIANCEOPS = "COMPLIANCEOPS";
    static final String PRODUCT_PERFORMANCE = "PERFORMANCE";
    static final String PRODUCT_XAMSECURE = "XAMSECURE";

    private final CloudAccountRepository cloudAccountRepository;
    private final ResourceIdResolver idResolver;
    private final CloudSecurityFindingRepository securityFindingRepository;
    private final ComplianceOpsService complianceOpsService;
    private final PerformanceInsightsService performanceInsightsService;
    private final UpwindPostureFindingRepository upwindPostureRepository;
    private final UpwindVulnerabilityFindingRepository upwindVulnerabilityRepository;

    public ResourceSecurityAggregationService(CloudAccountRepository cloudAccountRepository,
                                              ResourceIdResolver idResolver,
                                              CloudSecurityFindingRepository securityFindingRepository,
                                              ComplianceOpsService complianceOpsService,
                                              PerformanceInsightsService performanceInsightsService,
                                              UpwindPostureFindingRepository upwindPostureRepository,
                                              UpwindVulnerabilityFindingRepository upwindVulnerabilityRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.idResolver = idResolver;
        this.securityFindingRepository = securityFindingRepository;
        this.complianceOpsService = complianceOpsService;
        this.performanceInsightsService = performanceInsightsService;
        this.upwindPostureRepository = upwindPostureRepository;
        this.upwindVulnerabilityRepository = upwindVulnerabilityRepository;
    }

    /**
     * Aggregates the four product reads for one resource into a normalised, grouped response.
     *
     * @param accountId  the 12-digit AWS account id (already entitled by the controller prologue)
     * @param resourceId the CUR identifier of the resource, as the drawer holds it
     * @param region     the resource's region, when the report gave one; currently advisory only
     */
    public ResponseEntity<Object> aggregate(String accountId, String resourceId, String region) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            // Mirror computeResourceChange: an unknown account is a 404 with an empty body.
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        ResourceSecurityFindings body = new ResourceSecurityFindings();
        body.setResourceId(resourceId);

        // Resolve the id once; derive the two forms the product reads need.
        String arnCandidate = resourceId != null && resourceId.startsWith("arn:") ? resourceId : null;
        String shortIdCandidate = resourceId;
        boolean matched = false;
        try {
            Map<String, CanonicalResource> resolved =
                    idResolver.resolve(account.getId(), List.of(resourceId));
            CanonicalResource canonical = resolved.get(resourceId);
            if (canonical != null) {
                matched = true;
                shortIdCandidate = canonical.resourceId();
                // A short-id resource (S3/EC2/EBS) has no ARN in hand, but the inventory row does.
                // Carry it so the ARN-keyed products (Security Center, ComplianceOps) can match.
                // When the raw id already is an ARN we keep it verbatim; the inventory ARN stays a
                // fallback used only for short ids, and is null-safe when the row has none.
                if (arnCandidate == null && canonical.arn() != null) {
                    arnCandidate = canonical.arn();
                }
            }
        } catch (RuntimeException e) {
            logger.warn("[resource-security] Could not resolve resource {} for account {}: {}",
                    resourceId, accountId, e.getMessage());
        }
        body.setMatched(matched);

        List<ResourceSecurityGroup> groups = new ArrayList<>();
        List<ProductError> errors = new ArrayList<>();

        groups.add(securityCenterGroup(account, arnCandidate, errors));
        groups.add(complianceOpsGroup(account, arnCandidate, errors));
        groups.add(performanceGroup(account, shortIdCandidate, errors));
        groups.add(xamSecureGroup(account, arnCandidate, shortIdCandidate, errors));

        // Drop empty groups; the client renders only the products that had something to say.
        groups.removeIf(group -> group == null || group.getFindings().isEmpty());
        body.setGroups(groups);
        body.setErrors(errors);
        return ResponseEntity.ok(body);
    }

    /**
     * Security Center findings — the live {@code cloud_security_finding} rows keyed by ARN.
     *
     * <p>Only reachable when the resource id is an ARN: the repository matches on
     * {@code resource_arn}, so a short id cannot select anything. The deep link carries the same
     * {@code arnCandidate} the rows were read under — the one id every ARN-keyed row already holds.
     */
    private ResourceSecurityGroup securityCenterGroup(CloudAccount account, String arnCandidate,
                                                      List<ProductError> errors) {
        ResourceSecurityGroup group = new ResourceSecurityGroup(
                PRODUCT_SECURITY_CENTER, "Security Center", "/security", new ArrayList<>());
        if (arnCandidate == null) {
            return group;
        }
        try {
            List<CloudSecurityFinding> rows =
                    securityFindingRepository.findByResource(account.getId(), arnCandidate);
            for (CloudSecurityFinding row : rows) {
                SecurityDeepLink link = new SecurityDeepLink("/security",
                        Map.of("resource", arnCandidate));
                group.getFindings().add(new ResourceSecurityFinding(
                        row.getTitle(),
                        SecuritySeverity.normalize(row.getSeverity()),
                        row.getResourceType(),
                        link));
            }
            sortBySeverity(group);
        } catch (RuntimeException e) {
            logger.warn("[resource-security] Security Center read failed for account {}: {}",
                    account.getAwsAccountId(), e.getMessage());
            errors.add(new ProductError(PRODUCT_SECURITY_CENTER,
                    "Security Center findings could not be read."));
        }
        return group;
    }

    /**
     * ComplianceOps controls whose failing/undecided resources include this one.
     *
     * <p>{@code getReport} takes the internal {@code cloud_account} id as a String. Each matched
     * {@code ResourceRef} deep-links on the <b>exact matched {@code uid}</b> — not a separately
     * derived candidate — so the destination page's {@code resources[].uid === resource} filter
     * re-matches byte-for-byte.
     */
    private ResourceSecurityGroup complianceOpsGroup(CloudAccount account, String arnCandidate,
                                                     List<ProductError> errors) {
        ResourceSecurityGroup group = new ResourceSecurityGroup(
                PRODUCT_COMPLIANCEOPS, "ComplianceOps", "/complianceops", new ArrayList<>());
        if (arnCandidate == null) {
            return group;
        }
        try {
            ComplianceReport report =
                    complianceOpsService.getReport(String.valueOf(account.getId()), COMPLIANCE_TREND_DAYS);
            List<ComplianceReport.ControlView> controls =
                    report.getControls() == null ? List.of() : report.getControls();
            for (ComplianceReport.ControlView control : controls) {
                List<ComplianceReport.ResourceRef> resources =
                        control.getResources() == null ? List.of() : control.getResources();
                for (ComplianceReport.ResourceRef ref : resources) {
                    if (ref.getUid() != null && ref.getUid().equals(arnCandidate)) {
                        SecurityDeepLink link = new SecurityDeepLink("/complianceops",
                                Map.of("resource", ref.getUid()));
                        group.getFindings().add(new ResourceSecurityFinding(
                                control.getTitle(),
                                SecuritySeverity.normalize(control.getSeverity()),
                                control.getStatus(),
                                link));
                    }
                }
            }
            sortBySeverity(group);
        } catch (RuntimeException e) {
            logger.warn("[resource-security] ComplianceOps read failed for account {}: {}",
                    account.getAwsAccountId(), e.getMessage());
            errors.add(new ProductError(PRODUCT_COMPLIANCEOPS,
                    "ComplianceOps controls could not be read."));
        }
        return group;
    }

    /**
     * Performance insights whose resource id is this resource's short id.
     *
     * <p>{@code getInsights} takes the 12-digit AWS id. The severity is the enum
     * {@code InsightSeverity}, so it is normalised via {@code getSeverity().name()} — never passed
     * as an enum to the String-typed normaliser.
     */
    private ResourceSecurityGroup performanceGroup(CloudAccount account, String shortIdCandidate,
                                                   List<ProductError> errors) {
        ResourceSecurityGroup group = new ResourceSecurityGroup(
                PRODUCT_PERFORMANCE, "Performance Insights", "/performance", new ArrayList<>());
        if (shortIdCandidate == null || shortIdCandidate.isBlank()) {
            return group;
        }
        try {
            List<PerformanceInsightDto> insights =
                    performanceInsightsService.getInsights(account.getAwsAccountId(), null, false);
            if (insights != null) {
                for (PerformanceInsightDto insight : insights) {
                    if (shortIdCandidate.equals(insight.getResourceId())) {
                        SecurityDeepLink link = new SecurityDeepLink("/performance",
                                Map.of("resource", shortIdCandidate));
                        String severity = insight.getSeverity() == null
                                ? SecuritySeverity.MEDIUM
                                : SecuritySeverity.normalize(insight.getSeverity().name());
                        String title = insight.getInsight() != null
                                ? insight.getInsight() : insight.getDescription();
                        group.getFindings().add(new ResourceSecurityFinding(
                                title, severity, insight.getDescription(), link));
                    }
                }
            }
            sortBySeverity(group);
        } catch (RuntimeException e) {
            logger.warn("[resource-security] Performance read failed for account {}: {}",
                    account.getAwsAccountId(), e.getMessage());
            errors.add(new ProductError(PRODUCT_PERFORMANCE,
                    "Performance insights could not be read."));
        }
        return group;
    }

    /**
     * XamSecure (Upwind) findings — the posture and vulnerability rows this resource carries.
     *
     * <p>Upwind rows are keyed by the <b>12-digit AWS account id</b> ({@code cloud_account_id}) and
     * the resource's <b>full ARN</b> ({@code resource_id}), so this is ARN-primary like Security
     * Center: the {@code arnCandidate} is tried first and {@code shortIdCandidate} only as a
     * secondary key (for rows that may store a short id). When neither is present the group
     * contributes nothing.
     *
     * <p>Posture emits one row per matched failing finding. Vulnerability emits at most ONE summary
     * row — {@code "<N> vulnerabilities (<c> critical, <h> high)"} at the worst severity present —
     * built from a per-resource {@code (severity, count)} breakdown so the ~727K-row vulnerability
     * table is never loaded. Both deep-link to the XamSecure page's own tabs with no query, because
     * those tabs are server-filtered and carry no read-only {@code ?resource=} param to thread.
     */
    private ResourceSecurityGroup xamSecureGroup(CloudAccount account, String arnCandidate,
                                                 String shortIdCandidate, List<ProductError> errors) {
        ResourceSecurityGroup group = new ResourceSecurityGroup(
                PRODUCT_XAMSECURE, "XamSecure", "/xamsecure", new ArrayList<>());

        // Build the ARN-primary match keys: the ARN first, then the short id as a secondary key
        // when it is a distinct, non-blank value. No ARN and no usable short id → nothing to read.
        List<String> matchKeys = new ArrayList<>();
        if (arnCandidate != null) {
            matchKeys.add(arnCandidate);
        }
        if (shortIdCandidate != null && !shortIdCandidate.isBlank()
                && !shortIdCandidate.equals(arnCandidate)) {
            matchKeys.add(shortIdCandidate);
        }
        if (matchKeys.isEmpty()) {
            return group;
        }

        try {
            List<String> accountIds = List.of(account.getAwsAccountId());

            // POSTURE: one finding per matched failing row, de-duplicated by Upwind id across keys.
            Set<String> seenPostureIds = new LinkedHashSet<>();
            for (String key : matchKeys) {
                List<UpwindPostureFinding> rows =
                        upwindPostureRepository.findFailingByResource(accountIds, key);
                for (UpwindPostureFinding row : rows) {
                    if (row.getUpwindId() != null && !seenPostureIds.add(row.getUpwindId())) {
                        continue;
                    }
                    String title = row.getCheckTitle() != null ? row.getCheckTitle() : row.getTitle();
                    SecurityDeepLink link = new SecurityDeepLink("/xamsecure/posture", Map.of());
                    group.getFindings().add(new ResourceSecurityFinding(
                            title,
                            SecuritySeverity.normalize(row.getSeverity()),
                            row.getResourceType(),
                            link));
                }
            }

            // VULNERABILITY: merge the per-resource severity breakdown across keys, summing counts
            // per normalised severity. Never loads an entity — only (severity, count) pairs.
            Map<String, Long> bySeverity = new LinkedHashMap<>();
            for (String key : matchKeys) {
                List<Object[]> breakdown =
                        upwindVulnerabilityRepository.countOpenBySeverityForResource(accountIds, key);
                for (Object[] pair : breakdown) {
                    String severity = SecuritySeverity.normalize((String) pair[0]);
                    long count = ((Number) pair[1]).longValue();
                    bySeverity.merge(severity, count, Long::sum);
                }
            }
            long total = bySeverity.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                long critical = bySeverity.getOrDefault(SecuritySeverity.CRITICAL, 0L);
                long high = bySeverity.getOrDefault(SecuritySeverity.HIGH, 0L);
                String title = String.format("%d vulnerabilities (%d critical, %d high)",
                        total, critical, high);
                SecurityDeepLink link = new SecurityDeepLink("/xamsecure/vulnerabilities", Map.of());
                group.getFindings().add(new ResourceSecurityFinding(
                        title,
                        SecuritySeverity.worstOf(bySeverity.keySet()),
                        null,
                        link));
            }

            sortBySeverity(group);
        } catch (RuntimeException e) {
            logger.warn("[resource-security] XamSecure read failed for account {}: {}",
                    account.getAwsAccountId(), e.getMessage());
            errors.add(new ProductError(PRODUCT_XAMSECURE,
                    "XamSecure findings could not be read."));
        }
        return group;
    }

    /** Most-severe-first within a group, using the one severity ranking. */
    private void sortBySeverity(ResourceSecurityGroup group) {
        if (group.getFindings().size() > 1) {
            group.getFindings().sort(
                    Comparator.comparingInt(f -> SecuritySeverity.rank(f.getSeverity())));
        }
    }

}
