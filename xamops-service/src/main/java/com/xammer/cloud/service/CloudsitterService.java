package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.*;
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
                        a -> convertToDto(a, instanceTypes.getOrDefault(a.getResourceId(), "t3.micro")),
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
            return assignments.stream()
                    .mapToDouble(a -> calculateMonthlySavings(
                            a.getPolicy(),
                            instanceTypes.getOrDefault(a.getResourceId(), "t3.micro"),
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

    private CloudsitterAssignmentDto convertToDto(CloudsitterAssignment assignment, String instanceType) {
        CloudsitterAssignmentDto dto = new CloudsitterAssignmentDto();
        dto.setId(assignment.getId());
        dto.setResourceId(assignment.getResourceId());
        dto.setAccountId(assignment.getAccountId());
        dto.setRegion(assignment.getRegion());
        dto.setPolicy(assignment.getPolicy());
        dto.setActive(assignment.isActive());
        dto.setInstanceType(instanceType);

        double savings = calculateMonthlySavings(assignment.getPolicy(), instanceType, assignment.getRegion());
        dto.setMonthlySavings(savings);

        return dto;
    }

    private double calculateMonthlySavings(CloudsitterPolicy policy, String instanceType, String region) {
        try {
            int stoppedHoursPerMonth = calculateStoppedHoursPerMonth(policy);
            double savings = pricingService.calculateMonthlySavings(instanceType, region, stoppedHoursPerMonth);
            double roundedSavings = Math.round(savings * 100.0) / 100.0;
            
            logger.debug("CloudSitter Monthly Savings - Instance: {} in {}, Policy: '{}', " +
                    "Stopped hrs/month: {}, Savings: ${}",
                    instanceType, region, policy.getName(), stoppedHoursPerMonth, roundedSavings);
            
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
            int stoppedHoursPerMonth = (int) (stoppedHoursPerWeek * 4.33);
            
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

    @Transactional
    public void assignPolicyToInstances(CloudsitterAssignmentRequest request) {
        CloudsitterPolicy policy = policyRepo.findById(request.getPolicyId())
                .orElseThrow(() -> new RuntimeException("Policy not found: " + request.getPolicyId()));

        for (String instanceId : request.getInstanceIds()) {
            CloudsitterAssignment assignment = assignmentRepo.findByResourceId(instanceId)
                    .orElse(new CloudsitterAssignment());

            assignment.setResourceId(instanceId);
            assignment.setAccountId(request.getAccountId());
            assignment.setRegion(request.getRegion());
            assignment.setPolicy(policy);
            assignment.setActive(true);

            assignmentRepo.save(assignment);
            logger.info("Assigned policy {} to instance {}", policy.getName(), instanceId);
        }
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
    private static final int MIN_DATA_POINTS_PER_DAY = 12;       // need ≥50% coverage for a reliable estimate

    public CloudsitterRecommendationDto getScheduleRecommendation(
            String accountId, String instanceId, String region, int lookbackDays) {

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
                
                // Check if instance is tagged as production environment
                boolean isProduction = inst.tags().stream()
                        .anyMatch(t -> {
                            String key = t.key().toLowerCase();
                            String value = t.value() != null ? t.value().toLowerCase() : "";
                            return (key.equals("environment") || key.equals("env") || key.equals("stage"))
                                && (value.equals("production") || value.equals("prod") || value.equals("prd"));
                        });
                if (isProduction) {
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
                ZonedDateTime zdt = timestamps.get(i).atZone(ZoneOffset.UTC);
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

        // --- 4b. ALB traffic and Prophet schedule ---
        ElasticLoadBalancingV2Client elb = awsClientProvider.getElbv2Client(account, region);
        double[][] albGrid       = fetchAlbHourlyGrid(elb, cw, instanceId, region, startTime, endTime);
        boolean[][] prophetSchedule = getProphetSchedule(cpuRawSeries);

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
                // Prophet replaces the plain CPU threshold as baseline; all other signals stay as guards
                boolean baselineActive = prophetSchedule != null ? prophetSchedule[d][h] : cpuActive;
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

        return new CloudsitterRecommendationDto(
                recommendedSchedule, avgCpuGrid, avgMemGrid, avgNetGrid, avgDiskGrid,
                memAvailable, netAvailable, diskAvailable,
                totalPoints, lookbackDays, confidenceScore, hasEip,
                CPU_IDLE_THRESHOLD, MEM_IDLE_THRESHOLD, NETWORK_IDLE_THRESHOLD, DISK_IDLE_THRESHOLD);
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
            String instanceId, String region, Instant startTime, Instant endTime) {
        try {
            List<String> tgArns = new ArrayList<>();
            var tgResp = elb.describeTargetGroups(DescribeTargetGroupsRequest.builder().build());
            for (var tg : tgResp.targetGroups()) {
                boolean registered = elb.describeTargetHealth(
                        DescribeTargetHealthRequest.builder().targetGroupArn(tg.targetGroupArn()).build())
                        .targetHealthDescriptions().stream()
                        .anyMatch(thd -> instanceId.equals(thd.target().id()));
                if (registered) tgArns.add(tg.targetGroupArn());
            }

            if (tgArns.isEmpty()) return null;

            double[][] albGrid = new double[7][24];
            for (String tgArn : tgArns) {
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
                                                .namespace("AWS/ApplicationELB")
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
                        ZonedDateTime zdt = ts.get(i).atZone(ZoneOffset.UTC);
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
     * Calls the Prophet forecast service with 30+ days of hourly CPU data and returns a
     * [7][24] boolean grid where true = Prophet predicts the instance will be active.
     * Uses yhat_upper (80% confidence upper bound) for a conservative estimate.
     * Returns null if Prophet is unavailable or the series is too short.
     */
    private boolean[][] getProphetSchedule(List<Map<String, Object>> cpuRawSeries) {
        if (cpuRawSeries.size() < 48) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String json = restTemplate.postForObject(
                    forecastServiceUrl + "/forecast/usage",
                    new HttpEntity<>(Map.of("data", cpuRawSeries, "periods", 336), headers),
                    String.class);

            JsonNode root = objectMapper.readTree(json);
            if (!"success".equals(root.path("status").asText())) return null;

            boolean[][] grid = new boolean[7][24];
            for (JsonNode pt : root.path("forecast")) {
                String ds = pt.path("ds").asText();
                double yhatUpper = pt.path("yhat_upper").asDouble();
                Instant ts = Instant.parse(ds.endsWith("Z") ? ds : ds + "Z");
                ZonedDateTime zdt = ts.atZone(ZoneOffset.UTC);
                int d = zdt.getDayOfWeek().getValue() - 1;
                int h = zdt.getHour();
                if (yhatUpper >= CPU_IDLE_THRESHOLD) grid[d][h] = true;
            }
            logger.info("CloudSitter: Prophet schedule ready, trend={}", root.path("trend_direction").asText("STABLE"));
            return grid;
        } catch (Exception e) {
            logger.warn("CloudSitter: Prophet unavailable, falling back to threshold analysis: {}", e.getMessage());
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

    private boolean checkScheduleState(CloudsitterPolicy policy, ZonedDateTime now) {
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