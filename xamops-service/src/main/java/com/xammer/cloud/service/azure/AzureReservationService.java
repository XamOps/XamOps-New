package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureReservationData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for fetching Azure Reservation and Savings Plan data.
 *
 * Uses direct REST calls to:
 *   - Microsoft.Capacity/reservations (Reserved VM Instances)
 *   - Microsoft.BillingBenefits/savingsPlans (Azure Savings Plans)
 *   - Microsoft.Consumption/reservationRecommendations (purchase recommendations)
 *
 * Gracefully handles 403 responses when the SP lacks "Reservation Reader" (inventory) or
 * "Cost Management Reader" (recommendations), reporting the missing role rather than an empty page.
 */
@Service
public class AzureReservationService {

    private static final Logger log = LoggerFactory.getLogger(AzureReservationService.class);
    private static final String RESERVATION_CACHE_PREFIX = "azure:reservations:";
    private static final int CACHE_TTL_MINUTES = 60;

    private static final String ARM_BASE = "https://management.azure.com";

    /**
     * API version for {@code Microsoft.Consumption/reservationRecommendations}.
     *
     * <p>Generally available, and the version Azure itself stamps into the {@code nextLink} of its
     * own documented sample responses, so paging stays on the same contract we parse.
     */
    private static final String RECOMMENDATIONS_API_VERSION = "2024-08-01";

    /**
     * Look-back window requested from Azure.
     *
     * <p>
     * This is deliberately the 30-day window and not the API default of 7 days. Azure's cost fields
     * cover the window it analysed, so a 7-day response has to be multiplied by roughly four to
     * become a monthly figure — and that multiplication is exactly where an honest number turns
     * into a projection. Asking for 30 days makes the response already monthly, so the normal path
     * involves no scaling at all.
     */
    private static final String REQUESTED_LOOKBACK = "Last30Days";

    /** Days in the window we ask for, and the denominator every row is normalised to. */
    private static final double NORMALISED_DAYS = 30.0;

    /** How many pages of recommendations to follow before giving up, as a runaway guard. */
    private static final int MAX_RECOMMENDATION_PAGES = 20;

    /**
     * Readable labels for the {@code resourceType} values Azure returns, which arrive lower-cased
     * and unspaced ({@code virtualmachines}, {@code sqldatabases}).
     */
    private static final java.util.Map<String, String> RESOURCE_TYPE_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("virtualmachines", "Virtual Machines"),
            java.util.Map.entry("sqldatabases", "SQL Databases"),
            java.util.Map.entry("sqldatawarehouse", "SQL Data Warehouse"),
            java.util.Map.entry("suselinux", "SUSE Linux"),
            java.util.Map.entry("postgresql", "Azure Database for PostgreSQL"),
            java.util.Map.entry("managereddisk", "Managed Disks"),
            java.util.Map.entry("manageddisk", "Managed Disks"),
            java.util.Map.entry("mysql", "Azure Database for MySQL"),
            java.util.Map.entry("redhat", "Red Hat"),
            java.util.Map.entry("sqldatabasemanagedinstance", "SQL Managed Instance"),
            java.util.Map.entry("cosmosdb", "Cosmos DB"),
            java.util.Map.entry("rediscache", "Cache for Redis"),
            java.util.Map.entry("azuredataexplorer", "Data Explorer"),
            java.util.Map.entry("mariadb", "Azure Database for MariaDB"),
            java.util.Map.entry("appservice", "App Service"),
            java.util.Map.entry("blockblob", "Blob Storage"),
            java.util.Map.entry("databricks", "Databricks"),
            java.util.Map.entry("vmwarecloudsimple", "VMware Solution"),
            java.util.Map.entry("redhatosa", "Red Hat OpenShift"),
            java.util.Map.entry("dedicatedhost", "Dedicated Host"),
            java.util.Map.entry("netappstorage", "NetApp Storage"),
            java.util.Map.entry("azurefiles", "Azure Files"),
            java.util.Map.entry("sqledge", "SQL Edge"),
            java.util.Map.entry("virtualmachinesoftware", "Virtual Machine Software"));

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureReservationService(AzureClientProvider clientProvider,
                                   RedisCacheService redisCache,
                                   CachedDataRepository cachedDataRepository) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
    }

    /**
     * Returns reservation data for the given subscription.
     * Uses Redis cache (60-min TTL). Pass forceRefresh=true to bypass cache.
     */
    public AzureReservationData getReservationData(String subscriptionId, boolean forceRefresh) {
        String cacheKey = RESERVATION_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            Optional<AzureReservationData> cached = redisCache.get(cacheKey, AzureReservationData.class);
            if (cached.isPresent()) {
                log.info("Returning cached reservation data for {}", subscriptionId);
                return cached.get();
            }
        }

        AzureReservationData data = fetchLiveData(subscriptionId);
        redisCache.put(cacheKey, data, CACHE_TTL_MINUTES);
        return data;
    }

    private AzureReservationData fetchLiveData(String subscriptionId) {
        AzureReservationData data = new AzureReservationData();

        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            HttpClient httpClient = HttpClient.newHttpClient();

            // --- Reservations (Microsoft.Capacity) ---
            List<AzureReservationData.ReservationItem> reservations =
                    fetchReservations(httpClient, bearerToken, subscriptionId);
            data.setReservations(reservations);

            // --- Savings Plans (Microsoft.BillingBenefits) ---
            List<AzureReservationData.SavingsPlanItem> savingsPlans =
                    fetchSavingsPlans(httpClient, bearerToken, subscriptionId);
            data.setSavingsPlans(savingsPlans);

            // --- Metrics ---
            data.setMetrics(buildMetrics(reservations, savingsPlans));

            // --- Purchase recommendations (Microsoft.Consumption) ---
            buildRecommendations(httpClient, bearerToken, subscriptionId, data);
            summariseSavings(data, data.getRecommendations());
            saveReservationPageSnapshot(subscriptionId, data);

        } catch (Exception e) {
            log.error("Failed to fetch reservation data for {}: {}", subscriptionId, e.getMessage(), e);
        }

        return data;
    }

    /**
     * Splits priced savings from indicative ones.
     *
     * <p>
     * Only the priced total feeds the cross-provider savings dashboard. Recommendations now come
     * from Azure's Consumption reservation-recommendations API, so the priced total is a real
     * per-SKU figure computed at the subscription's own rates. A recommendation only lands in the
     * indicative bucket when Azure returned the row but not a readable savings amount.
     */
    private void summariseSavings(AzureReservationData data,
            List<AzureReservationData.ReservationRecommendation> recommendations) {

        double priced = 0.0;
        double indicative = 0.0;
        int indicativeCount = 0;

        for (AzureReservationData.ReservationRecommendation rec : recommendations) {
            if (rec.isIndicativeOnly()) {
                indicative += rec.getEstimatedMonthlySavings();
                indicativeCount++;
            } else {
                priced += rec.getEstimatedMonthlySavings();
            }
        }

        data.setIndicativeRecommendationCount(indicativeCount);

        // Adding amounts denominated in different currencies produces a number that is wrong in every
        // currency, and this total is what reaches the cross-provider savings figure. When Azure
        // reported more than one, no total is published and the reason says so.
        if (data.isMixedCurrencies()) {
            data.setTotalMonthlySavings(0.0);
            data.setIndicativeMonthlySavings(0.0);
            data.setSavingsUnavailableReason("Azure reported these recommendations in more than one "
                    + "currency, so they cannot be added into a single savings figure. The per-SKU "
                    + "amounts below are still correct individually.");
            return;
        }

        data.setTotalMonthlySavings(Math.round(priced * 100.0) / 100.0);
        data.setIndicativeMonthlySavings(Math.round(indicative * 100.0) / 100.0);

        // The cross-provider savings dashboard adds this to AWS and GCP figures, which are USD. Azure
        // reports in the enrollment's billing currency, and no exchange rate is applied anywhere — so
        // a non-USD enrollment contributes a number in the wrong unit. Flagged rather than silently
        // converted with an invented rate.
        String currency = data.getRecommendationCurrency();
        if (priced > 0 && currency != null && !"USD".equalsIgnoreCase(currency)) {
            data.setSavingsUnavailableReason("These savings are in " + currency
                    + ". Cross-provider savings totals are reported in USD and no conversion is applied, "
                    + "so this subscription's contribution to the group total is not comparable.");
            log.warn("Azure reservation savings for this subscription are in {} but the cross-provider "
                    + "savings total is USD; no conversion is applied", currency);
        }
    }

    /**
     * Publishes the snapshot {@code ClientSavingsService} reads for Azure.
     *
     * <p>
     * That service looks up {@code RESERVATION_PAGE::<providerAccountId>} for GCP and Azure alike,
     * but only GCP ever wrote it — so Azure reservation savings were always zero, and its
     * "data as of" timestamp silently ignored Azure. Key and field names mirror the GCP writer
     * exactly, because the reader is shared.
     *
     * <p>
     * Best-effort: a failure here must not cost the caller the reservation data it asked for.
     */
    private void saveReservationPageSnapshot(String subscriptionId, AzureReservationData data) {
        String dbKey = "RESERVATION_PAGE::" + subscriptionId;
        try {
            CachedData entry = cachedDataRepository.findById(dbKey).orElseGet(CachedData::new);
            entry.setCacheKey(dbKey);
            entry.setJsonData(objectMapper.writeValueAsString(data));
            entry.setLastUpdated(java.time.LocalDateTime.now());
            cachedDataRepository.save(entry);
            log.info("Saved Azure reservation page snapshot {} (priced savings {}, {} indicative)",
                    dbKey, data.getTotalMonthlySavings(), data.getIndicativeRecommendationCount());
        } catch (Exception e) {
            log.warn("Could not save Azure reservation page snapshot {}: {}", dbKey, e.getMessage());
        }
    }

    private List<AzureReservationData.ReservationItem> fetchReservations(
            HttpClient httpClient, String bearerToken, String subscriptionId) {

        String uri = "https://management.azure.com/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Capacity/reservations?api-version=2022-11-01";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Reservations API status: {} for {}", response.statusCode(), subscriptionId);

            if (response.statusCode() == 403) {
                log.warn("SP lacks 'Reservation Reader' role on subscription {}. " +
                        "Grant 'Reservation Reader' to enable reservation data.", subscriptionId);
                return new ArrayList<>();
            }

            if (response.statusCode() != 200) {
                log.warn("Reservations API returned {} for {}", response.statusCode(), subscriptionId);
                return new ArrayList<>();
            }

            return parseReservations(response.body());

        } catch (Exception e) {
            log.error("Error calling Reservations API for {}: {}", subscriptionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<AzureReservationData.ReservationItem> parseReservations(String json) throws Exception {
        List<AzureReservationData.ReservationItem> items = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");

        if (value.isMissingNode() || !value.isArray()) return items;

        for (JsonNode node : value) {
            AzureReservationData.ReservationItem item = new AzureReservationData.ReservationItem();

            item.setReservationId(node.path("id").asText(""));
            item.setSku(node.path("sku").path("name").asText(""));

            JsonNode props = node.path("properties");
            item.setDisplayName(props.path("displayName").asText(item.getSku()));
            item.setQuantity(props.path("quantity").asInt(1));
            item.setState(props.path("provisioningState").asText("Unknown"));
            item.setTerm(props.path("term").asText("P1Y"));
            item.setBillingPlan(props.path("billingPlan").asText("Upfront"));
            item.setExpiryDate(props.path("expiryDateTime").asText(
                    props.path("expiryDate").asText("")));
            item.setScope(props.path("appliedScopeType").asText("Shared"));

            // Location can be in reservedResourceProperties or at resource level
            String location = props.path("reservedResourceProperties").path("location").asText("");
            if (location.isEmpty()) {
                location = node.path("location").asText("");
            }
            item.setLocation(location);
            // Left null rather than zeroed. A hardcoded 0.0 here rendered as a red "0%" bar, which
            // reads as a completely unused reservation — an expensive, urgent finding — when the
            // truth is only that this endpoint does not report consumption. Utilization needs the
            // Consumption reservation-summaries API at a billing scope.
            item.setUtilizationPercentage(null);
            item.setUtilizationUnavailableReason("Azure's reservation list endpoint does not report "
                    + "consumption. Utilization requires the Consumption reservation-summaries API at a "
                    + "billing scope, which is not available for every subscription type.");

            items.add(item);
        }

        log.info("Parsed {} reservations", items.size());
        return items;
    }

    private List<AzureReservationData.SavingsPlanItem> fetchSavingsPlans(
            HttpClient httpClient, String bearerToken, String subscriptionId) {

        String uri = "https://management.azure.com/subscriptions/" + subscriptionId
                + "/providers/Microsoft.BillingBenefits/savingsPlans?api-version=2022-11-01";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Savings Plans API status: {} for {}", response.statusCode(), subscriptionId);

            if (response.statusCode() == 403) {
                log.warn("SP lacks 'Reservation Reader' role on subscription {}. " +
                        "Savings Plans data unavailable.", subscriptionId);
                return new ArrayList<>();
            }

            if (response.statusCode() != 200) {
                log.warn("Savings Plans API returned {} for {}", response.statusCode(), subscriptionId);
                return new ArrayList<>();
            }

            return parseSavingsPlans(response.body());

        } catch (Exception e) {
            log.error("Error calling Savings Plans API for {}: {}", subscriptionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<AzureReservationData.SavingsPlanItem> parseSavingsPlans(String json) throws Exception {
        List<AzureReservationData.SavingsPlanItem> items = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");

        if (value.isMissingNode() || !value.isArray()) return items;

        for (JsonNode node : value) {
            AzureReservationData.SavingsPlanItem item = new AzureReservationData.SavingsPlanItem();

            item.setSavingsPlanId(node.path("id").asText(""));

            JsonNode props = node.path("properties");
            item.setDisplayName(props.path("displayName").asText("Savings Plan"));
            item.setTerm(props.path("term").asText("P1Y"));
            item.setState(props.path("provisioningState").asText("Unknown"));
            item.setStartDate(props.path("effectiveDateTime").asText(""));
            item.setExpiryDate(props.path("expiryDateTime").asText(""));
            item.setAppliedScope(props.path("appliedScopeType").asText("Shared"));

            // Commitment: hourly amount
            JsonNode commitment = props.path("commitment");
            String amount = commitment.path("amount").asText("0");
            String currency = commitment.path("currencyCode").asText("INR");
            item.setCommitment(amount + " " + currency + "/hr");
            item.setPaymentOption(props.path("billingPlan").asText("Upfront"));

            items.add(item);
        }

        log.info("Parsed {} savings plans", items.size());
        return items;
    }

    private AzureReservationData.ReservationMetrics buildMetrics(
            List<AzureReservationData.ReservationItem> reservations,
            List<AzureReservationData.SavingsPlanItem> savingsPlans) {

        AzureReservationData.ReservationMetrics metrics = new AzureReservationData.ReservationMetrics();

        metrics.setTotalReservations(reservations.size());
        metrics.setActiveReservations((int) reservations.stream()
                .filter(r -> "Succeeded".equalsIgnoreCase(r.getState()))
                .count());

        metrics.setTotalSavingsPlans(savingsPlans.size());
        metrics.setActiveSavingsPlans((int) savingsPlans.stream()
                .filter(s -> "Succeeded".equalsIgnoreCase(s.getState()))
                .count());

        return metrics;
    }

    // -------------------------------------------------------------------------
    // Purchase recommendations
    // -------------------------------------------------------------------------

    /**
     * Fills in reservation purchase recommendations from Azure's Consumption API.
     *
     * <p>
     * <strong>What this replaced.</strong> Recommendations used to be manufactured locally: read the
     * cached billing summary, keep every service line above a spend threshold, and assert that
     * reserving it would save a flat 20%. Nothing about that figure came from Azure. Real reservation
     * discounts range from roughly 20% to over 60% depending on SKU, region and term, and some
     * services have no meaningful one-year discount at all — so the rate was wrong. It was also
     * applied to a service's <em>entire</em> spend rather than the reservable, steadily-running part
     * of it, so the base was wrong too. Two compounding errors in a number captioned "savings
     * potential" and drawn as a progress bar.
     *
     * <p>
     * Azure computes this properly: it simulates reservation purchases against the subscription's own
     * hourly usage records at the subscription's own negotiated rates, and returns the quantity that
     * maximises savings per SKU and term. That is what we now surface.
     *
     * <p>
     * When Azure cannot answer, the list stays empty and
     * {@link AzureReservationData#setRecommendationsUnavailableReason(String)} explains why. There is
     * deliberately no local fallback estimate: a wrong number presented as a saving is worse than an
     * empty tab that names the missing role.
     */
    private void buildRecommendations(HttpClient httpClient, String bearerToken,
            String subscriptionId, AzureReservationData data) {

        List<RawRecommendation> raw = new ArrayList<>();

        String uri = ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Consumption/reservationRecommendations"
                + "?api-version=" + RECOMMENDATIONS_API_VERSION
                + "&$filter=" + encodeQueryValue("properties/lookBackPeriod eq '" + REQUESTED_LOOKBACK + "'");

        boolean filtered = true;
        int page = 0;
        boolean truncated = false;

        try {
            while (uri != null) {
                if (page >= MAX_RECOMMENDATION_PAGES) {
                    truncated = true;
                    break;
                }
                page++;
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(uri))
                                .header("Authorization", "Bearer " + bearerToken)
                                .header("Content-Type", "application/json")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                log.info("Reservation recommendations API status: {} for {} (page {})",
                        status, subscriptionId, page);

                if (status == 400 && filtered) {
                    // Not every subscription offer supports the lookBackPeriod filter. Retry
                    // unfiltered rather than reporting nothing; every row carries its own window,
                    // so normalisation below still produces a correct monthly figure.
                    //
                    // Rows from the filtered attempt are discarded and the page counter is left
                    // alone: keeping them fed duplicates into the term-collapse (skewing
                    // termsConsidered), and resetting the counter doubled the request budget to 39
                    // sequential ARM calls.
                    log.info("Look-back filter rejected for {} — retrying unfiltered", subscriptionId);
                    filtered = false;
                    raw.clear();
                    uri = ARM_BASE + "/subscriptions/" + subscriptionId
                            + "/providers/Microsoft.Consumption/reservationRecommendations"
                            + "?api-version=" + RECOMMENDATIONS_API_VERSION;
                    continue;
                }

                if (status == 204) {
                    break;
                }

                if (status != 200) {
                    data.setRecommendationsUnavailableReason(
                            describeRecommendationFailure(status, response.body()));
                    log.warn("Reservation recommendations unavailable for {}: {} — {}",
                            subscriptionId, status, data.getRecommendationsUnavailableReason());
                    return;
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode value = root.path("value");
                if (value.isArray()) {
                    for (JsonNode node : value) {
                        RawRecommendation parsed = parseRecommendation(node);
                        if (parsed != null) {
                            raw.add(parsed);
                        }
                    }
                }

                String next = root.path("nextLink").asText("");
                uri = next.isBlank() ? null : next;
            }

            if (truncated) {
                // The savings total computed below reaches the cross-provider savings dashboard, so a
                // partial read has to be declared. Reporting it silently published a floor as though
                // it were the whole figure. (This is only reached on a real page-cap; a 204 exits the
                // loop with `uri` still set, which is why the flag exists rather than an `uri != null`
                // check.)
                log.warn("Stopped following reservation recommendation pages for {} after {} pages",
                        subscriptionId, MAX_RECOMMENDATION_PAGES);
                data.setRecommendationsUnavailableReason("Azure returned more reservation recommendations "
                        + "than XamOps reads in one pass (" + MAX_RECOMMENDATION_PAGES + " pages). The "
                        + "recommendations and savings shown are a partial view.");
            }

        } catch (Exception e) {
            data.setRecommendationsUnavailableReason(
                    "Could not reach Azure's reservation recommendations API: " + e.getMessage());
            log.error("Error calling reservation recommendations API for {}: {}",
                    subscriptionId, e.getMessage());
            return;
        }

        List<AzureReservationData.ReservationRecommendation> recommendations = collapse(raw);
        data.setRecommendations(recommendations);

        // One currency for the whole set, or none. `findFirst` over the rows would label a mixed set
        // with whichever code happened to appear first, and summing across currencies is an
        // arithmetic error rather than a display one.
        java.util.Set<String> currencies = raw.stream()
                .map(r -> r.currency)
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        if (currencies.size() > 1) {
            log.warn("Azure returned reservation recommendations for {} in {} different currencies ({}) "
                    + "— refusing to sum them", subscriptionId, currencies.size(), currencies);
            data.setRecommendationCurrency(null);
            data.setMixedCurrencies(true);
        } else {
            data.setRecommendationCurrency(currencies.isEmpty() ? null : currencies.iterator().next());
        }

        if (recommendations.isEmpty()) {
            log.info("Azure returned no reservation recommendations for {}", subscriptionId);
        } else {
            log.info("Parsed {} Azure reservation recommendations for {} from {} rows",
                    recommendations.size(), subscriptionId, raw.size());
        }
    }

    /** One row of the API response, before alternative terms and scopes are collapsed. */
    private static final class RawRecommendation {
        String id;
        String resourceType;
        String sku;
        String region;
        String scope;
        String term;
        Integer quantity;
        String currency;
        String firstUsageDate;
        int lookbackDays;
        boolean priced;
        double monthlySavings;
        double monthlyOnDemandCost;
        double monthlyReservedCost;

        String groupKey() {
            return nullSafe(resourceType) + '|' + nullSafe(sku) + '|' + nullSafe(region);
        }

        private static String nullSafe(String s) {
            return s == null ? "" : s.toLowerCase();
        }
    }

    /**
     * Maps one API row onto {@link RawRecommendation}, absorbing the two response shapes.
     *
     * <p>
     * The {@code kind} discriminator matters: on {@code legacy} rows the cost fields are bare
     * numbers with no currency and {@code lookBackPeriod} is a string like {@code Last30Days}; on
     * {@code modern} rows the same fields are {@code {currency, value}} objects and
     * {@code lookBackPeriod} is a plain day count. Reading a modern row with legacy assumptions
     * yields {@code 0.0} for every amount, which would look exactly like a subscription with
     * nothing to save.
     */
    private RawRecommendation parseRecommendation(JsonNode node) {
        JsonNode props = node.path("properties");
        if (props.isMissingNode()) {
            return null;
        }

        RawRecommendation r = new RawRecommendation();
        r.id = node.path("id").asText(node.path("name").asText(""));
        r.resourceType = emptyToNull(props.path("resourceType").asText(""));
        r.scope = emptyToNull(props.path("scope").asText(""));
        r.term = emptyToNull(props.path("term").asText(""));
        r.firstUsageDate = emptyToNull(props.path("firstUsageDate").asText(""));

        // Modern rows carry the SKU on properties.skuName and may omit the top-level sku entirely.
        r.sku = firstNonBlank(
                props.path("skuName").asText(""),
                node.path("sku").asText(""),
                props.path("normalizedSize").asText(""));

        r.region = firstNonBlank(
                node.path("location").asText(""),
                props.path("location").asText(""));

        if (props.path("recommendedQuantity").isNumber()) {
            r.quantity = (int) Math.round(props.path("recommendedQuantity").asDouble());
        }

        r.lookbackDays = parseLookbackDays(props.path("lookBackPeriod"));

        Amount netSavings = readAmount(props.path("netSavings"));
        Amount onDemand = readAmount(props.path("costWithNoReservedInstances"));
        Amount reserved = readAmount(props.path("totalCostWithReservedInstances"));

        r.currency = firstNonBlank(netSavings.currency, onDemand.currency, reserved.currency);
        r.priced = netSavings.present;

        double factor = NORMALISED_DAYS / r.lookbackDays;
        r.monthlySavings = netSavings.present ? netSavings.value * factor : 0.0;
        r.monthlyOnDemandCost = onDemand.present ? onDemand.value * factor : 0.0;
        r.monthlyReservedCost = reserved.present ? reserved.value * factor : 0.0;

        return r;
    }

    /** A currency amount that may arrive as a bare number or as {@code {currency, value}}. */
    private static final class Amount {
        boolean present;
        double value;
        String currency;
    }

    private static Amount readAmount(JsonNode node) {
        Amount a = new Amount();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return a;
        }
        if (node.isNumber()) {
            a.present = true;
            a.value = node.asDouble();
            return a;
        }
        JsonNode inner = node.path("value");
        if (inner.isNumber()) {
            a.present = true;
            a.value = inner.asDouble();
            a.currency = emptyToNull(node.path("currency").asText(""));
        }
        return a;
    }

    /**
     * Days of usage behind a recommendation.
     *
     * <p>
     * Legacy rows spell this {@code Last7Days}/{@code Last30Days}/{@code Last60Days}; modern rows
     * send {@code 7}/{@code 30}/{@code 60}. When it is absent we use the window we asked for, since
     * that is what the request constrained the response to.
     */
    private static int parseLookbackDays(JsonNode node) {
        if (node != null && node.isNumber()) {
            return clampLookback(node.asInt(), node);
        }
        String text = node == null ? "" : node.asText("");
        if (text != null && !text.isBlank()) {
            String digits = text.replaceAll("\\D+", "");
            if (!digits.isEmpty()) {
                try {
                    return clampLookback(Integer.parseInt(digits), node);
                } catch (NumberFormatException ignored) {
                    // fall through to the requested window
                }
            }
        }
        return (int) NORMALISED_DAYS;
    }

    /**
     * Accepts only the windows Azure documents, falling back to the one we asked for.
     *
     * <p>
     * This value is the divisor in {@code 30 / lookbackDays}, so an unexpected integer does real
     * damage rather than throwing. Stripping non-digits out of a date-like string, for instance, would
     * yield an eight-digit divisor and collapse every savings amount to approximately nothing — which
     * would then be published as a real figure. Only 7, 30 and 60 exist in the API.
     */
    private static int clampLookback(int days, JsonNode raw) {
        if (days == 7 || days == 30 || days == 60) {
            return days;
        }
        log.debug("Ignoring unrecognised reservation look-back window '{}'; treating it as the {} days "
                + "requested", raw, (int) NORMALISED_DAYS);
        return (int) NORMALISED_DAYS;
    }

    /**
     * Reduces the raw rows to one actionable recommendation per SKU.
     *
     * <p>
     * Azure returns a row per term and per scope for the same underlying usage. A one-year and a
     * three-year reservation for the same SKU are alternatives — you buy one — and a Shared-scope
     * recommendation covers the same machines as the Single-scope one at a different billing scope.
     * Emitting all of them would multiply the reported savings by three or four against usage that
     * can only be reserved once, and that total feeds the cross-provider savings figure.
     *
     * <p>
     * So: keep the highest-saving term per SKU, carry the runner-up term for comparison, and prefer
     * Single scope over Shared when both describe the same SKU.
     */
    private List<AzureReservationData.ReservationRecommendation> collapse(List<RawRecommendation> raw) {

        // Group by SKU and scope, so terms compete only against the same purchase decision.
        java.util.Map<String, List<RawRecommendation>> byScopedSku = new java.util.LinkedHashMap<>();
        for (RawRecommendation r : raw) {
            String key = r.groupKey() + '|' + (r.scope == null ? "" : r.scope.toLowerCase());
            byScopedSku.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        // Which SKUs have a Single-scope recommendation, so the Shared duplicate can be dropped.
        java.util.Set<String> skusWithSingleScope = new java.util.HashSet<>();
        for (RawRecommendation r : raw) {
            if ("single".equalsIgnoreCase(r.scope)) {
                skusWithSingleScope.add(r.groupKey());
            }
        }

        List<AzureReservationData.ReservationRecommendation> out = new ArrayList<>();

        for (List<RawRecommendation> group : byScopedSku.values()) {
            RawRecommendation head = group.get(0);
            if (!"single".equalsIgnoreCase(head.scope) && skusWithSingleScope.contains(head.groupKey())) {
                log.debug("Dropping {}-scope recommendation for {} — a subscription-scoped one covers "
                        + "the same usage", head.scope, head.sku);
                continue;
            }

            group.sort((a, b) -> Double.compare(b.monthlySavings, a.monthlySavings));
            RawRecommendation best = group.get(0);

            RawRecommendation alternative = null;
            for (int i = 1; i < group.size(); i++) {
                RawRecommendation candidate = group.get(i);
                if (candidate.term != null && !candidate.term.equals(best.term)) {
                    alternative = candidate;
                    break;
                }
            }

            out.add(toDto(best, alternative, group.size()));
        }

        out.sort((a, b) -> Double.compare(b.getEstimatedMonthlySavings(), a.getEstimatedMonthlySavings()));
        return out;
    }

    private AzureReservationData.ReservationRecommendation toDto(RawRecommendation best,
            RawRecommendation alternative, int termsConsidered) {

        AzureReservationData.ReservationRecommendation rec =
                new AzureReservationData.ReservationRecommendation();

        rec.setRecommendationId(best.id);
        rec.setResourceType(best.resourceType);
        rec.setServiceName(serviceLabel(best));
        rec.setSku(best.sku);
        rec.setRegion(best.region);
        rec.setScope(best.scope);
        rec.setRecommendedQuantity(best.quantity);
        rec.setRecommendedTerm(best.term);
        rec.setCurrency(best.currency);
        rec.setLookbackDays(best.lookbackDays);
        rec.setFirstUsageDate(best.firstUsageDate);

        rec.setEstimatedMonthlySavings(round2(best.monthlySavings));
        rec.setCurrentMonthlyCost(round2(best.monthlyOnDemandCost));
        rec.setReservedMonthlyCost(round2(best.monthlyReservedCost));
        rec.setEstimatedSavingsPercentage(best.monthlyOnDemandCost > 0
                ? round2(best.monthlySavings / best.monthlyOnDemandCost * 100.0)
                : 0.0);

        if (alternative != null) {
            rec.setAlternativeTerm(alternative.term);
            rec.setAlternativeTermMonthlySavings(round2(alternative.monthlySavings));
        }

        // Azure gave us the row but not a readable savings amount. Worth showing — it still names a
        // SKU worth reserving — but the figure must stay out of any headline total.
        rec.setIndicativeOnly(!best.priced);

        rec.setReason(buildReason(best, alternative, termsConsidered));
        return rec;
    }

    private String buildReason(RawRecommendation best, RawRecommendation alternative,
            int termsConsidered) {

        StringBuilder reason = new StringBuilder();

        if (!best.priced) {
            reason.append("Azure recommends reserving this SKU but did not return a savings amount, "
                    + "so no figure is claimed here. ");
        }

        reason.append("Azure analysed ").append(best.lookbackDays).append(" days of this ")
                .append("subscription's usage at its own rates");

        if (best.quantity != null && best.quantity > 0) {
            reason.append(" and recommends reserving ").append(best.quantity);
            if (best.sku != null && !best.sku.isBlank()) {
                reason.append(" \u00d7 ").append(best.sku);
            }
        }
        reason.append('.');

        if (best.lookbackDays != (int) NORMALISED_DAYS) {
            reason.append(" Figures are scaled from that ").append(best.lookbackDays)
                    .append("-day window to a 30-day month.");
        }

        if (alternative != null) {
            reason.append(" A ").append(alternative.term).append(" term was also costed and saves ")
                    .append("less; the two are alternatives, not additive.");
        } else if (termsConsidered > 1) {
            reason.append(" Multiple rows for this SKU were collapsed into the best one.");
        }

        return reason.toString();
    }

    private static String serviceLabel(RawRecommendation r) {
        if (r.resourceType == null || r.resourceType.isBlank()) {
            // Older EA responses omit resourceType; VM SKUs are the only thing they cover.
            return r.sku != null && r.sku.startsWith("Standard_") ? "Virtual Machines" : "Azure Reservation";
        }
        String key = r.resourceType.toLowerCase().replace(" ", "");
        String label = RESOURCE_TYPE_LABELS.get(key);
        return label != null ? label : r.resourceType;
    }

    /**
     * Turns an ARM error body into something the customer can act on.
     *
     * <p>A bare status code sends people to the wrong place: 403 here is almost always a missing
     * role rather than a broken integration, and 404 means the subscription's offer is not served
     * by the Consumption API at all, which no permission change will fix.
     */
    private String describeRecommendationFailure(int status, String body) {
        switch (status) {
            case 401:
            case 403:
                return "Azure denied access to reservation recommendations. Assign the "
                        + "'Cost Management Reader' role to the XamOps service principal on this "
                        + "subscription.";
            case 404:
                return "Azure does not serve reservation recommendations for this subscription. The "
                        + "Consumption API covers Enterprise Agreement, Microsoft Customer Agreement "
                        + "and CSP Azure-plan subscriptions; other offers, including sponsored and "
                        + "free-trial subscriptions, are not included.";
            case 429:
                return "Azure rate-limited the reservation recommendations request. It will be "
                        + "retried on the next refresh.";
            default:
                String detail = extractArmMessage(body);
                return "Azure returned HTTP " + status + " for reservation recommendations"
                        + (detail == null ? "." : ": " + detail);
        }
    }

    private String extractArmMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            return message.isBlank() ? null : message;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encodeQueryValue(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
