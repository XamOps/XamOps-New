package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.domain.type.OidShapeGuard;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.dto.CloudsitterRecommendationDto;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsForMetricRequest;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@Service
public class CloudsitterService {
    private static final Logger logger = LoggerFactory.getLogger(CloudsitterService.class);

    private final CloudsitterPolicyRepository policyRepo;
    private final CloudsitterAssignmentRepository assignmentRepo;
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository accountRepo;
    private final ObjectMapper objectMapper;
    private final TenantService tenantService;
    private final AwsPricingService pricingService;
    private final EmailService emailService;
    private final CloudsitterEmailBuilder emailBuilder;

    @Autowired
    @Lazy
    private CloudsitterService self;

    @Autowired
    private CloudsitterAuditLogRepository auditLogRepo;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${forecast.service.url:http://localhost:5002}")
    private String forecastServiceUrl;

    public CloudsitterService(CloudsitterPolicyRepository policyRepo,
            CloudsitterAssignmentRepository assignmentRepo,
            AwsClientProvider awsClientProvider,
            CloudAccountRepository accountRepo,
            ObjectMapper objectMapper,
            TenantService tenantService,
            AwsPricingService pricingService,
            EmailService emailService,
            CloudsitterEmailBuilder emailBuilder) {
        this.policyRepo = policyRepo;
        this.assignmentRepo = assignmentRepo;
        this.awsClientProvider = awsClientProvider;
        this.accountRepo = accountRepo;
        this.objectMapper = objectMapper;
        this.tenantService = tenantService;
        this.pricingService = pricingService;
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
    }

    // --- METHODS FOR UI ---

    @Transactional(readOnly = true)
    public List<CloudsitterPolicy> getAllPolicies() {
        return policyRepo.findAll();
    }

    @Transactional(readOnly = true)
    public Map<String, CloudsitterAssignmentDto> getAssignmentsForAccount(String accountId) {
        logger.debug("Fetching CloudSitter assignments for account: {}", accountId);

        List<CloudsitterAssignment> assignments = assignmentRepo.findAll().stream()
                .filter(a -> a.getAccountId().equals(accountId) && a.isActive())
                .collect(Collectors.toList());

        Map<String, String> instanceTypes = fetchInstanceTypes(accountId, assignments);

        return assignments.stream()
                .collect(Collectors.toMap(
                        CloudsitterAssignment::getResourceId,
                        // A null type means the lookup failed; convertToDto reports the
                        // saving as unknown rather than pricing the instance as a t3.micro.
                        a -> convertToDto(a, instanceTypes.get(a.getResourceId())),
                        (existing, replacement) -> existing));
    }

    @Transactional(readOnly = true)
    public double getTotalActiveSavings(String accountId) {
        if (accountId == null || accountId.isBlank()) return 0.0;
        try {
            List<CloudsitterAssignment> assignments = assignmentRepo.findAll().stream()
                    .filter(a -> accountId.equals(a.getAccountId()) && a.isActive())
                    .collect(Collectors.toList());
            Map<String, String> instanceTypes = fetchInstanceTypes(accountId, assignments);
            // Skip assignments whose instance type could not be resolved. Defaulting them
            // to t3.micro priced an m5.24xlarge at a fraction of its real cost with no
            // warning, understating the reported savings.
            return assignments.stream()
                    .filter(a -> instanceTypes.get(a.getResourceId()) != null)
                    .mapToDouble(a -> calculateMonthlySavings(
                            a.getPolicy(),
                            instanceTypes.get(a.getResourceId()),
                            a.getRegion()))
                    .sum();
        } catch (Exception e) {
            logger.warn("CloudSitter: Failed to compute active savings for account {}: {}", accountId, e.getMessage());
            return 0.0;
        }
    }

    private Map<String, String> fetchInstanceTypes(String accountId, List<CloudsitterAssignment> assignments) {
        Map<String, String> types = new java.util.HashMap<>();
        if (assignments.isEmpty())
            return types;

        try {
            CloudAccount account = accountRepo.findByAwsAccountId(accountId)
                    .stream().findFirst().orElse(null);

            if (account == null) {
                logger.warn("Account {} not found, cannot fetch instance types", accountId);
                return types;
            }

            Map<String, List<CloudsitterAssignment>> byRegion = assignments.stream()
                    .collect(Collectors.groupingBy(CloudsitterAssignment::getRegion));

            for (Map.Entry<String, List<CloudsitterAssignment>> entry : byRegion.entrySet()) {
                String region = entry.getKey();
                List<String> instanceIds = entry.getValue().stream()
                        .map(CloudsitterAssignment::getResourceId)
                        .collect(Collectors.toList());

                try {
                    Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
                    DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                            .instanceIds(instanceIds)
                            .build();

                    DescribeInstancesResponse response = ec2.describeInstances(request);

                    for (Reservation reservation : response.reservations()) {
                        for (Instance instance : reservation.instances()) {
                            types.put(instance.instanceId(), instance.instanceType().toString());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to fetch instance details for region {}: {}", region, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching instance types for account {}", accountId, e);
        }

        return types;
    }

    /**
     * @param instanceType resolved EC2 instance type, or null when the lookup failed. A null
     *                     yields an unknown saving rather than a figure derived from a
     *                     fabricated default type.
     */
    private CloudsitterAssignmentDto convertToDto(CloudsitterAssignment assignment, String instanceType) {
        CloudsitterAssignmentDto dto = new CloudsitterAssignmentDto();
        dto.setId(assignment.getId());
        dto.setResourceId(assignment.getResourceId());
        dto.setAccountId(assignment.getAccountId());
        dto.setRegion(assignment.getRegion());
        dto.setPolicy(assignment.getPolicy());
        dto.setActive(assignment.isActive());
        dto.setInstanceType(instanceType != null ? instanceType : "unknown");

        if (instanceType == null) {
            dto.setMonthlySavings(0.0);
            dto.setSavingsUnknown(true);
            return dto;
        }

        dto.setMonthlySavings(calculateMonthlySavings(assignment.getPolicy(), instanceType, assignment.getRegion()));
        return dto;
    }

    /**
     * Monthly saving from parking an instance on a schedule.
     *
     * <p>Only compute stops when an EC2 instance is stopped. Attached EBS volumes continue
     * to bill in full, and since February 2024 so does any associated public IPv4 address.
     * The previous calculation was compute-only, so it overstated the saving on every
     * instance with meaningful storage attached.
     */
    private double calculateMonthlySavings(CloudsitterPolicy policy, String instanceType, String region) {
        try {
            int stoppedHoursPerMonth = calculateStoppedHoursPerMonth(policy);
            AwsPricingService.RateQuote quote =
                    pricingService.calculateMonthlySavingsQuote(instanceType, region, stoppedHoursPerMonth);

            if (!quote.hasRate()) {
                logger.warn("CloudSitter: no usable rate for instance type {} in {} (policy '{}'), "
                                + "so its saving is reported as 0 rather than guessed: {}",
                        instanceType, region, policy.getName(), quote.note());
                return 0.0;
            }

            // A saving built on a 2024 table entry or a size-pattern guess is a projection, not a
            // figure to promise. It is still shown — a rough number beats an empty schedule page —
            // but the provenance is logged so a systematically estimated account is visible rather
            // than looking identical to a Price-List-backed one.
            if (!quote.isAuthoritative()) {
                logger.warn("CloudSitter: saving for {} in {} is derived from a non-authoritative rate "
                                + "({}): {}",
                        instanceType, region, quote.source(), quote.note());
            }

            // Compute is the only component that stops billing; nothing is netted off here
            // because storage cost is the same whether the instance runs or not. Rounding is
            // applied once, at the end.
            double roundedSavings = Math.round(quote.usdPerHour() * 100.0) / 100.0;

            logger.debug("CloudSitter monthly savings — instance {} in {}, policy '{}', "
                            + "stopped {} hrs/month, compute saving ${} (source {})",
                    instanceType, region, policy.getName(), stoppedHoursPerMonth, roundedSavings,
                    quote.source());

            return roundedSavings;
        } catch (Exception e) {
            logger.error("Failed to calculate savings for policy {}", policy.getId(), e);
            return 0.0;
        }
    }

    private int calculateStoppedHoursPerMonth(CloudsitterPolicy policy) {
        try {
            Map<String, List<Integer>> schedule = objectMapper.readValue(
                    policy.getScheduleJson(),
                    new TypeReference<Map<String, List<Integer>>>() {
                    });

            int totalRunningHoursPerWeek = 0;
            for (List<Integer> hours : schedule.values()) {
                totalRunningHoursPerWeek += hours.size();
            }

            int totalHoursPerWeek = 7 * 24;
            int stoppedHoursPerWeek = totalHoursPerWeek - totalRunningHoursPerWeek;
            // Scale by the true average month (730 h / 168 h per week) and round rather than
            // truncate. The previous `(int)(stoppedHoursPerWeek * 4.33)` discarded the
            // fractional hours on every policy.
            int stoppedHoursPerMonth = (int) Math.round(
                    stoppedHoursPerWeek * (HOURS_PER_MONTH / (double) totalHoursPerWeek));
            
            logger.debug("CloudSitter Savings Calculation - Policy '{}' (ID: {}): " +
                    "Running={} hrs/week, Stopped={} hrs/week, Stopped/month={} hrs",
                    policy.getName(), policy.getId(), 
                    totalRunningHoursPerWeek, stoppedHoursPerWeek, stoppedHoursPerMonth);
            
            return stoppedHoursPerMonth;
        } catch (Exception e) {
            logger.error("Failed to parse schedule for policy {}", policy.getId(), e);
            return 0;
        }
    }

    // ✅ UPDATED: Returns the created policy object
    @Transactional
    public CloudsitterPolicy createPolicy(CloudsitterPolicyDto dto) {
        OidShapeGuard.requireNotOidShaped("scheduleConfig", dto.getScheduleConfig());
        CloudsitterPolicy policy = new CloudsitterPolicy();
        policy.setName(dto.getName());
        policy.setType(dto.getType());
        policy.setTimeZone(dto.getTimeZone());
        policy.setScheduleJson(dto.getScheduleConfig());
        policy.setNotificationsEnabled(dto.isNotificationsEnabled());
        policy.setNotificationEmail(dto.getNotificationEmail());
        return policyRepo.save(policy);
    }

    // ✅ NEW: Update existing policy
    @Transactional
    public CloudsitterPolicy updatePolicy(Long id, CloudsitterPolicyDto dto) {
        OidShapeGuard.requireNotOidShaped("scheduleConfig", dto.getScheduleConfig());
        CloudsitterPolicy policy = policyRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Policy not found: " + id));

        policy.setName(dto.getName());
        policy.setTimeZone(dto.getTimeZone());
        policy.setScheduleJson(dto.getScheduleConfig());
        policy.setNotificationsEnabled(dto.isNotificationsEnabled());
        policy.setNotificationEmail(dto.getNotificationEmail());

        return policyRepo.save(policy);
    }

    // ✅ NEW: Delete policy (and remove from assignments)
    @Transactional
    public void deletePolicy(Long id) {
        CloudsitterPolicy policy = policyRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Policy not found: " + id));

        // Set assignments to null (remove policy) before deleting
        // Or you could delete the assignment entirely. Setting policy to null usually
        // implies "No Policy"
        List<CloudsitterAssignment> assignments = assignmentRepo.findAll(); // Optimization: Create findByPolicyId in
                                                                            // repo
        for (CloudsitterAssignment assignment : assignments) {
            if (assignment.getPolicy() != null && assignment.getPolicy().getId().equals(id)) {
                assignmentRepo.delete(assignment);
            }
        }

        policyRepo.delete(policy);
    }

    /**
     * Assigns a schedule policy to a set of instances.
     *
     * <p>Applies the same safety checks the recommender uses before an instance can be put
     * on a stop/start schedule. Without them this endpoint was a way to schedule shutdowns
     * for ASG members, Kubernetes nodes, and production-tagged instances that
     * {@code getScheduleRecommendation} explicitly refuses to recommend — the 10-minute
     * enforcer then stops whatever is assigned.
     *
     * @return per-instance outcome, so the caller can report which instances were skipped
     *         and why instead of silently assigning a subset
     */
    @Transactional
    public Map<String, String> assignPolicyToInstances(CloudsitterAssignmentRequest request) {
        CloudsitterPolicy policy = policyRepo.findById(request.getPolicyId())
                .orElseThrow(() -> new RuntimeException("Policy not found: " + request.getPolicyId()));

        CloudAccount account = accountRepo.findByAwsAccountId(request.getAccountId())
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + request.getAccountId()));

        Map<String, String> outcomes = new LinkedHashMap<>();

        for (String instanceId : request.getInstanceIds()) {
            String rejection = checkSchedulingEligibility(account, instanceId, request.getRegion());
            if (rejection != null) {
                logger.warn("CloudSitter: refusing to assign policy {} to instance {}: {}",
                        policy.getName(), instanceId, rejection);
                outcomes.put(instanceId, "SKIPPED: " + rejection);
                continue;
            }

            CloudsitterAssignment assignment = assignmentRepo.findByResourceId(instanceId)
                    .orElse(new CloudsitterAssignment());

            assignment.setResourceId(instanceId);
            assignment.setAccountId(request.getAccountId());
            assignment.setRegion(request.getRegion());
            assignment.setPolicy(policy);
            assignment.setActive(true);

            assignmentRepo.save(assignment);
            outcomes.put(instanceId, "ASSIGNED");
            logger.info("Assigned policy {} to instance {}", policy.getName(), instanceId);
        }
        return outcomes;
    }

    /**
     * Determines whether an instance may be placed on a stop/start schedule.
     *
     * <p>Mirrors the pre-checks in {@link #getScheduleRecommendation}: Auto Scaling Group
     * members are replaced rather than resized, Kubernetes nodes are cluster
     * infrastructure, and production-tagged instances should not be stopped automatically.
     *
     * @return a human-readable reason to refuse, or null when the instance is eligible
     */
    private String checkSchedulingEligibility(CloudAccount account, String instanceId, String region) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            DescribeInstancesResponse resp = ec2.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build());
            Optional<Instance> found = resp.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .findFirst();
            if (found.isEmpty()) {
                return "instance not found in region " + region;
            }
            Instance inst = found.get();

            if (inst.tags().stream().anyMatch(t -> "aws:autoscaling:groupName".equals(t.key()))) {
                return "instance is managed by an Auto Scaling Group, which would replace it after a stop";
            }

            boolean isK8sNode = inst.tags().stream().anyMatch(t -> {
                String key = t.key().toLowerCase();
                String value = t.value() != null ? t.value().toLowerCase() : "";
                return key.contains("kubernetes")
                        || key.startsWith("k8s.io/")
                        || key.startsWith("kubernetes.io/")
                        || key.equals("kubernetescluster")
                        || key.equals("k8s-node")
                        || value.contains("kubernetes")
                        || value.contains("k8s-node")
                        || value.contains("kube-node");
            });
            if (isK8sNode) {
                return "instance is a Kubernetes node and is required by its cluster";
            }

            if (isProductionTagged(inst)) {
                return "instance is tagged as production";
            }
            return null;
        } catch (Exception e) {
            // Fail closed: if eligibility cannot be established, do not schedule shutdowns.
            logger.warn("CloudSitter: could not verify eligibility of {}: {}", instanceId, e.getMessage());
            return "eligibility could not be verified: " + e.getMessage();
        }
    }

    /**
     * True when an instance's tags mark it as production.
     *
     * <p>Broader than an exact {@code environment=production} match: values such as
     * {@code Live}, {@code prod-web} and keys like {@code tier} were previously missed, so
     * production instances passed the check.
     */
    /**
     * Reasons a running instance must not be stopped, evaluated from the instance itself.
     *
     * <p>Complements {@link #checkSchedulingEligibility}, which runs once at assignment and
     * needs an API call. This runs on every enforcement pass using the description already
     * fetched, so an instance that changes character after enrolment stops being stopped.
     *
     * <p>Adds two cases the assignment-time check does not cover, both of which lose data
     * rather than merely being inconvenient:
     * <ul>
     *   <li>An instance-store-backed instance cannot be stopped at all.</li>
     *   <li>Instance-store (ephemeral NVMe) volumes are wiped on stop, so any data on them is
     *       gone with no warning.</li>
     * </ul>
     *
     * @return the reason to refuse, or null when the instance may be stopped
     */
    private String describeStopIneligibility(Instance inst) {
        if (inst.tags() != null
                && inst.tags().stream().anyMatch(t -> "aws:autoscaling:groupName".equals(t.key()))) {
            return "it has joined an Auto Scaling Group since being scheduled, which would replace "
                    + "it after a stop";
        }
        if (isProductionTagged(inst)) {
            return "it is now tagged as production";
        }
        if (inst.tags() != null && inst.tags().stream().anyMatch(t -> {
            String key = t.key() == null ? "" : t.key().toLowerCase();
            return key.startsWith("aws:elasticmapreduce")
                    || key.contains("kubernetes")
                    || key.startsWith("k8s.io/")
                    || key.startsWith("kubernetes.io/");
        })) {
            return "it is managed by EMR or Kubernetes and is required by its cluster";
        }
        // Instance-store-backed roots cannot be stopped; the API rejects the call.
        if (inst.rootDeviceTypeAsString() != null
                && inst.rootDeviceTypeAsString().contains("instance-store")) {
            return "its root device is instance-store, which cannot be stopped";
        }
        // Stopping wipes instance-store (ephemeral NVMe) volumes. A running instance's
        // blockDeviceMappings only lists EBS volumes, so the instance type is the available
        // signal: these families always ship local NVMe storage.
        if (hasInstanceStoreVolumes(inst.instanceTypeAsString())) {
            return "its instance type (" + inst.instanceTypeAsString() + ") includes instance-store "
                    + "volumes whose contents are destroyed by a stop";
        }
        // A Spot instance's capacity is not guaranteed to be available again on restart.
        if (inst.instanceLifecycleAsString() != null
                && inst.instanceLifecycleAsString().contains("spot")) {
            return "it is a Spot instance and its capacity may not be reclaimable after a stop";
        }
        return null;
    }

    /**
     * Instance families whose local NVMe storage is wiped by a stop.
     *
     * <p>Two groups: storage-optimised families that always include instance store, and any
     * family carrying the {@code d} modifier ({@code m5d}, {@code c6gd}, {@code r5dn}).
     * Package-private so the classification can be tested directly.
     *
     * @param instanceType e.g. {@code m5d.xlarge}; null or unparseable yields false
     */
    static boolean hasInstanceStoreVolumes(String instanceType) {
        if (instanceType == null || instanceType.isBlank() || !instanceType.contains(".")) {
            return false;
        }
        String family = instanceType.substring(0, instanceType.indexOf('.')).toLowerCase();

        // Storage-optimised families: local NVMe is the whole point of them.
        java.util.Set<String> alwaysInstanceStore = java.util.Set.of(
                "i3", "i3en", "i4i", "i4g", "im4gn", "is4gen",
                "d2", "d3", "d3en", "h1", "x1", "x1e");
        if (alwaysInstanceStore.contains(family)) {
            return true;
        }

        // The "d" modifier after the generation digit denotes local NVMe, e.g. m5d, c6gd, r5dn.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([a-z]+)(\\d+)([a-z]*)$").matcher(family);
        return m.matches() && m.group(3).contains("d");
    }

    // Package-private for testing: this matcher is the last guard before an automated stop.
    boolean isProductionTagged(Instance inst) {
        return inst.tags().stream().anyMatch(t -> {
            String key = t.key() == null ? "" : t.key().toLowerCase();
            String value = t.value() == null ? "" : t.value().toLowerCase();
            boolean envKey = key.equals("environment") || key.equals("env") || key.equals("stage")
                    || key.equals("tier") || key.equals("stack");
            if (!envKey) {
                return false;
            }
            return value.contains("prod") || value.contains("live") || value.equals("prd");
        });
    }

    // --- SCHEDULE RECOMMENDATION ---

    private static final String[] DAY_NAMES = {
        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    };
    private static final double CPU_IDLE_THRESHOLD = 5.0;  // Consider active if CPU >= 5% (was 12%)
    private static final double MEM_IDLE_THRESHOLD = 10.0;  // Consider active if mem >= 10% (was 20%)
    private static final double NETWORK_IDLE_THRESHOLD = 50.0;  // packets/hour (was 100)
    private static final double CPU_MAX_SPIKE_THRESHOLD = 60.0;  // any spike above this keeps hour running
    private static final double DISK_IDLE_THRESHOLD = 100.0;     // combined EBS read+write ops/hour
    private static final int MIN_CONSECUTIVE_IDLE_HOURS = 6;  // Need 6+ consecutive idle hours to recommend stopping (was 3)
    /** AWS bills a 730-hour month. */
    private static final double HOURS_PER_MONTH = 730.0;

    /** Ceiling on DescribeTargetGroups pages followed, as a loop guard. */
    private static final int MAX_ELB_PAGES = 20;

    /**
     * Chooses the CloudWatch namespace for a target group based on its protocol.
     *
     * <p>ALB target groups (HTTP/HTTPS) publish under {@code AWS/ApplicationELB}; NLB target
     * groups (TCP/TLS/UDP) publish under {@code AWS/NetworkELB}. Querying only the former
     * made every NLB-backed instance appear to have no traffic, so it could be recommended
     * for shutdown while actively serving connections.
     */
    private static String namespaceForTargetGroup(String protocol) {
        if (protocol == null) {
            return "AWS/ApplicationELB";
        }
        return switch (protocol.toUpperCase()) {
            case "TCP", "TLS", "UDP", "TCP_UDP" -> "AWS/NetworkELB";
            case "GENEVE" -> "AWS/GatewayELB";
            default -> "AWS/ApplicationELB";
        };
    }

    private static final int MIN_DATA_POINTS_PER_DAY = 12;       // need ≥50% coverage for a reliable estimate

    /**
     * Builds a schedule recommendation using UTC hour buckets.
     *
     * @deprecated the enforcer evaluates schedules in the <em>policy's</em> timezone
     *             ({@code CloudsitterPolicy.getTimeZone()}), so a UTC-bucketed
     *             recommendation adopted into a non-UTC policy is shifted by that zone's
     *             offset — for Asia/Kolkata, by 5h30m, which stops instances during real
     *             business hours. Use
     *             {@link #getScheduleRecommendation(String, String, String, int, String)}
     *             and pass the timezone the resulting policy will use.
     */
    @Deprecated
    public CloudsitterRecommendationDto getScheduleRecommendation(
            String accountId, String instanceId, String region, int lookbackDays) {
        return getScheduleRecommendation(accountId, instanceId, region, lookbackDays,
                ZoneOffset.UTC.getId());
    }

    /**
     * Builds a schedule recommendation with hour buckets computed in {@code timeZoneId}.
     *
     * <p>The returned {@code recommendedSchedule} uses the same day/hour keys as
     * {@code CloudsitterPolicy.scheduleJson}, and the enforcer evaluates those against the
     * policy's timezone. Bucketing here must therefore use the same zone the policy will
     * carry, otherwise the schedule is silently offset.
     *
     * @param timeZoneId IANA zone id the adopting policy will use, e.g. {@code Asia/Kolkata}
     */
    public CloudsitterRecommendationDto getScheduleRecommendation(
            String accountId, String instanceId, String region, int lookbackDays, String timeZoneId) {

        ZoneId bucketZone;
        try {
            bucketZone = ZoneId.of(timeZoneId == null || timeZoneId.isBlank()
                    ? ZoneOffset.UTC.getId() : timeZoneId);
        } catch (Exception e) {
            logger.warn("CloudSitter: invalid timezone '{}', falling back to UTC: {}",
                    timeZoneId, e.getMessage());
            bucketZone = ZoneOffset.UTC;
        }

        CloudAccount account = accountRepo.findByAwsAccountId(accountId)
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        // --- 1. EC2 pre-checks: ASG membership, K8s nodes, production instances, and EIP ---
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        boolean hasEip = false;
        try {
            DescribeInstancesResponse ec2Resp = ec2.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build());
            Optional<Instance> instanceOpt = ec2Resp.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .findFirst();
            if (instanceOpt.isPresent()) {
                Instance inst = instanceOpt.get();
                
                // Check if instance is in an Auto Scaling Group
                boolean isAsg = inst.tags().stream()
                        .anyMatch(t -> "aws:autoscaling:groupName".equals(t.key()));
                if (isAsg) {
                    logger.info("CloudSitter: Skipping recommendation for ASG-managed instance {}", instanceId);
                    return CloudsitterRecommendationDto.skipped("ASG_MANAGED");
                }
                
                // Check if instance is a Kubernetes node (EKS, kops, kubeadm, etc.)
                boolean isK8sNode = inst.tags().stream()
                        .anyMatch(t -> {
                            String key = t.key().toLowerCase();
                            String value = t.value() != null ? t.value().toLowerCase() : "";
                            return key.contains("kubernetes") 
                                || key.startsWith("k8s.io/")
                                || key.startsWith("kubernetes.io/")
                                || key.equals("kubernetescluster")
                                || key.equals("k8s-node")
                                || value.contains("kubernetes")
                                || value.contains("k8s-node")
                                || value.contains("kube-node");
                        });
                if (isK8sNode) {
                    logger.info("CloudSitter: Skipping recommendation for Kubernetes node {}", instanceId);
                    return CloudsitterRecommendationDto.skipped("K8S_NODE");
                }
                
                // Check if instance is tagged as production. Uses the shared, broader matcher:
                // the previous exact-value check missed Env=Live, tier=prod-web and similar.
                if (isProductionTagged(inst)) {
                    logger.info("CloudSitter: Skipping recommendation for production instance {}", instanceId);
                    return CloudsitterRecommendationDto.skipped("PRODUCTION");
                }
                
                // Check for Elastic IP
                hasEip = inst.networkInterfaces().stream()
                        .anyMatch(ni -> ni.association() != null
                                && ni.association().ipOwnerId() != null
                                && !"amazon".equals(ni.association().ipOwnerId()));
            }
        } catch (Exception e) {
            logger.warn("CloudSitter: Could not describe instance {} for pre-checks: {}", instanceId, e.getMessage());
        }

        // --- 1b. CloudWatch Alarms check — active alarm means the instance is misbehaving; don't recommend stopping ---
        CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, region);
        if (checkAlarmsActive(cw, instanceId)) {
            return CloudsitterRecommendationDto.skipped("ACTIVE_ALARM");
        }

        // --- 2. CloudWatch queries ---
        Instant startTime = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        Instant endTime = Instant.now();

        // CPU p95 — average masks bursts; p95 captures workloads that mostly idle but spike
        MetricDataQuery cpuQuery = MetricDataQuery.builder()
                .id("cpu")
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("AWS/EC2").metricName("CPUUtilization")
                                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build()).build())
                        .period(3600).stat("p95").build())
                .returnData(true).build();

        // CPU Maximum — spike guard: if CPU ever hit CPU_MAX_SPIKE_THRESHOLD in a slot, keep running
        MetricDataQuery cpuMaxQuery = MetricDataQuery.builder()
                .id("cpumax")
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("AWS/EC2").metricName("CPUUtilization")
                                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build()).build())
                        .period(3600).stat("Maximum").build())
                .returnData(true).build();

        // Memory — requires CloudWatch Agent (CWAgent namespace)
        MetricDataQuery memQuery = MetricDataQuery.builder()
                .id("mem")
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("CWAgent").metricName("mem_used_percent")
                                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build()).build())
                        .period(3600).stat("Average").build())
                .returnData(true).build();

        // Network — catches web servers with low CPU but live connections
        MetricDataQuery netQuery = MetricDataQuery.builder()
                .id("net")
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("AWS/EC2").metricName("NetworkPacketsIn")
                                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build()).build())
                        .period(3600).stat("Sum").build())
                .returnData(true).build();

        // Disk I/O — EBS read + write combined; catches DB and file servers that show near-zero CPU
        MetricDataQuery diskReadQuery = MetricDataQuery.builder()
                .id("diskr")
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("AWS/EC2").metricName("EBSReadOps")
                                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build()).build())
                        .period(3600).stat("Sum").build())
                .returnData(false).build();
        MetricDataQuery diskWriteQuery = MetricDataQuery.builder()
                .id("diskw")
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace("AWS/EC2").metricName("EBSWriteOps")
                                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build()).build())
                        .period(3600).stat("Sum").build())
                .returnData(false).build();
        MetricDataQuery diskQuery = MetricDataQuery.builder()
                .id("disk").expression("diskr + diskw").returnData(true).build();

        GetMetricDataResponse resp = cw.getMetricData(GetMetricDataRequest.builder()
                .startTime(startTime).endTime(endTime)
                .metricDataQueries(cpuQuery, cpuMaxQuery, memQuery, netQuery, diskReadQuery, diskWriteQuery, diskQuery)
                .build());

        // --- 3. Aggregate into [7 days][24 hours] grids ---
        double[][] cpuSum    = new double[7][24]; int[][] cpuCount    = new int[7][24];
        double[][] cpuMaxVal = new double[7][24]; // running maximum per slot across all weeks
        double[][] memSum    = new double[7][24]; int[][] memCount    = new int[7][24];
        double[][] netSum    = new double[7][24]; int[][] netCount    = new int[7][24];
        double[][] diskSum   = new double[7][24]; int[][] diskCount   = new int[7][24];
        List<Map<String, Object>> cpuRawSeries = new ArrayList<>();

        for (MetricDataResult result : resp.metricDataResults()) {
            List<Instant> timestamps = result.timestamps();
            List<Double> values = result.values();
            for (int i = 0; i < timestamps.size(); i++) {
                // Bucket in the schedule's own timezone so the recommendation lines up with
                // how the enforcer will later interpret it.
                ZonedDateTime zdt = timestamps.get(i).atZone(bucketZone);
                int d = zdt.getDayOfWeek().getValue() - 1;
                int h = zdt.getHour();
                double v = values.get(i);
                switch (result.id()) {
                    case "cpu"    -> {
                        cpuSum[d][h] += v; cpuCount[d][h]++;
                        cpuRawSeries.add(Map.of("ds", timestamps.get(i).toString(), "y", v));
                    }
                    case "cpumax" -> cpuMaxVal[d][h] = Math.max(cpuMaxVal[d][h], v);
                    case "mem"    -> { memSum[d][h] += v; memCount[d][h]++; }
                    case "net"    -> { netSum[d][h] += v; netCount[d][h]++; }
                    case "disk"   -> { diskSum[d][h] += v; diskCount[d][h]++; }
                }
            }
        }

        boolean memAvailable  = Arrays.stream(memCount).flatMapToInt(Arrays::stream).anyMatch(c -> c > 0);
        boolean netAvailable  = Arrays.stream(netCount).flatMapToInt(Arrays::stream).anyMatch(c -> c > 0);
        boolean diskAvailable = Arrays.stream(diskCount).flatMapToInt(Arrays::stream).anyMatch(c -> c > 0);

        // --- 4. Confidence check: bail out if data coverage is too thin ---
        int totalPoints = Arrays.stream(cpuCount).flatMapToInt(Arrays::stream).sum();
        double confidenceScore = Math.min(1.0, (double) totalPoints / (lookbackDays * 24.0));
        if (totalPoints < lookbackDays * MIN_DATA_POINTS_PER_DAY) {
            logger.info("CloudSitter: Insufficient data for {} ({} points, need {}+), skipping recommendation",
                    instanceId, totalPoints, lookbackDays * MIN_DATA_POINTS_PER_DAY);
            return CloudsitterRecommendationDto.skipped("INSUFFICIENT_DATA");
        }

        // --- 4b. ALB traffic and forecast-service schedule ---
        ElasticLoadBalancingV2Client elb = awsClientProvider.getElbv2Client(account, region);
        double[][] albGrid       = fetchAlbHourlyGrid(elb, cw, instanceId, region, startTime, endTime, bucketZone);
        boolean[][] forecastSchedule = getForecastSchedule(cpuRawSeries);

        // --- 5. Build per-day recommendation ---
        Map<String, List<Double>>   avgCpuGrid  = new LinkedHashMap<>();
        Map<String, List<Double>>   avgMemGrid  = new LinkedHashMap<>();
        Map<String, List<Double>>   avgNetGrid  = new LinkedHashMap<>();
        Map<String, List<Double>>   avgDiskGrid = new LinkedHashMap<>();
        Map<String, List<Integer>>  recommendedSchedule = new LinkedHashMap<>();

        for (int d = 0; d < 7; d++) {
            List<Double> dayCpu  = new ArrayList<>(24);
            List<Double> dayMem  = new ArrayList<>(24);
            List<Double> dayNet  = new ArrayList<>(24);
            List<Double> dayDisk = new ArrayList<>(24);
            boolean[] rawActive  = new boolean[24];

            for (int h = 0; h < 24; h++) {
                double avgCpu  = cpuCount[d][h]  > 0 ? cpuSum[d][h]  / cpuCount[d][h]  : 0.0;
                double maxCpu  = cpuMaxVal[d][h];
                double avgMem  = memCount[d][h]  > 0 ? memSum[d][h]  / memCount[d][h]  : 0.0;
                double avgNet  = netCount[d][h]  > 0 ? netSum[d][h]  / netCount[d][h]  : 0.0;
                double avgDisk = diskCount[d][h] > 0 ? diskSum[d][h] / diskCount[d][h] : 0.0;

                dayCpu.add(Math.round(avgCpu  * 100.0) / 100.0);
                dayMem.add(Math.round(avgMem  * 100.0) / 100.0);
                dayNet.add(Math.round(avgNet  *  10.0) /  10.0);
                dayDisk.add(Math.round(avgDisk *  10.0) /  10.0);

                boolean cpuActive      = avgCpu  >= CPU_IDLE_THRESHOLD;
                boolean cpuSpiked      = maxCpu  >= CPU_MAX_SPIKE_THRESHOLD;
                boolean memActive      = memAvailable  && avgMem  >= MEM_IDLE_THRESHOLD;
                boolean netActive      = netAvailable  && avgNet  >= NETWORK_IDLE_THRESHOLD;
                boolean diskActive     = diskAvailable && avgDisk >= DISK_IDLE_THRESHOLD;
                boolean albActive      = albGrid != null && albGrid[d][h] > 0;
                // The forecast-service p95 replaces the plain CPU threshold as baseline; all other signals stay as guards
                boolean baselineActive = forecastSchedule != null ? forecastSchedule[d][h] : cpuActive;
                rawActive[h] = baselineActive || cpuSpiked || memActive || netActive || diskActive || albActive;
            }

            // Absorb idle gaps shorter than MIN_CONSECUTIVE_IDLE_HOURS to prevent stop/start churn
            boolean[] finalActive = Arrays.copyOf(rawActive, 24);
            for (int h = 0; h < 24; h++) {
                if (!rawActive[h]) {
                    int idleRun = 0;
                    for (int k = h; k < 24 && !rawActive[k]; k++) idleRun++;
                    if (idleRun < MIN_CONSECUTIVE_IDLE_HOURS) {
                        for (int k = h; k < h + idleRun; k++) finalActive[k] = true;
                    }
                }
            }

            List<Integer> runningHours = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                if (finalActive[h]) runningHours.add(h);
            }

            avgCpuGrid.put(DAY_NAMES[d], dayCpu);
            avgMemGrid.put(DAY_NAMES[d], dayMem);
            avgNetGrid.put(DAY_NAMES[d], dayNet);
            avgDiskGrid.put(DAY_NAMES[d], dayDisk);
            recommendedSchedule.put(DAY_NAMES[d], runningHours);
        }

        CloudsitterRecommendationDto dto = new CloudsitterRecommendationDto(
                recommendedSchedule, avgCpuGrid, avgMemGrid, avgNetGrid, avgDiskGrid,
                memAvailable, netAvailable, diskAvailable,
                totalPoints, lookbackDays, confidenceScore, hasEip,
                CPU_IDLE_THRESHOLD, MEM_IDLE_THRESHOLD, NETWORK_IDLE_THRESHOLD, DISK_IDLE_THRESHOLD);
        // Echo the zone back so the caller binds the adopting policy to the same one.
        dto.setTimeZone(bucketZone.getId());
        return dto;
    }

    // --- RECOMMENDATION HELPERS ---

    /**
     * Returns true if any CloudWatch alarm for this instance is currently in ALARM state.
     * An active alarm means the instance is misbehaving; stopping it would hide the problem.
     */
    private boolean checkAlarmsActive(CloudWatchClient cw, String instanceId) {
        try {
            for (String metricName : List.of("CPUUtilization", "StatusCheckFailed")) {
                var resp = cw.describeAlarmsForMetric(DescribeAlarmsForMetricRequest.builder()
                        .namespace("AWS/EC2")
                        .metricName(metricName)
                        .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                        .build());
                boolean firing = resp.metricAlarms().stream()
                        .anyMatch(a -> StateValue.ALARM.equals(a.stateValue()));
                if (firing) {
                    logger.info("CloudSitter: Active {} alarm on {}, treating as always-on", metricName, instanceId);
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("CloudSitter: Could not check alarms for {}: {}", instanceId, e.getMessage());
        }
        return false;
    }

    /**
     * Finds every ALB target group this instance is registered in, then returns a
     * [7][24] grid of summed RequestCount per weekday/hour slot.
     * Returns null if the instance is not behind any ALB or the check fails.
     */
    private double[][] fetchAlbHourlyGrid(ElasticLoadBalancingV2Client elb, CloudWatchClient cw,
            String instanceId, String region, Instant startTime, Instant endTime, ZoneId bucketZone) {
        try {
            // Target group ARN -> CloudWatch namespace for its load balancer type.
            Map<String, String> tgArnToNamespace = new LinkedHashMap<>();

            // Paginated: an account with more target groups than one page previously had the
            // remainder silently ignored, so instances behind those groups looked traffic-free.
            String marker = null;
            int pages = 0;
            do {
                var tgResp = elb.describeTargetGroups(DescribeTargetGroupsRequest.builder()
                        .marker(marker)
                        .build());
                for (var tg : tgResp.targetGroups()) {
                    boolean registered = elb.describeTargetHealth(
                            DescribeTargetHealthRequest.builder().targetGroupArn(tg.targetGroupArn()).build())
                            .targetHealthDescriptions().stream()
                            .anyMatch(thd -> instanceId.equals(thd.target().id()));
                    if (registered) {
                        tgArnToNamespace.put(tg.targetGroupArn(), namespaceForTargetGroup(tg.protocolAsString()));
                    }
                }
                marker = tgResp.nextMarker();
            } while (marker != null && !marker.isBlank() && ++pages < MAX_ELB_PAGES);

            if (tgArnToNamespace.isEmpty()) return null;

            double[][] albGrid = new double[7][24];
            for (Map.Entry<String, String> entry : tgArnToNamespace.entrySet()) {
                String tgArn = entry.getKey();
                String albNamespace = entry.getValue();
                // CloudWatch dimension needs "targetgroup/name/id", not the full ARN
                int idx = tgArn.indexOf(":targetgroup/");
                if (idx < 0) continue;
                String tgDim = tgArn.substring(idx + 1);

                var albResp = cw.getMetricData(GetMetricDataRequest.builder()
                        .startTime(startTime).endTime(endTime)
                        .metricDataQueries(MetricDataQuery.builder()
                                .id("alb")
                                .metricStat(MetricStat.builder()
                                        .metric(Metric.builder()
                                                // Namespace is chosen per target-group protocol
                                                // below; ALB and NLB publish RequestCount under
                                                // different namespaces, and querying only
                                                // ApplicationELB made all NLB traffic invisible —
                                                // so an NLB-backed instance could be recommended
                                                // for shutdown.
                                                .namespace(albNamespace)
                                                .metricName("RequestCount")
                                                .dimensions(Dimension.builder()
                                                        .name("TargetGroup").value(tgDim).build())
                                                .build())
                                        .period(3600).stat("Sum").build())
                                .returnData(true).build())
                        .build());

                for (MetricDataResult r : albResp.metricDataResults()) {
                    List<Instant> ts = r.timestamps();
                    List<Double> vals = r.values();
                    for (int i = 0; i < ts.size(); i++) {
                        // Same bucketing zone as the CPU/memory grids.
                        ZonedDateTime zdt = ts.get(i).atZone(bucketZone);
                        albGrid[zdt.getDayOfWeek().getValue() - 1][zdt.getHour()] += vals.get(i);
                    }
                }
            }
            return albGrid;
        } catch (Exception e) {
            logger.warn("CloudSitter: ALB check failed for {}: {}", instanceId, e.getMessage());
            return null;
        }
    }

    /**
     * Calls the forecast service (/forecast/usage, resource=cpu) with 30+ days of hourly CPU
     * data and returns a [7][24] boolean grid where true = the forecast predicts the instance
     * will be active. Uses the forecast's p95 as the headroom bound; falls back to yhat_upper
     * (= p90, the upper bound of the central 80% interval) only for older forecast-service
     * builds that do not return p95.
     * Returns null if the forecast service is unavailable, the series is too short, or the
     * response method is {@code histogram} (a flat forecast that cannot separate idle hours
     * from active ones); the caller then uses the per-hour cpuActive baseline.
     * An hour-of-day-aware CPU histogram is a deferred follow-up (needs a backtest re-run).
     */
    boolean[][] getForecastSchedule(List<Map<String, Object>> cpuRawSeries) {
        if (cpuRawSeries.size() < 48) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // The injected RestTemplate has no configured timeout, so a hung forecast
            // service would pin this request thread indefinitely. The forecast is an enhancement
            // over the CPU-threshold baseline, so a failure here degrades rather than blocks.
            String json = restTemplate.postForObject(
                    forecastServiceUrl + "/forecast/usage",
                    new HttpEntity<>(Map.of("data", cpuRawSeries, "periods", 336, "resource", "cpu"), headers),
                    String.class);

            JsonNode root = objectMapper.readTree(json);
            if (!"success".equals(root.path("status").asText())) return null;
            // The decayed histogram is flat across the horizon (one quantile vector for every
            // hour), so thresholding it yields an all-on or all-off grid: diurnal instances would
            // never get stop windows and idle ones would lose the per-hour cpuActive baseline.
            // Use the per-hour threshold baseline instead, exactly as when the service is down.
            // Shaped forecasts (ets, or older Prophet builds that send no method) still feed the grid.
            String method = root.path("method").asText("");
            if ("histogram".equalsIgnoreCase(method)) {
                logger.info("CloudSitter: forecast method=histogram is flat across hours, "
                        + "using per-hour CPU threshold baseline (fallback_reason={})",
                        root.path("fallback_reason").asText(null));
                return null;
            }

            boolean[][] grid = new boolean[7][24];
            for (JsonNode pt : root.path("forecast")) {
                String ds = pt.path("ds").asText();
                // p95 is the honest headroom quantile; yhat_upper is only p90 (older builds lack p95).
                JsonNode p95 = pt.get("p95");
                double headroom = (p95 != null && p95.isNumber()) ? p95.asDouble() : pt.path("yhat_upper").asDouble();
                Instant ts = Instant.parse(ds.endsWith("Z") ? ds : ds + "Z");
                ZonedDateTime zdt = ts.atZone(ZoneOffset.UTC);
                int d = zdt.getDayOfWeek().getValue() - 1;
                int h = zdt.getHour();
                if (headroom >= CPU_IDLE_THRESHOLD) grid[d][h] = true;
            }
            logger.info("CloudSitter: forecast schedule ready, method={}, fallback_reason={}, trend={}",
                    root.path("method").asText("unknown"), root.path("fallback_reason").asText(null),
                    root.path("trend_direction").asText("STABLE"));
            return grid;
        } catch (Exception e) {
            logger.warn("CloudSitter: forecast service unavailable, falling back to threshold analysis: {}", e.getMessage());
            return null;
        }
    }

    // --- SCHEDULING ENGINE ---

    // (Existing scheduling logic remains unchanged)
    @Scheduled(cron = "0 0/10 * * * *")
    public void enforcePolicies() {
        long startTime = System.currentTimeMillis();
        logger.info("========================================");
        logger.info("CloudSitter: Starting policy enforcement cycle at {}", ZonedDateTime.now());
        logger.info("========================================");

        try {
            List<TenantDto> tenants = tenantService.getAllActiveTenants();
            logger.info("CloudSitter: Found {} active tenant(s) to process", tenants.size());

            int totalAssignmentsProcessed = 0;
            int totalActionsPerformed = 0;

            for (TenantDto tenant : tenants) {
                String tenantId = tenant.getTenantId();
                TenantContext.setCurrentTenant(tenantId);

                try {
                    logger.info("CloudSitter: Processing tenant: {} ({})", tenant.getCompanyName(), tenantId);
                    int[] results = self.enforcePoliciesForCurrentTenant();
                    totalAssignmentsProcessed += results[0];
                    totalActionsPerformed += results[1];
                    logger.info("CloudSitter: Tenant {} complete - {} assignments checked, {} actions performed",
                            tenantId, results[0], results[1]);
                } catch (Exception e) {
                    logger.error("CloudSitter: Failed to enforce policies for Tenant {}", tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
            long duration = System.currentTimeMillis() - startTime;
            logger.info("========================================");
            logger.info("CloudSitter: Enforcement cycle complete in {}ms", duration);
            logger.info("========================================");
        } catch (Exception e) {
            logger.error("CloudSitter: Fatal error during enforcement cycle", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int[] enforcePoliciesForCurrentTenant() {
        List<CloudsitterAssignment> assignments = assignmentRepo.findAllByActiveTrue();
        logger.info("CloudSitter: Found {} active assignment(s) for current tenant", assignments.size());

        int assignmentsProcessed = 0;
        int actionsPerformed = 0;

        for (CloudsitterAssignment assignment : assignments) {
            try {
                logger.debug("CloudSitter: Processing assignment {} for instance {}",
                        assignment.getId(), assignment.getResourceId());
                boolean actionTaken = processAssignment(assignment);
                assignmentsProcessed++;
                if (actionTaken) {
                    actionsPerformed++;
                }
            } catch (Exception e) {
                logger.error("CloudSitter: Failed to process policy for instance {}",
                        assignment.getResourceId(), e);
            }
        }
        return new int[] { assignmentsProcessed, actionsPerformed };
    }

    public Map<String, Object> getAuditLog(String accountId, Long clientId, int page, int size) {
        accountRepo.findByAwsAccountIdAndClientId(accountId, clientId)
                .orElseThrow(() -> new AccessDeniedException("Account not found: " + accountId));
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CloudsitterAuditLog> result = auditLogRepo.findByAccountIdOrderByTimestampDesc(accountId, pageable);
        Map<String, Object> response = new HashMap<>();
        response.put("content", result.getContent());
        response.put("totalElements", result.getTotalElements());
        response.put("totalPages", result.getTotalPages());
        response.put("page", page);
        return response;
    }

    private boolean processAssignment(CloudsitterAssignment assignment) {
        CloudsitterPolicy policy = assignment.getPolicy();
        String instanceId = assignment.getResourceId();

        logger.debug("CloudSitter: Checking instance {} with policy '{}'", instanceId, policy.getName());

        CloudAccount account = accountRepo.findByAwsAccountId(assignment.getAccountId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + assignment.getAccountId()));

        ZoneId timezone;
        try {
            timezone = ZoneId.of(policy.getTimeZone());
        } catch (Exception e) {
            logger.warn("CloudSitter: Invalid timezone '{}' for policy {}, using UTC",
                    policy.getTimeZone(), policy.getId());
            timezone = ZoneId.of("UTC");
        }

        ZonedDateTime now = ZonedDateTime.now(timezone);
        boolean shouldBeRunning = checkScheduleState(policy, now);

        logger.debug("CloudSitter: Current time in {}: {} (hour: {})",
                timezone, now, now.getHour());
        logger.debug("CloudSitter: Instance {} should be: {}",
                instanceId, shouldBeRunning ? "RUNNING" : "STOPPED");

        Ec2Client ec2 = awsClientProvider.getEc2Client(account, assignment.getRegion());
        Instance instance = describeInstance(ec2, instanceId);

        if (instance == null) {
            logger.warn("CloudSitter: Instance {} not found - it may have been terminated. Skipping policy enforcement.", instanceId);
            return false;
        }

        String currentState = instance.state().nameAsString();

        logger.debug("CloudSitter: Instance {} current state: {}", instanceId, currentState);

        // Re-check eligibility at enforcement time, not just at assignment. An instance can join
        // an Auto Scaling Group, be gaind a production tag, or be re-provisioned as a Kubernetes
        // node long after the policy was attached, and the scheduler would keep stopping it every
        // night. Only stops are gated: starting an instance is always safe.
        if (!shouldBeRunning && "running".equals(currentState)) {
            String rejection = describeStopIneligibility(instance);
            if (rejection != null) {
                logger.warn("CloudSitter: refusing to stop {} — {}", instanceId, rejection);
                writeAudit("STOP_SKIPPED", assignment, currentState, "SKIPPED", rejection);
                return false;
            }
        }

        if (shouldBeRunning && "stopped".equals(currentState)) {
            logger.info("⚡ CloudSitter: STARTING instance {} (Policy: '{}', Timezone: {})",
                    instanceId, policy.getName(), timezone);
            logger.info("   Reason: Schedule indicates instance should be running at hour {}", now.getHour());

            try {
                ec2.startInstances(r -> r.instanceIds(instanceId));
                logger.info("✅ CloudSitter: Successfully initiated START for instance {}", instanceId);
                writeAudit("STARTED", assignment, currentState, "SUCCESS", null);
                sendStateChangeEmail("STARTED", assignment, instance, now);
                return true;
            } catch (Exception e) {
                logger.error("❌ CloudSitter: Failed to start instance {}", instanceId, e);
                writeAudit("STARTED", assignment, currentState, "FAILURE", e.getMessage());
                throw e;
            }

        } else if (!shouldBeRunning && "running".equals(currentState)) {
            logger.info("🛑 CloudSitter: STOPPING instance {} (Policy: '{}', Timezone: {})",
                    instanceId, policy.getName(), timezone);
            logger.info("   Reason: Schedule indicates instance should be stopped at hour {}", now.getHour());

            boolean hibernationConfigured = instance.hibernationOptions() != null
                    && instance.hibernationOptions().configured();

            StopInstancesRequest.Builder stopReq = StopInstancesRequest.builder().instanceIds(instanceId);

            if (hibernationConfigured) {
                stopReq.hibernate(true);
                logger.info("   Using HIBERNATION for instance {}", instanceId);
            } else {
                logger.info("   Using STOP for instance {}", instanceId);
            }

            try {
                ec2.stopInstances(stopReq.build());
                logger.info("✅ CloudSitter: Successfully initiated {} for instance {}",
                        hibernationConfigured ? "HIBERNATION" : "STOP", instanceId);
                writeAudit("STOPPED", assignment, currentState, "SUCCESS", null);
                sendStateChangeEmail("STOPPED", assignment, instance, now);
                return true;
            } catch (Exception e) {
                logger.error("❌ CloudSitter: Failed to stop instance {}", instanceId, e);
                writeAudit("STOPPED", assignment, currentState, "FAILURE", e.getMessage());
                throw e;
            }
        } else {
            logger.debug("CloudSitter: No action needed for instance {} (current: {}, desired: {})",
                    instanceId, currentState, shouldBeRunning ? "running" : "stopped");
            return false;
        }
    }

    private void writeAudit(String action, CloudsitterAssignment assignment,
                            String previousState, String outcome, String errorMessage) {
        try {
            CloudsitterAuditLog entry = new CloudsitterAuditLog();
            entry.setAccountId(assignment.getAccountId());
            entry.setInstanceId(assignment.getResourceId());
            entry.setRegion(assignment.getRegion());
            entry.setAction(action);
            entry.setPolicyId(assignment.getPolicy().getId());
            entry.setPolicyName(assignment.getPolicy().getName());
            entry.setPreviousState(previousState);
            entry.setOutcome(outcome);
            entry.setErrorMessage(errorMessage);
            entry.setTimestamp(LocalDateTime.now());
            auditLogRepo.save(entry);
        } catch (Exception e) {
            logger.warn("CloudSitter audit write failed for instance {}: {}", assignment.getResourceId(), e.getMessage());
        }
    }

    private void sendStateChangeEmail(String action, CloudsitterAssignment assignment,
                                      Instance instance, ZonedDateTime actionTime) {
        CloudsitterPolicy policy = assignment.getPolicy();
        if (!policy.isNotificationsEnabled()) return;

        String email = policy.getNotificationEmail();
        if (email == null || email.isBlank()) return;

        String instanceId = assignment.getResourceId();
        String instanceName = instance.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(software.amazon.awssdk.services.ec2.model.Tag::value)
                .findFirst()
                .orElse(null);

        try {
            String subject = emailBuilder.subject(action, instanceId, policy.getName());
            String html = emailBuilder.buildHtml(action, instanceId, instanceName,
                    assignment.getRegion(), policy.getName(), policy.getTimeZone(), actionTime);
            emailService.sendHtmlEmail(email, subject, html);
            logger.info("📧 CloudSitter: Notification sent to {} for instance {} ({})",
                    email, instanceId, action);
        } catch (Exception e) {
            logger.warn("CloudSitter: Failed to send notification email for instance {}: {}",
                    instanceId, e.getMessage());
        }
    }

    /**
     * Whether the policy says the instance should be running at {@code now}.
     *
     * <p>Package-private so the timezone semantics can be tested directly: the same instant
     * evaluated in two zones must give different answers, which is the whole point of
     * evaluating in the policy's zone rather than UTC.
     */
    boolean checkScheduleState(CloudsitterPolicy policy, ZonedDateTime now) {
        String currentDay = now.getDayOfWeek().toString().toUpperCase();
        int currentHour = now.getHour();

        try {
            Map<String, List<Integer>> schedule = objectMapper.readValue(
                    policy.getScheduleJson(),
                    new TypeReference<Map<String, List<Integer>>>() {
                    });

            logger.debug("CloudSitter: Checking schedule for {} at hour {} (day: {})",
                    policy.getName(), currentHour, currentDay);

            if (schedule.containsKey(currentDay)) {
                List<Integer> activeHours = schedule.get(currentDay);
                boolean isActive = activeHours.contains(currentHour);
                logger.debug("CloudSitter: Day {} has {} active hours, hour {} is {}",
                        currentDay, activeHours.size(), currentHour,
                        isActive ? "ACTIVE" : "INACTIVE");
                return isActive;
            } else {
                logger.debug("CloudSitter: Day {} not in schedule, defaulting to STOPPED", currentDay);
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.error("CloudSitter: Failed to parse schedule JSON for policy {}, defaulting to RUNNING",
                    policy.getId(), e);
            return true;
        }

        return false;
    }

    private Instance describeInstance(Ec2Client ec2, String instanceId) {
        try {
            var response = ec2.describeInstances(r -> r.instanceIds(instanceId));

            if (response.reservations().isEmpty() || response.reservations().get(0).instances().isEmpty()) {
                logger.warn("CloudSitter: Instance {} not found or has no reservations", instanceId);
                return null;
            }

            return response.reservations().get(0).instances().get(0);
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            logger.warn("CloudSitter: Instance {} does not exist or cannot be accessed: {}", instanceId, e.awsErrorDetails().errorMessage());
            return null;
        } catch (Exception e) {
            logger.error("CloudSitter: Unexpected error describing instance {}", instanceId, e);
            return null;
        }
    }
}