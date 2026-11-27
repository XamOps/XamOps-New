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

    /** DB lease so only one instance runs each enforcement cycle. */
    @Autowired(required = false)
    private com.xammer.cloud.service.upwind.UpwindSyncLease schedulerLease;

    /** Azure list prices (USD), for each VM's 24/7 run cost on the page. */
    @Autowired(required = false)
    private AzureRetailPricingService retailPricingService;

    /** Converts those USD prices into the subscription's billing currency. */
    @Autowired(required = false)
    private AzureBillingCurrencyService billingCurrencyService;

    /**
     * AWS and GCP assignments. Policies live in one table shared by all three clouds, so these are
     * read to keep the Azure page from listing, editing or deleting a policy that schedules EC2 or
     * GCE instances.
     */
    @Autowired(required = false)
    private CloudsitterAssignmentRepository awsAssignmentRepo;

    @Autowired(required = false)
    private GcpCloudsitterAssignmentRepository gcpAssignmentRepo;

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

    /**
     * Policies the Azure page can use: every policy except those scheduling AWS or GCP instances and
     * no Azure VM. The table is shared by the three clouds, so this listed EC2 and GCE policies too,
     * and assigning or editing one from here changed those instances' schedules.
     */
    @Transactional(readOnly = true)
    public List<CloudsitterPolicy> getAllPolicies() {
        Set<Long> otherClouds = policyIdsUsedByOtherClouds();
        if (otherClouds.isEmpty()) {
            return policyRepo.findAll();
        }
        Set<Long> azureUsed = assignmentRepo.findAllByActiveTrue().stream()
                .map(AzureCloudsitterAssignment::getPolicy)
                .filter(Objects::nonNull)
                .map(CloudsitterPolicy::getId)
                .collect(Collectors.toSet());
        return policyRepo.findAll().stream()
                .filter(p -> !otherClouds.contains(p.getId()) || azureUsed.contains(p.getId()))
                .collect(Collectors.toList());
    }

    /** Ids of policies that active AWS or GCP assignments use. */
    private Set<Long> policyIdsUsedByOtherClouds() {
        Set<Long> ids = new HashSet<>();
        if (awsAssignmentRepo != null) {
            awsAssignmentRepo.findAllByActiveTrue().stream()
                    .map(CloudsitterAssignment::getPolicy)
                    .filter(Objects::nonNull)
                    .forEach(p -> ids.add(p.getId()));
        }
        if (gcpAssignmentRepo != null) {
            gcpAssignmentRepo.findAllByActiveTrue().stream()
                    .map(GcpCloudsitterAssignment::getPolicy)
                    .filter(Objects::nonNull)
                    .forEach(p -> ids.add(p.getId()));
        }
        return ids;
    }

    /**
     * Refuses a change to a policy that AWS or GCP instances also run on: from the Azure page it
     * would silently move their start and stop times, or delete a policy they depend on.
     */
    private void requireNotUsedByOtherClouds(Long policyId, String action) {
        if (policyIdsUsedByOtherClouds().contains(policyId)) {
            throw new IllegalStateException("This policy also schedules AWS or GCP instances, so it "
                    + "cannot be " + action + " from the Azure page. Change it from that cloud's "
                    + "CloudSitter page, or create a separate policy for Azure.");
        }
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
        requireNotUsedByOtherClouds(id, "edited");
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
        // Only Azure assignments are removed below, so deleting a policy EC2 or GCE instances use
        // either failed on their foreign key or left them pointing at a deleted policy.
        requireNotUsedByOtherClouds(id, "deleted");
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
        return listVmsForSubscription(subscriptionId, false);
    }

    /**
     * @param refresh skip the 60-minute list cache. The list carries each VM's power state, so the
     *                page's Refresh button showed states up to an hour old without this.
     */
    public List<Map<String, String>> listVmsForSubscription(String subscriptionId, boolean refresh) {
        String cacheKey = "azure-cloudsitter-vms:" + subscriptionId;

        if (refresh) {
            cacheService.evict(cacheKey);
        } else {
            Optional<List<Map<String, String>>> cached = cacheService.get(
                    cacheKey, new TypeReference<List<Map<String, String>>>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        List<Map<String, String>> vms = fetchVmsFromAzure(subscriptionId);
        if (!vms.isEmpty()) {
            cacheService.put(cacheKey, vms, VM_LIST_CACHE_TTL_MINUTES);
        }
        return vms;
    }

    /**
     * Lists the subscription's VMs, or throws when Azure cannot be read. This returned an empty list
     * on any failure, which the page showed as "No instances matched your search criteria".
     */
    private List<Map<String, String>> fetchVmsFromAzure(String subscriptionId) {
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            AzureBillingCurrencyService.BillingCurrency billing = billingCurrencyService != null
                    ? billingCurrencyService.forSubscription(subscriptionId)
                    : AzureBillingCurrencyService.BillingCurrency.USD;
            List<Map<String, String>> result = new ArrayList<>();
            for (VirtualMachine vm : azure.virtualMachines().list()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("name", vm.name());
                entry.put("resourceGroup", vm.resourceGroupName());
                entry.put("region", vm.regionName());
                String size = vm.size() != null ? vm.size().toString() : "Unknown";
                entry.put("size", size);
                // powerState() reads the instance view, one call per VM. One failure used to abort the
                // whole listing; now that VM is listed with an unknown state.
                String state;
                try {
                    state = vm.powerState() != null ? vm.powerState().toString() : "Unknown";
                } catch (Exception e) {
                    logger.warn("Azure CloudSitter: could not read power state of VM {}: {}", vm.name(), e.getMessage());
                    state = "Unknown";
                }
                entry.put("state", state);
                // The page priced every Azure VM from an EC2 price table, which has no Azure sizes,
                // so each one got the same made-up $0.05/hour. This is Azure's list price for the
                // size in its region, 730 hours a month, in the billing currency.
                monthlyRunCost(size, vm.regionName(), billing).ifPresent(cost -> {
                    entry.put("monthlyCost", String.valueOf(cost));
                    entry.put("currency", billing.code());
                });
                result.add(entry);
            }
            return result;
        } catch (Exception e) {
            logger.error("Azure CloudSitter: Failed to list VMs for subscription {}", subscriptionId, e);
            throw new IllegalStateException("Could not list the subscription's virtual machines: " + e.getMessage(), e);
        }
    }

    /** A VM size's 24/7 monthly list price in the billing currency; empty when it cannot be priced. */
    private Optional<Double> monthlyRunCost(String size, String region,
                                            AzureBillingCurrencyService.BillingCurrency billing) {
        if (retailPricingService == null || size == null || "Unknown".equals(size) || region == null) {
            return Optional.empty();
        }
        try {
            return retailPricingService.getVmMonthlyPriceUsd(size, region)
                    .map(usd -> Math.round(billing.fromUsd(usd) * 100.0) / 100.0);
        } catch (Exception e) {
            logger.debug("Azure CloudSitter: no list price for {} in {}: {}", size, region, e.getMessage());
            return Optional.empty();
        }
    }

    // --- ENFORCEMENT ENGINE ---

    // Five seconds after the AWS scheduler ("0 0/10"), as intended. This was "0 5/10", which is
    // five minutes, so every scheduled start and stop happened at :05 rather than on the hour.
    @Scheduled(cron = "5 0/10 * * * *")
    public void enforcePolicies() {
        // One cycle per deployment, not per instance (see CloudsitterService#enforcePolicies).
        if (schedulerLease == null) {
            runEnforcementCycle();
            return;
        }
        schedulerLease.runIfLeader("CLOUDSITTER_AZURE", java.time.Duration.ofMinutes(9), this::runEnforcementCycle);
    }

    private void runEnforcementCycle() {
        long start = System.currentTimeMillis();
        logger.info("Azure CloudSitter: Starting policy enforcement cycle at {}", ZonedDateTime.now());
        try {
            List<TenantDto> tenants = tenantService.getAllActiveTenants();
            int totalAssignments = 0, totalActions = 0;
            if (tenants.isEmpty()) {
                // Single-tenant mode: getAllActiveTenants() always returns an empty list, so the
                // tenant loop below never ran and no Azure schedule was ever enforced. Run once
                // against the "system" tenant, the one TenantFilter assigns to every request.
                TenantContext.setCurrentTenant("system");
                try {
                    int[] results = self.enforcePoliciesForCurrentTenant();
                    totalAssignments += results[0];
                    totalActions += results[1];
                } catch (Exception e) {
                    logger.error("Azure CloudSitter: Enforcement failed", e);
                } finally {
                    TenantContext.clear();
                }
            }
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
        // "Stopped" is an OS shutdown: the VM keeps its compute allocation and Azure still bills
        // for it. Only RUNNING and DEALLOCATED were handled, so a stopped VM was neither started
        // during its scheduled hours (the AWS scheduler starts stopped instances) nor
        // deallocated outside them, and kept billing.
        boolean isStopped = PowerState.STOPPED.equals(currentState);

        if (shouldBeRunning && (isDeallocated || isStopped)) {
            logger.info("Azure CloudSitter: STARTING VM {} in RG {} (policy: {})", vmName, resourceGroup, policy.getName());
            return startVm(subscriptionId, resourceGroup, vmName);
        } else if (!shouldBeRunning && (isRunning || isStopped)) {
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
        // The page's VM list carries power state too; drop it so the change shows on the next load.
        cacheService.evict("azure-cloudsitter-vms:" + subscriptionId);
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
