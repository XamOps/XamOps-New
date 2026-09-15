package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.azure.AzureBudgetDto;
import com.xammer.cloud.dto.azure.AzureBudgetCreateRequestDto;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Azure budgets, read and written through the Consumption Budgets API.
 *
 * <p>
 * This class previously could not talk to Azure at all — it had no SDK import and no credential.
 * Instead, {@code azure.budget.mock} defaulted to {@code true} and was set in no properties file,
 * so the mock path was what ran in production: three invented budgets ("Monthly VM Budget", $800
 * limit, $456.30 spent) served from a hardcoded list. Those reached both {@code /finops/budgets}
 * and the Azure FinOps report, where they were indistinguishable from real data. Creating a budget
 * wrote a fabricated row to Redis and reported success; deleting one threw
 * {@code "Azure Budget API integration pending"}.
 *
 * <p>
 * Everything here now goes to {@code Microsoft.Consumption/budgets}. Three things about that API
 * shape the code:
 *
 * <p>
 * <b>It does not cover every subscription.</b> Microsoft documents Consumption as supporting
 * Enterprise Agreement, Web Direct and CSP Azure-plan subscriptions only. On anything else the
 * call fails, and the honest answer is an empty list plus a log line naming the reason — not a
 * fabricated budget.
 *
 * <p>
 * <b>Start dates are constrained.</b> A budget must start on the first of a month, on or after
 * June 2017, and no more than twelve months ahead. A naive {@code LocalDate.now()} is rejected, so
 * the start is snapped to the first of the current month.
 *
 * <p>
 * <b>Writes need more than read access.</b> Listing needs {@code Microsoft.Consumption/budgets/read}
 * (Cost Management Reader); create and delete need write, which Reader does not carry. A 403 on
 * write is surfaced as a clear message rather than silently falling back to a local copy, because
 * a budget that exists only in XamOps' Redis will never actually alert anyone.
 */
@Service
@Slf4j
public class AzureBudgetService {

    private static final String ARM = "https://management.azure.com";
    private static final String SCOPE = "https://management.azure.com/.default";

    /**
     * Consumption API version. Budgets have been stable across recent versions; bump deliberately
     * and re-check {@code currentSpend} / {@code forecastSpend} if you do.
     */
    private static final String API_VERSION = "2023-05-01";

    private static final String BUDGET_CACHE_PREFIX = "azure:budgets:";
    private static final int CACHE_TTL_MINUTES = 10;

    /** Budgets accrue over a recurring period; monthly is what the UI's single limit field means. */
    private static final String DEFAULT_TIME_GRAIN = "Monthly";

    /** Azure's constraint on budget names. Applied before create and delete alike. */
    private static final String BUDGET_NAME_PATTERN = "^[a-zA-Z0-9_-]{1,63}$";

    /**
     * How far ahead a created budget runs.
     *
     * <p>
     * Azure requires an end date and places no upper bound on it. A long horizon is chosen so a
     * budget does not silently stop evaluating a year after someone created it.
     */
    private static final int BUDGET_YEARS = 5;

    /**
     * How long to wait on ARM before giving up.
     *
     * <p>
     * Both a connect and a per-request timeout, because {@code createBudget} and
     * {@code deleteBudget} are called straight from the controller on the request thread. Without a
     * request timeout a hung ARM connection holds that thread indefinitely, and the
     * {@code orTimeout} on the read path does not help — it completes the future while the blocked
     * {@code send} keeps its thread.
     */
    private static final java.time.Duration HTTP_TIMEOUT = java.time.Duration.ofSeconds(30);

    /**
     * Alert thresholds, as a percentage of the budget, applied to a budget created with contacts.
     *
     * <p>80% gives warning before the limit is reached; 100% reports that it has been.
     */
    private static final int[] NOTIFICATION_THRESHOLDS = { 80, 100 };

    /**
     * Why a created budget will not alert anyone.
     *
     * <p>
     * Azure requires at least one contact email or action group on a subscription-scoped budget
     * notification — {@code contactRoles} alone is not accepted — so a budget created without an
     * address tracks spend and shows in the portal but can never fire. That is the same property the
     * class javadoc gives as its reason for refusing a local-only budget, so it is reported rather
     * than left for the customer to discover when the alert does not arrive.
     */
    static final String NO_NOTIFICATION_WARNING =
            "This budget tracks spend but will not send alerts: no alert email was supplied, and Azure "
                    + "requires at least one contact address on a subscription budget notification. "
                    + "Re-create it with an alert email, or add a notification in the Azure portal.";

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    public AzureBudgetService(AzureClientProvider clientProvider, RedisCacheService redisCacheService) {
        this.clientProvider = clientProvider;
        this.redisCacheService = redisCacheService;
    }

    /**
     * Signals that budgets could not be read, as distinct from there being none.
     *
     * <p>
     * Previously every failure — 403, 404, throttling, any other status, any exception — returned an
     * empty list, which {@code getBudgetsSync} then cached for ten minutes and the controller served
     * as {@code 200 OK []}. So a missing role assignment and a subscription with no budgets were
     * byte-for-byte identical to the client, and the mock budgets this class replaced were swapped
     * for an equally confident "no budgets configured". {@code carriesRemediation} marks the cases
     * the customer can act on.
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
     * Every budget defined on the subscription.
     *
     * @throws BudgetsUnavailableException when budgets could not be read at all. An empty return
     *         therefore means Azure answered and the subscription has none — the two are no longer
     *         indistinguishable.
     */
    public List<AzureBudgetDto> getBudgetsSync(String subscriptionId) {
        String cacheKey = BUDGET_CACHE_PREFIX + subscriptionId;

        Optional<List<AzureBudgetDto>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<AzureBudgetDto>>() {});
        if (cached.isPresent()) {
            log.info("Returning cached budgets for Azure subscription {}", subscriptionId);
            return cached.get();
        }

        // Only a successful read is cached. Caching a failure hid the subscription's budgets for ten
        // minutes after a single throttled request.
        List<AzureBudgetDto> budgets = fetchBudgets(subscriptionId);
        redisCacheService.put(cacheKey, budgets, CACHE_TTL_MINUTES);
        return budgets;
    }

    private List<AzureBudgetDto> fetchBudgets(String subscriptionId) {
        String uri = budgetsBaseUri(subscriptionId) + "?api-version=" + API_VERSION;
        HttpResponse<String> response;
        try {
            response = send(HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + bearerToken(subscriptionId))
                    .GET());
        } catch (Exception e) {
            log.error("Failed to read Azure budgets for {}: {}", subscriptionId, e.getMessage());
            throw new BudgetsUnavailableException(
                    "Could not reach the Azure budgets API: " + e.getMessage(), false);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            log.warn("Cannot read Azure budgets for {}: access denied.", subscriptionId);
            throw new BudgetsUnavailableException("Azure denied access to budgets on this subscription. "
                    + "Assign the 'Cost Management Reader' role to the XamOps service principal.", true);
        }
        if (status == 404) {
            // Consumption is not available on every offer type; a 404 on the provider path is
            // how that surfaces, and it is a configuration fact rather than an error.
            log.warn("Azure budgets unavailable for {}: Consumption does not cover this subscription "
                    + "type.", subscriptionId);
            throw new BudgetsUnavailableException("Azure does not serve budgets for this subscription. "
                    + "The Consumption API covers Enterprise Agreement, Web Direct and CSP Azure-plan "
                    + "subscriptions; other offers are not included.", false);
        }
        if (status == 429) {
            log.warn("Azure throttled the budgets request for {}", subscriptionId);
            throw new BudgetsUnavailableException("Azure rate-limited the budgets request. It will be "
                    + "retried on the next refresh.", false);
        }
        if (status != 200) {
            log.warn("Azure budgets API returned {} for {}: {}", status, subscriptionId,
                    truncate(response.body()));
            throw new BudgetsUnavailableException(
                    "Azure returned HTTP " + status + " for budgets on this subscription.", false);
        }

        try {
            return parseBudgets(response.body());
        } catch (Exception e) {
            log.error("Could not parse the Azure budgets response for {}: {}", subscriptionId, e.getMessage());
            throw new BudgetsUnavailableException(
                    "Azure returned a budgets response that could not be read.", false);
        }
    }

    private List<AzureBudgetDto> parseBudgets(String body) throws Exception {
        List<AzureBudgetDto> budgets = new ArrayList<>();
        JsonNode value = objectMapper.readTree(body).path("value");
        if (!value.isArray()) {
            return budgets;
        }

        for (JsonNode node : value) {
            JsonNode props = node.path("properties");
            JsonNode currentSpend = props.path("currentSpend");
            JsonNode forecastSpend = props.path("forecastSpend");
            String name = node.path("name").asText("");

            // A budget whose limit cannot be read cannot be evaluated, and rendering it as a limit of
            // zero puts it on screen at 0% utilisation — a confident zero of exactly the kind this
            // rewrite exists to remove. `amount` is required by the API, so this only fires on a
            // malformed response, and skipping with a warning is better than charting a fiction.
            JsonNode amount = props.path("amount");
            if (!amount.isNumber()) {
                log.warn("Skipping Azure budget '{}': the response carried no readable amount", name);
                continue;
            }

            // Currency comes from the spend objects rather than the amount, because the budget's
            // own amount carries no unit. Falling back to the forecast's unit, then USD, keeps the
            // figure labelled even on a budget that has not accrued anything yet.
            String unit = currentSpend.path("unit").asText(
                    forecastSpend.path("unit").asText("USD"));

            budgets.add(new AzureBudgetDto(
                    name,
                    amount.asDouble(),
                    unit,
                    currentSpend.path("amount").asDouble(0.0),
                    forecastSpend.path("amount").asDouble(0.0)));
        }
        log.info("Parsed {} Azure budgets", budgets.size());
        return budgets;
    }

    /**
     * Creates or replaces a budget.
     *
     * <p>
     * Throws rather than falling back to local storage. The previous implementation stored a
     * fabricated budget in Redis and told the user it had been created — but a budget that exists
     * only here never evaluates spend and never sends an alert, which is worse than a clear
     * failure.
     */
    public AzureBudgetDto createBudget(String subscriptionId, AzureBudgetCreateRequestDto request, String username) {
        if (request.getBudgetLimit() <= 0) {
            throw new IllegalArgumentException("Budget limit must be greater than 0");
        }
        String name = request.getBudgetName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Budget name is required");
        }
        // Azure constrains budget names to letters, digits, underscore and hyphen. Rejecting here
        // gives a usable message instead of an opaque 400 from ARM.
        if (!name.matches(BUDGET_NAME_PATTERN)) {
            throw new IllegalArgumentException("Budget name must be 1-63 characters using only letters, "
                    + "digits, underscores and hyphens");
        }

        log.info("Creating Azure budget '{}' with limit {} for subscription {} by user {}",
                name, request.getBudgetLimit(), subscriptionId, username);

        // Azure requires the start date to be the first of a month; today's date is rejected.
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = start.plusYears(BUDGET_YEARS);
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;

        List<String> contacts = validContactEmails(request.getContactEmails());

        String payload;
        try {
            var root = objectMapper.createObjectNode();
            var props = root.putObject("properties");
            props.put("category", "Cost");
            props.put("amount", request.getBudgetLimit());
            props.put("timeGrain", DEFAULT_TIME_GRAIN);
            var period = props.putObject("timePeriod");
            period.put("startDate", start.format(iso));
            period.put("endDate", end.format(iso));

            // A budget with no notifications tracks spend and never tells anyone. Azure requires at
            // least one contact email or action group on a subscription-scoped notification, so this
            // is only possible when the caller supplied an address; when they did not, the budget is
            // still created and the response says it cannot alert.
            if (!contacts.isEmpty()) {
                var notifications = props.putObject("notifications");
                for (int threshold : NOTIFICATION_THRESHOLDS) {
                    var rule = notifications.putObject("actual_GreaterThan_" + threshold + "_Percent");
                    rule.put("enabled", true);
                    rule.put("operator", "GreaterThan");
                    rule.put("threshold", threshold);
                    rule.put("thresholdType", "Actual");
                    var emails = rule.putArray("contactEmails");
                    contacts.forEach(emails::add);
                }
            } else {
                log.warn("Creating Azure budget '{}' on {} without notifications — it will track spend "
                        + "but cannot alert anyone", name, subscriptionId);
            }

            payload = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Could not build the Azure budget request: " + e.getMessage(), e);
        }

        String uri = budgetsBaseUri(subscriptionId) + "/" + name + "?api-version=" + API_VERSION;
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + bearerToken(subscriptionId))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload)));

            if (response.statusCode() == 403) {
                throw new IllegalStateException("The service principal cannot create budgets on this "
                        + "subscription. 'Cost Management Reader' allows reading budgets but not creating "
                        + "them — assign 'Cost Management Contributor'.");
            }
            if (response.statusCode() == 404) {
                throw new IllegalStateException("Budgets are not available for this subscription. The Azure "
                        + "Consumption API supports Enterprise Agreement, Web Direct and CSP Azure-plan "
                        + "subscriptions.");
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new IllegalStateException("Azure rejected the budget: " + truncate(response.body()));
            }

            evictBudgetCache(subscriptionId);

            // Read the created budget back rather than echoing the request, so the caller sees the
            // spend Azure has actually accrued instead of a placeholder.
            List<AzureBudgetDto> created = parseSingle(response.body());
            AzureBudgetDto result = created.isEmpty()
                    ? new AzureBudgetDto(name, request.getBudgetLimit(),
                            request.getBudgetUnit() != null ? request.getBudgetUnit() : "USD", 0.0, 0.0)
                    : created.get(0);

            result.setAlertsEnabled(!contacts.isEmpty());
            if (contacts.isEmpty()) {
                result.setWarning(NO_NOTIFICATION_WARNING);
            }
            return result;

        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create the Azure budget: " + e.getMessage(), e);
        }
    }

    /**
     * Keeps only syntactically plausible addresses, so one typo does not make ARM reject the whole
     * budget with an opaque 400.
     */
    private List<String> validContactEmails(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (String raw : requested) {
            if (raw == null) {
                continue;
            }
            String email = raw.trim();
            if (email.matches("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")) {
                valid.add(email);
            } else if (!email.isEmpty()) {
                log.warn("Ignoring '{}' as a budget alert contact: not a valid email address", email);
            }
        }
        return valid;
    }

    /** Parses a single-budget response body (create/update returns the object, not a list). */
    private List<AzureBudgetDto> parseSingle(String body) {
        try {
            var wrapper = objectMapper.createObjectNode();
            wrapper.putArray("value").add(objectMapper.readTree(body));
            return parseBudgets(objectMapper.writeValueAsString(wrapper));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void deleteBudget(String subscriptionId, String budgetName) {
        // Validated with the same rule as create. Without it the name goes straight into the URI
        // path, so a value containing a slash or a query character addresses a different resource.
        if (budgetName == null || !budgetName.matches(BUDGET_NAME_PATTERN)) {
            throw new IllegalArgumentException("Budget name must be 1-63 characters using only letters, "
                    + "digits, underscores and hyphens");
        }

        log.info("Deleting Azure budget '{}' for subscription {}", budgetName, subscriptionId);

        String uri = budgetsBaseUri(subscriptionId) + "/" + budgetName + "?api-version=" + API_VERSION;
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + bearerToken(subscriptionId))
                    .DELETE());

            // 204 is the documented success; 200 is accepted too, and 404 means it is already gone,
            // which satisfies the caller's intent.
            if (response.statusCode() == 403) {
                throw new IllegalStateException("The service principal cannot delete budgets on this "
                        + "subscription. Assign 'Cost Management Contributor'.");
            }
            if (response.statusCode() != 200 && response.statusCode() != 204
                    && response.statusCode() != 404) {
                throw new IllegalStateException("Azure rejected the delete: " + truncate(response.body()));
            }
            evictBudgetCache(subscriptionId);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete the Azure budget: " + e.getMessage(), e);
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    private String budgetsBaseUri(String subscriptionId) {
        return ARM + "/subscriptions/" + subscriptionId + "/providers/Microsoft.Consumption/budgets";
    }

    private String bearerToken(String subscriptionId) {
        TokenCredential credential = clientProvider.getCredential(subscriptionId);
        return credential.getToken(new TokenRequestContext().addScopes(SCOPE)).block().getToken();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return httpClient.send(builder.timeout(HTTP_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Clears the cached budget list.
     *
     * <p>
     * Only that one key. An earlier version also evicted {@code azure:finops-report:<sub>} on the
     * stated grounds that the report caches its own copy — nothing writes that key, so the eviction
     * was a no-op and the rationale was wrong. {@code AzureFinOpsService} builds its report fresh and
     * calls {@link #getBudgetsSync}, so evicting the budget key is what actually refreshes it.
     */
    private void evictBudgetCache(String subscriptionId) {
        redisCacheService.evict(BUDGET_CACHE_PREFIX + subscriptionId);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "(no response body)";
        }
        return body.length() > 400 ? body.substring(0, 400) + "…" : body;
    }
}
