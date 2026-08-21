package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureReservationData;
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
 *
 * Gracefully handles 403 responses when the SP lacks "Reservation Reader" role.
 */
@Service
public class AzureReservationService {

    private static final Logger log = LoggerFactory.getLogger(AzureReservationService.class);
    private static final String RESERVATION_CACHE_PREFIX = "azure:reservations:";
    private static final int CACHE_TTL_MINUTES = 60;
    private static final double RECOMMENDATION_COST_THRESHOLD = 100.0;
    private static final double SAVINGS_PERCENTAGE = 20.0;

    /**
     * Lower-cased fragments of billing service names for which Azure actually sells
     * reservations or reserved capacity.
     *
     * <p>Without this the recommendation builder walked every line on the bill and proposed
     * "reserving" each one, so a subscription was told it could save 20% on Bandwidth, Virtual
     * Network and Load Balancer — none of which Azure offers any reservation for. Those cards
     * could never be acted on, and their invented savings inflated the headline total.
     *
     * <p>Kept as fragments because Cost Management service names vary in form
     * ({@code Virtual Machines}, {@code Azure Database for PostgreSQL}).
     */
    private static final List<String> RESERVABLE_SERVICE_FRAGMENTS = List.of(
            "virtual machine",
            "sql database",
            "sql managed instance",
            "cosmos db",
            "cache for redis",
            "redis cache",
            "database for mysql",
            "database for postgresql",
            "database for mariadb",
            "app service",
            "data explorer",
            "databricks",
            "synapse",
            "netapp",
            "dedicated host",
            "vmware",
            // Blob and Files both sell reserved capacity, so a generic Storage line qualifies.
            "storage");

    /**
     * Services that contain a reservable fragment but are explicitly not reservable, checked
     * before {@link #RESERVABLE_SERVICE_FRAGMENTS} so a broad fragment cannot admit them.
     */
    private static final List<String> NON_RESERVABLE_SERVICE_FRAGMENTS = List.of(
            "storage sync",
            "storage explorer");

    /**
     * Whether Azure sells a reservation for this billing service line.
     */
    private static boolean isReservable(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }
        String name = serviceName.toLowerCase();
        if (NON_RESERVABLE_SERVICE_FRAGMENTS.stream().anyMatch(name::contains)) {
            return false;
        }
        return RESERVABLE_SERVICE_FRAGMENTS.stream().anyMatch(name::contains);
    }

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureReservationService(AzureClientProvider clientProvider,
                                   RedisCacheService redisCache) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
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

            // --- Recommendations from Redis billing summary ---
            data.setRecommendations(buildRecommendations(subscriptionId));

        } catch (Exception e) {
            log.error("Failed to fetch reservation data for {}: {}", subscriptionId, e.getMessage(), e);
        }

        return data;
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
            item.setUtilizationPercentage(0.0); // not available in basic list endpoint

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

    private List<AzureReservationData.ReservationRecommendation> buildRecommendations(
            String subscriptionId) {

        List<AzureReservationData.ReservationRecommendation> recommendations = new ArrayList<>();

        try {
            String billingKey = "azure:billing-summary:" + subscriptionId;
            Optional<List<AzureDashboardData.BillingSummary>> billingOpt = redisCache.get(
                    billingKey, new TypeReference<List<AzureDashboardData.BillingSummary>>() {});

            if (billingOpt.isEmpty()) {
                log.debug("No billing summary in Redis for {} — skipping recommendations", subscriptionId);
                return recommendations;
            }

            for (AzureDashboardData.BillingSummary billing : billingOpt.get()) {
                double cost = billing.getCost();
                if (cost <= RECOMMENDATION_COST_THRESHOLD) {
                    continue;
                }
                if (!isReservable(billing.getService())) {
                    log.debug("Skipping reservation suggestion for {} — Azure sells no reservation "
                            + "for this service", billing.getService());
                    continue;
                }
                AzureReservationData.ReservationRecommendation rec =
                        new AzureReservationData.ReservationRecommendation();
                rec.setServiceName(billing.getService());
                rec.setCurrentMonthlyCost(cost);
                rec.setEstimatedSavingsPercentage(SAVINGS_PERCENTAGE);
                rec.setEstimatedMonthlySavings(cost * SAVINGS_PERCENTAGE / 100.0);
                rec.setRecommendedTerm("1 Year");
                // Flagged as indicative so the figure is not summed into a headline total or
                // shown with the authority of a priced recommendation. It is a flat percentage
                // of current spend, not a computed difference between on-demand and reserved
                // rates for a specific SKU — Azure's own Consumption reservationRecommendations
                // API would be needed for that.
                rec.setIndicativeOnly(true);
                rec.setReason("Indicative only: a flat ~" + (int) SAVINGS_PERCENTAGE
                        + "% of current spend, not a per-SKU quote. Review in Azure "
                        + "before purchasing.");
                recommendations.add(rec);
            }

        } catch (Exception e) {
            log.warn("Could not generate recommendations for {}: {}", subscriptionId, e.getMessage());
        }

        return recommendations;
    }
}
