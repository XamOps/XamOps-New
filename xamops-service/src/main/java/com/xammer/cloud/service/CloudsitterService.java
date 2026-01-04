package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy; // ✅ ADDED IMPORT
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
    private final AwsPricingService pricingService;

    // ✅ FIXED: Added @Lazy to break the circular dependency
    @Autowired
    @Lazy
    private CloudsitterService self;

    public CloudsitterService(CloudsitterPolicyRepository policyRepo,
            CloudsitterAssignmentRepository assignmentRepo,
            AwsClientProvider awsClientProvider,
            CloudAccountRepository accountRepo,
            ObjectMapper objectMapper,
            TenantService tenantService,
            AwsPricingService pricingService) {
        this.policyRepo = policyRepo;
        this.assignmentRepo = assignmentRepo;
        this.awsClientProvider = awsClientProvider;
        this.accountRepo = accountRepo;
        this.objectMapper = objectMapper;
        this.tenantService = tenantService;
        this.pricingService = pricingService;
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
            return Math.round(savings * 100.0) / 100.0;
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
    public void createPolicy(CloudsitterPolicyDto dto) {
        CloudsitterPolicy policy = new CloudsitterPolicy();
        policy.setName(dto.getName());
        policy.setType(dto.getType());
        policy.setTimeZone(dto.getTimeZone());
        policy.setScheduleJson(dto.getScheduleConfig());
        policy.setNotificationsEnabled(dto.isNotificationsEnabled());
        policy.setNotificationEmail(dto.getNotificationEmail());
        policyRepo.save(policy);
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

    // --- SCHEDULING ENGINE (MULTI-TENANT AWARE) ---

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

                    // Call Transactional method via SELF proxy with @Lazy
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
        String currentState = instance.state().nameAsString();

        logger.debug("CloudSitter: Instance {} current state: {}", instanceId, currentState);

        if (shouldBeRunning && "stopped".equals(currentState)) {
            logger.info("⚡ CloudSitter: STARTING instance {} (Policy: '{}', Timezone: {})",
                    instanceId, policy.getName(), timezone);
            logger.info("   Reason: Schedule indicates instance should be running at hour {}", now.getHour());

            try {
                ec2.startInstances(r -> r.instanceIds(instanceId));
                logger.info("✅ CloudSitter: Successfully initiated START for instance {}", instanceId);
                return true;
            } catch (Exception e) {
                logger.error("❌ CloudSitter: Failed to start instance {}", instanceId, e);
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
                return true;
            } catch (Exception e) {
                logger.error("❌ CloudSitter: Failed to stop instance {}", instanceId, e);
                throw e;
            }
        } else {
            logger.debug("CloudSitter: No action needed for instance {} (current: {}, desired: {})",
                    instanceId, currentState, shouldBeRunning ? "running" : "stopped");
            return false;
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
        return ec2.describeInstances(r -> r.instanceIds(instanceId))
                .reservations().get(0).instances().get(0);
    }
}