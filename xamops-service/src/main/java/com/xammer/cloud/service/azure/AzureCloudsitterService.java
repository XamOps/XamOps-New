package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
public class AzureCloudsitterService {

    private static final Logger logger = LoggerFactory.getLogger(AzureCloudsitterService.class);

    private static final int VM_LIST_CACHE_TTL_MINUTES = 60;
    private static final int VM_STATE_CACHE_TTL_MINUTES = 5;

    private final AzureCloudsitterAssignmentRepository assignmentRepo;
    private final CloudsitterPolicyRepository policyRepo;
    private final CloudAccountRepository accountRepo;
    private final AzureClientProvider azureClientProvider;
    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final TenantService tenantService;

    @Autowired
    @Lazy
    private AzureCloudsitterService self;

    public AzureCloudsitterService(AzureCloudsitterAssignmentRepository assignmentRepo,
                                    CloudsitterPolicyRepository policyRepo,
                                    CloudAccountRepository accountRepo,
                                    AzureClientProvider azureClientProvider,
                                    RedisCacheService cacheService,
                                    ObjectMapper objectMapper,
                                    TenantService tenantService) {
        this.assignmentRepo = assignmentRepo;
        this.policyRepo = policyRepo;
        this.accountRepo = accountRepo;
        this.azureClientProvider = azureClientProvider;
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
    public Map<String, CloudsitterAssignmentDto> getAssignmentsForSubscription(String subscriptionId) {
        List<AzureCloudsitterAssignment> assignments =
                assignmentRepo.findAllBySubscriptionIdAndActiveTrue(subscriptionId);

        return assignments.stream()
                .collect(Collectors.toMap(
                        a -> a.getVmName() + "|" + a.getResourceGroup(),
                        a -> convertToDto(a),
                        (existing, replacement) -> existing));
    }

    private CloudsitterAssignmentDto convertToDto(AzureCloudsitterAssignment assignment) {
        CloudsitterAssignmentDto dto = new CloudsitterAssignmentDto();
        dto.setId(assignment.getId());
        dto.setResourceId(assignment.getVmName() + "|" + assignment.getResourceGroup());
        dto.setAccountId(assignment.getSubscriptionId());
        dto.setRegion(assignment.getResourceGroup());
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
        for (AzureCloudsitterAssignment a : assignmentRepo.findAll()) {
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
            // resourceKey format: "vmName|resourceGroup"
            String[] parts = resourceKey.split("\\|", 2);
            if (parts.length != 2) continue;
            String vmName = parts[0];
            String resourceGroup = parts[1];

            AzureCloudsitterAssignment assignment =
                    assignmentRepo.findByVmNameAndResourceGroupAndSubscriptionId(vmName, resourceGroup, request.getAccountId())
                            .orElse(new AzureCloudsitterAssignment());

            assignment.setVmName(vmName);
            assignment.setResourceGroup(resourceGroup);
            assignment.setSubscriptionId(request.getAccountId());
            assignment.setPolicy(policy);
            assignment.setActive(true);

            assignmentRepo.save(assignment);
            logger.info("Azure CloudSitter: Assigned policy {} to VM {} in RG {}", policy.getName(), vmName, resourceGroup);
        }
    }

    // --- CACHED VM LISTING ---

    public List<Map<String, String>> listVmsForSubscription(String subscriptionId) {
        String cacheKey = "azure-cloudsitter-vms:" + subscriptionId;

        Optional<List<Map<String, String>>> cached = cacheService.get(
                cacheKey, new TypeReference<List<Map<String, String>>>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Map<String, String>> vms = fetchVmsFromAzure(subscriptionId);
        if (!vms.isEmpty()) {
            cacheService.put(cacheKey, vms, VM_LIST_CACHE_TTL_MINUTES);
        }
        return vms;
    }

    private List<Map<String, String>> fetchVmsFromAzure(String subscriptionId) {
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            List<Map<String, String>> result = new ArrayList<>();
            for (VirtualMachine vm : azure.virtualMachines().list()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("name", vm.name());
                entry.put("resourceGroup", vm.resourceGroupName());
                entry.put("region", vm.regionName());
                entry.put("size", vm.size() != null ? vm.size().toString() : "Unknown");
                entry.put("state", vm.powerState() != null ? vm.powerState().toString() : "Unknown");
                result.add(entry);
            }
            return result;
        } catch (Exception e) {
            logger.error("Azure CloudSitter: Failed to list VMs for subscription {}", subscriptionId, e);
            return Collections.emptyList();
        }
    }

    // --- ENFORCEMENT ENGINE ---

    @Scheduled(cron = "0 5/10 * * * *") // Offset by 5 seconds from AWS scheduler to avoid contention
    public void enforcePolicies() {
        long start = System.currentTimeMillis();
        logger.info("Azure CloudSitter: Starting policy enforcement cycle at {}", ZonedDateTime.now());
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
                    logger.error("Azure CloudSitter: Failed for tenant {}", tenant.getTenantId(), e);
                } finally {
                    TenantContext.clear();
                }
            }
            logger.info("Azure CloudSitter: Enforcement complete in {}ms — {} assignments, {} actions",
                    System.currentTimeMillis() - start, totalAssignments, totalActions);
        } catch (Exception e) {
            logger.error("Azure CloudSitter: Fatal error during enforcement cycle", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int[] enforcePoliciesForCurrentTenant() {
        List<AzureCloudsitterAssignment> assignments = assignmentRepo.findAllByActiveTrue();
        int processed = 0, actions = 0;
        for (AzureCloudsitterAssignment assignment : assignments) {
            try {
                if (processAssignment(assignment)) actions++;
                processed++;
            } catch (Exception e) {
                logger.error("Azure CloudSitter: Failed to process assignment for VM {}",
                        assignment.getVmName(), e);
            }
        }
        return new int[]{processed, actions};
    }

    private boolean processAssignment(AzureCloudsitterAssignment assignment) {
        CloudsitterPolicy policy = assignment.getPolicy();
        String vmName = assignment.getVmName();
        String resourceGroup = assignment.getResourceGroup();
        String subscriptionId = assignment.getSubscriptionId();

        ZoneId timezone;
        try {
            timezone = ZoneId.of(policy.getTimeZone());
        } catch (Exception e) {
            timezone = ZoneId.of("UTC");
        }

        boolean shouldBeRunning = checkScheduleState(policy, ZonedDateTime.now(timezone));
        PowerState currentState = getVmPowerState(subscriptionId, resourceGroup, vmName);

        if (currentState == null) {
            logger.warn("Azure CloudSitter: Could not determine state for VM {} in RG {}", vmName, resourceGroup);
            return false;
        }

        boolean isRunning = PowerState.RUNNING.equals(currentState);
        boolean isDeallocated = PowerState.DEALLOCATED.equals(currentState)
                || PowerState.DEALLOCATING.equals(currentState);

        if (shouldBeRunning && isDeallocated) {
            logger.info("Azure CloudSitter: STARTING VM {} in RG {} (policy: {})", vmName, resourceGroup, policy.getName());
            return startVm(subscriptionId, resourceGroup, vmName);
        } else if (!shouldBeRunning && isRunning) {
            logger.info("Azure CloudSitter: DEALLOCATING VM {} in RG {} (policy: {})", vmName, resourceGroup, policy.getName());
            return deallocateVm(subscriptionId, resourceGroup, vmName);
        }
        return false;
    }

    private PowerState getVmPowerState(String subscriptionId, String resourceGroup, String vmName) {
        String cacheKey = "azure-cloudsitter-state:" + subscriptionId + ":" + resourceGroup + ":" + vmName;

        Optional<String> cached = cacheService.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return PowerState.fromString(cached.get());
        }

        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            VirtualMachine vm = azure.virtualMachines().getByResourceGroup(resourceGroup, vmName);
            if (vm == null) return null;
            PowerState state = vm.powerState();
            if (state != null) {
                cacheService.put(cacheKey, state.toString(), VM_STATE_CACHE_TTL_MINUTES);
            }
            return state;
        } catch (Exception e) {
            logger.warn("Azure CloudSitter: Failed to get power state for VM {} in RG {}: {}",
                    vmName, resourceGroup, e.getMessage());
            return null;
        }
    }

    private boolean startVm(String subscriptionId, String resourceGroup, String vmName) {
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            VirtualMachine vm = azure.virtualMachines().getByResourceGroup(resourceGroup, vmName);
            if (vm == null) return false;
            vm.start();
            evictStateCache(subscriptionId, resourceGroup, vmName);
            logger.info("Azure CloudSitter: START completed for VM {} in RG {}", vmName, resourceGroup);
            return true;
        } catch (Exception e) {
            logger.error("Azure CloudSitter: Failed to start VM {} in RG {}", vmName, resourceGroup, e);
            return false;
        }
    }

    private boolean deallocateVm(String subscriptionId, String resourceGroup, String vmName) {
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            VirtualMachine vm = azure.virtualMachines().getByResourceGroup(resourceGroup, vmName);
            if (vm == null) return false;
            vm.deallocate(); // stops billing for compute, not just OS shutdown
            evictStateCache(subscriptionId, resourceGroup, vmName);
            logger.info("Azure CloudSitter: DEALLOCATE completed for VM {} in RG {}", vmName, resourceGroup);
            return true;
        } catch (Exception e) {
            logger.error("Azure CloudSitter: Failed to deallocate VM {} in RG {}", vmName, resourceGroup, e);
            return false;
        }
    }

    private void evictStateCache(String subscriptionId, String resourceGroup, String vmName) {
        cacheService.evict("azure-cloudsitter-state:" + subscriptionId + ":" + resourceGroup + ":" + vmName);
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
            logger.error("Azure CloudSitter: Failed to parse schedule for policy {}, defaulting to RUNNING",
                    policy.getId(), e);
            return true;
        }
        return false;
    }
}
