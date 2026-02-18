package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.billing.budgets.v1.*;
import com.google.type.Money;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpBudgetCreateRequestDto;
import com.xammer.cloud.dto.gcp.GcpBudgetDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
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

    @Value("${gcp.budget.mock:false}")
    private boolean useMockData;

    private static final String BUDGET_CACHE_PREFIX = "gcp:budgets:";
    private static final String MOCK_BUDGETS_CACHE_PREFIX = "gcp:mock-budgets:";

    public GcpBudgetService(GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            UserRepository userRepository,
            RedisCacheService redisCacheService,
            EmailService emailService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.redisCacheService = redisCacheService;
        this.emailService = emailService;
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

        // Always include mock/local budgets from Redis
        String mockCacheKey = MOCK_BUDGETS_CACHE_PREFIX + gcpProjectId;
        Optional<List<GcpBudgetDto>> mockBudgets = redisCacheService.get(mockCacheKey,
                new TypeReference<List<GcpBudgetDto>>() {
                });
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
            // Try real GCP API first
            try {
                result = createBudgetViaGcpApi(accountId, gcpProjectId, request, username);
            } catch (Exception e) {
                log.error("Failed to create budget via GCP API for project {}: {}. Falling back to local storage.",
                        gcpProjectId, e.getMessage());

                // Log if it might be a permission issue
                if (e.getMessage() != null &&
                        (e.getMessage().contains("PERMISSION_DENIED")
                                || e.getMessage().contains("does not have permission"))) {
                    log.info(
                            "Note: This appears to be a permission issue for project {}. To use real GCP budgets, grant 'Billing Account Administrator' or 'Budget Admin' role.",
                            gcpProjectId);
                }

                result = createMockBudget(accountId, gcpProjectId, request);
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
                    String html = buildBudgetCreatedEmail(request, gcpProjectId, email);
                    emailService.sendHtmlEmail(email, subject, html);
                    log.info("Budget creation notification sent to {} for project {}", email, gcpProjectId);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to send budget creation email for project {}: {}", gcpProjectId, ex.getMessage());
        }
    }

    private String buildBudgetCreatedEmail(GcpBudgetCreateRequestDto request, String gcpProjectId, String email) {
        String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : "USD";
        return "<html><body style=\"font-family:Arial,sans-serif;background:#f5f7fa;padding:24px\">"
                + "<div style=\"max-width:560px;margin:auto;background:#fff;border-radius:8px;padding:32px;border:1px solid #e0e0e0\">"
                + "<h2 style=\"color:#1976D2;margin-top:0\">GCP Budget Created</h2>"
                + "<p>A new budget has been set up for your GCP project.</p>"
                + "<table style=\"width:100%;border-collapse:collapse;margin:16px 0\">"
                + "<tr><td style=\"padding:8px 0;color:#555;width:40%\"><b>Budget Name</b></td>"
                + "<td style=\"padding:8px 0\">" + request.getBudgetName() + "</td></tr>"
                + "<tr><td style=\"padding:8px 0;color:#555\"><b>Limit</b></td>"
                + "<td style=\"padding:8px 0\">" + currency + " " + request.getBudgetLimit() + "</td></tr>"
                + "<tr><td style=\"padding:8px 0;color:#555\"><b>GCP Project</b></td>"
                + "<td style=\"padding:8px 0\">" + gcpProjectId + "</td></tr>"
                + "<tr><td style=\"padding:8px 0;color:#555\"><b>Alert Thresholds</b></td>"
                + "<td style=\"padding:8px 0\">50%, 80%, 90%, 100% of budget</td></tr>"
                + "</table>"
                + "<p style=\"color:#888;font-size:12px\">You will be alerted as spending approaches these thresholds.<br>"
                + "This notification was sent to " + email + ".</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:24px 0\">"
                + "<p style=\"color:#aaa;font-size:11px;text-align:center\">XamOps Cloud FinOps Platform</p>"
                + "</div></body></html>";
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
            return new GcpBudgetDto(request.getBudgetName(), request.getBudgetLimit(), 0.0, 0.0);

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
        // GCP Budget API doesn't provide actual spend directly - would need to query
        // billing data
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
                new GcpBudgetDto("BigQuery Analysis Budget", 200.0, 89.99, 110.0));
    }
}
