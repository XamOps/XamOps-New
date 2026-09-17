package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.billing.budgets.v1.*;
import com.google.type.Money;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpBudgetCreateRequestDto;
import com.xammer.cloud.dto.gcp.GcpBudgetDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.BudgetEmailBuilder;
import com.xammer.cloud.service.EmailService;
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
    private final EmailService emailService;
    private final BudgetEmailBuilder budgetEmailBuilder;

    /**
     * Source of the spend figures the Budgets API does not return.
     *
     * <p>Lazy because the FinOps report reaches budgets through the cost service, and eager wiring
     * in both directions would be a startup cycle.
     */
    private final GcpCostService gcpCostService;

    @Value("${gcp.budget.mock:false}")
    private boolean useMockData;

    private static final String BUDGET_CACHE_PREFIX = "gcp:budgets:";
    private static final String MOCK_BUDGETS_CACHE_PREFIX = "gcp:mock-budgets:";

    public GcpBudgetService(GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            UserRepository userRepository,
            RedisCacheService redisCacheService,
            EmailService emailService,
            BudgetEmailBuilder budgetEmailBuilder,
            @org.springframework.context.annotation.Lazy GcpCostService gcpCostService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.redisCacheService = redisCacheService;
        this.emailService = emailService;
        this.budgetEmailBuilder = budgetEmailBuilder;
        this.gcpCostService = gcpCostService;
    }

    /**
     * Fills in the spend figures the Cloud Billing Budgets API does not return.
     *
     * <p>The API describes a budget's configuration — name, amount, thresholds — but not its
     * consumption, which lives in the billing export. Both fields were previously left at a
     * hardcoded {@code 0.0}, so every budget in the list rendered at 0% however much had been spent
     * against it.
     *
     * <p>Spend is the project's month-to-date total, which is the figure a project-scoped budget is
     * evaluated against. A budget narrowed by service or label will read high here; that is recorded
     * in {@code spendUnavailableReason} rather than left for the reader to infer.
     */
    private void backfillSpend(List<GcpBudgetDto> budgets, String gcpProjectId) {
        if (budgets == null || budgets.isEmpty()) {
            return;
        }
        try {
            double mtdSpend = gcpCostService.getUnfilteredMonthToDateSpend(gcpProjectId).join();
            double forecast = gcpCostService.calculateForecastedSpend(mtdSpend);
            budgets.forEach(b -> {
                b.setActualSpend(mtdSpend);
                b.setForecastedSpend(forecast);
            });
            log.info("Backfilled spend for {} budget(s) on project {}: MTD {}, forecast {}",
                    budgets.size(), gcpProjectId, mtdSpend, forecast);
        } catch (Exception e) {
            // Null, not zero. A budget showing 0% because the billing export could not be read is
            // indistinguishable from one that is genuinely untouched, and only one of those is safe
            // to ignore.
            log.warn("Could not read month-to-date spend for project {}; budget spend reported as "
                    + "unavailable: {}", gcpProjectId, e.getMessage());
            budgets.forEach(b -> {
                b.setActualSpend(null);
                b.setForecastedSpend(null);
                b.setSpendUnavailableReason("Spend against this budget could not be read: the BigQuery "
                        + "billing export for this project returned no month-to-date total.");
            });
        }
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
    public List<GcpBudgetDto> getBudgetsSync(String accountId) {
        String cacheKey = BUDGET_CACHE_PREFIX + accountId;

        // Check cache first
        Optional<List<GcpBudgetDto>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<GcpBudgetDto>>() {
                });
        if (cached.isPresent()) {
            log.info("Returning cached budgets for account {}", accountId);
            return cached.get();
        }

        // Robust account lookup
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found for ID: " + accountId));
        String gcpProjectId = account.getGcpProjectId();

        List<GcpBudgetDto> allBudgets = new ArrayList<>();

        // Locally-stored budgets are NOT merged into real results any more.
        //
        // They used to be, unconditionally — "always include mock/local budgets from Redis". A
        // locally-stored budget is one this service invented when the real Budgets API call failed:
        // it never evaluates spend and never alerts anyone, but it appeared in the list beside real
        // budgets with nothing to distinguish it. Any left over from that behaviour are reported
        // once here and then ignored, so the list only ever contains budgets that actually exist in
        // GCP.
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        Optional<List<GcpBudgetDto>> strandedLocal = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {
                });
        if (strandedLocal.isPresent() && !strandedLocal.get().isEmpty() && !useMockData) {
            log.warn("Ignoring {} locally-stored budget(s) for project {} — these were never created in "
                    + "GCP and cannot alert. Recreate them once the service account has budget "
                    + "permissions.", strandedLocal.get().size(), gcpProjectId);
        }

        // Mock mode is a local-development affordance only; the flag defaults to false.
        if (useMockData) {
            allBudgets.addAll(strandedLocal.orElseGet(java.util.ArrayList::new));
            if (allBudgets.isEmpty()) {
                allBudgets = getMockBudgets(gcpProjectId);
            }
            log.warn("gcp.budget.mock is enabled — returning {} SYNTHETIC budgets for project {}",
                    allBudgets.size(), gcpProjectId);
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

        // The Budgets API returns configuration only, so spend has to come from the billing export.
        // Without this the list served real budget names and limits with every spend field at zero.
        backfillSpend(allBudgets, gcpProjectId);

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
    public GcpBudgetDto createBudget(String accountId, GcpBudgetCreateRequestDto request, String username) {
        // Robust account lookup
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found for ID: " + accountId));
        String gcpProjectId = account.getGcpProjectId();

        log.info("Creating GCP budget '{}' for project {} by user {}",
                request.getBudgetName(), gcpProjectId, username);

        GcpBudgetDto result;

        // Mock data mode - store in Redis
        if (useMockData) {
            result = createMockBudget(accountId, gcpProjectId, request);
        } else {
            // Fails loudly rather than falling back to local storage.
            //
            // The fallback used to write an invented budget into Redis, send a "budget created"
            // email and return success. That budget existed only inside XamOps: it never evaluated
            // spend and never alerted, so the user believed they had a cost guardrail that could not
            // fire. A clear failure they can act on is strictly better than a silent one they
            // cannot see.
            try {
                result = createBudgetViaGcpApi(accountId, gcpProjectId, request, username);
            } catch (Exception e) {
                log.error("Failed to create budget via GCP API for project {}: {}", gcpProjectId,
                        e.getMessage());

                boolean permissionProblem = e.getMessage() != null
                        && (e.getMessage().contains("PERMISSION_DENIED")
                                || e.getMessage().contains("does not have permission"));

                throw new IllegalStateException(permissionProblem
                        ? "The service account cannot create budgets on this project. Grant 'Billing "
                                + "Account Administrator' or 'Budget Admin' and try again."
                        : "GCP rejected the budget: " + e.getMessage(), e);
            }
        }

        // Send budget-creation notification email (failure must not block the response)
        sendBudgetCreatedEmail(request, gcpProjectId, username);

        return result;
    }

    private void sendBudgetCreatedEmail(GcpBudgetCreateRequestDto request, String gcpProjectId, String username) {
        try {
            userRepository.findByUsername(username).ifPresent(user -> {
                String email = user.getEmail();
                if (email != null && !email.isBlank()) {
                    String subject = "GCP Budget Created: " + request.getBudgetName();
                    String html = budgetEmailBuilder.buildGcpBudgetCreatedEmail(
                            request.getBudgetName(), request.getBudgetLimit(),
                            request.getCurrencyCode() != null ? request.getCurrencyCode() : "USD",
                            gcpProjectId);
                    emailService.sendHtmlEmail(email, subject, html);
                    log.info("Budget creation notification sent to {} for project {}", email, gcpProjectId);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to send budget creation email for project {}: {}", gcpProjectId, ex.getMessage());
        }
    }



    /**
     * Create a mock budget stored in Redis (for testing or when GCP permissions are
     * unavailable).
     */
    private GcpBudgetDto createMockBudget(String accountId, String gcpProjectId, GcpBudgetCreateRequestDto request) {
        log.info("[LOCAL] Creating local budget '{}' with limit {} for project {}",
                request.getBudgetName(), request.getBudgetLimit(), gcpProjectId);

        GcpBudgetDto newBudget = new GcpBudgetDto(
                request.getBudgetName(),
                request.getBudgetLimit(),
                0.0, // actual spend
                request.getBudgetLimit() * 0.7 // forecasted (placeholder)
        );

        // Get existing mock budgets and add the new one
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        List<GcpBudgetDto> existingBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {
                }).orElse(new ArrayList<>());

        // Check for duplicate name
        boolean exists = existingBudgets.stream()
                .anyMatch(b -> b.getBudgetName().equals(request.getBudgetName()));
        if (exists) {
            throw new RuntimeException("Budget with name '" + request.getBudgetName() + "' already exists");
        }

        existingBudgets.add(newBudget);
        redisCacheService.put(mockCacheKey, existingBudgets, 1440); // Cache for 24 hours

        evictBudgetCache(accountId, gcpProjectId);
        log.info("Successfully created local budget '{}' for project {}", request.getBudgetName(), gcpProjectId);
        return newBudget;
    }

    /**
     * Create budget via actual GCP Cloud Billing Budgets API.
     */
    private GcpBudgetDto createBudgetViaGcpApi(String accountId, String gcpProjectId, GcpBudgetCreateRequestDto request,
            String username) {
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

            Money.Builder moneyBuilder = Money.newBuilder()
                    .setCurrencyCode(currencyCode)
                    .setUnits(units);
            if (nanos > 0) {
                moneyBuilder.setNanos(nanos);
            }
            Money budgetMoney = moneyBuilder.build();

            BudgetAmount budgetAmount = BudgetAmount.newBuilder()
                    .setSpecifiedAmount(budgetMoney)
                    .build();

            // Build budget with standard monthly filter and threshold mappings
            Budget.Builder budgetBuilder = Budget.newBuilder()
                    .setDisplayName(request.getBudgetName())
                    .setAmount(budgetAmount)
                    .setBudgetFilter(Filter.newBuilder()
                            .addProjects("projects/" + gcpProjectId)
                            .setCalendarPeriod(CalendarPeriod.MONTH)
                            .build());

            // Add alert threshold rules
            List<Double> thresholds = (request.getAlertThresholds() != null && !request.getAlertThresholds().isEmpty())
                    ? request.getAlertThresholds()
                    : List.of(0.5, 0.8, 0.9, 1.0);

            for (Double threshold : thresholds) {
                budgetBuilder.addThresholdRules(ThresholdRule.newBuilder()
                        .setThresholdPercent(threshold)
                        .setSpendBasis(ThresholdRule.Basis.CURRENT_SPEND)
                        .build());
            }

            Budget budget = budgetBuilder.build();

            log.info("Built Budget object: {}", budget.toString());

            CreateBudgetRequest createRequest = CreateBudgetRequest.newBuilder()
                    .setParent(parent)
                    .setBudget(budget)
                    .build();

            log.info("Creating GCP budget with: parent={}, displayName={}, amount={} {}, project={}",
                    parent, request.getBudgetName(), units, currencyCode, gcpProjectId);

            Budget createdBudget = client.createBudget(createRequest);
            log.info("Successfully created GCP budget via API: {}", createdBudget.getName());

            evictBudgetCache(accountId, gcpProjectId);
            // Report the real spend the new budget starts life against rather than an assumed zero:
            // a budget created mid-month already has the month's spend counting towards it.
            GcpBudgetDto created =
                    new GcpBudgetDto(request.getBudgetName(), request.getBudgetLimit(), null, null);
            backfillSpend(List.of(created), gcpProjectId);
            return created;

        } catch (com.google.api.gax.rpc.ApiException e) {
            String details = "";
            if (e.getCause() instanceof io.grpc.StatusRuntimeException) {
                io.grpc.StatusRuntimeException statusEx = (io.grpc.StatusRuntimeException) e.getCause();
                details = " | Trailers: " + statusEx.getTrailers();
            }
            log.error("GCP API Error creating budget '{}' for project {}: StatusCode={}, Reason={}, Message={}{}",
                    request.getBudgetName(), gcpProjectId, e.getStatusCode(), e.getReason(), e.getMessage(), details);
            throw new RuntimeException("GCP API Error: " + e.getMessage(), e);
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
    public void deleteBudget(String accountId, String budgetName) {
        // Robust account lookup
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found for ID: " + accountId));
        String gcpProjectId = account.getGcpProjectId();

        log.info("Deleting GCP budget '{}' for project {}", budgetName, gcpProjectId);

        // First, try to delete from local/mock storage
        boolean deletedFromLocal = deleteLocalBudget(gcpProjectId, budgetName);

        // If mock mode or deleted from local, we're done
        if (useMockData) {
            evictBudgetCache(accountId, gcpProjectId);
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
                evictBudgetCache(accountId, gcpProjectId);
                return;
            }

            // Check if it's a permission error
            if (e.getMessage() != null && e.getMessage().contains("PERMISSION_DENIED")) {
                log.warn("GCP Budget API permission denied. Cannot delete budget '{}' from GCP.", budgetName);
                if (deletedFromLocal) {
                    evictBudgetCache(accountId, gcpProjectId);
                    return;
                }
            }
            throw e;
        }

        evictBudgetCache(accountId, gcpProjectId);
    }

    /**
     * Delete a budget from local Redis storage.
     * 
     * @return true if budget was found and deleted
     */
    private boolean deleteLocalBudget(String gcpProjectId, String budgetName) {
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        Optional<List<GcpBudgetDto>> mockBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {
                });

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
    private void evictBudgetCache(String accountId, String gcpProjectId) {
        // Evict by both IDs to be safe, as fetching uses accountId but internal logic
        // often uses gcpProjectId
        redisCacheService.evict(BUDGET_CACHE_PREFIX + accountId);
        redisCacheService.evict("gcp:finops-report:" + accountId);

        if (gcpProjectId != null && !gcpProjectId.equals(accountId)) {
            redisCacheService.evict(BUDGET_CACHE_PREFIX + gcpProjectId);
            redisCacheService.evict("gcp:finops-report:" + gcpProjectId);
        }
    }

    /**
     * Map GCP Budget to DTO.
     */
    private GcpBudgetDto mapToDto(Budget budget) {
        double limit = 0.0;
        if (budget.hasAmount() && budget.getAmount().hasSpecifiedAmount()) {
            Money money = budget.getAmount().getSpecifiedAmount();
            limit = money.getUnits() + (money.getNanos() / 1_000_000_000.0);
        }
        // Spend is left null here and filled in by backfillSpend, which reads the billing export.
        // These were hardcoded to 0.0, and because that is a perfectly plausible spend figure the
        // zero travelled all the way to the progress bar as though it had been measured.
        return new GcpBudgetDto(budget.getDisplayName(), limit, null, null);
    }

    /**
     * Generate mock budgets for testing.
     */
    private List<GcpBudgetDto> getMockBudgets(String gcpProjectId) {
        return List.of(
                new GcpBudgetDto("Monthly Cloud Run Budget", 500.0, 234.50, 450.0),
                new GcpBudgetDto("Compute Engine Budget", 1000.0, 750.25, 920.0),
                new GcpBudgetDto("BigQuery Analysis Budget", 200.0, 89.99, 110.0));
    }
}
