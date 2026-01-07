package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.cloud.compute.v1.Operation;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.gcp.GcpPricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class CloudsitterService {
    private static final Logger logger = LoggerFactory.getLogger(CloudsitterService.class);

    private final CloudsitterPolicyRepository policyRepo;
    private final CloudsitterAssignmentRepository assignmentRepo;
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository accountRepo;
    private final ObjectMapper objectMapper;
    private final TenantService tenantService;
    private final AwsPricingService awsPricingService;

    // GCP Services
    private final GcpClientProvider gcpClientProvider;
    private final GcpPricingService gcpPricingService;

    @Autowired
    @Lazy
    private CloudsitterService self;

    public CloudsitterService(CloudsitterPolicyRepository policyRepo,
            CloudsitterAssignmentRepository assignmentRepo,
            AwsClientProvider awsClientProvider,
            CloudAccountRepository accountRepo,
            ObjectMapper objectMapper,
            TenantService tenantService,
            AwsPricingService awsPricingService,
            GcpClientProvider gcpClientProvider,
            GcpPricingService gcpPricingService) {
        this.policyRepo = policyRepo;
        this.assignmentRepo = assignmentRepo;
        this.awsClientProvider = awsClientProvider;
        this.accountRepo = accountRepo;
        this.objectMapper = objectMapper;
        this.tenantService = tenantService;
        this.awsPricingService = awsPricingService;
        this.gcpClientProvider = gcpClientProvider;
        this.gcpPricingService = gcpPricingService;
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

        // Find the account to determine provider
        CloudAccount account = findAccount(accountId);

        Map<String, String> instanceTypes;

        if (account != null && "GCP".equalsIgnoreCase(account.getProvider())) {
            instanceTypes = fetchGcpInstanceTypes(account, assignments);
        } else {
            instanceTypes = fetchAwsInstanceTypes(account, assignments);
        }

        return assignments.stream()
                .collect(Collectors.toMap(
                        CloudsitterAssignment::getResourceId,
                        a -> convertToDto(a, instanceTypes.getOrDefault(a.getResourceId(), "unknown"), account),
                        (existing, replacement) -> existing));
    }

    // ✅ FIXED: Correctly handling List vs Optional return types
    private CloudAccount findAccount(String accountId) {
        // Try AWS ID first (findByAwsAccountId returns List)
        List<CloudAccount> awsAccounts = accountRepo.findByAwsAccountId(accountId);
        if (!awsAccounts.isEmpty()) {
            return awsAccounts.get(0);
        }

        // Try GCP Project ID (findByGcpProjectId returns Optional)
        return accountRepo.findByGcpProjectId(accountId).orElse(null);
    }

    private Map<String, String> fetchAwsInstanceTypes(CloudAccount account, List<CloudsitterAssignment> assignments) {
        Map<String, String> types = new java.util.HashMap<>();
        if (assignments.isEmpty() || account == null)
            return types;

        try {
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
                        for (software.amazon.awssdk.services.ec2.model.Instance instance : reservation.instances()) {
                            types.put(instance.instanceId(), instance.instanceType().toString());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to fetch AWS instance details for region {}: {}", region, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching AWS instance types for account {}", account.getAwsAccountId(), e);
        }
        return types;
    }

    private Map<String, String> fetchGcpInstanceTypes(CloudAccount account, List<CloudsitterAssignment> assignments) {
        Map<String, String> types = new java.util.HashMap<>();
        if (assignments.isEmpty() || account == null)
            return types;

        // Group by Zone (GCP Assignment 'region' field usually stores zone like
        // 'us-central1-a')
        Map<String, List<CloudsitterAssignment>> byZone = assignments.stream()
                .collect(Collectors.groupingBy(CloudsitterAssignment::getRegion));

        try {
            var clientOpt = gcpClientProvider.getInstancesClient(account.getGcpProjectId());
            if (clientOpt.isEmpty())
                return types;

            try (InstancesClient client = clientOpt.get()) {
                for (Map.Entry<String, List<CloudsitterAssignment>> entry : byZone.entrySet()) {
                    String zone = entry.getKey();
                    for (CloudsitterAssignment assignment : entry.getValue()) {
                        try {
                            Instance instance = client.get(account.getGcpProjectId(), zone, assignment.getResourceId());
                            // Machine type is a URL, we simplify it
                            types.put(assignment.getResourceId(), instance.getMachineType());
                        } catch (Exception e) {
                            logger.warn("Failed to fetch GCP instance {} in zone {}", assignment.getResourceId(), zone);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching GCP instance types for project {}", account.getGcpProjectId(), e);
        }
        return types;
    }

    private CloudsitterAssignmentDto convertToDto(CloudsitterAssignment assignment, String instanceType,
            CloudAccount account) {
        CloudsitterAssignmentDto dto = new CloudsitterAssignmentDto();
        dto.setId(assignment.getId());
        dto.setResourceId(assignment.getResourceId());
        dto.setAccountId(assignment.getAccountId());
        dto.setRegion(assignment.getRegion());
        dto.setPolicy(assignment.getPolicy());
        dto.setActive(assignment.isActive());

        // Clean up GCP machine type URL if needed
        String cleanType = instanceType;
        if (cleanType.contains("/")) {
            cleanType = cleanType.substring(cleanType.lastIndexOf("/") + 1);
        }
        dto.setInstanceType(cleanType);

        double savings = calculateMonthlySavings(assignment.getPolicy(), cleanType, assignment.getRegion(), account);
        dto.setMonthlySavings(savings);

        return dto;
    }

    private double calculateMonthlySavings(CloudsitterPolicy policy, String instanceType, String region,
            CloudAccount account) {
        try {
            int stoppedHoursPerMonth = calculateStoppedHoursPerMonth(policy);

            if (account != null && "GCP".equalsIgnoreCase(account.getProvider())) {
                return gcpPricingService.calculateMonthlySavings(instanceType, region, stoppedHoursPerMonth);
            } else {
                return awsPricingService.calculateMonthlySavings(instanceType, region, stoppedHoursPerMonth);
            }
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
            return (int) (stoppedHoursPerWeek * 4.33);
        } catch (Exception e) {
            logger.error("Failed to parse schedule for policy {}", policy.getId(), e);
            return 0;
        }
    }

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

    @Transactional
    public void deletePolicy(Long id) {
        CloudsitterPolicy policy = policyRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Policy not found: " + id));

        List<CloudsitterAssignment> assignments = assignmentRepo.findAll();
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
            assignment.setRegion(request.getRegion()); // For GCP, this should be the ZONE
            assignment.setPolicy(policy);
            assignment.setActive(true);

            assignmentRepo.save(assignment);
            logger.info("Assigned policy {} to instance {}", policy.getName(), instanceId);
        }
    }

    // --- SCHEDULING ENGINE ---

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
            logger.info("CloudSitter: Summary - {} assignments processed, {} actions performed",
                    totalAssignmentsProcessed, totalActionsPerformed);
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

    private boolean processAssignment(CloudsitterAssignment assignment) {
        CloudsitterPolicy policy = assignment.getPolicy();
        String instanceId = assignment.getResourceId();

        logger.debug("CloudSitter: Checking instance {} with policy '{}'", instanceId, policy.getName());

        CloudAccount account = findAccount(assignment.getAccountId());

        if (account == null) {
            logger.error("Account not found for assignment: {}", assignment.getAccountId());
            return false;
        }

        // Determine if this is AWS or GCP
        boolean isGcp = "GCP".equalsIgnoreCase(account.getProvider());

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

        if (isGcp) {
            return processGcpAssignment(account, assignment, shouldBeRunning, instanceId, policy, timezone);
        } else {
            return processAwsAssignment(account, assignment, shouldBeRunning, instanceId, policy, timezone);
        }
    }

    // --- AWS LOGIC ---
    private boolean processAwsAssignment(CloudAccount account, CloudsitterAssignment assignment,
            boolean shouldBeRunning,
            String instanceId, CloudsitterPolicy policy, ZoneId timezone) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, assignment.getRegion());
            software.amazon.awssdk.services.ec2.model.Instance instance = describeAwsInstance(ec2, instanceId);
            String currentState = instance.state().nameAsString();

            logger.debug("CloudSitter (AWS): Instance {} current state: {}", instanceId, currentState);

            if (shouldBeRunning && "stopped".equals(currentState)) {
                logger.info("⚡ CloudSitter: STARTING AWS instance {} (Policy: '{}', Timezone: {})",
                        instanceId, policy.getName(), timezone);
                ec2.startInstances(r -> r.instanceIds(instanceId));
                return true;
            } else if (!shouldBeRunning && "running".equals(currentState)) {
                logger.info("🛑 CloudSitter: STOPPING AWS instance {} (Policy: '{}', Timezone: {})",
                        instanceId, policy.getName(), timezone);

                boolean hibernationConfigured = instance.hibernationOptions() != null
                        && instance.hibernationOptions().configured();
                StopInstancesRequest.Builder stopReq = StopInstancesRequest.builder().instanceIds(instanceId);

                if (hibernationConfigured) {
                    stopReq.hibernate(true);
                    logger.info("   Using HIBERNATION for instance {}", instanceId);
                }
                ec2.stopInstances(stopReq.build());
                return true;
            }
        } catch (Exception e) {
            logger.error("Error processing AWS assignment for {}", instanceId, e);
            throw e;
        }
        return false;
    }

    // --- GCP LOGIC ---
    private boolean processGcpAssignment(CloudAccount account, CloudsitterAssignment assignment,
            boolean shouldBeRunning,
            String instanceId, CloudsitterPolicy policy, ZoneId timezone) {
        String projectId = account.getGcpProjectId();
        String zone = assignment.getRegion(); // Assignment 'region' field holds Zone for GCP

        try {
            var clientOpt = gcpClientProvider.getInstancesClient(projectId);
            if (clientOpt.isEmpty()) {
                logger.error("Could not create GCP InstancesClient for project {}", projectId);
                return false;
            }

            try (InstancesClient client = clientOpt.get()) {
                Instance instance = client.get(projectId, zone, instanceId);
                String status = instance.getStatus(); // PROVISIONING, STAGING, RUNNING, STOPPING, SUSPENDING,
                                                      // SUSPENDED, REPAIRING, and TERMINATED.

                logger.debug("CloudSitter (GCP): Instance {} in zone {} status: {}", instanceId, zone, status);

                if (shouldBeRunning && ("TERMINATED".equals(status) || "STOPPED".equals(status))) {
                    logger.info("⚡ CloudSitter: STARTING GCP instance {} (Policy: '{}', Timezone: {})",
                            instanceId, policy.getName(), timezone);
                    Operation op = client.startAsync(projectId, zone, instanceId).get(60, TimeUnit.SECONDS);
                    logger.info("   GCP Start Operation: {}", op.getStatus());
                    return true;

                } else if (!shouldBeRunning && "RUNNING".equals(status)) {
                    logger.info("🛑 CloudSitter: STOPPING GCP instance {} (Policy: '{}', Timezone: {})",
                            instanceId, policy.getName(), timezone);
                    Operation op = client.stopAsync(projectId, zone, instanceId).get(60, TimeUnit.SECONDS);
                    logger.info("   GCP Stop Operation: {}", op.getStatus());
                    return true;
                }
            }
        } catch (Exception e) {
            logger.error("Error processing GCP assignment for {} in zone {}", instanceId, zone, e);
        }
        return false;
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
                return isActive;
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.error("CloudSitter: Failed to parse schedule JSON for policy {}, defaulting to RUNNING",
                    policy.getId(), e);
            return true;
        }
        return false;
    }

    private software.amazon.awssdk.services.ec2.model.Instance describeAwsInstance(Ec2Client ec2, String instanceId) {
        return ec2.describeInstances(r -> r.instanceIds(instanceId))
                .reservations().get(0).instances().get(0);
    }
}