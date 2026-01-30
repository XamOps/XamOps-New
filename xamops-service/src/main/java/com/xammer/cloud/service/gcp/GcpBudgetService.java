package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.billing.budgets.v1.*;
import com.google.type.Money;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.gcp.GcpBudgetCreateRequestDto;
import com.xammer.cloud.dto.gcp.GcpBudgetDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpBudgetService {

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final RedisCacheService redisCacheService;

    @Value("${gcp.budget.mock:false}")
    private boolean useMockData;

    private static final String BUDGET_CACHE_PREFIX = "gcp:budgets:";
    private static final String MOCK_BUDGETS_CACHE_PREFIX = "gcp:mock-budgets:";

    public GcpBudgetService(GcpClientProvider gcpClientProvider,
                           CloudAccountRepository cloudAccountRepository,
                           UserRepository userRepository,
                           RedisCacheService redisCacheService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.redisCacheService = redisCacheService;
    }

    /**
     * Get all budgets for a GCP project (async).
     */
    public CompletableFuture<List<GcpBudgetDto>> getBudgets(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getBudgetsSync(gcpProjectId));
    }

    /**
     * Synchronous version of getBudgets for internal use.
     * Returns both GCP API budgets and locally stored mock budgets.
     */
    public List<GcpBudgetDto> getBudgetsSync(String gcpProjectId) {
        String cacheKey = BUDGET_CACHE_PREFIX + gcpProjectId;

        // Check cache first
        Optional<List<GcpBudgetDto>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<GcpBudgetDto>>() {});
        if (cached.isPresent()) {
            log.info("Returning cached budgets for GCP project {}", gcpProjectId);
            return cached.get();
        }

        List<GcpBudgetDto> allBudgets = new ArrayList<>();

        // Always include mock/local budgets from Redis
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        Optional<List<GcpBudgetDto>> mockBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {});
        if (mockBudgets.isPresent()) {
            allBudgets.addAll(mockBudgets.get());
            log.info("Found {} local budgets for project {}", mockBudgets.get().size(), gcpProjectId);
        }

        // If mock data mode, just return local budgets
        if (useMockData) {
            if (allBudgets.isEmpty()) {
                allBudgets = getMockBudgets(gcpProjectId);
            }
            redisCacheService.put(cacheKey, allBudgets, 10);
            return allBudgets;
        }

        // Try to fetch from GCP API
        try {
            List<GcpBudgetDto> gcpBudgets = fetchBudgetsFromGcpApi(gcpProjectId);
            allBudgets.addAll(gcpBudgets);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("PERMISSION_DENIED")) {
                log.warn("GCP Budget API permission denied for project {}. Showing local budgets only.", gcpProjectId);
            } else {
                log.error("Failed to fetch GCP budgets for project {}: {}", gcpProjectId, e.getMessage());
            }
        }

        redisCacheService.put(cacheKey, allBudgets, 10);
        return allBudgets;
    }

    /**
     * Fetch budgets from GCP Cloud Billing Budgets API.
     */
    private List<GcpBudgetDto> fetchBudgetsFromGcpApi(String gcpProjectId) {
        // Get billing account ID
        Optional<String> billingAccountIdOpt = gcpClientProvider.getBillingAccountIdForProject(gcpProjectId);
        if (billingAccountIdOpt.isEmpty()) {
            log.warn("Could not determine billing account for project {}", gcpProjectId);
            return Collections.emptyList();
        }

        String billingAccountId = billingAccountIdOpt.get();
        Optional<BudgetServiceClient> clientOpt = gcpClientProvider.getBudgetServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.error("Failed to create BudgetServiceClient for project {}", gcpProjectId);
            return Collections.emptyList();
        }

        try (BudgetServiceClient client = clientOpt.get()) {
            String parent = "billingAccounts/" + billingAccountId;
            ListBudgetsRequest request = ListBudgetsRequest.newBuilder().setParent(parent).build();
            return StreamSupport.stream(
                    client.listBudgets(request).iterateAll().spliterator(), false)
                    .map(this::mapToDto)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch GCP budgets: " + e.getMessage(), e);
        }
    }

    /**
     * Create a new budget for a GCP project.
     * Falls back to storing in Redis if GCP API permissions are unavailable.
     */
    public GcpBudgetDto createBudget(String gcpProjectId, GcpBudgetCreateRequestDto request, String username) {
        log.info("Creating GCP budget '{}' for project {} by user {}",
                request.getBudgetName(), gcpProjectId, username);

        // Mock data mode - store in Redis
        if (useMockData) {
            return createMockBudget(gcpProjectId, request);
        }

        // Try real GCP API first
        try {
            return createBudgetViaGcpApi(gcpProjectId, request, username);
        } catch (Exception e) {
            // Check if it's a permission error - fall back to mock mode
            if (e.getMessage() != null &&
                (e.getMessage().contains("PERMISSION_DENIED") ||
                 e.getMessage().contains("does not have permission"))) {
                log.warn("GCP Budget API permission denied for project {}. Falling back to local storage. " +
                        "To use real GCP budgets, grant 'Billing Account Administrator' or 'Budget Admin' role.",
                        gcpProjectId);
                return createMockBudget(gcpProjectId, request);
            }
            throw e;
        }
    }

    /**
     * Create a mock budget stored in Redis (for testing or when GCP permissions are unavailable).
     */
    private GcpBudgetDto createMockBudget(String gcpProjectId, GcpBudgetCreateRequestDto request) {
        log.info("[LOCAL] Creating local budget '{}' with limit {} for project {}",
                request.getBudgetName(), request.getBudgetLimit(), gcpProjectId);

        GcpBudgetDto newBudget = new GcpBudgetDto(
                request.getBudgetName(),
                request.getBudgetLimit(),
                0.0,  // actual spend
                request.getBudgetLimit() * 0.7  // forecasted (placeholder)
        );

        // Get existing mock budgets and add the new one
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        List<GcpBudgetDto> existingBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {}).orElse(new ArrayList<>());

        // Check for duplicate name
        boolean exists = existingBudgets.stream()
                .anyMatch(b -> b.getBudgetName().equals(request.getBudgetName()));
        if (exists) {
            throw new RuntimeException("Budget with name '" + request.getBudgetName() + "' already exists");
        }

        existingBudgets.add(newBudget);
        redisCacheService.put(mockCacheKey, existingBudgets, 1440); // Cache for 24 hours

        evictBudgetCache(gcpProjectId);
        log.info("Successfully created local budget '{}' for project {}", request.getBudgetName(), gcpProjectId);
        return newBudget;
    }

    /**
     * Create budget via actual GCP Cloud Billing Budgets API.
     */
    private GcpBudgetDto createBudgetViaGcpApi(String gcpProjectId, GcpBudgetCreateRequestDto request, String username) {
        // Validate budget limit
        if (request.getBudgetLimit() <= 0) {
            throw new IllegalArgumentException("Budget limit must be greater than 0, got: " + request.getBudgetLimit());
        }

        // Get billing account ID
        Optional<String> billingAccountIdOpt = gcpClientProvider.getBillingAccountIdForProject(gcpProjectId);
        if (billingAccountIdOpt.isEmpty()) {
            throw new RuntimeException("Could not determine billing account for project " + gcpProjectId);
        }

        String billingAccountId = billingAccountIdOpt.get();
        Optional<BudgetServiceClient> clientOpt = gcpClientProvider.getBudgetServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            throw new RuntimeException("Failed to create BudgetServiceClient for project " + gcpProjectId);
        }

        try (BudgetServiceClient client = clientOpt.get()) {
            String parent = "billingAccounts/" + billingAccountId;

            // Build budget amount - handle both whole and fractional amounts
            String currencyCode = request.getCurrencyCode() != null ? request.getCurrencyCode() : "USD";
            long units = (long) request.getBudgetLimit();
            int nanos = (int) ((request.getBudgetLimit() - units) * 1_000_000_000);

            Money budgetMoney = Money.newBuilder()
                    .setCurrencyCode(currencyCode)
                    .setUnits(units)
                    .setNanos(nanos)
                    .build();

            BudgetAmount budgetAmount = BudgetAmount.newBuilder()
                    .setSpecifiedAmount(budgetMoney)
                    .build();

            // Build budget filter - set empty to apply to entire billing account
            Filter budgetFilter = Filter.newBuilder().build();

            // Build budget - keep it simple like AWS
            Budget.Builder budgetBuilder = Budget.newBuilder()
                    .setDisplayName(request.getBudgetName())
                    .setBudgetFilter(budgetFilter)
                    .setAmount(budgetAmount);

            // Only add NotificationsRule if we have notification channels configured
            // For now, we skip this to avoid INVALID_ARGUMENT errors
            // Future enhancement: create notification channel first, then add it here
            // NotificationsRule notificationsRule = NotificationsRule.newBuilder()
            //     .setDisableDefaultIamRecipients(false)
            //     .addMonitoringNotificationChannels("projects/.../notificationChannels/...")
            //     .build();
            // budgetBuilder.setNotificationsRule(notificationsRule);

            Budget budget = budgetBuilder.build();

            CreateBudgetRequest createRequest = CreateBudgetRequest.newBuilder()
                    .setParent(parent)
                    .setBudget(budget)
                    .build();

            log.info("Creating GCP budget with: parent={}, displayName={}, amount={} {}, budgetLimit={}",
                    parent, request.getBudgetName(), units, currencyCode, request.getBudgetLimit());

            Budget createdBudget = client.createBudget(createRequest);
            log.info("Successfully created GCP budget via API: {}", createdBudget.getName());

            evictBudgetCache(gcpProjectId);
            return new GcpBudgetDto(request.getBudgetName(), request.getBudgetLimit(), 0.0, 0.0);

        } catch (Exception e) {
            log.error("Failed to create GCP budget '{}' for project {}: {}",
                    request.getBudgetName(), gcpProjectId, e.getMessage(), e);
            throw new RuntimeException("Failed to create GCP budget: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a budget by name for a GCP project.
     * Handles both GCP API budgets and locally stored budgets.
     */
    public void deleteBudget(String gcpProjectId, String budgetName) {
        log.info("Deleting GCP budget '{}' for project {}", budgetName, gcpProjectId);

        // First, try to delete from local/mock storage
        boolean deletedFromLocal = deleteLocalBudget(gcpProjectId, budgetName);

        // If mock mode or deleted from local, we're done
        if (useMockData) {
            evictBudgetCache(gcpProjectId);
            return;
        }

        // Try to delete from GCP API
        try {
            deleteBudgetFromGcpApi(gcpProjectId, budgetName);
        } catch (Exception e) {
            // If deleted from local storage, that's OK
            if (deletedFromLocal) {
                log.info("Budget '{}' was deleted from local storage. GCP API delete skipped: {}",
                        budgetName, e.getMessage());
                evictBudgetCache(gcpProjectId);
                return;
            }

            // Check if it's a permission error
            if (e.getMessage() != null && e.getMessage().contains("PERMISSION_DENIED")) {
                log.warn("GCP Budget API permission denied. Cannot delete budget '{}' from GCP.", budgetName);
                if (deletedFromLocal) {
                    evictBudgetCache(gcpProjectId);
                    return;
                }
            }
            throw e;
        }

        evictBudgetCache(gcpProjectId);
    }

    /**
     * Delete a budget from local Redis storage.
     * @return true if budget was found and deleted
     */
    private boolean deleteLocalBudget(String gcpProjectId, String budgetName) {
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        Optional<List<GcpBudgetDto>> mockBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {});

        if (mockBudgets.isPresent()) {
            List<GcpBudgetDto> budgets = new ArrayList<>(mockBudgets.get());
            int originalSize = budgets.size();
            budgets.removeIf(b -> b.getBudgetName().equals(budgetName));

            if (budgets.size() < originalSize) {
                redisCacheService.put(mockCacheKey, budgets, 1440);
                log.info("Deleted local budget '{}' for project {}", budgetName, gcpProjectId);
                return true;
            }
        }
        return false;
    }

    /**
     * Delete a budget via GCP Cloud Billing Budgets API.
     */
    private void deleteBudgetFromGcpApi(String gcpProjectId, String budgetName) {
        // Get billing account ID
        Optional<String> billingAccountIdOpt = gcpClientProvider.getBillingAccountIdForProject(gcpProjectId);
        if (billingAccountIdOpt.isEmpty()) {
            throw new RuntimeException("Could not determine billing account for project " + gcpProjectId);
        }

        String billingAccountId = billingAccountIdOpt.get();
        Optional<BudgetServiceClient> clientOpt = gcpClientProvider.getBudgetServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            throw new RuntimeException("Failed to create BudgetServiceClient for project " + gcpProjectId);
        }

        try (BudgetServiceClient client = clientOpt.get()) {
            String parent = "billingAccounts/" + billingAccountId;

            // Find the budget by display name
            ListBudgetsRequest listRequest = ListBudgetsRequest.newBuilder()
                    .setParent(parent)
                    .build();

            String budgetResourceName = null;
            for (Budget budget : client.listBudgets(listRequest).iterateAll()) {
                if (budget.getDisplayName().equals(budgetName)) {
                    budgetResourceName = budget.getName();
                    break;
                }
            }

            if (budgetResourceName == null) {
                log.warn("Budget '{}' not found in GCP API for project {}.",
                        budgetName, gcpProjectId);
                return;
            }

            DeleteBudgetRequest deleteRequest = DeleteBudgetRequest.newBuilder()
                    .setName(budgetResourceName)
                    .build();

            client.deleteBudget(deleteRequest);
            log.info("Successfully deleted GCP budget via API: {}", budgetResourceName);

        } catch (Exception e) {
            log.error("Failed to delete GCP budget '{}' for project {}: {}",
                    budgetName, gcpProjectId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete GCP budget: " + e.getMessage(), e);
        }
    }

    /**
     * Evict budget cache for a GCP project.
     */
    private void evictBudgetCache(String gcpProjectId) {
        String cacheKey = BUDGET_CACHE_PREFIX + gcpProjectId;
        redisCacheService.evict(cacheKey);
        redisCacheService.evict("gcp:finops-report:" + gcpProjectId);
    }

    /**
     * Map GCP Budget to DTO.
     */
    private GcpBudgetDto mapToDto(Budget budget) {
        double limit = 0.0;
        if (budget.hasAmount() && budget.getAmount().hasSpecifiedAmount()) {
            limit = budget.getAmount().getSpecifiedAmount().getUnits();
        }
        // GCP Budget API doesn't provide actual spend directly - would need to query billing data
        double actual = 0.0;
        double forecast = 0.0;
        return new GcpBudgetDto(budget.getDisplayName(), limit, actual, forecast);
    }

    /**
     * Generate mock budgets for testing.
     */
    private List<GcpBudgetDto> getMockBudgets(String gcpProjectId) {
        return List.of(
                new GcpBudgetDto("Monthly Cloud Run Budget", 500.0, 234.50, 450.0),
                new GcpBudgetDto("Compute Engine Budget", 1000.0, 750.25, 920.0),
                new GcpBudgetDto("BigQuery Analysis Budget", 200.0, 89.99, 110.0)
        );
    }
}
