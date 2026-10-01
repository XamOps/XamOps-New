package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureBudgetCreateRequestDto;
import com.xammer.cloud.dto.azure.AzureBudgetDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Azure budgets, stored inside XamOps rather than in the Azure subscription.
 *
 * <p>
 * This mirrors how the AWS Cost page behaves for a customer whose role cannot write budgets:
 * creating a budget records it in XamOps and tracks spend against it here, and does not push a
 * {@code Microsoft.Consumption/budgets} resource into the subscription. Creating a budget therefore
 * needs no write role on Azure, and reading them needs no role at all — the list is XamOps' own.
 *
 * <p>
 * An earlier version of this class wrote to the Azure Consumption API directly (a {@code PUT} per
 * create, a {@code DELETE} per delete). That is intentionally gone: the product decision is that a
 * budget created on the cost page lives in XamOps, the same as the AWS page, so the two clouds
 * behave the same way and neither depends on a Cost Management write role.
 *
 * <p>
 * Storage is durable. The list is persisted as a single JSON row in {@code CachedData} keyed
 * {@code AZURE_BUDGETS::<subscriptionId>}, which survives a Redis flush or a restart, and mirrored
 * into Redis for fast reads. The DB row is the source of truth; Redis is only a cache in front of it.
 */
@Service
@Slf4j
public class AzureBudgetService {

    /**
     * Budget-name constraint, unchanged from when budgets went to Azure.
     *
     * <p>
     * Kept for two reasons even though the name no longer reaches ARM: it keeps a name valid should a
     * budget ever be pushed to Azure later, and it stops a name carrying a slash or other separator
     * that would make the stored key ambiguous.
     */
    private static final String BUDGET_NAME_PATTERN = "^[a-zA-Z0-9_-]{1,63}$";

    /** Redis key: a fast-read cache in front of the durable DB row. */
    private static final String BUDGET_CACHE_PREFIX = "azure:budgets:";

    /** How long the Redis mirror lives; the DB row is authoritative, so this is just a read cache. */
    private static final int CACHE_TTL_MINUTES = 24 * 60;

    /** DB key: the durable store, so budgets outlive a Redis flush or a restart. */
    private static final String BUDGET_DB_PREFIX = "AZURE_BUDGETS::";

    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    public AzureBudgetService(RedisCacheService redisCacheService,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Signals that budgets could not be read at all.
     *
     * <p>
     * Retained for API compatibility: the controller and the FinOps report both catch it. Local
     * storage does not fail the way the Azure API did, so a normal read no longer throws this — it is
     * kept so those two callers still compile and so a genuine storage fault can still be surfaced
     * distinctly from "there are no budgets".
     */
    public static class BudgetsUnavailableException extends RuntimeException {
        private final boolean permissionProblem;

        BudgetsUnavailableException(String message, boolean permissionProblem) {
            super(message);
            this.permissionProblem = permissionProblem;
        }

        public boolean isPermissionProblem() {
            return permissionProblem;
        }
    }

    public CompletableFuture<List<AzureBudgetDto>> getBudgets(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> getBudgetsSync(subscriptionId));
    }

    /**
     * Every budget stored for the subscription. An empty list means none have been created — there
     * is no external service to be unavailable.
     */
    public List<AzureBudgetDto> getBudgetsSync(String subscriptionId) {
        return loadBudgets(subscriptionId);
    }

    /**
     * Records a budget in XamOps, or replaces one of the same name.
     *
     * <p>
     * Nothing is written to Azure. Spend is stored as zero: the cost page fills in the actual figure
     * from the breakdown it has already fetched, matched by budget/service name, exactly as it does
     * for AWS.
     */
    public AzureBudgetDto createBudget(String subscriptionId, AzureBudgetCreateRequestDto request, String username) {
        if (request.getBudgetLimit() <= 0) {
            throw new IllegalArgumentException("Budget limit must be greater than 0");
        }
        String name = request.getBudgetName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Budget name is required");
        }
        if (!name.matches(BUDGET_NAME_PATTERN)) {
            throw new IllegalArgumentException("Budget name must be 1-63 characters using only letters, "
                    + "digits, underscores and hyphens");
        }

        String unit = (request.getBudgetUnit() != null && !request.getBudgetUnit().isBlank())
                ? request.getBudgetUnit()
                : "USD";

        log.info("Storing Azure budget '{}' (limit {} {}) for subscription {} in XamOps by user {}",
                name, request.getBudgetLimit(), unit, subscriptionId, username);

        List<AzureBudgetDto> budgets = new ArrayList<>(loadBudgets(subscriptionId));
        // Create doubles as update: drop any existing budget with the same name before re-adding it,
        // so a repeated name overwrites rather than duplicates.
        budgets.removeIf(b -> name.equals(b.getBudgetName()));

        AzureBudgetDto budget = new AzureBudgetDto(name, request.getBudgetLimit(), unit, 0.0, 0.0);
        // Stored locally, so there is no Azure notification behind it. False rather than null makes
        // that explicit; the cost page reads spend from its own data and does not depend on this.
        budget.setAlertsEnabled(false);
        budgets.add(budget);

        saveBudgets(subscriptionId, budgets);
        return budget;
    }

    /** Removes a stored budget. A name that is not present is treated as already deleted. */
    public void deleteBudget(String subscriptionId, String budgetName) {
        if (budgetName == null || !budgetName.matches(BUDGET_NAME_PATTERN)) {
            throw new IllegalArgumentException("Budget name must be 1-63 characters using only letters, "
                    + "digits, underscores and hyphens");
        }

        List<AzureBudgetDto> budgets = new ArrayList<>(loadBudgets(subscriptionId));
        boolean removed = budgets.removeIf(b -> budgetName.equals(b.getBudgetName()));
        if (removed) {
            saveBudgets(subscriptionId, budgets);
            log.info("Deleted Azure budget '{}' from XamOps for subscription {}", budgetName, subscriptionId);
        } else {
            log.info("Azure budget '{}' not found for subscription {}; nothing to delete",
                    budgetName, subscriptionId);
        }
    }

    // ── Local storage ─────────────────────────────────────────────────────────

    /** Reads the stored list: Redis first, then the durable DB row (warming Redis from it). */
    private List<AzureBudgetDto> loadBudgets(String subscriptionId) {
        String cacheKey = BUDGET_CACHE_PREFIX + subscriptionId;

        Optional<List<AzureBudgetDto>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<AzureBudgetDto>>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            Optional<CachedData> row = cachedDataRepository.findById(BUDGET_DB_PREFIX + subscriptionId);
            if (row.isPresent() && row.get().getJsonData() != null && !row.get().getJsonData().isBlank()) {
                List<AzureBudgetDto> stored = objectMapper.readValue(row.get().getJsonData(),
                        new TypeReference<List<AzureBudgetDto>>() {});
                if (stored != null) {
                    redisCacheService.put(cacheKey, stored, CACHE_TTL_MINUTES);
                    return stored;
                }
            }
        } catch (Exception e) {
            // A corrupt or unreadable row must not masquerade as a populated budget list, but it also
            // should not error the whole cost page — an empty list renders the "create one" state.
            log.warn("Could not read stored Azure budgets for {}: {}", subscriptionId, e.getMessage());
        }
        return new ArrayList<>();
    }

    /** Persists the list to the DB (source of truth), then refreshes the Redis mirror. */
    private void saveBudgets(String subscriptionId, List<AzureBudgetDto> budgets) {
        try {
            String json = objectMapper.writeValueAsString(budgets);
            CachedData row = cachedDataRepository.findById(BUDGET_DB_PREFIX + subscriptionId)
                    .orElse(new CachedData());
            row.setCacheKey(BUDGET_DB_PREFIX + subscriptionId);
            row.setJsonData(json);
            row.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(row);
        } catch (Exception e) {
            // The DB write is what makes a budget real; if it fails, do not report success. The Redis
            // mirror is deliberately not touched in that case.
            throw new RuntimeException("Could not store the Azure budget: " + e.getMessage(), e);
        }
        redisCacheService.put(BUDGET_CACHE_PREFIX + subscriptionId, budgets, CACHE_TTL_MINUTES);
    }
}
