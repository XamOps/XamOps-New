package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.cloud.compute.v1.InstancesScopedList;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.domain.type.OidShapeGuard;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class GcpCloudsitterService {

    private static final Logger logger = LoggerFactory.getLogger(GcpCloudsitterService.class);

    // Cache TTLs
    private static final int VM_LIST_CACHE_TTL_MINUTES = 60;
    private static final int VM_STATE_CACHE_TTL_MINUTES = 5;

    private final GcpCloudsitterAssignmentRepository assignmentRepo;
    private final CloudsitterPolicyRepository policyRepo;
    private final CloudAccountRepository accountRepo;
    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final TenantService tenantService;

    @Autowired
    @Lazy
    private GcpCloudsitterService self;

    public GcpCloudsitterService(GcpCloudsitterAssignmentRepository assignmentRepo,
                                  CloudsitterPolicyRepository policyRepo,
                                  CloudAccountRepository accountRepo,
                                  GcpClientProvider gcpClientProvider,
                                  RedisCacheService cacheService,
                                  ObjectMapper objectMapper,
                                  TenantService tenantService) {
        this.assignmentRepo = assignmentRepo;
        this.policyRepo = policyRepo;
        this.accountRepo = accountRepo;
        this.gcpClientProvider = gcpClientProvider;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.tenantService = tenantService;
    }

    // --- UI METHODS ---

    @Transactional(readOnly = true)
    public List<CloudsitterPolicy> getAllPolicies() {
        return policyRepo.findAll();
    }

    @Transactional(readOnly = true)
    public Map<String, CloudsitterAssignmentDto> getAssignmentsForProject(String gcpProjectId) {
        List<GcpCloudsitterAssignment> assignments =
                assignmentRepo.findAllByGcpProjectIdAndActiveTrue(gcpProjectId);

        return assignments.stream()
                .collect(Collectors.toMap(
                        a -> a.getInstanceName() + "|" + a.getZone(),
                        a -> convertToDto(a),
                        (existing, replacement) -> existing));
    }

    private CloudsitterAssignmentDto convertToDto(GcpCloudsitterAssignment assignment) {
        CloudsitterAssignmentDto dto = new CloudsitterAssignmentDto();
        dto.setId(assignment.getId());
        dto.setResourceId(assignment.getInstanceName() + "|" + assignment.getZone());
        dto.setAccountId(assignment.getGcpProjectId());
        dto.setRegion(assignment.getZone());
        dto.setPolicy(assignment.getPolicy());
        dto.setActive(assignment.isActive());
        return dto;
    }

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

    @Transactional
    public void deletePolicy(Long id) {
        CloudsitterPolicy policy = policyRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Policy not found: " + id));
        List<GcpCloudsitterAssignment> assignments = assignmentRepo.findAll();
        for (GcpCloudsitterAssignment a : assignments) {
            if (a.getPolicy() != null && a.getPolicy().getId().equals(id)) {
                assignmentRepo.delete(a);
            }
        }
        policyRepo.delete(policy);
    }

    @Transactional
    public void assignPolicyToInstances(CloudsitterAssignmentRequest request) {
        CloudsitterPolicy policy = policyRepo.findById(request.getPolicyId())
                .orElseThrow(() -> new RuntimeException("Policy not found: " + request.getPolicyId()));

        for (String resourceKey : request.getInstanceIds()) {
            // resourceKey format: "instanceName|zone"
            String[] parts = resourceKey.split("\\|", 2);
            if (parts.length != 2) continue;
            String instanceName = parts[0];
            String zone = parts[1];

            GcpCloudsitterAssignment assignment =
                    assignmentRepo.findByInstanceNameAndZoneAndGcpProjectId(instanceName, zone, request.getAccountId())
                            .orElse(new GcpCloudsitterAssignment());

            assignment.setInstanceName(instanceName);
            assignment.setZone(zone);
            assignment.setGcpProjectId(request.getAccountId());
            assignment.setPolicy(policy);
            assignment.setActive(true);

            assignmentRepo.save(assignment);
            logger.info("GCP CloudSitter: Assigned policy {} to instance {} in zone {}",
                    policy.getName(), instanceName, zone);
        }
    }

    // --- CACHED VM LISTING ---

    public List<Map<String, String>> listVmsForProject(String gcpProjectId) {
        String cacheKey = "gcp-cloudsitter-vms:" + gcpProjectId;

        Optional<List<Map<String, String>>> cached = cacheService.get(
                cacheKey, new TypeReference<List<Map<String, String>>>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Map<String, String>> vms = fetchVmsFromGcp(gcpProjectId);
        if (!vms.isEmpty()) {
            cacheService.put(cacheKey, vms, VM_LIST_CACHE_TTL_MINUTES);
        }
        return vms;
    }

    private List<Map<String, String>> fetchVmsFromGcp(String gcpProjectId) {
        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            logger.warn("GCP CloudSitter: Could not get InstancesClient for project {}", gcpProjectId);
            return Collections.emptyList();
        }

        List<Map<String, String>> result = new ArrayList<>();
        try (InstancesClient client = clientOpt.get()) {
            for (Map.Entry<String, InstancesScopedList> entry : client.aggregatedList(gcpProjectId).iterateAll()) {
                String zoneKey = entry.getKey(); // e.g. "zones/us-central1-a"
                if (entry.getValue().getInstancesList() == null) continue;
                for (Instance instance : entry.getValue().getInstancesList()) {
                    String zone = zoneKey.startsWith("zones/") ? zoneKey.substring(6) : zoneKey;
                    Map<String, String> vm = new HashMap<>();
                    vm.put("name", instance.getName());
                    vm.put("zone", zone);
                    vm.put("status", instance.getStatus());
                    vm.put("machineType", extractLastSegment(instance.getMachineType()));
                    result.add(vm);
                }
            }
        } catch (Exception e) {
            logger.error("GCP CloudSitter: Failed to list VMs for project {}", gcpProjectId, e);
        }
        return result;
    }

    private String extractLastSegment(String url) {
        if (url == null || !url.contains("/")) return url;
        return url.substring(url.lastIndexOf('/') + 1);
    }

    // --- ENFORCEMENT ENGINE ---

    @Scheduled(cron = "0 0/10 * * * *")
    public void enforcePolicies() {
        long start = System.currentTimeMillis();
        logger.info("GCP CloudSitter: Starting policy enforcement cycle at {}", ZonedDateTime.now());
        try {
            List<TenantDto> tenants = tenantService.getAllActiveTenants();
            int totalAssignments = 0, totalActions = 0;
            for (TenantDto tenant : tenants) {
                TenantContext.setCurrentTenant(tenant.getTenantId());
                try {
                    int[] results = self.enforcePoliciesForCurrentTenant();
                    totalAssignments += results[0];
                    totalActions += results[1];
                } catch (Exception e) {
                    logger.error("GCP CloudSitter: Failed for tenant {}", tenant.getTenantId(), e);
                } finally {
                    TenantContext.clear();
                }
            }
            logger.info("GCP CloudSitter: Enforcement complete in {}ms — {} assignments, {} actions",
                    System.currentTimeMillis() - start, totalAssignments, totalActions);
        } catch (Exception e) {
            logger.error("GCP CloudSitter: Fatal error during enforcement cycle", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int[] enforcePoliciesForCurrentTenant() {
        List<GcpCloudsitterAssignment> assignments = assignmentRepo.findAllByActiveTrue();
        int processed = 0, actions = 0;
        for (GcpCloudsitterAssignment assignment : assignments) {
            try {
                if (processAssignment(assignment)) actions++;
                processed++;
            } catch (Exception e) {
                logger.error("GCP CloudSitter: Failed to process assignment for instance {}",
                        assignment.getInstanceName(), e);
            }
        }
        return new int[]{processed, actions};
    }

    private boolean processAssignment(GcpCloudsitterAssignment assignment) {
        CloudsitterPolicy policy = assignment.getPolicy();
        String instanceName = assignment.getInstanceName();
        String zone = assignment.getZone();
        String gcpProjectId = assignment.getGcpProjectId();

        ZoneId timezone;
        try {
            timezone = ZoneId.of(policy.getTimeZone());
        } catch (Exception e) {
            timezone = ZoneId.of("UTC");
        }

        boolean shouldBeRunning = checkScheduleState(policy, ZonedDateTime.now(timezone));
        String currentStatus = getVmStatus(gcpProjectId, zone, instanceName);

        if (currentStatus == null) {
            logger.warn("GCP CloudSitter: Could not determine state for instance {} in zone {}", instanceName, zone);
            return false;
        }

        if (shouldBeRunning && "TERMINATED".equals(currentStatus)) {
            logger.info("GCP CloudSitter: STARTING instance {} in zone {} (policy: {})",
                    instanceName, zone, policy.getName());
            return startVm(gcpProjectId, zone, instanceName);
        } else if (!shouldBeRunning && "RUNNING".equals(currentStatus)) {
            logger.info("GCP CloudSitter: STOPPING instance {} in zone {} (policy: {})",
                    instanceName, zone, policy.getName());
            return stopVm(gcpProjectId, zone, instanceName);
        }
        return false;
    }

    private String getVmStatus(String gcpProjectId, String zone, String instanceName) {
        String cacheKey = "gcp-cloudsitter-state:" + gcpProjectId + ":" + zone + ":" + instanceName;

        Optional<String> cached = cacheService.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return null;

        try (InstancesClient client = clientOpt.get()) {
            Instance instance = client.get(gcpProjectId, zone, instanceName);
            String status = instance.getStatus();
            cacheService.put(cacheKey, status, VM_STATE_CACHE_TTL_MINUTES);
            return status;
        } catch (Exception e) {
            logger.warn("GCP CloudSitter: Failed to get VM status for {} in zone {}: {}",
                    instanceName, zone, e.getMessage());
            return null;
        }
    }

    private boolean startVm(String gcpProjectId, String zone, String instanceName) {
        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return false;
        try (InstancesClient client = clientOpt.get()) {
            client.startAsync(gcpProjectId, zone, instanceName);
            evictStateCache(gcpProjectId, zone, instanceName);
            logger.info("GCP CloudSitter: START initiated for {} in zone {}", instanceName, zone);
            return true;
        } catch (Exception e) {
            logger.error("GCP CloudSitter: Failed to start {} in zone {}", instanceName, zone, e);
            return false;
        }
    }

    private boolean stopVm(String gcpProjectId, String zone, String instanceName) {
        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return false;
        try (InstancesClient client = clientOpt.get()) {
            client.stopAsync(gcpProjectId, zone, instanceName);
            evictStateCache(gcpProjectId, zone, instanceName);
            logger.info("GCP CloudSitter: STOP initiated for {} in zone {}", instanceName, zone);
            return true;
        } catch (Exception e) {
            logger.error("GCP CloudSitter: Failed to stop {} in zone {}", instanceName, zone, e);
            return false;
        }
    }

    private void evictStateCache(String gcpProjectId, String zone, String instanceName) {
        cacheService.evict("gcp-cloudsitter-state:" + gcpProjectId + ":" + zone + ":" + instanceName);
    }

    private boolean checkScheduleState(CloudsitterPolicy policy, ZonedDateTime now) {
        String currentDay = now.getDayOfWeek().toString().toUpperCase();
        int currentHour = now.getHour();
        try {
            Map<String, List<Integer>> schedule = objectMapper.readValue(
                    policy.getScheduleJson(),
                    new TypeReference<Map<String, List<Integer>>>() {});
            if (schedule.containsKey(currentDay)) {
                return schedule.get(currentDay).contains(currentHour);
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.error("GCP CloudSitter: Failed to parse schedule for policy {}, defaulting to RUNNING",
                    policy.getId(), e);
            return true;
        }
        return false;
    }
}
