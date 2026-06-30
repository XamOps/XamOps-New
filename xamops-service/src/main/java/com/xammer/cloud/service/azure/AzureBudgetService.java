package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.azure.AzureBudgetDto;
import com.xammer.cloud.dto.azure.AzureBudgetCreateRequestDto;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AzureBudgetService {

    private final RedisCacheService redisCacheService;

    @Value("${azure.budget.mock:true}")
    private boolean useMockData;

    private static final String BUDGET_CACHE_PREFIX = "azure:budgets:";
    private static final String MOCK_BUDGETS_CACHE_PREFIX = "azure:mock-budgets:";

    public AzureBudgetService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /**
     * Get all budgets for an Azure subscription (async).
     */
    public CompletableFuture<List<AzureBudgetDto>> getBudgets(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> getBudgetsSync(subscriptionId));
    }

    /**
     * Synchronous version of getBudgets.
     * Returns both Azure API budgets and locally stored mock budgets.
     */
    public List<AzureBudgetDto> getBudgetsSync(String subscriptionId) {
        String cacheKey = BUDGET_CACHE_PREFIX + subscriptionId;

        // Check cache first
        Optional<List<AzureBudgetDto>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<AzureBudgetDto>>() {});
        if (cached.isPresent()) {
            log.info("Returning cached budgets for Azure subscription {}", subscriptionId);
            return cached.get();
        }

        List<AzureBudgetDto> allBudgets = new ArrayList<>();

        // Always include mock/local budgets from Redis
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + subscriptionId;
        Optional<List<AzureBudgetDto>> mockBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<AzureBudgetDto>>() {});
        if (mockBudgets.isPresent()) {
            allBudgets.addAll(mockBudgets.get());
            log.info("Found {} local budgets for Azure subscription {}", mockBudgets.get().size(), subscriptionId);
        }

        // If mock data mode, just return local budgets
        if (useMockData) {
            if (allBudgets.isEmpty()) {
                allBudgets = getMockBudgets(subscriptionId);
            }
            redisCacheService.put(cacheKey, allBudgets, 10);
            return allBudgets;
        }

        // Try to fetch from Azure Consumption API
        try {
            List<AzureBudgetDto> azureBudgets = fetchBudgetsFromAzureApi(subscriptionId);
            allBudgets.addAll(azureBudgets);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("AuthorizationFailed")) {
                log.warn("Azure Budget API permission denied for subscription {}. Showing local budgets only.",
                        subscriptionId);
            } else {
                log.error("Failed to fetch Azure budgets for subscription {}: {}", subscriptionId, e.getMessage());
            }
        }

        redisCacheService.put(cacheKey, allBudgets, 10);
        return allBudgets;
    }

    /**
     * Fetch budgets from Azure Consumption Budgets API.
     */
    private List<AzureBudgetDto> fetchBudgetsFromAzureApi(String subscriptionId) {
        try {
            // Note: Azure Consumption Budgets API integration would go here
            // GET https://management.azure.com/subscriptions/{subscriptionId}/providers/Microsoft.Consumption/budgets
            log.info("Azure budget API fetch attempted for subscription {}", subscriptionId);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch budgets from Azure API for subscription {}: {}",
                    subscriptionId, e.getMessage());
            throw new RuntimeException("Failed to fetch Azure budgets: " + e.getMessage(), e);
        }
    }

    /**
     * Create a new budget for an Azure subscription.
     * Falls back to storing in Redis if Azure API permissions are unavailable.
     */
    public AzureBudgetDto createBudget(String subscriptionId, AzureBudgetCreateRequestDto request, String username) {
        log.info("Creating Azure budget '{}' with limit {} for subscription {} by user {}",
                request.getBudgetName(), request.getBudgetLimit(), subscriptionId, username);

        AzureBudgetDto result;

        if (useMockData) {
            result = createMockBudget(subscriptionId, request);
        } else {
            // Try real Azure API first, fall back to local storage
            try {
                result = createBudgetViaAzureApi(subscriptionId, request, username);
            } catch (Exception e) {
                log.error("Failed to create budget via Azure API for subscription {}: {}. Falling back to local storage.",
                        subscriptionId, e.getMessage());
                result = createMockBudget(subscriptionId, request);
            }
        }

        return result;
    }

    /**
     * Create budget via Azure Consumption Budgets API.
     */
    private AzureBudgetDto createBudgetViaAzureApi(String subscriptionId, AzureBudgetCreateRequestDto request,
                                                    String username) {
        // Validate budget limit
        if (request.getBudgetLimit() <= 0) {
            throw new IllegalArgumentException("Budget limit must be greater than 0");
        }

        // For now, fall through to mock implementation
        // Real Azure Budget API integration would use:
        // PUT https://management.azure.com/subscriptions/{subscriptionId}/providers/Microsoft.Consumption/budgets/{budgetName}
        log.info("Azure Budget API create not yet fully implemented. Using local storage for subscription {}",
                subscriptionId);
        return createMockBudget(subscriptionId, request);
    }

    /**
     * Create a mock budget stored in Redis.
     */
    private AzureBudgetDto createMockBudget(String subscriptionId, AzureBudgetCreateRequestDto request) {
        log.info("[LOCAL] Creating local budget '{}' with limit {} for Azure subscription {}",
                request.getBudgetName(), request.getBudgetLimit(), subscriptionId);

        AzureBudgetDto newBudget = new AzureBudgetDto(
                request.getBudgetName(),
                request.getBudgetLimit(),
                request.getBudgetUnit() != null ? request.getBudgetUnit() : "USD",
                0.0, // actual spend
                request.getBudgetLimit() * 0.7 // forecasted (placeholder)
        );

        // Get existing mock budgets and add the new one
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + subscriptionId;
        List<AzureBudgetDto> existingBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<AzureBudgetDto>>() {}).orElse(new ArrayList<>());

        // Check for duplicate name
        boolean exists = existingBudgets.stream()
                .anyMatch(b -> b.getBudgetName().equals(request.getBudgetName()));
        if (exists) {
            throw new RuntimeException("Budget with name '" + request.getBudgetName() + "' already exists");
        }

        existingBudgets.add(newBudget);
        redisCacheService.put(mockCacheKey, existingBudgets, 1440); // Cache for 24 hours

        evictBudgetCache(subscriptionId);
        log.info("Successfully created local budget '{}' for Azure subscription {}", request.getBudgetName(), subscriptionId);
        return newBudget;
    }

    /**
     * Delete a budget by name for an Azure subscription.
     */
    public void deleteBudget(String subscriptionId, String budgetName) {
        log.info("Deleting Azure budget '{}' for subscription {}", budgetName, subscriptionId);

        // Try to delete from local/mock storage
        boolean deletedFromLocal = deleteLocalBudget(subscriptionId, budgetName);

        if (useMockData) {
            evictBudgetCache(subscriptionId);
            return;
        }

        // Try to delete from Azure API
        try {
            deleteBudgetFromAzureApi(subscriptionId, budgetName);
        } catch (Exception e) {
            if (deletedFromLocal) {
                log.info("Budget '{}' was deleted from local storage. Azure API delete skipped: {}",
                        budgetName, e.getMessage());
                evictBudgetCache(subscriptionId);
                return;
            }
            throw e;
        }

        evictBudgetCache(subscriptionId);
    }

    /**
     * Delete a budget from local Redis storage.
     */
    private boolean deleteLocalBudget(String subscriptionId, String budgetName) {
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + subscriptionId;
        Optional<List<AzureBudgetDto>> mockBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<AzureBudgetDto>>() {});

        if (mockBudgets.isPresent()) {
            List<AzureBudgetDto> budgets = new ArrayList<>(mockBudgets.get());
            int originalSize = budgets.size();
            budgets.removeIf(b -> b.getBudgetName().equals(budgetName));

            if (budgets.size() < originalSize) {
                redisCacheService.put(mockCacheKey, budgets, 1440);
                log.info("Deleted local budget '{}' for Azure subscription {}", budgetName, subscriptionId);
                return true;
            }
        }
        return false;
    }

    /**
     * Delete a budget via Azure Consumption Budgets API.
     */
    private void deleteBudgetFromAzureApi(String subscriptionId, String budgetName) {
        // DELETE https://management.azure.com/subscriptions/{subscriptionId}/providers/Microsoft.Consumption/budgets/{budgetName}
        log.info("Azure Budget API delete not yet fully implemented for subscription {}", subscriptionId);
        throw new RuntimeException("Azure Budget API integration pending");
    }

    /**
     * Evict budget cache for an Azure subscription.
     */
    private void evictBudgetCache(String subscriptionId) {
        redisCacheService.evict(BUDGET_CACHE_PREFIX + subscriptionId);
        redisCacheService.evict("azure:finops-report:" + subscriptionId);
    }

    /**
     * Generate mock budgets for testing.
     */
    private List<AzureBudgetDto> getMockBudgets(String subscriptionId) {
        return List.of(
                new AzureBudgetDto("Monthly VM Budget", 800.0, "USD", 456.30, 650.0),
                new AzureBudgetDto("Storage Budget", 200.0, "USD", 112.50, 160.0),
                new AzureBudgetDto("App Service Budget", 500.0, "USD", 380.75, 520.0)
        );
    }
}
